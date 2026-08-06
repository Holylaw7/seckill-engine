package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.gateway.config.CanaryTrafficProperties;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 Canary 发布工作流单测：5→25→50→100 正常推进、CRITICAL 阻止升级并回滚。
 */
class CanaryReleaseWorkflowTest {

    private static HealthSnapshot healthy(double qps) {
        return HealthSnapshot.of(qps, 0.0, 300, 5, 0, 0, 0, 0);
    }

    @Test
    void timelineShouldAdvanceTo100WhenHealthy() {
        CanaryTrafficProperties properties = new CanaryTrafficProperties();
        properties.setEnabled(true);
        properties.setWeight(0);
        CanaryReleaseWorkflow workflow = new CanaryReleaseWorkflow(properties);

        boolean completed = workflow.runTimeline(() -> healthy(500));

        assertThat(completed).isTrue();
        assertThat(properties.getWeight()).isEqualTo(100);
        assertThat(workflow.history()).hasSize(4);
        assertThat(workflow.history().get(0).trafficWeight()).isEqualTo(5);
        assertThat(workflow.history().get(3).trafficWeight()).isEqualTo(100);
        assertThat(workflow.history()).allMatch(record -> record.rollbackStatus().equals("NONE"));
    }

    @Test
    void criticalHealthShouldBlockEscalationAndRollback() {
        CanaryTrafficProperties properties = new CanaryTrafficProperties();
        properties.setEnabled(true);
        properties.setWeight(0);
        CanaryReleaseWorkflow workflow = new CanaryReleaseWorkflow(properties);
        Supplier<HealthSnapshot> health = new Supplier<>() {
            private int calls = 0;

            @Override
            public HealthSnapshot get() {
                calls++;
                if (calls == 2) {
                    // 25% 阶段出现 Critical：oversell>0
                    return HealthSnapshot.of(500, 0.0, 300, 5, 2, 0, 0, 0);
                }
                return healthy(500);
            }
        };

        boolean completed = workflow.runTimeline(health);

        assertThat(completed).isFalse();
        assertThat(properties.getWeight()).isEqualTo(5);
        assertThat(workflow.history()).hasSize(2);
        assertThat(workflow.history().get(1).healthLevel())
                .isEqualTo(com.seckill.common.canary.CanaryHealthEvaluator.Level.CRITICAL);
        assertThat(workflow.history().get(1).rollbackStatus()).isEqualTo("ROLLED_BACK_TO_5");
    }

    @Test
    void rollbackShouldReturnWeightToZero() {
        CanaryTrafficProperties properties = new CanaryTrafficProperties();
        properties.setEnabled(true);
        properties.setWeight(50);
        CanaryReleaseWorkflow workflow = new CanaryReleaseWorkflow(properties);

        workflow.rollback();

        assertThat(properties.getWeight()).isZero();
        assertThat(workflow.history().get(0).trafficWeight()).isZero();
        assertThat(workflow.history().get(0).rollbackStatus()).isEqualTo("ROLLED_BACK_TO_0");
    }
}
