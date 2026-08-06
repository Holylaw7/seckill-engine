package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.gateway.canary.ProductionCanaryExecutor.ExecutionResult;
import com.seckill.gateway.canary.ProductionCanaryExecutor.StageObservation;
import com.seckill.gateway.canary.ProductionCanaryManager.Stage;
import com.seckill.gateway.config.CanaryTrafficProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.9 ProductionCanaryExecutor：Stage 0 → 5% → 25% → 50% → 100% → GA 全流程；
 * preflight 失败、阶段健康 CRITICAL、窗口未满足均失败。
 */
class ProductionCanaryExecutorTest {

    private static HealthSnapshot healthy() {
        return HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, 0);
    }

    private static CanaryObservationWindow window() {
        Instant now = Instant.now();
        return new CanaryObservationWindow(now.minusSeconds(31 * 60), now, 5,
                12000, 12000, 0, 300, 5, 0, "NONE");
    }

    private static List<StageObservation> observations() {
        return List.of(
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()));
    }

    @Test
    void allStagesHealthyShouldCompleteToGa() {
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);

        ExecutionResult result = executor.execute(List.of(), observations());

        assertThat(result.completed()).isTrue();
        assertThat(result.finalStage()).isEqualTo(Stage.GA);
        assertThat(result.failures()).isEmpty();
        assertThat(result.history()).hasSize(5);
    }

    @Test
    void preflightFailureShouldBlockExecution() {
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);

        ExecutionResult result = executor.execute(
                List.of("redis-consistency", "dependency-scan"), observations());

        assertThat(result.completed()).isFalse();
        assertThat(result.failures()).containsExactly("redis-consistency", "dependency-scan");
        assertThat(manager.stage()).isEqualTo(Stage.INIT);
    }

    @Test
    void criticalAt25ShouldRollbackToInitAndFail() {
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);
        List<StageObservation> observations = List.of(
                new StageObservation(window(), healthy()),
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, 3, 0, 0, 0)),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()));

        ExecutionResult result = executor.execute(List.of(), observations);

        assertThat(result.completed()).isFalse();
        // 25% 推进评估出现 CRITICAL：回退到最近确认阶段（INIT，全量 stable），禁止扩大流量
        assertThat(manager.stage()).isEqualTo(Stage.INIT);
        assertThat(result.failures()).anyMatch(failure -> failure.contains("ROLLED_BACK_TO_INIT"));
    }

    @Test
    void unsatisfiedFivePercentWindowShouldFail() {
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);
        Instant now = Instant.now();
        CanaryObservationWindow shortWindow = new CanaryObservationWindow(
                now.minusSeconds(600), now, 5, 1000, 1000, 0, 300, 5, 0, "NONE");
        List<StageObservation> observations = List.of(
                new StageObservation(shortWindow, healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()),
                new StageObservation(window(), healthy()));

        ExecutionResult result = executor.execute(List.of(), observations);

        assertThat(result.completed()).isFalse();
        assertThat(result.failures()).anyMatch(failure -> failure.contains("5%"));
        assertThat(manager.stage()).isEqualTo(Stage.INIT);
    }
}
