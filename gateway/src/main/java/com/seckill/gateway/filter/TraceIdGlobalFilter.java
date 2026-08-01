package com.seckill.gateway.filter;

import com.seckill.common.trace.TraceIdUtils;
import com.seckill.gateway.constant.GatewayConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * TraceId 全局过滤器：生成/复用 TraceId，向下游透传 X-Trace-Id，响应头回写。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER);
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = TraceIdUtils.generate();
        }
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(GatewayConstants.TRACE_ID_HEADER, traceId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        mutatedExchange.getResponse().getHeaders().set(GatewayConstants.TRACE_ID_HEADER, traceId);
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.TRACE_FILTER_ORDER;
    }
}
