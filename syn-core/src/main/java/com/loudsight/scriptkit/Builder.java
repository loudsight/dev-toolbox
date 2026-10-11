package com.loudsight.scriptkit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The browser/test builder image (build/automation/builder-container) and what running it needs. */
@SuppressFBWarnings(
        value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "/var/run/docker.sock is where the Docker daemon listens on every host this runs on; the builder mounts it by that path.")
public final class Builder {

    public static final String DIR = "build/automation/builder-container";
    public static final String DEFAULT_IMAGE = "localhost:5001/loudsight/builder-container:0.0.1";
    public static final Path DOCKER_SOCKET = Path.of("/var/run/docker.sock");

    private Builder() {
    }

    /** {@code BUILDER_IMAGE}, else the default tag. */
    public static String image() {
        return Env.get("BUILDER_IMAGE").orElse(DEFAULT_IMAGE);
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
