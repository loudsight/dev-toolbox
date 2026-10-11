package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import com.loudsight.syn.spi.Output;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Rot in a repo's knowledge-base references, found by grep and the file system:
 * <ol>
 *   <li>paths under a root that was retired (the knowledge base moved: {@code kb.retired-roots}),</li>
 *   <li>paths into the knowledge-base directory that no longer exist - a reorganisation leaves Java comments,
 *       memories and other documents pointing at files that moved,</li>
 *   <li>relative links inside the knowledge base's own markdown that do not resolve,</li>
 *   <li>tracked symlinks that dangle - a directory rename breaks them silently, and content greps never see them.</li>
 * </ol>
 *
 * <p>Deliberately NOT a link checker for URLs - only repo-local paths, which are the ones that rot silently.
 * Exits 1 if anything is unresolved, so it can gate a commit.
 *
 * <p>Configured in {@code .syn/config.properties} (see {@link SynConfig}); every key has a default for a repo whose
 * knowledge base is {@code kb/}: {@code kb.dir}, {@code kb.retired-roots}, {@code kb.subdirs}, {@code kb.exclude}
 * (a regex for paths whose stale references are expected, such as a staging area) and {@code kb.advice} (text added
 * to the closing advice).
 */
@Command(name = "kb-check-links", mixinStandardHelpOptions = true,
        description = "report kb/ document references and symlinks that no longer resolve — use when moving, renaming or retiring kb docs",
        footer = "Example: syn kb-check-links --quiet && git commit ...")
public final class KbCheckLinks implements SynCommand {

    /** Build output and tooling directories: never a place a reference can rot. */
    private static final String BASE_EXCLUDES = "/target/|.ignored|\\.git/|node_modules/|\\.idea/";
    private static final List<String> INCLUDES = List.of("--include=*.md", "--include=*.java", "--include=*.sh", "--include=*.xml",
            "--include=*.yaml", "--include=*.yml", "--include=*.json", "--include=*.js", "--include=*.mjs", "--include=*.py",
            "--include=*.html", "--exclude-dir=node_modules", "--exclude-dir=target", "--exclude-dir=.git",
            "--exclude-dir=.ignored", "--exclude-dir=.idea");
    private static final String DEFAULT_DIR = "kb";
    private static final List<String> DEFAULT_SUBDIRS = List.of("plans", "issues", "recipes", "architecture", "decisions", "skills", "reports");
    private static final Pattern LEADING_NON_LETTERS = Pattern.compile("^[^A-Za-z]*");
    private static final int MAX_SHOWN = 70;

    @Option(names = "--quiet", description = "Leave out the advice on fixing")
    private boolean quiet;

    /** A {@code grep -n -o} hit: file, line and the matched text. */
    private record Hit(String file, String line, String match) {

        /** A {@code grep -n -o} line: file:line:match. */
        static Hit parse(String hit) {
            String[] parts = hit.split(":", 3);
            return new Hit(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "");
        }
    }

    /** One run's state: the root being checked, where to write, and how many dead references turned up. */
    private static final class Run {

        private final Path root;
        private final Output out;
        private final Pattern excludes;
        private int failures;

        Run(Path root, Output out, Pattern excludes) {
            this.root = root;
            this.out = out;
            this.excludes = excludes;
        }

        void report(String where, String what) {
            failures++;
            out.println(String.format(Locale.ROOT, "%-58s %s", where, what));
        }

        /** {@code grep -rnoE <pattern> <options> <where>}, minus excluded paths. */
        List<Hit> grep(String pattern, List<String> options, String where) {
            List<String> command = new ArrayList<>(List.of("grep", "-s", "-rnoE", pattern));
            command.addAll(options);
            command.add(where);
            return Cmd.of(command).dir(root).capture().lines().stream()
                    .filter(line -> !line.isEmpty() && !excludes.matcher(line).find())
                    .map(Hit::parse).toList();
        }

        void reportMatch(Hit hit) {
            String what = LEADING_NON_LETTERS.matcher(hit.match()).replaceFirst("");
            report(hit.file() + ":" + hit.line(),
                    what.codePointCount(0, what.length()) > MAX_SHOWN ? what.substring(0, what.offsetByCodePoints(0, MAX_SHOWN)) : what);
        }
    }

    @Override
    public Tier tier() {
        return Tier.READ_ONLY;
    }

    @Override
    public List<String> keywords() {
        return List.of("dead links", "broken references", "stale paths", "kb", "symlinks", "sweep");
    }

