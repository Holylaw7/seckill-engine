package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthDecision;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryHealthEvaluator.Level;
import com.seckill.gateway.config.CanaryTrafficProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Phase 6.7 Canary 发布工作流：prepare → 5% → 25% → 50% → 100%。
 *
 * <p>每阶段执行 Health Gate：WARNING 记录并继续（升级前须人工确认），
 * CRITICAL 自动阻止升级并回滚到上一稳定权重；回滚状态与阶段快照可审计。</p>
 */
@Component
@RequiredArgsConstructor
public class CanaryReleaseWorkflow {

    public static final List<Integer> TIMELINE = List.of(5, 25, 50, 100);

    public record StageRecord(Instant startTime, Instant endTime, int trafficWeight,
                              double qps, double p99Ms, double errorRate, long oversell,
                              double mqLagSeconds, Level healthLevel, List<String> healthReasons,
                              String rollbackStatus) {
    }

    private final CanaryTrafficProperties properties;
    private final List<StageRecord> history = new CopyOnWriteArrayList<>();
    private volatile int stableWeight = 0;

    /**
     * 执行完整时间线；任一步 CRITICAL 立即停止，权重停留在上一稳定档并返回 false。
     * 完整回滚（0%）由运维显式调用 {@link #rollback()}。
     */
    public synchronized boolean runTimeline(Supplier<HealthSnapshot> healthSupplier) {
        for (int weight : TIMELINE) {
            StageRecord record = advance(weight, healthSupplier.get());
            if (record.healthLevel() == Level.CRITICAL) {
                return false;
            }
        }
        return true;
    }

    /**
     * 推进到指定权重；CRITICAL 时回退到当前稳定权重。
     */
    public synchronized StageRecord advance(int weight, HealthSnapshot snapshot) {
        Instant start = Instant.now();
        HealthDecision decision = CanaryHealthEvaluator.evaluate(snapshot);
        String rollbackStatus = "NONE";
        if (decision.isCritical()) {
            properties.setWeight(stableWeight);
            rollbackStatus = "ROLLED_BACK_TO_" + stableWeight;
        } else {
            properties.setWeight(weight);
            stableWeight = weight;
        }
        StageRecord record = new StageRecord(
                start, Instant.now(), properties.getWeight(),
                snapshot.qps(),
                snapshot.p99Ms(), snapshot.errorRate(), snapshot.oversell(),
                snapshot.mqLagSeconds(), decision.level(), decision.reasons(), rollbackStatus);
        history.add(record);
        return record;
    }

    /**
     * 回滚到 0%（全部 stable），返回回滚后快照。
     */
    public synchronized StageRecord rollback() {
        Instant start = Instant.now();
        properties.setWeight(0);
        stableWeight = 0;
        StageRecord record = new StageRecord(
                start, Instant.now(), 0, 0, 0, 0, 0, 0,
                Level.PASS, List.of(), "ROLLED_BACK_TO_0");
        history.add(record);
        return record;
    }

    public List<StageRecord> history() {
        return List.copyOf(history);
    }
}
