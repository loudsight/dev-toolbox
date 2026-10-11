package com.loudsight.scriptkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlockTest {

    /** Needs the flock(1) binary: util-linux has it (the deploy host); stock macOS does not. */
    @Test
    void excludesAShellFlockUntilClosed(@TempDir Path dir) {
        assumeTrue(Cmd.of("sh", "-c", "command -v flock").discardErr().capture().ok(), "flock(1) not installed");
        Path lock = dir.resolve("x.lock");
        try (Flock _ = Flock.acquire(lock)) {
            assertEquals(1, Cmd.of("flock", "-n", lock.toString(), "true").discardErr().capture().exitCode());
        }
        assertEquals(0, Cmd.of("flock", "-n", lock.toString(), "true").discardErr().capture().exitCode());
    }
}
