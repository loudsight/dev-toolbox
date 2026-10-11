package com.loudsight.syn.plugin.kit;

import com.loudsight.scriptkit.Checks;
import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.core.SynConfig;
import com.loudsight.syn.spi.Context;
import com.loudsight.syn.spi.ExitCodes;
import com.loudsight.syn.spi.SynCommand;
import com.loudsight.syn.spi.Tier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Writes the guard hooks and points public repos at them, so a commit or push that carries a private string or a secret is
 * refused (see {@code publish-check-private}).
 *
 * <p>The hooks are shipped by this plugin and written into one directory ({@code publish.hooks-dir} in
 * {@code .syn/config.properties}, default {@code .syn/hooks/public}), rewritten to the shipped text on every run so they cannot
 * drift. Each public repo's <em>local</em> {@code core.hooksPath} points at that directory: no hook file is added to a public
 * repo, and nothing but the {@code .public-repo} marker and the optional {@code .public-allowlist} is published. A hook finds
 * syn through {@code $SYN}, then the launcher named by {@code publish.launcher} (a repo-relative path), then {@code syn} on the
 * PATH. The setting is per clone, so a fresh clone is unguarded until this runs; CI runs the same check as the backstop.
 * Idempotent.
 */
@Command(name = "publish-install-hooks", mixinStandardHelpOptions = true,
        description = "guard public repos with private-string hooks and a CI workflow — use when making a repo public or cloning one",
        footer = {"Examples:",
                "  syn publish-install-hooks                  # every submodule that has a .public-repo marker",
                "  syn publish-install-hooks --mark useful    # mark one repo public and guard it",
                "  syn publish-install-hooks --ci             # also write the CI workflow into each marked repo"})
public final class PublishInstallHooks implements SynCommand {

    private static final String MARKER = ".public-repo";
    private static final String ALLOWLIST = ".public-allowlist";
    private static final List<String> HOOKS = List.of("pre-commit", "pre-push");
    private static final String DEFAULT_HOOKS_DIR = ".syn/hooks/public";
    private static final String LAUNCHER_PLACEHOLDER = "@LAUNCHER@";
    private static final String WORKFLOW = ".github/workflows/public-guard.yml";

    @Parameters(arity = "0..*", paramLabel = "REPO", description = "Repos to guard (default: every submodule with a .public-repo marker)")
    private @Nullable List<Path> repos;

    @Option(names = "--hooks", paramLabel = "DIR", description = "Where the hooks are written (default: publish.hooks-dir, else .syn/hooks/public)")
    private @Nullable Path hooks;

    @Option(names = "--ci", description = "Also write .github/workflows/public-guard.yml, the CI backstop for pushes that skip the hooks")
    private boolean ci;

    @Option(names = "--mark", description = "Create .public-repo and an empty .public-allowlist in each REPO that lacks them")
    private boolean mark;

    @Override
    public Tier tier() {
        return Tier.LOCAL_MUTATE;
    }

    @Override
    public List<String> keywords() {
        return List.of("public repo", "hooks", "pre-commit", "pre-push", "guard", "core.hooksPath", "open source", "make public", "install");
    }

    @Override
    public int run(Context context) throws IOException {
        Checks checks = new Checks(context.out().stream());
        SynConfig config = SynConfig.load(context.repoRoot());
        Path hookDir = (hooks != null ? hooks : config.path("publish.hooks-dir").orElse(context.repoRoot().resolve(DEFAULT_HOOKS_DIR))).toAbsolutePath();
        installHooks(checks, context, hookDir, config.path("publish.launcher").map(Path::toAbsolutePath));
        if (!checks.passed()) {
            return ExitCodes.FAILURE;
        }
        List<Path> targets = repos == null || repos.isEmpty() ? markedSubmodules(context.repoRoot()) : repos.stream().map(Path::toAbsolutePath).toList();
        if (targets.isEmpty()) {
            checks.skip("no repo given and no submodule has a " + MARKER + " marker; use --mark REPO to make one public");
        }
        for (Path repo : targets) {
            install(checks, context, repo, hookDir);
        }
        return checks.passed() ? ExitCodes.OK : ExitCodes.FAILURE;
    }

