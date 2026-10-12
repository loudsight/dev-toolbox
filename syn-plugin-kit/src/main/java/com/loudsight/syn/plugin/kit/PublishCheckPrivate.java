package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Checks;
import com.loudsight.scriptkit.Cmd;
import com.loudsight.scriptkit.Env;
import com.loudsight.syn.plugin.kit.PublishScan.Finding;
import com.loudsight.syn.plugin.kit.PublishScan.Item;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The guard on a public repo: refuses a commit, a push, a tree or a piece of text that carries a private string or a
 * secret, so the repo can stay public without trusting anyone to remember what must not go in it.
 *
 * <p>A one-off history scan proves a repo is clean today; this keeps it clean, because a public repo keeps receiving
 * commits. It checks what would be <em>published</em>, which is wider than file contents: file names, commit messages,
 * author and committer identities, symlink targets and submodule URLs too.
 *
 * <p>Two rule sources. The owner's private denylist (see {@link PrivateTerms}) names what must never appear; it lives
 * outside every public repo because it is itself private, and the command <strong>fails closed</strong>: in a public
 * repo a missing or empty denylist is a failure, never a silent pass. Generic credentials are caught by a small built-in
 * tripwire list ({@link SecretTripwires}) and, when installed, a real scanner (betterleaks, else gitleaks) for depth.
 * Output names the denylist line, never its text, so the report can appear in a public CI log.
 *
 * <p>A repo is "public" when it has a {@code .public-repo} file at its root; without one the command skips, so the hooks
 * are safe to install everywhere. {@code --path} (a tree or one file) and {@code --strict} are always strict. A hook can
 * be bypassed with {@code --no-verify}, so CI runs the same command and blocks the merge.
 */
@Command(name = "publish-check-private", mixinStandardHelpOptions = true,
        description = "block a commit, push or text that carries a private string or secret — use when guarding a public repo before publishing",
        footer = {"Examples:",
                "  syn publish-check-private --staged                  # pre-commit hook, in a repo with .public-repo",
                "  git push → hook runs: syn publish-check-private --push-stdin   # pre-push, reads git's ref list",
                "  syn publish-check-private --path ~/dev/public-staging/x         # a cleansed tree before its first commit",
                "  syn publish-check-private --path post.md                         # text, before it is saved or posted",
                "Denylist: --denylist, else $SYN_PRIVATE_DENYLIST, else ~/.config/syn/private-denylist.txt (outside every repo)."})
public final class PublishCheckPrivate implements SynCommand {

    private static final String MARKER = ".public-repo";
    private static final String ALLOWLIST = ".public-allowlist";
    private static final String ZEROS = "0000000000000000000000000000000000000000";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_REPORTED = 40;
    private static final int SCANNER_TAIL_LINES = 8;

    @Option(names = "--staged", description = "Check the staged change (the pre-commit hook)")
    private boolean staged;

    @Option(names = "--range", paramLabel = "REV", split = " ", description = "Check the commits selected by these rev-list arguments, e.g. 'origin/master..HEAD'")
    private @Nullable List<String> range;

    @Option(names = "--push-stdin", description = "Check what a push would send, reading git's pre-push lines from stdin")
    private boolean pushStdin;

    @Option(names = "--path", paramLabel = "PATH", description = "Check a directory tree or one file instead of a repo")
    private @Nullable Path path;

    @Option(names = "--repo", paramLabel = "DIR", description = "The repo to check (default: the git repo of the current directory)")
    private @Nullable Path repo;

    @Option(names = "--denylist", paramLabel = "FILE", description = "The private denylist (default: $SYN_PRIVATE_DENYLIST, else ~/.config/syn/private-denylist.txt)")
    private @Nullable Path denylist;

    @Option(names = "--allowlist", paramLabel = "FILE", description = "Permitted substrings (default: .public-allowlist in the repo or tree)")
    private @Nullable Path allowlist;

    @Option(names = "--scanner", paramLabel = "BIN", description = "The secret scanner (default: $SYN_SECRET_SCANNER, else betterleaks, else gitleaks on PATH); 'none' runs the built-in tripwires only")
    private @Nullable String scanner;

    @Option(names = "--require-scanner", description = "Fail, rather than warn, when no secret scanner is available")
    private boolean requireScanner;

