package com.loudsight.scriptkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The docker image build for a deployment wrapper module ({@code deployments/<module>/}), which
 * internal-parent's {@code custom-build} profile runs through {@code scripts/jx image-build-module}
 * whenever the module has a {@code .docker-image} file. That file holds the module's settings; they
 * reproduce the per-module differences of the shell build scripts this replaced (2026-09-27).
 *
 * <p><b>Image labels.</b> They record exactly which artifact went into an image. Without them, "the
 * image exists" and "the image contains the code under test" are independent facts and nothing
 * checks the second: on 2026-08-08 PlaywrightStackIT reported 68 passed against a backend-server
 * image built three days earlier, and the run was reported as validation. PlaywrightStackIT now
 * reads these labels back and refuses to start a stack whose images do not match the working tree
 * (StackFreshness), and treats a missing label as a failure - an unlabelled image is the exact
 * unknown the labels remove. On a deploy host they answer "what is actually running?" without
 * extracting and decompiling a jar.
 */
public final class DockerImageBuild {

    public static final String CONFIG_FILE = ".docker-image";

    /** Where a module's packaged artifact is looked for under {@code target/}. */
    public enum PackageLookup {
        /** {@code <module>-*-bin.tar.gz}, else {@code <module>-*.jar}. */
        TARBALL_OR_JAR,
        /** Exactly {@code <module>-<version>-bin.tar.gz}. */
        TARBALL_EXACT_VERSION,
        /** {@code <module>-*.jar}. */
        JAR,
        /** No artifact: the Dockerfile builds from the module directory alone. */
        NONE
    }

    /**
     * A module's {@code .docker-image} settings.
     *
     * @param module   the artifact name prefix; defaults to the module directory's name
     * @param lookup   where the packaged artifact is looked for
     * @param userArgs whether to pass the building user as {@code UID}/{@code GID}/{@code UNAME} build args
     * @param labels   whether to stamp the artifact labels
     */
    public record Config(String module, PackageLookup lookup, boolean userArgs, boolean labels) {

        public static Config read(Path moduleDir) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(moduleDir.resolve(CONFIG_FILE))) {
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Path name = moduleDir.toAbsolutePath().normalize().getFileName();
            return new Config(
                    properties.getProperty("module", name == null ? "" : name.toString()),
                    PackageLookup.valueOf(properties.getProperty("package", "tarball-or-jar").toUpperCase(Locale.ROOT).replace('-', '_')),
                    Boolean.parseBoolean(properties.getProperty("user-args", "true")),
                    Boolean.parseBoolean(properties.getProperty("labels", "true")));
        }
    }

    /** The user the image is built for, as {@code id -u}, {@code id -g} and {@code whoami} report it. */
    public record User(String uid, String gid, String name) {

        public static User current() {
            return new User(Cmd.of("id", "-u").capture().orThrow(), Cmd.of("id", "-g").capture().orThrow(),
                    Cmd.of("whoami").capture().orThrow());
        }
    }

    private DockerImageBuild() {
    }

    /** The packaged artifact under {@code <moduleDir>/target}, if the lookup finds one. */
    public static Optional<Path> findPackage(Path moduleDir, Config config, String version) {
        String module = config.module();
        return switch (config.lookup()) {
            case TARBALL_OR_JAR -> first(moduleDir, module + "-*-bin.tar.gz").or(() -> firstJar(moduleDir, module));
            case TARBALL_EXACT_VERSION -> first(moduleDir, module + "-" + version + "-bin.tar.gz");
            case JAR -> firstJar(moduleDir, module);
            case NONE -> Optional.empty();
        };
    }

    /** What the lookup expects, for the error when it finds nothing. */
    public static String expected(Config config, String version) {
        String module = config.module();
        return switch (config.lookup()) {
            case TARBALL_OR_JAR -> module + "-*-bin.tar.gz or " + module + "-*.jar";
            case TARBALL_EXACT_VERSION -> module + "-" + version + "-bin.tar.gz";
            case JAR -> module + "-*.jar";
            case NONE -> "nothing";
        };
    }

    private static Optional<Path> firstJar(Path moduleDir, String module) {
        return matching(moduleDir, module + "-*.jar").stream()
                .filter(path -> {
                    String name = String.valueOf(path.getFileName());
                    return !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar") && !name.endsWith("-tests.jar");
                })
                .findFirst();
    }

    private static Optional<Path> first(Path moduleDir, String glob) {
        return matching(moduleDir, glob).stream().findFirst();
    }

    private static List<Path> matching(Path moduleDir, String glob) {
        Path target = moduleDir.resolve("target");
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> entries = Files.list(target)) {
            return entries.filter(path -> {
                Path name = path.getFileName();
                return name != null && matcher.matches(name);
            }).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The {@code --label} arguments for {@code packageFile}. The package's sha256 is what the freshness
     * check keys on: it is the only fingerprint that is honest mid-change - HEAD does not describe
     * uncommitted work. The source SHA is informational (module versions do not share one SHA
     * suffix, so a single commit SHA does not identify every image anyway).
     */
    public static List<String> labels(Path packageFile, String sourceSha, Instant builtAt) {
        Path name = packageFile.getFileName();
        return List.of(
                "--label", "com.loudsight.package-file=" + (name == null ? "" : name.toString()),
                "--label", "com.loudsight.package-sha256=" + sha256(packageFile),
                "--label", "com.loudsight.source-sha=" + sourceSha,
                "--label", "com.loudsight.built-at=" + DateTimeFormatter.ISO_INSTANT.format(builtAt.truncatedTo(ChronoUnit.SECONDS)));
    }

    /** {@code git rev-parse --short HEAD} in {@code dir}, suffixed {@code -dirty} when the tree differs from HEAD; {@code unknown} outside git. */
    public static String sourceSha(Path dir) {
        Cmd.Result head = Cmd.of("git", "-C", dir.toString(), "rev-parse", "--short", "HEAD").mergeErr().capture();
        if (!head.ok()) {
            return "unknown";
        }
        boolean dirty = Cmd.of("git", "-C", dir.toString(), "diff", "--quiet", "HEAD").mergeErr().capture().exitCode() != 0;
        return head.stdout().strip() + (dirty ? "-dirty" : "");
    }

    /** The full {@code docker build} command line, in the order the shell scripts built it. */
    public static List<String> command(Path moduleDir, String version, List<String> tags, Config config,
            @Nullable Path packageFile, @Nullable User user, List<String> labels) {
        List<String> command = new ArrayList<>(List.of("docker", "build", "--build-arg", "VERSION=" + version));
        if (packageFile != null) {
            command.addAll(List.of("--build-arg", "PACKAGE_FILE=target/" + packageFile.getFileName()));
        }
        if (config.userArgs() && user != null) {
            command.addAll(List.of("--build-arg", "UID=" + user.uid(), "--build-arg", "GID=" + user.gid(),
                    "--build-arg", "UNAME=" + user.name()));
        }
        command.addAll(labels);
        for (String tag : tags) {
            command.addAll(List.of("-t", tag));
        }
        command.addAll(List.of("-f", moduleDir.resolve("Dockerfile").toString(), moduleDir.toString()));
        return command;
    }

    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
