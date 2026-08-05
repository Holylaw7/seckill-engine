package com.seckill.common.canary;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthDecision;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryHealthEvaluator.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 健康门禁规则单测。
 */
class CanaryHealthEvaluatorTest {

    private static HealthSnapshot healthy() {
        return new HealthSnapshot(100, 0.0, 300, 5, 0, 0, 0, 0);
    }

    @Test
    void healthyShouldPass() {
        HealthDecision decision = CanaryHealthEvaluator.evaluate(healthy());
        assertThat(decision.level()).isEqualTo(Level.PASS);
        assertThat(decision.isPass()).isTrue();
    }

    @Test
    void warningThresholdsShouldBeDetected() {
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.2, 300, 5, 0, 0, 0, 0)).level())
                .isEqualTo(Level.WARNING);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 600, 5, 0, 0, 0, 0)).level())
                .isEqualTo(Level.WARNING);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 300, 45, 0, 0, 0, 0)).level())
                .isEqualTo(Level.WARNING);
    }

    @Test
    void criticalThresholdsShouldTakePrecedence() {
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 1.5, 300, 5, 0, 0, 0, 0)).level())
                .isEqualTo(Level.CRITICAL);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 300, 5, 1, 0, 0, 0)).level())
                .isEqualTo(Level.CRITICAL);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 300, 5, 0, 1, 0, 0)).level())
                .isEqualTo(Level.CRITICAL);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 300, 5, 0, 0, 3, 0)).level())
                .isEqualTo(Level.CRITICAL);
        assertThat(CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 0.0, 300, 5, 0, 0, 0, 2)).level())
                .isEqualTo(Level.CRITICAL);
    }

    @Test
    void criticalShouldListReasons() {
        HealthDecision decision = CanaryHealthEvaluator.evaluate(
                new HealthSnapshot(100, 2.0, 800, 60, 1, 0, 1, 1));
        assertThat(decision.isCritical()).isTrue();
        assertThat(decision.reasons()).contains("oversell>0", "error_rate>1%", "dlq>0");
    }
}
