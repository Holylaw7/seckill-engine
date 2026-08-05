package com.seckill.integration.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 持续压测驱动（Phase 6.4）：固定并发 + 分波次 barrier 连续压测，直到达到指定时长。
 * 禁止固定 sleep 等待业务结果。
 */
public final class SustainedLoadExecutor {

    private SustainedLoadExecutor() {
    }

    public record Result(long totalRequests, long successCount, long failureCount,
                         List<Long> latenciesNanos, long startNanos, long endNanos) {

        public double qps() {
            double seconds = (endNanos - startNanos) / 1_000_000_000.0;
            return seconds <= 0 ? 0 : totalRequests / seconds;
        }
    }

    public static Result run(int concurrency, Duration duration, LoadAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "sustained-load");
            thread.setDaemon(true);
            return thread;
        });
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong success = new AtomicLong();
        AtomicLong failure = new AtomicLong();
        long start = System.nanoTime();
        long deadline = start + duration.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                CountDownLatch barrier = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(concurrency);
                for (int i = 0; i < concurrency; i++) {
                    pool.submit(() -> {
                        try {
                            barrier.await();
                            long taskStart = System.nanoTime();
                            boolean ok = action.execute(0);
                            latencies.add(System.nanoTime() - taskStart);
                            if (ok) {
                                success.incrementAndGet();
                            } else {
                                failure.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failure.incrementAndGet();
                        } catch (Exception e) {
                            failure.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                barrier.countDown();
                if (!done.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("sustained load wave timed out");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(success.get() + failure.get(), success.get(), failure.get(),
                latencies, start, System.nanoTime());
    }
}
