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
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

/**
 * 请求日志过滤器：记录方法、URI、TraceId、状态码、耗时、来源 IP。
 */
@Component
@RequiredArgsConstructor
public class RequestLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogGlobalFilter.class);

    private final GatewayProfileRecorder profileRecorder;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER);
        return chain.filter(exchange).doFinally(signal -> {
            long logStart = System.nanoTime();
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            Tags tags = Tags.of(
                    "method", request.getMethod() == null ? "-" : request.getMethod().name(),
                    "uri", request.getURI().getPath(),
                    "application", "gateway");
            Timer.builder("gateway_request_duration").tags(tags)
                    .register(meterRegistry)
                    .record(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS);
            Counter.builder("gateway_request_total").tags(tags)
                    .register(meterRegistry).increment();
            if (status != null && status.value() >= 500) {
                Counter.builder("gateway_error_total").tags(tags)
                        .register(meterRegistry).increment();
            }
            if (status != null && status.value() == 429) {
                Counter.builder("gateway_429_total").tags(tags)
                        .register(meterRegistry).increment();
                // Phase 6.6 限流指标别名（告警统一使用 gateway_rate_limit_total）
                Counter.builder("gateway_rate_limit_total").tags(tags)
                        .register(meterRegistry).increment();
            }
            log.info("gateway request method={} uri={} traceId={} status={} cost={}ms ip={}",
                    request.getMethod(), request.getURI(), traceId,
                    status == null ? "-" : status.value(),
                    System.currentTimeMillis() - start,
                    request.getRemoteAddress());
            if (log.isDebugEnabled()) {
                log.debug("gateway request headers traceId={} headers={}", traceId, request.getHeaders());
            }
            profileRecorder.record(exchange, "log", logStart);
            profileRecorder.recordSnapshot(exchange, startNanos);
        });
    }

    @Override
    public int getOrder() {
        return GatewayConstants.LOG_FILTER_ORDER;
    }
}