    /** The hooks are managed files: always rewritten to the shipped text (with the launcher baked in), and made executable. */
    private static void installHooks(Checks checks, Context context, Path hookDir, java.util.Optional<Path> launcher) throws IOException {
        String candidate = launcher.map(path -> "\"$here/" + hookDir.relativize(path).normalize().toString().replace('\\', '/') + "\"").orElse("\"\"");
        for (String hook : HOOKS) {
            Path file = hookDir.resolve(hook);
            String text = template(hook).replace(LAUNCHER_PLACEHOLDER, candidate);
            boolean current = Files.isRegularFile(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8)) && Files.isExecutable(file);
            if (current) {
                checks.ok(hook + " hook is up to date");
            } else if (context.dryRun()) {
                checks.ok("would write the " + hook + " hook to " + file);
            } else {
                Files.createDirectories(hookDir);
                Files.writeString(file, text, StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
                checks.ok("wrote the " + hook + " hook to " + file);
            }
        }
    }

    private static String template(String hook) throws IOException {
        try (InputStream in = PublishInstallHooks.class.getResourceAsStream("hooks/" + hook + ".tmpl")) {
            if (in == null) {
                throw new IllegalStateException(hook + " hook template is missing from the plugin");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void install(Checks checks, Context context, Path repo, Path hookDir) throws IOException {
        if (!Files.isDirectory(repo.resolve(".git")) && !Files.isRegularFile(repo.resolve(".git"))) {
            checks.bad(repo + " is not a git repo");
            return;
        }
        if (mark && !context.dryRun()) {
            createIfAbsent(repo.resolve(MARKER), "");
            createIfAbsent(repo.resolve(ALLOWLIST), """
                    # Substrings the public repo may contain although they match the private denylist, one per line.
                    # Each is blanked out of a line before the denylist is applied, so list only what is meant to be public.
                    """);
        }
        if (!Files.exists(repo.resolve(MARKER)) && !mark) {
            String advice = repo.getFileName() + " has no " + MARKER + " yet: the guard skips it until it does (--mark creates it)";
            checks.warn(advice);
        }
        if (ci) {
            installWorkflow(checks, context, repo);
        }
        if (context.dryRun()) {
            checks.ok(repo.getFileName() + ": would set core.hooksPath to " + hookDir);
            return;
        }
        Cmd.Result set = Cmd.of("git", "config", "core.hooksPath", hookDir.toString()).dir(repo).discardErr().capture();
        checks.check(set.ok(), repo.getFileName() + ": core.hooksPath -> " + hookDir, repo.getFileName() + ": git config failed (exit " + set.exitCode() + ")");
    }

    /** The CI workflow text that {@code --ci} installs, as shipped in this plugin. */
    static String workflow() throws IOException {
        try (InputStream in = PublishInstallHooks.class.getResourceAsStream("public-guard.yml")) {
            if (in == null) {
                throw new IllegalStateException("public-guard.yml is missing from the plugin");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The workflow is a managed file: always rewritten to the shipped text, so a repo cannot drift from it. */
    private static void installWorkflow(Checks checks, Context context, Path repo) throws IOException {
        Path file = repo.resolve(WORKFLOW);
        String text = workflow();
        boolean current = Files.isRegularFile(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8));
        if (current) {
            checks.ok(repo.getFileName() + ": " + WORKFLOW + " is up to date");
        } else if (context.dryRun()) {
            checks.ok(repo.getFileName() + ": would write " + WORKFLOW);
        } else {
            Files.createDirectories(Objects.requireNonNullElse(file.getParent(), repo));
            Files.writeString(file, text, StandardCharsets.UTF_8);
            checks.ok(repo.getFileName() + ": wrote " + WORKFLOW + " (set the PRIVATE_DENYLIST secret in the repo's CI settings)");
        }
    }

    private static void createIfAbsent(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    private static List<Path> markedSubmodules(Path superproject) {
        Cmd.Result listing = Cmd.of("git", "config", "--file", ".gitmodules", "--get-regexp", "^submodule\\..*\\.path$")
                .dir(superproject).discardErr().capture();
        List<Path> marked = new ArrayList<>();
        for (String line : listing.lines()) {
            String relative = line.substring(line.indexOf(' ') + 1).strip();
            Path repo = superproject.resolve(relative);
            if (Files.exists(repo.resolve(MARKER))) {
                marked.add(repo);
            }
        }
        return marked;
    }
}
