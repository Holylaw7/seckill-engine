package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryHealthEvaluator.Level;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.gateway.canary.ProductionCanaryManager.Stage;
import com.seckill.gateway.canary.ProductionCanaryManager.StageRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6.9 生产 Canary 发布执行器：
 *
 * <ol>
 *   <li>Stage 0：Pre-Canary 检查（配置 / Redis 一致性 / 依赖门禁，由调用方提供失败清单）；</li>
 *   <li>5%：观察窗口 + 健康 PASS 后进入 CANARY_5；</li>
 *   <li>25% → 50% → 100%（FULL_RELEASE）→ GA：每阶段独立窗口 + Health Gate；</li>
 *   <li>CRITICAL / 窗口未满足 / 阶段未到达 → 失败并回滚上一稳定阶段。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ProductionCanaryExecutor {

    /**
     * 每阶段观察数据：5% / 25% / 50% / 100% / GA 共 5 个。
     */
    public record StageObservation(CanaryObservationWindow window, HealthSnapshot health) {
    }

    public record ExecutionResult(boolean completed, Stage finalStage,
                                  List<String> failures, List<StageRecord> history) {
    }

    private static final Stage[] TARGETS = {
            Stage.CANARY_5, Stage.CANARY_25, Stage.CANARY_50, Stage.FULL_RELEASE, Stage.GA
    };

    private final ProductionCanaryManager manager;

    /**
     * @param preflightFailures Stage 0 失败清单（配置 / Redis / 依赖门禁）；空表示通过
     * @param observations      长度必须为 5，对应 5/25/50/100/GA 各阶段观察窗口与健康快照
     */
    public synchronized ExecutionResult execute(List<String> preflightFailures,
                                                List<StageObservation> observations) {
        List<String> failures = new ArrayList<>(preflightFailures == null ? List.of() : preflightFailures);
        if (observations == null || observations.size() != TARGETS.length) {
            failures.add("observations must contain exactly 5 stages");
            return new ExecutionResult(false, manager.stage(), failures, manager.history());
        }
        if (!failures.isEmpty()) {
            return new ExecutionResult(false, manager.stage(), failures, manager.history());
        }

        // 5%：Stage 0 通过后，观察窗口 + 健康 PASS 才进入 CANARY_5
        StageObservation five = observations.get(0);
        if (five == null || five.window() == null || !five.window().isSatisfied()
                || five.health() == null
                || isCriticalOrWarning(five.health())) {
            failures.add("stage 5% observation not satisfied or health not PASS");
            return new ExecutionResult(false, manager.stage(), failures, manager.history());
        }
        manager.start();
        if (manager.stage() != Stage.CANARY_5) {
            failures.add("stage 5% not entered");
            return new ExecutionResult(false, manager.stage(), failures, manager.history());
        }

        // 25% → 50% → 100% → GA
        for (int i = 1; i < TARGETS.length; i++) {
            Stage target = TARGETS[i];
            StageObservation observation = observations.get(i);
            if (observation == null || observation.window() == null
                    || !observation.window().isSatisfied() || observation.health() == null) {
                failures.add("stage " + target + " observation missing");
                break;
            }
            StageRecord record = manager.observeAndAdvance(observation.health(), observation.window());
            if (record.healthLevel() == Level.CRITICAL || record.healthLevel() == Level.WARNING) {
                failures.add("stage " + target + " health " + record.healthLevel()
                        + ": " + record.healthReasons() + " -> " + record.rollbackStatus());
                break;
            }
            if (manager.stage() != target) {
                failures.add("stage " + target + " not reached: " + record.rollbackStatus());
                break;
            }
        }
        boolean completed = failures.isEmpty() && manager.stage() == Stage.GA;
        return new ExecutionResult(completed, manager.stage(), failures, manager.history());
    }

    private static boolean isCriticalOrWarning(HealthSnapshot health) {
        var decision = com.seckill.common.canary.CanaryHealthEvaluator.evaluate(health);
        return decision.level() == Level.CRITICAL || decision.level() == Level.WARNING;
    }
}
