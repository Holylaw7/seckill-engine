package com.seckill.common.canary;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6.7 Canary 健康门禁（纯逻辑，无框架依赖）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>WARNING：error_rate &gt; 0.1% 或 p99 &gt; 500ms 或 MQ lag &gt; 30s；</li>
 *   <li>CRITICAL：error_rate &gt; 1% 或 oversell &gt; 0 或 deadlock &gt; 0 或
 *       inventory_diff != 0 或 DLQ &gt; 0；Critical 自动阻止升级。</li>
 * </ul>
 */
public final class CanaryHealthEvaluator {

    public enum Level {
        PASS, WARNING, CRITICAL
    }

    public record HealthSnapshot(double qps, double errorRate, double p99Ms, double mqLagSeconds,
                                 long oversell, long deadlock, long inventoryDiff, long dlq) {
    }

    public record HealthDecision(Level level, List<String> reasons) {

        public boolean isCritical() {
            return level == Level.CRITICAL;
        }

        public boolean isPass() {
            return level == Level.PASS;
        }
    }

    private CanaryHealthEvaluator() {
    }

    public static HealthDecision evaluate(HealthSnapshot snapshot) {
        List<String> critical = new ArrayList<>();
        if (snapshot.errorRate() > 1.0) {
            critical.add("error_rate>1%");
        }
        if (snapshot.oversell() > 0) {
            critical.add("oversell>0");
        }
        if (snapshot.deadlock() > 0) {
            critical.add("deadlock>0");
        }
        if (snapshot.inventoryDiff() != 0) {
            critical.add("inventory_diff!=0");
        }
        if (snapshot.dlq() > 0) {
            critical.add("dlq>0");
        }
        if (!critical.isEmpty()) {
            return new HealthDecision(Level.CRITICAL, critical);
        }

        List<String> warnings = new ArrayList<>();
        if (snapshot.errorRate() > 0.1) {
            warnings.add("error_rate>0.1%");
        }
        if (snapshot.p99Ms() > 500) {
            warnings.add("p99>500ms");
        }
        if (snapshot.mqLagSeconds() > 30) {
            warnings.add("mq_lag>30s");
        }
        return warnings.isEmpty()
                ? new HealthDecision(Level.PASS, List.of())
                : new HealthDecision(Level.WARNING, warnings);
    }
}
