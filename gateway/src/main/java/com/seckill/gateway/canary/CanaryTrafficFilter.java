package com.seckill.gateway.canary;

import com.seckill.gateway.config.CanaryTrafficProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * Phase 6.7 Canary 决策过滤器：
 * 附加 X-Canary-Version 头（下游可观测）并记录 gateway_canary_decision_total。
 * 不修改路由/业务语义；后端版本组由部署拓扑承载。
 */
@Component
@RequiredArgsConstructor
public class CanaryTrafficFilter implements GlobalFilter, Ordered {

    public static final String CANARY_VERSION_ATTRIBUTE = "canary.version";
    public static final String CANARY_VERSION_HEADER = "X-Canary-Version";

    private final CanaryTrafficProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Gauge.builder("gateway_canary_weight", properties, CanaryTrafficProperties::getWeight)
                .tag("application", "gateway")
                .register(meterRegistry);
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        String userId = request.getHeaders().getFirst(properties.getUserIdHeader());
        String requestId = request.getHeaders().getFirst(properties.getRequestIdHeader());
        String canaryHeader = request.getHeaders().getFirst(properties.getCanaryHeader());
        boolean canary = CanaryRoutePredicate.shouldCanary(properties, userId, requestId, canaryHeader);
        String version = canary ? properties.getVersion() : "stable";
        exchange.getAttributes().put(CANARY_VERSION_ATTRIBUTE, version);
        Counter.builder("gateway_canary_decision_total")
                .tag("application", "gateway")
                .tag("target", version)
                .register(meterRegistry)
                .increment();
        ServerHttpRequest mutated = request.mutate()
                .header(CANARY_VERSION_HEADER, version)
                .build();
        return chain.filter(exchange.mutate().request(mutated).build()).doFinally(signal -> {
            long elapsedNanos = System.nanoTime() - startNanos;
            Timer.builder("gateway_canary_latency")
                    .tag("application", "gateway")
                    .tag("target", version)
                    .register(meterRegistry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            Counter.builder("gateway_canary_request_total")
                    .tag("application", "gateway")
                    .tag("target", version)
                    .register(meterRegistry)
                    .increment();
            Integer status = exchange.getResponse().getStatusCode() == null
                    ? null : exchange.getResponse().getStatusCode().value();
            if (status != null && (status >= 500 || status == 429)) {
                Counter.builder("gateway_canary_error_total")
                        .tag("application", "gateway")
                        .tag("target", version)
                        .register(meterRegistry)
                        .increment();
            }
        });
    }

    @Override
    public int getOrder() {
        // 位于 TraceId 之后、路由过滤之前
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
