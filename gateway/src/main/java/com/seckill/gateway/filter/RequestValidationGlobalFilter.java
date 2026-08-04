package com.seckill.gateway.filter;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.gateway.constant.GatewayConstants;
import com.seckill.gateway.util.GatewayResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 请求参数基础校验：JSON Content-Type、请求体大小上限。
 */
@Component
@RequiredArgsConstructor
public class RequestValidationGlobalFilter implements GlobalFilter, Ordered {

    private static final long MAX_BODY_BYTES = 64 * 1024;
    private static final List<String> IGNORE_PATHS = List.of("/api/v1/auth/login", "/api/v1/payments/callback");

    private final GatewayProfileRecorder profileRecorder;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = profileRecorder.enabled() ? System.nanoTime() : 0L;
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        if (isBodyMethod(method) && !isIgnored(path)) {
            MediaType contentType = request.getHeaders().getContentType();
            if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return GatewayResponses.writeJson(exchange, HttpStatus.BAD_REQUEST,
                        Result.error(ErrorCode.PARAM_ERROR.getCode(), "Content-Type 必须为 application/json"));
            }
            long contentLength = request.getHeaders().getContentLength();
            if (contentLength > MAX_BODY_BYTES) {
                return GatewayResponses.writeJson(exchange, HttpStatus.BAD_REQUEST,
                        Result.error(ErrorCode.PARAM_ERROR.getCode(), "请求体过大"));
            }
        }
        profileRecorder.record(exchange, "validation", start);
        return chain.filter(exchange);
    }

    private static boolean isBodyMethod(HttpMethod method) {
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method);
    }

    private static boolean isIgnored(String path) {
        return IGNORE_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.VALIDATION_FILTER_ORDER;
    }
}
