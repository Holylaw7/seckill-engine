package com.seckill.common.canary;

import com.seckill.common.canary.GaReleaseDecision.Decision;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.9 GA 决策：全 PASS → GA READY；任一 FAIL → RC1 STABLE。
 */
class GaReleaseDecisionTest {

    @Test
    void allGatesPassShouldBeGaReady() {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canary-5", true);
        gates.put("canary-25", true);
        gates.put("canary-50", true);
        gates.put("100-validation", true);
        gates.put("rollback", true);
        gates.put("dependency-scan", true);
        gates.put("oversell-0", true);

        Decision decision = GaReleaseDecision.evaluate(gates);

        assertThat(decision.isGaReady()).isTrue();
        assertThat(decision.failures()).isEmpty();
    }

    @Test
    void anyGateFailShouldBeRcStableWithFailures() {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canary-5", true);
        gates.put("canary-25", false);
        gates.put("dependency-scan", false);

        Decision decision = GaReleaseDecision.evaluate(gates);

        assertThat(decision.isGaReady()).isFalse();
        assertThat(decision.failures()).containsExactly("canary-25", "dependency-scan");
    }
}
