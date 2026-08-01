package com.seckill.auth.filter;

import com.seckill.auth.constant.AuthConstants;
import com.seckill.common.trace.TraceIdUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * TraceId 过滤器：从 X-Trace-Id 读取或生成，绑定 ThreadLocal 并在请求结束清理。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(AuthConstants.HEADER_TRACE_ID);
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            traceId = TraceIdUtils.generate();
        }
        TraceIdUtils.set(traceId);
        response.setHeader(AuthConstants.HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdUtils.clear();
        }
    }
}
