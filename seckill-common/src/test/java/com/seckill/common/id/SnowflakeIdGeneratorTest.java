package com.seckill.common.id;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class SnowflakeIdGeneratorTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 100_000;

    @Test
    void shouldGenerateOneMillionUniqueIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        Set<Long> ids = new HashSet<>();
        long previous = -1L;
        for (int i = 0; i < 1_000_000; i++) {
            long id = generator.nextId();
            assertTrue(ids.add(id), "duplicate id: " + id);
            assertTrue(id > previous, "id not increasing");
            previous = id;
        }
        assertEquals(1_000_000, ids.size());
    }

    @Test
    void shouldGenerateUniqueIdsUnderConcurrency() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < PER_THREAD; i++) {
                            ids.add(generator.nextId());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "generation timed out");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(THREADS * PER_THREAD, ids.size());
    }

    @Test
    void smallBackwardShouldWaitAndContinue() {
        long t0 = 1_800_000_000_000L;
        TimelineClock clock = new TimelineClock(t0, t0 - 3, t0, t0 + 1);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);
        long first = generator.nextId();
        long second = generator.nextId();
        assertTrue(second > first);
    }

    @Test
    void largeBackwardShouldFailFast() {
        long t0 = 1_800_000_000_000L;
        TimelineClock clock = new TimelineClock(t0, t0 - 10);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, clock);
        generator.nextId();
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    @Test
    void workerIdOutOfRangeShouldReject() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }

    @Test
    void differentWorkerIdsShouldNotCollide() {
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(1);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(2);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            ids.add(a.nextId());
            ids.add(b.nextId());
        }
        assertEquals(200_000, ids.size());
    }

    /**
     * 可编排时间线的测试时钟：每次调用按序返回下一个时间点。
     */
    private static final class TimelineClock extends Clock {

        private final long[] timeline;
        private final AtomicInteger index = new AtomicInteger();

        TimelineClock(long... timeline) {
            this.timeline = timeline;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return timeline[Math.min(index.getAndIncrement(), timeline.length - 1)];
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
