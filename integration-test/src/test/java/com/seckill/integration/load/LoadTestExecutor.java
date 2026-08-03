package com.seckill.integration.load;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实并发负载驱动：
 * ExecutorService 固定并发 + CountDownLatch barrier 同时放行 + 超时控制 + 成败统计。
 */
public final class LoadTestExecutor {

    private LoadTestExecutor() {
    }

    public static LoadMetrics run(LoadConfig config, LoadAction action) throws Exception {
        int total = config.totalRequests();
        ExecutorService pool = Executors.newFixedThreadPool(config.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "load-worker");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch barrier = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        long[] latencies = new long[total];
        Arrays.fill(latencies, -1L);

        try {
            for (int i = 0; i < total; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        barrier.await();
                        long start = System.nanoTime();
                        boolean ok;
                        try {
                            ok = action.execute(index);
                        } catch (Exception e) {
                            ok = false;
                        }
                        latencies[index] = System.nanoTime() - start;
                        if (ok) {
                            success.incrementAndGet();
                        } else {
                            failure.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failure.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            long startNanos = System.nanoTime();
            barrier.countDown();
            if (!done.await(config.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("load execution timed out after " + config.timeout()
                        + ", completed=" + (success.get() + failure.get()) + "/" + total);
            }
            long endNanos = System.nanoTime();
            return LoadMetrics.of(total, success.get(), failure.get(), startNanos, endNanos, latencies);
        } finally {
            pool.shutdownNow();
        }
    }
}
