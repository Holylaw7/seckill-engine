package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryHealthEvaluator.Level;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.gateway.canary.ProductionCanaryManager.Stage;
import com.seckill.gateway.config.CanaryTrafficProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.8 ProductionCanaryManager：状态机推进、窗口门禁、WARNING 暂停、CRITICAL 自动回滚。
 */
class ProductionCanaryManagerTest {

    private static CanaryTrafficProperties properties() {
        CanaryTrafficProperties properties = new CanaryTrafficProperties();
        properties.setEnabled(false);
        properties.setWeight(0);
        return properties;
    }

    private static HealthSnapshot healthy() {
        return HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, 0);
    }

    private static CanaryObservationWindow satisfiedWindow(int percent) {
        Instant now = Instant.now();
        return new CanaryObservationWindow(now.minusSeconds(31 * 60), now, percent,
                12000, 12000, 0, 300, 5, 0, "NONE");
    }

    @Test
    void shouldAdvanceThroughAllStagesToGaWhenHealthy() {
        CanaryTrafficProperties properties = properties();
        ProductionCanaryManager manager = new ProductionCanaryManager(properties);

        assertThat(manager.start()).isTrue();
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_5);
        assertThat(properties.getWeight()).isEqualTo(5);

        manager.observeAndAdvance(healthy(), satisfiedWindow(5));
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_25);
        assertThat(properties.getWeight()).isEqualTo(25);

        manager.observeAndAdvance(healthy(), satisfiedWindow(25));
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_50);

        manager.observeAndAdvance(healthy(), satisfiedWindow(50));
        assertThat(manager.stage()).isEqualTo(Stage.FULL_RELEASE);
        assertThat(properties.getWeight()).isEqualTo(100);

        manager.observeAndAdvance(healthy(), satisfiedWindow(100));
        assertThat(manager.stage()).isEqualTo(Stage.GA);
        assertThat(manager.history()).hasSize(5);
    }

    @Test
    void unsatisfiedWindowShouldNotAdvance() {
        CanaryTrafficProperties properties = properties();
        ProductionCanaryManager manager = new ProductionCanaryManager(properties);
        manager.start();
        Instant now = Instant.now();
        CanaryObservationWindow shortWindow = new CanaryObservationWindow(
                now.minusSeconds(600), now, 5, 1000, 1000, 0, 300, 5, 0, "NONE");

        ProductionCanaryManager.StageRecord record =
                manager.observeAndAdvance(healthy(), shortWindow);

        assertThat(manager.stage()).isEqualTo(Stage.CANARY_5);
        assertThat(record.rollbackStatus()).isEqualTo("NOT_READY");
    }

    @Test
    void warningShouldPauseAndResumeAfterConfirmation() {
        CanaryTrafficProperties properties = properties();
        ProductionCanaryManager manager = new ProductionCanaryManager(properties);
        manager.start();

        ProductionCanaryManager.StageRecord record = manager.observeAndAdvance(
                HealthSnapshot.of(500, 0.2, 300, 5, 0, 0, 0, 0), satisfiedWindow(5));

        assertThat(record.healthLevel()).isEqualTo(Level.WARNING);
        assertThat(manager.isPaused()).isTrue();
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_5);

        manager.resume();
        manager.observeAndAdvance(healthy(), satisfiedWindow(5));
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_25);
    }

    @Test
    void criticalShouldAutoRollbackToPreviousStableStage() {
        CanaryTrafficProperties properties = properties();
        ProductionCanaryManager manager = new ProductionCanaryManager(properties);
        manager.start();
        manager.observeAndAdvance(healthy(), satisfiedWindow(5));
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_25);

        ProductionCanaryManager.StageRecord record = manager.observeAndAdvance(
                HealthSnapshot.of(500, 0.0, 300, 5, 2, 0, 0, 0), satisfiedWindow(25));

        assertThat(manager.stage()).isEqualTo(Stage.CANARY_5);
        assertThat(properties.getWeight()).isEqualTo(5);
        assertThat(record.rollbackStatus()).isEqualTo("ROLLED_BACK_TO_CANARY_5");
    }

    @Test
    void rollbackShouldReturnToInitAndZeroWeight() {
        CanaryTrafficProperties properties = properties();
        ProductionCanaryManager manager = new ProductionCanaryManager(properties);
        manager.start();
        manager.observeAndAdvance(healthy(), satisfiedWindow(5));
        manager.observeAndAdvance(healthy(), satisfiedWindow(25));

        manager.rollback();

        assertThat(manager.stage()).isEqualTo(Stage.INIT);
        assertThat(properties.getWeight()).isZero();
        assertThat(properties.isEnabled()).isFalse();
    }
}
