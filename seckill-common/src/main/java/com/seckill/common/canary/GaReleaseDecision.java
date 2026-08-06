package com.seckill.common.canary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6.9 GA 发布决策（纯逻辑）：全部门禁 PASS → GA READY；任一 FAIL → RC1 STABLE。
 */
public final class GaReleaseDecision {

    public record Decision(boolean gaReady, List<String> failures) {

        public boolean isGaReady() {
            return gaReady;
        }
    }

    private GaReleaseDecision() {
    }

    /**
     * @param gates 门禁名 → 是否通过（保持顺序）
     */
    public static Decision evaluate(Map<String, Boolean> gates) {
        List<String> failures = new ArrayList<>();
        Map<String, Boolean> ordered = new LinkedHashMap<>(gates);
        for (Map.Entry<String, Boolean> entry : ordered.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                failures.add(entry.getKey());
            }
        }
        return new Decision(failures.isEmpty(), failures);
    }
}
