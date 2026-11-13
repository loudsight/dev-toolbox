package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseLockTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final LongPredicate ALL_ALIVE = ReleaseLockTest::alive;
    private static final LongPredicate ALL_DEAD = ALL_ALIVE.negate();

    private static boolean alive(long ignoredPid) {
        return true;
    }

    @Test
    void theLockIsAFileInsideTheCache(@TempDir Path cache) {
        assertEquals(cache.resolve("release.lock"), ReleaseLock.path(cache));
        try (ReleaseLock _ = ReleaseLock.acquire(cache)) {
            assertTrue(Files.isRegularFile(cache.resolve("release.lock")));
            ReleaseLock.Holder holder = ReleaseLock.liveHolder(cache).orElseThrow();
            assertEquals(ProcessHandle.current().pid(), holder.pid());
        }
        assertFalse(Files.exists(cache.resolve("release.lock")), "closing releases the lock");
        assertTrue(ReleaseLock.liveHolder(cache).isEmpty());
    }

    @Test
    void aLockHeldByALiveProcessIsReported(@TempDir Path cache) throws IOException {
        write(cache, "pid=4242\nstarted=" + NOW.minus(Duration.ofMinutes(20)) + "\n");

        ReleaseLock.Holder holder = ReleaseLock.liveHolder(cache, NOW, ALL_ALIVE).orElseThrow();

        assertEquals(4242, holder.pid());
        assertEquals(NOW.minus(Duration.ofMinutes(20)), holder.started());
    }

    @Test
    void aLockLeftByADeadProcessIsIgnored(@TempDir Path cache) throws IOException {
        write(cache, "pid=4242\nstarted=" + NOW.minus(Duration.ofMinutes(1)) + "\n");

        assertTrue(ReleaseLock.liveHolder(cache, NOW, ALL_DEAD).isEmpty());
    }

    @Test
    void aLockFromAProcessThatHasExitedIsIgnored(@TempDir Path cache) throws Exception {
        Process exited = new ProcessBuilder("true").start();
        assertTrue(exited.waitFor(60, TimeUnit.SECONDS));
        write(cache, "pid=" + exited.pid() + "\nstarted=" + ZonedDateTime.now(ZoneOffset.UTC).toInstant() + "\n");

        assertTrue(ReleaseLock.liveHolder(cache).isEmpty());
    }

    @Test
    void aLockOlderThanTheBoundIsIgnoredEvenIfItsPidIsAlive(@TempDir Path cache) throws IOException {
        write(cache, "pid=4242\nstarted=" + NOW.minus(ReleaseLock.MAX_AGE).minusSeconds(1) + "\n");

        assertTrue(ReleaseLock.liveHolder(cache, NOW, ALL_ALIVE).isEmpty());
    }

    @Test
    void anUnreadableLockIsIgnored(@TempDir Path cache) throws IOException {
        write(cache, "not a lock\n");

        assertTrue(ReleaseLock.liveHolder(cache, NOW, ALL_ALIVE).isEmpty());
    }

    @Test
    void aSecondReleaseIsRefusedWhileALiveLockIsHeld(@TempDir Path cache) throws IOException {
        write(cache, "pid=4242\nstarted=" + NOW + "\n");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ReleaseLock.acquire(cache, 7, NOW, ALL_ALIVE));

        assertTrue(String.valueOf(refused.getMessage()).contains("pid 4242"), refused.getMessage());
        assertTrue(Files.readString(ReleaseLock.path(cache)).contains("pid=4242"), "the holder's lock is left alone");
    }

    @Test
    void aStaleLockIsTakenOver(@TempDir Path cache) throws IOException {
        write(cache, "pid=4242\nstarted=" + NOW + "\n");

        try (ReleaseLock _ = ReleaseLock.acquire(cache, 7, NOW, ALL_DEAD)) {
            assertEquals(7, ReleaseLock.liveHolder(cache, NOW, ALL_ALIVE).orElseThrow().pid());
        }
        assertFalse(Files.exists(ReleaseLock.path(cache)));
    }

    @Test
    void closingLeavesALockThatAnotherProcessHasSinceTaken(@TempDir Path cache) throws IOException {
        try (ReleaseLock _ = ReleaseLock.acquire(cache, 7, NOW, ALL_DEAD)) {
            write(cache, "pid=4242\nstarted=" + NOW + "\n");
        }

        assertTrue(Files.readString(ReleaseLock.path(cache)).contains("pid=4242"));
    }

    /** A rebuild that started before the release took the lock is waited out, so it cannot write into the tree the release builds. */
    @Test
    void acquiringWaitsForARebuildInProgress(@TempDir Path cache) throws Exception {
        DirLock rebuild = DirLock.acquire(cache.resolve(ReleaseLock.BUILD_LOCK), ReleaseLock.BUILD_LOCK_STALE);
        CompletableFuture<ReleaseLock> release = CompletableFuture.supplyAsync(() -> ReleaseLock.acquire(cache));
        try {
            Thread.sleep(500);
            assertFalse(release.isDone(), "the release waits while a rebuild holds the build lock");
        } finally {
            rebuild.close();
        }
        try (ReleaseLock _ = release.get(5, TimeUnit.SECONDS)) {
            assertTrue(ReleaseLock.liveHolder(cache).isPresent());
        } catch (TimeoutException e) {
            throw new AssertionError("the release never got the lock", e);
        }
    }

    private static void write(Path cache, String content) throws IOException {
        Files.createDirectories(cache);
        Files.writeString(ReleaseLock.path(cache), content);
    }
}
