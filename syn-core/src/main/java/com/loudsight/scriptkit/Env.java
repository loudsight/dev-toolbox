package com.loudsight.scriptkit;

import com.loudsight.syn.core.SynConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The environment a script runs in. {@code scripts/jx} exports {@code REPO_ROOT} and
 * {@code JX_SCRIPT}; everything else comes from the caller's environment ({@code scripts/myEnv.sh}).
 */
public final class Env {

    private Env() {
    }

    /**
     * The repo root: {@code REPO_ROOT} when set, else the nearest ancestor holding a {@code .syn} directory (the repo's syn
     * settings) or AGENTS.md and .git, else the nearest ancestor with a {@code .git}. The superproject, not a submodule, wins
     * from inside one when it has {@code .syn}.
     */
    public static Path repoRoot() {
        return get("REPO_ROOT").map(Path::of).orElseGet(() -> findRoot(Path.of("").toAbsolutePath()));
    }

    static Path findRoot(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(SynConfig.DIRECTORY)) || (Files.exists(dir.resolve("AGENTS.md")) && Files.exists(dir.resolve(".git")))) {
                return dir;
            }
        }
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        throw new IllegalStateException("not inside a repo (no .syn, AGENTS.md + .git, or .git above " + start + ")");
    }

    /** The running script's source file, as set by {@code scripts/jx}. */
    public static Optional<Path> script() {
        return get("JX_SCRIPT").map(Path::of);
    }

    public static Optional<String> get(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }

    public static String require(String name) {
        return get(name).orElseThrow(() -> new IllegalStateException(
                name + " is not set - run through scripts/jx after sourcing scripts/myEnv.sh"));
    }

    public static Path workRoot() {
        return Path.of(require("WORK_ROOT"));
    }

    /**
     * What sourcing a shell env file ({@code export KEY=value} lines, e.g. /path/to/secrets.env)
     * would set: the variables whose value differs afterwards. bash does the sourcing, so shell
     * syntax (quoting, expansion) means what it always meant; values travel NUL-separated, so they
     * may contain anything but NUL.
     */
    public static Map<String, String> sourced(Path envFile) {
        Cmd.Result result = Cmd.of("bash", "-c", "set -a && . \"$1\" && env -0", "_", envFile.toString()).capture();
        if (!result.ok()) {
            throw new IllegalStateException("could not source " + envFile + " (exit " + result.exitCode() + ")");
        }
        Map<String, String> changed = new TreeMap<>();
        for (String entry : result.stdout().split("\0", -1)) {
            int equals = entry.indexOf('=');
            if (equals > 0) {
                String name = entry.substring(0, equals);
                String value = entry.substring(equals + 1);
                if (!value.equals(System.getenv(name)) && !"_".equals(name) && !"SHLVL".equals(name) && !"PWD".equals(name)) {
                    changed.put(name, value);
                }
            }
        }
        return changed;
    }

    /** {@code APP_ENV}; environment-specific behaviour keys on this, never on Spring profiles. */
    public static Optional<String> appEnv() {
        return get("APP_ENV");
    }
}
