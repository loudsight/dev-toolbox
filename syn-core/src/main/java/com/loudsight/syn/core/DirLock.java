package com.loudsight.syn.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A cross-process lock that is an atomically created directory: portable where {@code flock(1)} is
 * not (stock macOS has none). Many syn processes may find the head stale at once and only one
 * should rebuild it. A lock older than {@code staleAfter} was left by a killed process and is taken over.
 */
public final class DirLock implements AutoCloseable {

    private static final long POLL_MILLIS = 200;

    private final Path dir;

    private DirLock(Path dir) {
        this.dir = dir;
    }

    /** Blocks until the lock at {@code dir} is held. */
    public static DirLock acquire(Path dir, Duration staleAfter) {
        try {
            Files.createDirectories(Objects.requireNonNull(dir.getParent(), "a lock path needs a parent directory"));
            while (true) {
                try {
                    Files.createDirectory(dir);
                    return new DirLock(dir);
                } catch (FileAlreadyExistsException held) {
                    if (isStale(dir, staleAfter)) {
                        Files.deleteIfExists(dir);
                    } else {
                        Thread.sleep(POLL_MILLIS);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not lock " + dir, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted locking " + dir, e);
        }
    }

    private static boolean isStale(Path dir, Duration staleAfter) throws IOException {
        return Files.exists(dir) && Files.getLastModifiedTime(dir).toInstant().isBefore(ZonedDateTime.now(ZoneOffset.UTC).toInstant().minus(staleAfter));
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("could not release " + dir, e);
        }
    }
}
