package com.loudsight.scriptkit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An exclusive {@code flock(2)} lock on a file, shared with shell scripts that take the same lock
 * with {@code flock}. Java's own {@code FileChannel.lock()} uses fcntl record locks, which on Linux
 * do NOT exclude flock locks - so a Java script and a shell script "sharing" a lock file that way
 * would run concurrently.
 *
 * <p>The lock is held by a child {@code flock <file> sh -c 'echo locked; read x'}: it prints once
 * it holds the lock, then waits on stdin. Closing this releases it; if the JVM dies instead, the
 * child sees end-of-input, exits, and the kernel drops the lock with it.
 */
@SuppressFBWarnings(
        value = "COMMAND_INJECTION",
        justification = "Runs the fixed flock command on a lock-file path chosen by the repo's own scripts.")
public final class Flock implements AutoCloseable {

    private final Process holder;

    private Flock(Process holder) {
        this.holder = holder;
    }

    /** Blocks until the lock on {@code file} is held. */
    public static Flock acquire(Path file) {
        try {
            Process holder = new ProcessBuilder(List.of("flock", file.toString(), "sh", "-c", "echo locked; read x"))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String line;
            // Only the one "locked" line is ever written; closing our end does not touch the lock.
            try (BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
                line = out.readLine();
            }
            if (!"locked".equals(line)) {
                holder.destroyForcibly();
                throw new IllegalStateException("could not lock " + file + " (flock exited " + holder.waitFor() + ")");
            }
            return new Flock(holder);
        } catch (IOException e) {
            throw new UncheckedIOException("could not run flock for " + file, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted locking " + file, e);
        }
    }

    @Override
    public void close() {
        try {
            holder.getOutputStream().close();
            if (!holder.waitFor(5, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        } catch (IOException e) {
            holder.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            holder.destroyForcibly();
        }
    }
}
