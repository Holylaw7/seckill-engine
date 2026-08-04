package com.seckill.gateway.filter;

import com.seckill.gateway.config.GatewayProfileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 逐 Filter 耗时采集（Phase 6.3，默认关闭）。
 * 仅记录阶段耗时，不缓存/不改变任何鉴权、限流、业务语义。
 */
@Component
@RequiredArgsConstructor
public class GatewayProfileRecorder {

    public static final String PROFILE_ATTR = "GATEWAY_PROFILE_ATTR";
    private static final int MAX_SAMPLES = 20000;

    private final GatewayProfileProperties properties;
    private final Queue<Map<String, Long>> samples = new ConcurrentLinkedQueue<>();

    public boolean enabled() {
        return properties.isEnabled();
    }

    public void record(ServerWebExchange exchange, String stage, long startNanos) {
        if (!properties.isEnabled()) {
            return;
        }
        long costMicros = (System.nanoTime() - startNanos) / 1000;
        Object value = exchange.getAttributes().get(PROFILE_ATTR);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Long> costs = (Map<String, Long>) map;
            costs.merge(stage, costMicros, Long::sum);
        }
    }

    /** doFinally 时落一份快照（含 total），供同 JVM 压测聚合 */
    public void recordSnapshot(ServerWebExchange exchange, long totalStartNanos) {
        if (!properties.isEnabled()) {
            return;
        }
        Object value = exchange.getAttributes().get(PROFILE_ATTR);
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> costs = (Map<String, Long>) map;
        long totalMicros = (System.nanoTime() - totalStartNanos) / 1000;
        costs.put("total", totalMicros);
        samples.add(costs);
        while (samples.size() > MAX_SAMPLES) {
            samples.poll();
        }
    }

    public List<Map<String, Long>> samples() {
        return List.copyOf(samples);
    }

    /** Trace 阶段初始化共享 Map（exchange 变更时对象仍共享） */
    public void begin(ServerWebExchange exchange) {
        if (!properties.isEnabled()) {
            return;
        }
        exchange.getAttributes().computeIfAbsent(PROFILE_ATTR,
                key -> new java.util.concurrent.ConcurrentHashMap<String, Long>());
    }
}
