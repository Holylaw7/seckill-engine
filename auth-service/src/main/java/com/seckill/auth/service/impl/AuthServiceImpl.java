package com.seckill.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.auth.config.JwtProperties;
import com.seckill.auth.constant.AuthConstants;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.SessionResponse;
import com.seckill.auth.entity.User;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.auth.risk.RiskContext;
import com.seckill.auth.risk.RiskResult;
import com.seckill.auth.risk.RiskService;
import com.seckill.auth.security.JwtTokenIssuer;
import com.seckill.auth.service.AuthService;
import com.seckill.auth.service.SessionService;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final SessionService sessionService;
    private final RiskService riskService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    @Override
    public LoginResponse login(LoginRequest request, String ip, String device) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        String identity = user != null ? String.valueOf(user.getId()) : request.getUsername();

        RiskContext context = new RiskContext(
                user != null ? String.valueOf(user.getId()) : null,
                ip, RiskContext.ACTION_LOGIN, device);
        RiskResult risk = riskService.check(context);
        if (risk.decision() != RiskResult.Decision.PASS) {
            riskService.record(context, risk);
            throw new BusinessException(risk.errorCode(), risk.reason());
        }

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            long failures = riskService.recordLoginFailure(identity);
            if (failures >= AuthConstants.LOGIN_FAIL_FREEZE_THRESHOLD) {
                throw new BusinessException(ErrorCode.RISK_REJECTED, "登录失败次数过多，账号已临时冻结");
            }
            if (failures >= AuthConstants.LOGIN_FAIL_CAPTCHA_THRESHOLD) {
                throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED, "需要人机验证");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        riskService.resetLoginFailure(identity);
        sessionService.createSession(String.valueOf(user.getId()), user.getUsername(), ip, device);

        List<String> roles = parseRoles(user.getRoles());
        String token = JwtTokenIssuer.issue(
                String.valueOf(user.getId()), user.getUsername(), roles,
                jwtProperties.getSecret(), jwtProperties.getExpireMinutes());
        long expiresAt = System.currentTimeMillis() + jwtProperties.getExpireMinutes() * 60_000L;
        return new LoginResponse(token, String.valueOf(user.getId()), user.getUsername(), roles, expiresAt);
    }

    @Override
    public void logout(String userId) {
        sessionService.deleteSession(userId);
    }

    @Override
    public SessionResponse getSession(String userId) {
        Map<Object, Object> session = sessionService.getSession(userId);
        if (session == null || session.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "会话不存在或已过期");
        }
        sessionService.touch(userId);
        return new SessionResponse(
                String.valueOf(session.get("userId")),
                String.valueOf(session.get("username")),
                String.valueOf(session.get("tokenVersion")),
                Long.parseLong(String.valueOf(session.get("loginTime"))),
                Long.parseLong(String.valueOf(session.get("lastActiveTime"))),
                String.valueOf(session.get("device")));
    }

    private static List<String> parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of(AuthConstants.ROLE_USER);
        }
        List<String> result = new ArrayList<>();
        for (String role : roles.split(",")) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
