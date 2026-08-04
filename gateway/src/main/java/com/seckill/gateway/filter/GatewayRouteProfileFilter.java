package com.seckill.gateway.filter;

import com.seckill.gateway.constant.GatewayConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 路由阶段计时（Phase 6.3 Profiling）：覆盖 RateLimit + 路由 + 后端 + 响应。
 */
@Component
@RequiredArgsConstructor
public class GatewayRouteProfileFilter implements GlobalFilter, Ordered {

    private final GatewayProfileRecorder profileRecorder;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!profileRecorder.enabled()) {
            return chain.filter(exchange);
        }
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal ->
                profileRecorder.record(exchange, "route", start));
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ROUTE_PROFILE_FILTER_ORDER;
    }
}
