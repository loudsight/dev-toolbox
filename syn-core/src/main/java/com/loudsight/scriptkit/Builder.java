package com.loudsight.scriptkit;

import com.loudsight.syn.core.SynConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The browser/test builder image (context directory {@code builder.dir}, tag {@code builder.image}) and what running it needs. */
@SuppressFBWarnings(
        value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "/var/run/docker.sock is where the Docker daemon listens on every host this runs on; the builder mounts it by that path.")
public final class Builder {

    /** Used when {@code builder.dir} is not set in {@code .syn/config.properties}. */
    public static final String DEFAULT_DIR = "builder-container";
    /** Used when neither {@code BUILDER_IMAGE} nor {@code builder.image} is set. */
    public static final String DEFAULT_IMAGE = "builder-container:latest";
    public static final Path DOCKER_SOCKET = Path.of("/var/run/docker.sock");

    private Builder() {
    }

    /** The builder image's build context, relative to the repo root: {@code builder.dir}, else {@link #DEFAULT_DIR}. */
    public static String dir() {
        return SynConfig.load(Env.repoRoot()).get("builder.dir").orElse(DEFAULT_DIR);
    }

    /** {@code BUILDER_IMAGE}, else {@code builder.image}, else {@link #DEFAULT_IMAGE}. */
    public static String image() {
        return Env.get("BUILDER_IMAGE").or(() -> SynConfig.load(Env.repoRoot()).get("builder.image")).orElse(DEFAULT_IMAGE);
    }

    /**
     * The docker socket's group id, following the symlink Docker Desktop makes. GNU and BSD stat
     * spell the format flag differently, so both are tried.
     */
    public static Optional<String> dockerSocketGid() {
        if (!Files.exists(DOCKER_SOCKET)) {
            return Optional.empty();
        }
        for (List<String> format : List.of(List.of("-c", "%g"), List.of("-f", "%g"))) {
            Cmd.Result result = Cmd.of("stat", "-L", format.get(0), format.get(1), DOCKER_SOCKET.toString()).discardErr().capture();
            if (result.ok() && !result.stdout().isBlank()) {
                return Optional.of(result.stdout().strip());
            }
        }
        return Optional.empty();
    }
}
