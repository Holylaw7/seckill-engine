package com.seckill.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.common.security.InternalSignature;
import com.seckill.inventory.config.InternalAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存管理接口 ACL（Phase 6.6）：
 * /admin/reconcile（查询 diff）允许 inventory-service 或 admin；
 * /admin/reconcile/repair、syncSummary 仅允许 admin。
 */
@Component
@RequiredArgsConstructor
public class InternalApiAuthFilter extends OncePerRequestFilter {

    private static final String NAME_HEADER = "X-Service-Name";
    private static final String TIMESTAMP_HEADER = "X-Service-Timestamp";
    private static final String SIGNATURE_HEADER = "X-Service-Signature";
    private static final long NONCE_TTL_MILLIS = 60_000L;
    private static final int MAX_NONCES = 10000;

    private final InternalAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/inventory/admin/");
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
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (name == null || timestamp == null || signature == null) {
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
        String nonceKey = name + ":" + timestamp;
        Long previous = nonces.putIfAbsent(nonceKey, System.currentTimeMillis());
        if (previous != null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }
        trimNonces();

        String secret = "admin".equals(name) ? properties.getAdminSecret()
                : properties.getClients().get(name);
        boolean sigOk = secret != null && !secret.isBlank()
                && InternalSignature.verify(secret, name + ":" + timestamp, signature);
        if (!sigOk) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
            return;
        }
        String path = request.getRequestURI();
        boolean adminOnly = path.contains("/reconcile/repair") || path.contains("/syncSummary");
        boolean allowed = adminOnly ? "admin".equals(name)
                : "admin".equals(name) || "inventory-service".equals(name);
        if (!allowed) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void trimNonces() {
        if (nonces.size() < MAX_NONCES) {
            return;
        }
        long now = System.currentTimeMillis();
        nonces.entrySet().removeIf(entry -> now - entry.getValue() > NONCE_TTL_MILLIS);
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(errorCode)));
    }
}
