package com.loudsight.syn.cli;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.core.ContentHasher;
import com.loudsight.syn.core.DirLock;
import com.loudsight.useful.helper.logging.LoggingHelper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Keeps the running head, and the plugins built beside it, current with their sources. The launcher
 * ({@code scripts/syn}) builds the head once into {@code <cache>/lib}; from then on the head itself
 * notices, at startup, that its source directories hashed (by {@link ContentHasher}: content, not
 * mtime) differently from when they were built, rebuilds, and re-runs the same command with the fresh code.
 *
 * <p>Only active when launched with {@code -Dsyn.cache=<dir>}; an IDE or test run is left alone. What is
 * built is configured by system properties, so the generic head carries nothing about any one repo:
 * <ul>
 *   <li>{@code syn.build.pom} - the reactor to build, relative to the repo root (default: dev-toolbox's
 *       own reactor, which has the head and no plugins);</li>
 *   <li>{@code syn.sources} - comma-separated directories, relative to the repo root, whose content decides
 *       freshness (default: the head's own);</li>
 *   <li>{@code syn.plugins} - when set, the reactor's plugin modules are built into {@code <cache>/plugins}
 *       too, through the {@code syn.plugins.dir} profile of their parent.</li>
 * </ul>
 */
final class SelfUpdate {

    /** The directories whose content the head is built from, relative to the repo root. */
    static final List<String> HEAD_SOURCES = List.of(
            "dev-toolbox/syn-spi",
            "dev-toolbox/syn-core",
            "dev-toolbox/syn-cli");

    static final String DEFAULT_POM = "dev-toolbox/pom.xml";

    static final String CACHE_PROPERTY = "syn.cache";
    static final String POM_PROPERTY = "syn.build.pom";
    static final String SOURCES_PROPERTY = "syn.sources";
    static final String PLUGINS_PROPERTY = "syn.plugins";
    static final String PLUGINS_DIR_PROPERTY = "syn.plugins.dir";
    static final String LOGBACK_PROPERTY = "logback.configurationFile";

    /** System properties the re-run inherits, so it is configured exactly as this run was. */
    private static final List<String> FORWARDED = List.of(CACHE_PROPERTY, POM_PROPERTY, SOURCES_PROPERTY, PLUGINS_PROPERTY, PLUGINS_DIR_PROPERTY, LOGBACK_PROPERTY);

    private static final LoggingHelper LOGGER = LoggingHelper.wrap(SelfUpdate.class);
    private static final Duration STALE_LOCK = Duration.ofMinutes(5);

    /** What the stamp says about the cached build. */
    enum State {
        FRESH,
        /** No stamp, but the launcher has just built it: record the hash and carry on. */
        ADOPT,
        STALE
    }

    /** What to build and where it goes. */
    record Config(Path repoRoot, Path cache, String pom, List<String> sources, boolean plugins, Path pluginsDir) {

        Config {
            sources = List.copyOf(sources);
        }

        Path lib() {
            return cache.resolve("lib");
        }

        Path stamp() {
            return cache.resolve("stamp");
        }
    }

    private SelfUpdate() {
    }

    /** The configuration from system properties, or empty when this run is not under the launcher or updates are switched off. */
    static Optional<Config> configure(Path repoRoot) {
        return configure(repoRoot, System.getenv("SYN_NO_SELF_UPDATE") != null);
    }

    /** As above, with the {@code SYN_NO_SELF_UPDATE} switch given instead of read, so a test does not depend on its caller's environment. */
    static Optional<Config> configure(Path repoRoot, boolean switchedOff) {
        String cache = System.getProperty(CACHE_PROPERTY);
        if (cache == null || switchedOff) {
            return Optional.empty();
        }
        String sources = System.getProperty(SOURCES_PROPERTY);
        List<String> directories = sources == null
                ? HEAD_SOURCES
                : Arrays.stream(sources.split(",", -1)).map(String::trim).filter(directory -> !directory.isEmpty()).toList();
        Path cacheDir = Path.of(cache);
        Path pluginsDir = Syn.pluginsDir(repoRoot, cacheDir);
        return Optional.of(new Config(repoRoot, cacheDir, System.getProperty(POM_PROPERTY, DEFAULT_POM), directories,
                System.getProperty(PLUGINS_PROPERTY) != null, pluginsDir));
    }

    /**
     * Classifies the cached build. An unstamped cache has an unknown age and must not be trusted unless the
     * launcher has just built it; a build missing a part (the plugins) is stale whatever the stamp says.
     */
    static State assess(String currentHash, Optional<String> stamp, boolean justBuilt, boolean artifactsPresent) {
        if (!artifactsPresent) {
            return State.STALE;
        }
        if (stamp.isPresent() && sameHash(stamp.get(), currentHash)) {
            return State.FRESH;
        }
        return stamp.isEmpty() && justBuilt ? State.ADOPT : State.STALE;
    }

    private static boolean sameHash(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rebuilds and re-runs when stale.
     *
     * @return the exit code when this process was superseded (the re-run's, or 1 if the rebuild failed:
     *         syn will not run code that differs from its sources), else empty: carry on
     */
    static OptionalInt refresh(Path repoRoot, String... args) {
        Optional<Config> configured = configure(repoRoot);
        if (configured.isEmpty()) {
            return OptionalInt.empty();
        }
        Config config = configured.get();
        Optional<String> fingerprint = new ContentHasher(repoRoot).hashModules(config.sources());
        if (fingerprint.isEmpty()) {
            LOGGER.logWarn("cannot fingerprint the sources {} under {}; not checking for updates", config.sources(), repoRoot);
            return OptionalInt.empty();
        }
        String current = fingerprint.get();
        State state = assess(current, readStamp(config.stamp()), System.getenv("SYN_BOOTSTRAPPED") != null, artifactsPresent(config));
        if (state == State.FRESH) {
            return OptionalInt.empty();
        }
        if (state == State.ADOPT) {
            writeStamp(config.stamp(), current);
            return OptionalInt.empty();
        }
        if (System.getenv("SYN_REEXEC") != null) {
            LOGGER.logWarn("syn is still stale after a rebuild; running it as it is");
            return OptionalInt.empty();
        }
        try (DirLock _ = DirLock.acquire(config.cache().resolve(".build.lock"), STALE_LOCK)) {
            // Another syn may have rebuilt while this one waited for the lock.
            boolean rebuilt = artifactsPresent(config) && readStamp(config.stamp()).filter(stamp -> sameHash(stamp, current)).isPresent();
            if (!rebuilt) {
                LOGGER.logWarn("sources changed, rebuilding syn");
                build(config);
                writeStamp(config.stamp(), current);
            }
        } catch (IllegalStateException | UncheckedIOException | Cmd.CmdException e) {
            LOGGER.logError("could not rebuild syn - not running a build that differs from its sources: " + e.getMessage(), e);
            return OptionalInt.of(1);
        }
        return OptionalInt.of(rerun(config, args));
    }

    private static boolean artifactsPresent(Config config) {
        return Files.isDirectory(config.lib()) && (!config.plugins() || Files.isDirectory(config.pluginsDir()));
    }

    /** The head, then the plugins: a plugin that fails to build must not stop the head being rebuilt. */
    private static void build(Config config) {
        Path pom = config.repoRoot().resolve(config.pom());
        Path freshLib = config.cache().resolve("lib.new");
        deleteTree(freshLib);
        mavenOrFail(config, List.of("-q", "-f", pom.toString(), "-pl", ":syn-cli", "-am", "package", "-Dmaven.test.skip=true",
                "-Dsyn.lib.dir=" + freshLib));
        swap(config.cache().resolve("lib.old"), freshLib, config.lib());

        if (config.plugins()) {
            Path freshPlugins = config.cache().resolve("plugins.new");
            deleteTree(freshPlugins);
            mavenOrFail(config, List.of("-q", "-f", pom.toString(), "package", "-Dmaven.test.skip=true", "-Dsyn.plugins.dir=" + freshPlugins));
            swapPlugins(config.cache().resolve("plugins.old"), freshPlugins, config.pluginsDir());
        }
    }

    private static void mavenOrFail(Config config, List<String> goals) {
        mavenOrFail(goals, offline -> maven(config.repoRoot(), offline, goals), System.err::print);
    }

    /**
     * Offline first, online as the fallback (the order scripts/syn uses). Maven's output is held back: a success
     * logs one INFO line, a failure hands the full output of both attempts to {@code onFailure} and throws.
     */
    static void mavenOrFail(List<String> goals, Function<Boolean, Cmd.Result> run, Consumer<String> onFailure) {
        Cmd.Result offline = run.apply(true);
        if (!offline.ok()) {
            Cmd.Result online = run.apply(false);
            if (!online.ok()) {
                onFailure.accept(offline.stdout() + online.stdout());
                throw new IllegalStateException("Maven failed (" + String.join(" ", goals) + "): output printed above");
            }
        }
        LOGGER.logInfo("syn rebuilt ({})", String.join(" ", goals));
    }

    private static Cmd.Result maven(Path repoRoot, boolean offline, List<String> goals) {
        List<String> command = new ArrayList<>(List.of("mvn"));
        if (offline) {
            command.add("-o");
        }
        command.addAll(goals);
        return Cmd.of(command).dir(repoRoot).mergeErr().capture();
    }

    /**
     * Replaces each plugin the build produced, {@code <fresh>/<name>}, with {@code <pluginsDir>/<name>}, and nothing else:
     * the directory may also hold plugins that came from elsewhere. Superseded copies go to {@code oldDir}, outside
     * {@code pluginsDir}, where the head would not load them. A plugin the build no longer produces is left in place.
     */
    static void swapPlugins(Path oldDir, Path fresh, Path pluginsDir) {
        deleteTree(oldDir);
        try {
            Files.createDirectories(pluginsDir);
            if (Files.isDirectory(fresh)) {
                try (Stream<Path> built = Files.list(fresh)) {
                    for (Path plugin : built.filter(Files::isDirectory).sorted().toList()) {
                        String name = Objects.requireNonNull(plugin.getFileName()).toString();
                        swap(oldDir.resolve(name), plugin, pluginsDir.resolve(name));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not swap plugins into " + pluginsDir, e);
        }
        deleteTree(oldDir);
        deleteTree(fresh);
    }

    /** Replaces {@code target} with {@code fresh}, moving what was there to {@code old}; a running syn keeps reading the files it already opened. */
    private static void swap(Path old, Path fresh, Path target) {
        deleteTree(old);
        try {
            if (Files.exists(target)) {
                Files.createDirectories(Objects.requireNonNull(old.getParent()));
                Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
            }
            if (Files.exists(fresh)) {
                Files.move(fresh, target, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.createDirectories(target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not swap in " + target, e);
        }
        deleteTree(old);
    }

    private static int rerun(Config config, String... args) {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        for (String property : FORWARDED) {
            String value = System.getProperty(property);
            if (value != null) {
                command.add("-D" + property + "=" + value);
            }
        }
        command.addAll(List.of("-cp", config.lib() + "/*", Syn.class.getName()));
        command.addAll(List.of(args));
        return Cmd.of(command).env("SYN_REEXEC", "1").stream();
    }

    private static Optional<String> readStamp(Path stampFile) {
        try {
            return Files.exists(stampFile) ? Optional.of(Files.readString(stampFile).strip()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeStamp(Path stampFile, String hash) {
        try {
            Files.createDirectories(Objects.requireNonNull(stampFile.toAbsolutePath().getParent()));
            Files.writeString(stampFile, hash + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
