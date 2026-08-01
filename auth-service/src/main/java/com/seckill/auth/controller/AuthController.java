package com.seckill.auth.controller;

import com.seckill.auth.constant.AuthConstants;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.SessionResponse;
import com.seckill.auth.service.AuthService;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return Result.success(authService.login(
                request,
                resolveIp(httpRequest),
                httpRequest.getHeader(AuthConstants.HEADER_DEVICE)));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        authService.logout(userId);
        return Result.success();
    }

    @GetMapping("/session")
    public Result<SessionResponse> session(@RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(authService.getSession(userId));
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
