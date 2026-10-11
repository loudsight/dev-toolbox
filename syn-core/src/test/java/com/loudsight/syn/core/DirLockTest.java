package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirLockTest {

    @Test
    void holdsTheLockUntilClosed(@TempDir Path temp) {
        Path lock = temp.resolve("x.lock");
        try (DirLock _ = DirLock.acquire(lock, Duration.ofMinutes(5))) {
            assertTrue(Files.isDirectory(lock));
        }
        assertFalse(Files.exists(lock));
    }

    @Test
    void aSecondAcquirerWaitsForTheFirst(@TempDir Path temp) throws Exception {
        Path lock = temp.resolve("x.lock");
        DirLock first = DirLock.acquire(lock, Duration.ofMinutes(5));
        CompletableFuture<DirLock> second = CompletableFuture.supplyAsync(() -> DirLock.acquire(lock, Duration.ofMinutes(5)));
        try {
            Thread.sleep(500);
            assertFalse(second.isDone());
        } finally {
            first.close();
        }
        try (DirLock _ = second.get(5, TimeUnit.SECONDS)) {
            assertTrue(Files.isDirectory(lock));
        } catch (TimeoutException e) {
            throw new AssertionError("second acquirer never got the lock", e);
        }
    }

    @Test
    void aLockLeftByAKilledProcessIsTakenOver(@TempDir Path temp) throws IOException {
        Path lock = temp.resolve("x.lock");
        Files.createDirectory(lock);
        Files.setLastModifiedTime(lock, FileTime.from(ZonedDateTime.now(ZoneOffset.UTC).toInstant().minus(Duration.ofMinutes(10))));

        try (DirLock _ = DirLock.acquire(lock, Duration.ofMinutes(5))) {
            assertTrue(Files.isDirectory(lock));
        }
    }
}
