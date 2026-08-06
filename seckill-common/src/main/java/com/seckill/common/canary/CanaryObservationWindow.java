package com.seckill.common.canary;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 6.8 金丝雀观察窗口：每阶段至少观察 30 分钟或 10000 请求（满足任意一个）。
 */
public record CanaryObservationWindow(Instant startTime, Instant endTime, int trafficPercent,
                                      long requestCount, long successCount, long errorCount,
                                      double p99Ms, double mqLagSeconds, long inventoryDiff,
                                      String rollbackStatus) {

    public static final long MIN_OBSERVATION_MINUTES = 30;
    public static final long MIN_REQUEST_COUNT = 10_000;

    public boolean isSatisfied() {
        return elapsedMinutes() >= MIN_OBSERVATION_MINUTES || requestCount >= MIN_REQUEST_COUNT;
    }

    public long elapsedMinutes() {
        return Duration.between(startTime, endTime).toMinutes();
    }
}
