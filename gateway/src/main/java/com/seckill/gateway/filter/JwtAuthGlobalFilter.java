package com.seckill.gateway.filter;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.gateway.config.JwtProperties;
import com.seckill.gateway.constant.GatewayConstants;
import com.seckill.gateway.security.JwtTokenParser;
import com.seckill.gateway.security.JwtTokenParser.Claims;
import com.seckill.gateway.util.GatewayResponses;
import com.seckill.gateway.filter.GatewayProfileRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * JWT Token 解析入口：白名单放行，其余请求解析 + 验签，将 userId 透传下游（X-User-Id）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> WHITE_LIST = List.of("/api/v1/auth/login", "/api/v1/payments/callback");

    private final JwtProperties jwtProperties;
    private final GatewayProfileRecorder profileRecorder;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = profileRecorder.enabled() ? System.nanoTime() : 0L;
        String path = exchange.getRequest().getURI().getPath();
        if (!jwtProperties.isEnabled() || isWhitelist(path)) {
            profileRecorder.record(exchange, "jwt", start);
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            token = authorization.substring(BEARER_PREFIX.length());
        }

        Optional<Claims> claims = token == null
                ? Optional.empty()
                : JwtTokenParser.parse(token, jwtProperties.getSecret());
        if (claims.isEmpty()) {
            return GatewayResponses.writeJson(exchange, HttpStatus.UNAUTHORIZED, Result.error(ErrorCode.UNAUTHORIZED));
        }

        String userId = claims.get().userId();
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(GatewayConstants.USER_ID_HEADER, userId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        mutatedExchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, userId);
        profileRecorder.record(mutatedExchange, "jwt", start);
        return chain.filter(mutatedExchange);
    }

    private static boolean isWhitelist(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.JWT_FILTER_ORDER;
    }
}
