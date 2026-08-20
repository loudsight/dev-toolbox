package com.loudsight.tools.junithelper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DatabusExampleTest {

    @Test
    @Disabled("Flaky timing test")
    public void simplePublishSubscribeTest() throws Exception {
        final DatabusExample.Databus databus = new DatabusExample.AeronDatabus();
        try (var executorService = Executors.newSingleThreadExecutor()) {
            AtomicBoolean running = new AtomicBoolean(true);
            new DatabusExample.ProcessTwo(databus);
            DatabusExample.ProcessOne processOne = new DatabusExample.ProcessOne(databus);
            executorService.submit(() -> {while (running.get()) {processOne.publishStatus();}});

            // this databus doesn't know anything about the internals of processOne and ProcessTwo
            // All we care about is that when the system is started up ProcessTwo publishes an incrementing status
            // Which proves that system is up and functioning - similar to an integration test
            assertProcessTwoPublishesIncrementingStatuses(databus, running, 1);
        }
    }


    @Test
    @Disabled("Flaky timing test")
    public void simpleProcessOneTest() throws Exception {
        final DatabusExample.Databus databus = new DatabusExample.AeronDatabus();
        try (var executorService = Executors.newSingleThreadExecutor()) {
            AtomicBoolean running = new AtomicBoolean(true);
            DatabusExample.ProcessOne processOne = new DatabusExample.ProcessOne(databus);
            executorService.submit(() -> {while (running.get()) {processOne.publishStatus();}});

            var latch = new CountDownLatch(10);
            var publications = new ArrayList<DatabusExample.ProcessOne.Status>();
            databus.makeSubscriber(DatabusExample.Topics.PROCESS_ONE_STATUS, DatabusExample.ProcessOne.Status.class,
                    status -> {
                        publications.add((DatabusExample.ProcessOne.Status) status);
                        latch.countDown();
                        if (latch.getCount() == 0) {
                            running.set(false);
                        }
                    });
            // this databus doesn't know anything about the internals of processOne
            // All we care about is that when the system is started up ProcessOne publishes an incrementing status
            // Which proves that process is working - This is functional unit test
            // assertTrue(latch.await(10L, java.util.concurrent.TimeUnit.SECONDS));
            // AtomicInteger statusCode = new AtomicInteger(0);
            // publications.forEach(it -> assertEquals(statusCode.getAndIncrement(), it.code()));
        }
    }


    @Test
    @Disabled("Flaky timing test")
    public void simpleProcessTwoTest() throws Exception {
        final DatabusExample.Databus databus = new DatabusExample.AeronDatabus();
        try (var executorService = Executors.newSingleThreadExecutor()) {
            AtomicBoolean running = new AtomicBoolean(true);
            new DatabusExample.ProcessTwo(databus);
            var processOnePublisher = databus.makePublisher(DatabusExample.Topics.PROCESS_ONE_STATUS, DatabusExample.ProcessOne.Status.class);
            executorService.submit(() -> {
                AtomicInteger statusCode = new AtomicInteger(1);
                while (running.get()) {processOnePublisher.publish((DatabusExample.ProcessOne.Status) statusCode::getAndIncrement);}
            });

            // this databus doesn't know anything about the internals of processTwo
            // All we care about is that It reacts publications that appear to be from ProcessOne
            // Which proves that process is working - This is functional unit test
            assertProcessTwoPublishesIncrementingStatuses(databus, running, 2);
        }
    }

    /**
     * Subscribe to ProcessTwo's status topic, wait for ten publications, and assert their codes
     * increment from {@code firstExpectedCode}.
     *
     * Shared by the two tests that observe ProcessTwo. They differ only in what drives the
     * publishing and which code the sequence starts at - the observation itself was duplicated
     * verbatim, which cpd-check rejects on test sources since 227a73ad. The differing intent of
     * each test stays at its call site as a comment; only the mechanism lives here.
     */
    private void assertProcessTwoPublishesIncrementingStatuses(DatabusExample.Databus databus,
                                                               AtomicBoolean running,
                                                               int firstExpectedCode) throws InterruptedException {
        var latch = new CountDownLatch(10);
        var publications = new ArrayList<DatabusExample.ProcessTwo.Status>();
        databus.makeSubscriber(DatabusExample.Topics.PROCESS_TWO_STATUS, DatabusExample.ProcessTwo.Status.class,
                status -> {
                    publications.add((DatabusExample.ProcessTwo.Status) status);
                    latch.countDown();
                    if (latch.getCount() == 0) {
                        running.set(false);
                    }
                });
        assertTrue(latch.await(10L, java.util.concurrent.TimeUnit.SECONDS));
        AtomicInteger statusCode = new AtomicInteger(firstExpectedCode);
        publications.forEach(it -> assertEquals(statusCode.getAndIncrement(), it.code()));
    }

}
