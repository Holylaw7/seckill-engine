package com.seckill.integration.load;

import java.util.Arrays;

/**
 * 压测指标：请求计数、耗时（纳秒）、QPS 与 RT 分位数。
 */
public final class LoadMetrics {

    private final int totalRequests;
    private final int successCount;
    private final int failureCount;
    private final long startEpochMillis;
    private final long endEpochMillis;
    private final long startNanos;
    private final long endNanos;
    private final long[] latenciesNanos;

    private LoadMetrics(int totalRequests, int successCount, int failureCount,
                        long startEpochMillis, long endEpochMillis,
                        long startNanos, long endNanos, long[] latenciesNanos) {
        this.totalRequests = totalRequests;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.startEpochMillis = startEpochMillis;
        this.endEpochMillis = endEpochMillis;
        this.startNanos = startNanos;
        this.endNanos = endNanos;
        this.latenciesNanos = latenciesNanos;
    }

    public static LoadMetrics of(int totalRequests, int successCount, int failureCount,
                                 long startEpochMillis, long endEpochMillis,
                                 long startNanos, long endNanos, long[] latenciesNanos) {
        return new LoadMetrics(totalRequests, successCount, failureCount,
                startEpochMillis, endEpochMillis, startNanos, endNanos, latenciesNanos);
    }

    public int totalRequests() {
        return totalRequests;
    }

    public int successCount() {
        return successCount;
    }

    public int failureCount() {
        return failureCount;
    }

    public double successRate() {
        return totalRequests == 0 ? 0.0 : successCount * 100.0 / totalRequests;
    }

    public long startTimeMillis() {
        return startEpochMillis;
    }

    public long endTimeMillis() {
        return endEpochMillis;
    }

    public long durationMillis() {
        return (endNanos - startNanos) / 1_000_000;
    }

    public double qps() {
        double seconds = (endNanos - startNanos) / 1_000_000_000.0;
        return seconds <= 0 ? 0.0 : totalRequests / seconds;
    }

    public double avgRtMs() {
        long[] valid = validLatencies();
        if (valid.length == 0) {
            return 0.0;
        }
        return Arrays.stream(valid).average().orElse(0.0) / 1_000_000.0;
    }

    public double p50Ms() {
        return percentileMs(50);
    }

    public double p95Ms() {
        return percentileMs(95);
    }

    public double p99Ms() {
        return percentileMs(99);
    }

    private double percentileMs(double percentile) {
        long[] sorted = validLatencies();
        if (sorted.length == 0) {
            return 0.0;
        }
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private long[] validLatencies() {
        return Arrays.stream(latenciesNanos).filter(value -> value >= 0).toArray();
    }
}
