package com.seckill.common.canary;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.8 观察窗口：≥30min 或 ≥10000 请求满足其一。
 */
class CanaryObservationWindowTest {

    private static CanaryObservationWindow window(Instant start, Instant end, long requests) {
        return new CanaryObservationWindow(start, end, 5, requests, requests, 0,
                300, 5, 0, "NONE");
    }

    @Test
    void thirtyMinutesShouldSatisfyEvenWithFewRequests() {
        CanaryObservationWindow window = window(
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:30:00Z"), 100);
        assertThat(window.isSatisfied()).isTrue();
        assertThat(window.elapsedMinutes()).isEqualTo(30);
    }

    @Test
    void tenThousandRequestsShouldSatisfyEvenBeforeThirtyMinutes() {
        CanaryObservationWindow window = window(
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:05:00Z"), 10000);
        assertThat(window.isSatisfied()).isTrue();
    }

    @Test
    void shortWindowWithFewRequestsShouldNotSatisfy() {
        CanaryObservationWindow window = window(
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:10:00Z"), 5000);
        assertThat(window.isSatisfied()).isFalse();
    }
}