    @Override
    public int run(Context context) {
        Output out = context.out();
        Path root = context.repoRoot();
        SynConfig config = SynConfig.load(root);
        String dir = config.get("kb.dir").orElse(DEFAULT_DIR);
        String dirPattern = escapeForGrep(dir);
        List<String> subdirs = config.list("kb.subdirs").isEmpty() ? DEFAULT_SUBDIRS : config.list("kb.subdirs");
        List<String> retiredRoots = config.list("kb.retired-roots");
        String extraExcludes = config.get("kb.exclude").map(regex -> "|" + regex).orElse("");
        Run run = new Run(root, out, Pattern.compile(BASE_EXCLUDES + extraExcludes));

        for (String retired : retiredRoots) {
            out.println("== Dead `" + retired + "/` paths (that path became " + dir + "/) ==");
            for (Hit hit : run.grep("(^|[^A-Za-z0-9_./-])" + escapeForGrep(retired) + "/(" + String.join("|", subdirs) + ")/[A-Za-z0-9._/-]*",
                    INCLUDES, ".")) {
                run.reportMatch(hit);
            }
            out.println("");
        }

        out.println("== " + dir + "/ paths that do not resolve ==");
        for (Hit hit : run.grep("(^|[^A-Za-z0-9_./-])" + dirPattern + "/[A-Za-z0-9._/-]+\\.(md|txt)", INCLUDES, ".")) {
            String target = dir + "/" + hit.match().substring(hit.match().indexOf(dir + "/") + dir.length() + 1);
            if (!Files.exists(root.resolve(target))) {
                run.report(hit.file() + ":" + hit.line(), target);
            }
        }

        out.println("");
        out.println("== Relative links inside " + dir + " documents that do not resolve ==");
        for (Hit hit : run.grep("\\]\\([^)]+\\)", List.of("--include=*.md"), dir)) {
            String link = linkTarget(hit.match());
            if (link.isEmpty() || link.startsWith("http") || link.startsWith("#") || link.startsWith("mailto:")) {
                continue;
            }
            Path source = Path.of(hit.file()).getParent();
            if (!Files.exists(root.resolve(source == null ? Path.of(link) : source.resolve(link)))) {
                run.report(hit.file() + ":" + hit.line(), link);
            }
        }

        out.println("");
        out.println("== Tracked symlinks that do not resolve (superproject and submodules) ==");
        for (String entry : Cmd.of("git", "ls-files", "-s", "--recurse-submodules").dir(root).capture().lines()) {
            if (entry.startsWith("120000 ")) {
                String link = entry.substring(entry.indexOf('\t') + 1);
                Path path = root.resolve(link);
                if (!Files.exists(path)) {
                    run.report(link, "-> " + readLink(path));
                }
            }
        }

        out.println("");
        if (run.failures == 0) {
            out.println("OK - no dead " + dir + " references.");
            return ExitCodes.OK;
        }
        out.println(run.failures + " dead reference(s).");
        if (!quiet) {
            out.println("");
            out.println("Fixing:");
            if (!retiredRoots.isEmpty()) {
                out.println("  - " + retiredRoots.stream().map(retired -> "`" + retired + "/...`").collect(Collectors.joining(" or "))
                        + " -> `" + dir + "/...`");
            }
            out.println("  - a moved file    -> find it with: git -C " + dir + " log --diff-filter=D --name-only --oneline");
            out.println("  - a retired file  -> point at whatever absorbed it, or drop the reference");
            config.get("kb.advice").ifPresent(advice -> out.println("  - " + advice));
        }
        return ExitCodes.FAILURE;
    }

    /** {@code text} as a literal inside an extended regular expression: each character that is not a word character or a slash is escaped. */
    private static String escapeForGrep(String text) {
        return text.replaceAll("([^A-Za-z0-9_/-])", "\\\\$1");
    }

    /**
     * The path in a markdown {@code ](target)} match: what follows the {@code ](}, up to the first {@code )} or
     * {@code #}. A match without the opening is returned whole.
     */
    private static String linkTarget(String match) {
        int open = match.indexOf("](");
        if (open < 0) {
            return match;
        }
        String rest = match.substring(open + "](".length());
        int close = rest.indexOf(')');
        String target = close < 0 ? rest : rest.substring(0, close);
        int fragment = target.indexOf('#');
        return fragment < 0 ? target : target.substring(0, fragment);
    }

    private static String readLink(Path path) {
        try {
            return Files.readSymbolicLink(path).toString();
        } catch (IOException | UnsupportedOperationException ignoredNotALink) {
            return ""; // as readlink prints nothing
        }
    }
}