    @Option(names = "--strict", description = "Check even a repo with no .public-repo marker")
    private boolean strict;

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("private", "secret", "leak", "public repo", "denylist", "pre-commit", "pre-push", "publish", "redact",
                "token", "credentials", "open source", "sanitize");
    }

    @Override
    public int run(Context context) throws IOException {
        Checks checks = new Checks(context.out().stream());
        int modes = (staged ? 1 : 0) + (range != null ? 1 : 0) + (pushStdin ? 1 : 0) + (path != null ? 1 : 0);
        if (modes != 1) {
            context.out().errorln("give exactly one of --staged, --range, --push-stdin, --path");
            return ExitCodes.USAGE;
        }
        Path repoDir = path != null ? path.toAbsolutePath() : RepoDir.resolve(repo, context);
        if (path == null && !strict && !Files.exists(repoDir.resolve(MARKER))) {
            checks.skip(repoDir + " has no " + MARKER + " marker: not a public repo, nothing to check");
            return ExitCodes.OK;
        }
        Path denyFile = denylist != null ? denylist : defaultDenylist();
        if (!Files.isRegularFile(denyFile)) {
            checks.bad("private denylist not found at " + denyFile + ": refusing to pass without it"
                    + " (set SYN_PRIVATE_DENYLIST or --denylist)");
            return ExitCodes.FAILURE;
        }
        Path allowFile = allowlist != null ? allowlist : directoryOf(repoDir).resolve(ALLOWLIST);
        PrivateTerms terms = PrivateTerms.parse(Files.readAllLines(denyFile, StandardCharsets.UTF_8),
                Files.isRegularFile(allowFile) ? Files.readAllLines(allowFile, StandardCharsets.UTF_8) : List.of());
        if (terms.size() == 0) {
            checks.bad("private denylist " + denyFile + " has no entries: refusing to pass an empty list");
            return ExitCodes.FAILURE;
        }

        List<List<String>> ranges = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        if (staged) {
            items.addAll(new PublishScan(repoDir).staged());
        } else if (path != null) {
            items.addAll(PublishScan.tree(repoDir));
        } else {
            if (range != null) {
                ranges.add(range);
            } else {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    ranges.addAll(pushedRanges(repoDir, in));
                }
            }
            PublishScan scan = new PublishScan(repoDir);
            for (List<String> revs : ranges) {
                items.addAll(scan.commits(revs));
            }
        }

        List<Finding> findings = PublishScan.scan(items, terms);
        report(checks, findings, items.size(), terms.size());
        runScanner(checks, repoDir, ranges);
        return checks.passed() ? ExitCodes.OK : ExitCodes.FAILURE;
    }

    private static void report(Checks checks, List<Finding> findings, int pieces, int entries) {
        if (findings.isEmpty()) {
            checks.ok("no private string or secret tripwire in " + pieces + " pieces of text (" + entries + " denylist entries)");
            return;
        }
        checks.bad(findings.size() + " private string(s) or secret(s) found; text is not repeated here:");
        findings.stream().limit(MAX_REPORTED).forEach(finding -> checks.detail(finding.where() + " — " + finding.what()));
        if (findings.size() > MAX_REPORTED) {
            checks.detail("... and " + (findings.size() - MAX_REPORTED) + " more");
        }
    }

    private static Path defaultDenylist() {
        return Env.get("SYN_PRIVATE_DENYLIST").map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".config", "syn", "private-denylist.txt"));
    }

    /** Git's pre-push protocol: one line per ref, "local-ref local-sha remote-ref remote-sha"; deletions carry nothing to check. */
    static List<List<String>> pushedRanges(Path repoDir, BufferedReader in) throws IOException {
        List<List<String>> ranges = new ArrayList<>();
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            String[] parts = WHITESPACE.split(line.strip(), -1);
            if (parts.length < 4 || ZEROS.equals(parts[1])) {
                continue;
            }
            boolean newRef = ZEROS.equals(parts[3]);
            boolean knownRemote = !newRef && Cmd.of("git", "cat-file", "-e", parts[3] + "^{commit}").dir(repoDir).discardErr().capture().ok();
            ranges.add(knownRemote ? List.of(parts[3] + ".." + parts[1]) : List.of(parts[1], "--not", "--remotes"));
        }
        return ranges;
    }

    private void runScanner(Checks checks, Path repoDir, List<List<String>> ranges) {
        String binary = scanner != null ? scanner : locateScanner();
        if (binary == null || "none".equals(binary)) {
            String message = "no secret scanner (betterleaks or gitleaks) found: only the built-in tripwires ran";
            if (requireScanner) {
                checks.bad(message);
            } else {
                checks.warn(message);
            }
            return;
        }
        List<List<String>> invocations = new ArrayList<>();
        if (staged) {
            invocations.add(List.of("git", "--staged", "."));
        } else if (path != null) {
            invocations.add(List.of("dir", repoDir.toString()));
        } else {
            for (List<String> revs : ranges) {
                invocations.add(List.of("git", "--log-opts", String.join(" ", revs), "."));
            }
        }
        for (List<String> invocation : invocations) {
            List<String> command = new ArrayList<>(List.of(binary));
            command.addAll(invocation);
            command.addAll(List.of("--no-banner", "--no-color", "--redact"));
            Cmd.Result result;
            try {
                result = Cmd.of(command).dir(directoryOf(repoDir)).mergeErr().capture();
            } catch (Cmd.CmdException e) {
                checks.bad(binary + " could not be run, so it checked nothing: " + e.getMessage());
                continue;
            }
            if (result.ok()) {
                checks.ok(binary + " found no secrets (" + String.join(" ", invocation.subList(0, Math.min(2, invocation.size()))) + ")");
            } else if (result.exitCode() == 1) {
                checks.bad(binary + " found secrets; its redacted report ends:");
                List<String> lines = result.lines();
                lines.subList(Math.max(0, lines.size() - SCANNER_TAIL_LINES), lines.size()).forEach(checks::detail);
            } else {
                checks.bad(binary + " failed (exit " + result.exitCode() + "), so it checked nothing");
            }
        }
    }

    /** {@code target} itself when it is a directory, else the directory holding it (the file a {@code --path} names). */
    private static Path directoryOf(Path target) {
        return Files.isDirectory(target) ? target : Objects.requireNonNullElse(target.toAbsolutePath().getParent(), target);
    }

    private @Nullable String locateScanner() {
        String named = Env.get("SYN_SECRET_SCANNER").orElse(null);
        if (named != null) {
            return named;
        }
        for (String candidate : List.of("betterleaks", "gitleaks")) {
            if (Cmd.of("sh", "-c", "command -v " + candidate).discardErr().capture().ok()) {
                return candidate;
            }
        }
        return null;
    }
}
