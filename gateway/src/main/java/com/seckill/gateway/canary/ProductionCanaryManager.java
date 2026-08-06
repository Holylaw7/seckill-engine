package com.seckill.gateway.canary;

import com.seckill.common.canary.CanaryHealthEvaluator;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthDecision;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryHealthEvaluator.Level;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.gateway.config.CanaryTrafficProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 6.8 生产 Canary 发布管理器：
 *
 * <p>状态机：INIT → CANARY_5 → CANARY_25 → CANARY_50 → FULL_RELEASE → GA。</p>
 *
 * <ul>
 *   <li>窗口未满足（&lt;30min 且 &lt;10000 请求）→ 不推进（NOT_READY）；</li>
 *   <li>WARNING → 暂停升级（paused=true，等待人工确认 {@link #resume()}）；</li>
 *   <li>CRITICAL → 自动回滚上一稳定阶段与权重；</li>
 *   <li>显式 {@link #rollback()} → 全量回滚 0%（INIT）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductionCanaryManager {

    public enum Stage {
        INIT(0), CANARY_5(5), CANARY_25(25), CANARY_50(50), FULL_RELEASE(100), GA(100);

        private final int weight;

        Stage(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    private static final Map<Stage, Stage> NEXT = Map.of(
            Stage.CANARY_5, Stage.CANARY_25,
            Stage.CANARY_25, Stage.CANARY_50,
            Stage.CANARY_50, Stage.FULL_RELEASE,
            Stage.FULL_RELEASE, Stage.GA);

    public record StageRecord(Instant startTime, Instant endTime, Stage from, Stage to,
                              int trafficWeight, Level healthLevel, List<String> healthReasons,
                              String rollbackStatus, CanaryObservationWindow window) {
    }

    private final CanaryTrafficProperties properties;
    private final List<StageRecord> history = new CopyOnWriteArrayList<>();

    private volatile Stage stage = Stage.INIT;
    private volatile boolean paused = false;

    public synchronized boolean start() {
        if (stage != Stage.INIT) {
            return false;
        }
        stage = Stage.CANARY_5;
        properties.setWeight(Stage.CANARY_5.weight());
        properties.setEnabled(true);
        history.add(new StageRecord(Instant.now(), Instant.now(), Stage.INIT, Stage.CANARY_5,
                Stage.CANARY_5.weight(), Level.PASS, List.of(), "NONE", null));
        return true;
    }

    /**
     * 观察窗口满足后推进；WARNING 暂停，CRITICAL 自动回滚上一稳定阶段。
     */
    public synchronized StageRecord observeAndAdvance(HealthSnapshot health,
                                                      CanaryObservationWindow window) {
        if (stage == Stage.INIT) {
            return record(stage, stage, 0, Level.PASS, List.of(), "NOT_STARTED", window);
        }
        if (stage == Stage.GA) {
            return record(stage, stage, stage.weight(), Level.PASS, List.of(), "GA_ALREADY", window);
        }
        if (paused) {
            return record(stage, stage, stage.weight(), Level.WARNING, List.of("paused_awaiting_confirmation"),
                    "PAUSED", window);
        }
        if (window == null || !window.isSatisfied()) {
            return record(stage, stage, stage.weight(), Level.PASS,
                    List.of("observation_window_not_satisfied"), "NOT_READY", window);
        }

        HealthDecision decision = CanaryHealthEvaluator.evaluate(health);
        if (decision.isCritical()) {
            Stage fallback = previous(stage);
            stage = fallback;
            properties.setWeight(fallback.weight());
            return record(fallback, fallback, fallback.weight(), decision.level(),
                    decision.reasons(), "ROLLED_BACK_TO_" + fallback.name(), window);
        }
        if (decision.level() == Level.WARNING) {
            paused = true;
            return record(stage, stage, stage.weight(), decision.level(),
                    decision.reasons(), "PAUSED_WAITING_CONFIRMATION", window);
        }

        Stage next = NEXT.get(stage);
        Stage from = stage;
        if (next != null) {
            stage = next;
            properties.setWeight(next.weight());
        }
        return record(from, stage, stage.weight(), Level.PASS, List.of(), "NONE", window);
    }

    public synchronized void resume() {
        paused = false;
    }

    /**
     * 全量回滚：权重 0，阶段回到 INIT。
     */
    public synchronized StageRecord rollback() {
        Stage from = stage;
        stage = Stage.INIT;
        paused = false;
        properties.setWeight(0);
        properties.setEnabled(false);
        return record(from, Stage.INIT, 0, Level.PASS, List.of(), "ROLLED_BACK_TO_0", null);
    }

    public Stage stage() {
        return stage;
    }

    public boolean isPaused() {
        return paused;
    }

    public List<StageRecord> history() {
        return List.copyOf(history);
    }

    private static Stage previous(Stage current) {
        return switch (current) {
            case CANARY_5, INIT -> Stage.INIT;
            case CANARY_25 -> Stage.CANARY_5;
            case CANARY_50 -> Stage.CANARY_25;
            case FULL_RELEASE -> Stage.CANARY_50;
            case GA -> Stage.FULL_RELEASE;
        };
    }

    private StageRecord record(Stage from, Stage to, int weight, Level level,
                               List<String> reasons, String rollbackStatus,
                               CanaryObservationWindow window) {
        StageRecord record = new StageRecord(Instant.now(), Instant.now(), from, to, weight,
                level, reasons, rollbackStatus, window);
        history.add(record);
        return record;
    }
}
