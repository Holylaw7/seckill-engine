package com.seckill.integration.load;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 稳定性压测指标采集器：累计计数 + 每 10s 窗口采样（RT 分位数、类型分布、错误码分布）。
 */
final class StableMetricsCollector {

    record WindowSample(
            long timestampMillis,
            long elapsedSeconds,
            long requestCount,
            long success,
            long failed,
            double qps,
            double avgRT,
            double p95,
            double p99,
            long heapUsed,
            int threadCount,
            long gcCount,
            long mqProducer,
            long mqConsumer,
            long mqBacklog,
            String redisStock,
            String mysqlAvailable,
            String mysqlLocked,
            Map<String, Long> typeCounts,
            Map<String, Long> errorCodes) {
    }

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private final AtomicLong unexpectedFailures = new AtomicLong();
    private final AtomicLong windowRequests = new AtomicLong();
    private final AtomicLong windowSuccess = new AtomicLong();
    private final AtomicLong windowFailed = new AtomicLong();
    private final Map<String, AtomicLong> typeCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorCodes = new ConcurrentHashMap<>();
    private final AtomicReference<ConcurrentLinkedQueue<Long>> latencies =
            new AtomicReference<>(new ConcurrentLinkedQueue<>());
    private final List<WindowSample> windows = new CopyOnWriteArrayList<>();

    void record(String type, long latencyNanos, boolean success, int code) {
        totalRequests.incrementAndGet();
        windowRequests.incrementAndGet();
        if (success) {
            totalSuccess.incrementAndGet();
            windowSuccess.incrementAndGet();
        } else {
            totalFailed.incrementAndGet();
            windowFailed.incrementAndGet();
            if (!isExpected(code)) {
                unexpectedFailures.incrementAndGet();
            }
        }
        typeCounts.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        errorCodes.computeIfAbsent(String.valueOf(code), k -> new AtomicLong()).incrementAndGet();
        latencies.get().add(latencyNanos);
    }

    WindowSample sample(long elapsedSeconds, long heapUsed, int threadCount, long gcCount,
                        long mqProducer, long mqConsumer, long mqBacklog, String redisStock,
                        String mysqlAvailable, String mysqlLocked) {
        ConcurrentLinkedQueue<Long> current = latencies.getAndSet(new ConcurrentLinkedQueue<>());
        long[] values = current.stream().mapToLong(Long::longValue).toArray();
        long windowRequestsValue = windowRequests.getAndSet(0);
        long windowSuccessValue = windowSuccess.getAndSet(0);
        long windowFailedValue = windowFailed.getAndSet(0);
        double qps = windowRequestsValue / 10.0;
        double avgRT = values.length == 0 ? 0 : Arrays.stream(values).average().orElse(0) / 1_000_000.0;
        Arrays.sort(values);
        WindowSample sample = new WindowSample(
                System.currentTimeMillis(),
                elapsedSeconds,
                windowRequestsValue,
                windowSuccessValue,
                windowFailedValue,
                Math.round(qps * 100.0) / 100.0,
                Math.round(avgRT * 100.0) / 100.0,
                Math.round(percentile(values, 95) * 100.0) / 100.0,
                Math.round(percentile(values, 99) * 100.0) / 100.0,
                heapUsed,
                threadCount,
                gcCount,
                mqProducer,
                mqConsumer,
                mqBacklog,
                redisStock,
                mysqlAvailable,
                mysqlLocked,
                snapshot(typeCounts),
                snapshot(errorCodes));
        windows.add(sample);
        return sample;
    }

    long totalRequests() {
        return totalRequests.get();
    }

    long totalSuccess() {
        return totalSuccess.get();
    }

    long totalFailed() {
        return totalFailed.get();
    }

    long unexpectedFailures() {
        return unexpectedFailures.get();
    }

    List<WindowSample> windows() {
        return List.copyOf(windows);
    }

    private static boolean isExpected(int code) {
        return code == 0 || code == 30004 || code == 30005;
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private static Map<String, Long> snapshot(Map<String, AtomicLong> source) {
        Map<String, Long> copy = new TreeMap<>();
        source.forEach((key, value) -> copy.put(key, value.get()));
        return copy;
    }
}
