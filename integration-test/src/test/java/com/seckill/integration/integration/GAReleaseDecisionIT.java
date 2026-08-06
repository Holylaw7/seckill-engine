package com.seckill.integration.integration;

import com.seckill.common.canary.GaReleaseDecision;
import com.seckill.common.canary.GaReleaseDecision.Decision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.9 GA 决策验收：ALL PASS → GA READY；ANY FAIL → RC1 STABLE。
 * 纯逻辑验收（不启动中间件）。
 */
@Tag("integration")
class GAReleaseDecisionIT {

    @Test
    void allGatesPassShouldBeGaReady() {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("dependency-scan", true);
        gates.put("canary-5", true);
        gates.put("canary-25", true);
        gates.put("canary-50", true);
        gates.put("100-validation", true);
        gates.put("oversell-0", true);
        gates.put("deadlock-0", true);
        gates.put("inventory-diff-0", true);
        gates.put("rollback", true);

        Decision decision = GaReleaseDecision.evaluate(gates);

        assertThat(decision.isGaReady()).isTrue();
        assertThat(decision.failures()).isEmpty();
    }

    @Test
    void anyGateFailShouldBeRcStable() {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("dependency-scan", true);
        gates.put("canary-5", true);
        gates.put("oversell-0", false);
        gates.put("deadlock-0", false);

        Decision decision = GaReleaseDecision.evaluate(gates);

        assertThat(decision.isGaReady()).isFalse();
        assertThat(decision.failures()).containsExactly("oversell-0", "deadlock-0");
    }
}
