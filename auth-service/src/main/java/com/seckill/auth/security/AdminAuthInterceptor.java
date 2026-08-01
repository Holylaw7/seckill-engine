package com.seckill.auth.security;

import com.seckill.auth.config.JwtProperties;
import com.seckill.auth.constant.AuthConstants;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.common.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * 管理接口安全（冻结）：JWT 有效 + roles 含 ADMIN 双重校验。
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            token = authorization.substring(BEARER_PREFIX.length());
        }
        Optional<JwtTokenIssuer.Claims> claims = token == null
                ? Optional.empty()
                : JwtTokenIssuer.parse(token, jwtProperties.getSecret());
        if (claims.isEmpty()) {
            writeJson(response, HttpStatus.UNAUTHORIZED.value(), Result.error(ErrorCode.UNAUTHORIZED));
            return false;
        }
        if (!claims.get().roles().contains(AuthConstants.ROLE_ADMIN)) {
            writeJson(response, HttpStatus.FORBIDDEN.value(), Result.error(ErrorCode.FORBIDDEN));
            return false;
        }
        return true;
    }

    private static void writeJson(HttpServletResponse response, int status, Result<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtils.toJson(body));
    }
}
