package com.seckill.gateway.filter;

import com.seckill.gateway.constant.GatewayConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 请求日志过滤器：记录方法、URI、TraceId、状态码、耗时、来源 IP。
 */
@Component
public class RequestLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER);
        return chain.filter(exchange).doFinally(signal -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            log.info("gateway request method={} uri={} traceId={} status={} cost={}ms ip={}",
                    request.getMethod(), request.getURI(), traceId,
                    status == null ? "-" : status.value(),
                    System.currentTimeMillis() - start,
                    request.getRemoteAddress());
        });
    }

    @Override
    public int getOrder() {
        return GatewayConstants.LOG_FILTER_ORDER;
    }
}
