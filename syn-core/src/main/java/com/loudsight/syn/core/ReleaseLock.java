package com.loudsight.syn.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Says that a release is using the tree syn builds itself from. A release edits the very sources the head hashes
 * and builds the same modules into the same {@code target/} directories, so a syn started elsewhere must not rebuild
 * the shared cache while it runs; the head's self-update reads {@link #liveHolder} and runs the cached build as it is.
 *
 * <p>The lock is a file {@value #FILE_NAME} inside the cache, holding the holder's pid and when it took the lock. It is
 * ignored, and may be taken over, when that pid is no longer alive or the lock is older than {@link #MAX_AGE}: a killed
 * release must not stop syn rebuilding for ever, and a recycled pid cannot keep a lock past the bound.
 */
public final class ReleaseLock implements AutoCloseable {

    public static final String FILE_NAME = "release.lock";
    /** Longer than any release has taken; past this a lock is a leftover whatever its pid says. */
    public static final Duration MAX_AGE = Duration.ofHours(6);
    /** The lock the head's self-update holds while it rebuilds the cache. */
    public static final String BUILD_LOCK = ".build.lock";
    /** A build lock older than this was left by a killed rebuild. */
    public static final Duration BUILD_LOCK_STALE = Duration.ofMinutes(5);

    private static final String PID = "pid";
    private static final String STARTED = "started";

    /** Who holds the lock: a pid and when it took the lock. */
    public record Holder(long pid, Instant started) {
    }

    private final Path file;
    private final long pid;

    private ReleaseLock(Path file, long pid) {
        this.file = file;
        this.pid = pid;
    }

    /** Where the lock lives in {@code cache}. */
    public static Path path(Path cache) {
        return cache.resolve(FILE_NAME);
    }

    /** The holder of a lock that still counts, or empty when there is none or it is stale. */
    public static Optional<Holder> liveHolder(Path cache) {
        return liveHolder(cache, ZonedDateTime.now(ZoneOffset.UTC).toInstant(), ReleaseLock::isAlive);
    }

    static Optional<Holder> liveHolder(Path cache, Instant now, LongPredicate alive) {
        return read(path(cache)).filter(holder -> alive.test(holder.pid()) && !holder.started().isBefore(now.minus(MAX_AGE)));
    }

    /**
     * Takes the lock for this process, then waits for a rebuild already in progress to finish, so nothing writes to the
     * cache or the build tree once this returns.
     *
     * @throws IllegalStateException when another live process holds it
     */
    public static ReleaseLock acquire(Path cache) {
        ReleaseLock lock = acquire(cache, ProcessHandle.current().pid(), ZonedDateTime.now(ZoneOffset.UTC).toInstant(), ReleaseLock::isAlive);
        try {
            DirLock.acquire(cache.resolve(BUILD_LOCK), BUILD_LOCK_STALE).close();
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
        return lock;
    }

    static ReleaseLock acquire(Path cache, long pid, Instant now, LongPredicate alive) {
        Path file = path(cache);
        try {
            Files.createDirectories(cache);
            Path written = Files.createTempFile(cache, FILE_NAME, ".tmp");
            try {
                Files.writeString(written, PID + "=" + pid + "\n" + STARTED + "=" + now + "\n", StandardCharsets.UTF_8);
                return link(file, written, pid, cache, now, alive);
            } finally {
                Files.deleteIfExists(written);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not take the release lock " + file, e);
        }
    }

    /** A hard link appears whole or not at all, so no reader ever sees a half-written lock. */
    private static ReleaseLock link(Path file, Path written, long pid, Path cache, Instant now, LongPredicate alive) throws IOException {
        try {
            Files.createLink(file, written);
        } catch (FileAlreadyExistsException held) {
            Optional<Holder> holder = liveHolder(cache, now, alive);
            if (holder.isPresent()) {
                throw new IllegalStateException("a release already holds " + file + " (pid " + holder.get().pid() + " since "
                        + holder.get().started() + ")", held);
            }
            Files.deleteIfExists(file);
            Files.createLink(file, written);
        }
        return new ReleaseLock(file, pid);
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static Optional<Holder> read(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the release lock " + file, e);
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : content.split("\n", -1)) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
            }
        }
        try {
            return Optional.of(new Holder(Long.parseLong(Objects.requireNonNullElse(fields.get(PID), "")),
                    Instant.parse(Objects.requireNonNullElse(fields.get(STARTED), ""))));
        } catch (NumberFormatException | DateTimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Removes the lock, unless another process has since taken it over. */
    @Override
    public void close() {
        if (read(file).filter(holder -> holder.pid() == pid).isPresent()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("could not release " + file, e);
            }
        }
    }
}
