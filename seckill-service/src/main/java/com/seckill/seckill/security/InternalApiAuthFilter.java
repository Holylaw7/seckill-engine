package com.seckill.seckill.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.common.security.InternalSignature;
import com.seckill.seckill.config.InternalAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 内部接口 Service ACL（Phase 6.6）：
 * X-Service-Name / X-Service-Timestamp / X-Service-Nonce / X-Service-Signature
 * 校验 + 时间窗 + 防重放。
 * 路径规则：/pre-deducts/confirm 仅 order-service；/stocks/recover 仅 inventory-service。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalApiAuthFilter extends OncePerRequestFilter {

    private static final String NAME_HEADER = "X-Service-Name";
    private static final String TIMESTAMP_HEADER = "X-Service-Timestamp";
    private static final String NONCE_HEADER = "X-Service-Nonce";
    private static final String SIGNATURE_HEADER = "X-Service-Signature";
    private static final long NONCE_TTL_MILLIS = 60_000L;
    private static final String NONCE_KEY_PREFIX = "seckill:security:nonce:";

    private final InternalAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/seckill/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        String name = request.getHeader(NAME_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (name == null || name.isBlank() || timestamp == null
                || nonce == null || nonce.isBlank() || nonce.length() > 128 || signature == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }
        if (Math.abs(System.currentTimeMillis() - ts) > properties.getWindowMillis()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }
        String secret = properties.getClients().get(name);
        boolean sigOk = secret != null
                && InternalSignature.verify(secret, name, timestamp, nonce, signature);
        if (!sigOk) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
            return;
        }
        if (!tryAcquireNonce(name, nonce)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }

        String path = request.getRequestURI();
        boolean allowed = path.contains("/pre-deducts/confirm") && "order-service".equals(name)
                || path.contains("/stocks/recover") && "inventory-service".equals(name);
        if (!allowed) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryAcquireNonce(String serviceName, String nonce) {
        String key = NONCE_KEY_PREFIX + serviceName + ":" + nonce;
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofMillis(NONCE_TTL_MILLIS));
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            // 防重放组件不可用时拒绝请求，不能退化为单实例内存防重放。
            log.error("internal nonce store unavailable, serviceName={}, error={}",
                    serviceName, e.getMessage());
            return false;
        }
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(errorCode)));
    }
}
