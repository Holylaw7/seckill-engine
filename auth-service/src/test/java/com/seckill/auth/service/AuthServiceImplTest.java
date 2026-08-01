package com.seckill.auth.service;

import com.seckill.auth.config.JwtProperties;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.SessionResponse;
import com.seckill.auth.entity.User;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.auth.risk.RiskContext;
import com.seckill.auth.risk.RiskResult;
import com.seckill.auth.risk.RiskService;
import com.seckill.auth.service.impl.AuthServiceImpl;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private SessionService sessionService;
    @Mock
    private RiskService riskService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtProperties jwtProperties;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret");
        jwtProperties.setExpireMinutes(120);
        authService = new AuthServiceImpl(userMapper, sessionService, riskService, jwtProperties, passwordEncoder);
    }

    private User normalUser() {
        User user = new User();
        user.setId(10001L);
        user.setUsername("alice");
        user.setPasswordHash("hash");
        user.setStatus(1);
        user.setRoles("USER");
        return user;
    }

    @Test
    void loginShouldSucceedAndIssueToken() {
        when(userMapper.selectOne(any())).thenReturn(normalUser());
        when(riskService.check(any(RiskContext.class))).thenReturn(RiskResult.pass());
        when(passwordEncoder.matches(eq("pass123"), anyString())).thenReturn(true);

        LoginResponse response = authService.login(new LoginRequest("alice", "pass123"), "127.0.0.1", null);

        assertTrue(response.getToken() != null && !response.getToken().isBlank());
        assertEquals("10001", response.getUserId());
        assertTrue(response.getRoles().contains("USER"));
        verify(sessionService).createSession(eq("10001"), eq("alice"), eq("127.0.0.1"), eq(null));
        verify(riskService).resetLoginFailure("10001");
    }

    @Test
    void wrongPasswordShouldRejectAndRecordFailure() {
        when(userMapper.selectOne(any())).thenReturn(normalUser());
        when(riskService.check(any(RiskContext.class))).thenReturn(RiskResult.pass());
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);
        when(riskService.recordLoginFailure("10001")).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("alice", "wrong"), "127.0.0.1", null));
        assertEquals(20001, e.getErrorCode().getCode());
        assertEquals("用户名或密码错误", e.getMessage());
        verify(riskService).recordLoginFailure("10001");
        verify(sessionService, never()).createSession(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void captchaThresholdShouldBeTriggered() {
        when(userMapper.selectOne(any())).thenReturn(normalUser());
        when(riskService.check(any(RiskContext.class))).thenReturn(RiskResult.pass());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(riskService.recordLoginFailure("10001")).thenReturn(5L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("alice", "wrong"), "127.0.0.1", null));
        assertEquals(20004, e.getErrorCode().getCode());
    }

    @Test
    void freezeThresholdShouldBeTriggered() {
        when(userMapper.selectOne(any())).thenReturn(normalUser());
        when(riskService.check(any(RiskContext.class))).thenReturn(RiskResult.pass());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(riskService.recordLoginFailure("10001")).thenReturn(10L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("alice", "wrong"), "127.0.0.1", null));
        assertEquals(20003, e.getErrorCode().getCode());
    }

    @Test
    void disabledUserShouldBeRejected() {
        User user = normalUser();
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(riskService.check(any(RiskContext.class))).thenReturn(RiskResult.pass());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("alice", "pass123"), "127.0.0.1", null));
        assertEquals(20002, e.getErrorCode().getCode());
    }

    @Test
    void blacklistedUserShouldBeRejectedBeforePasswordCheck() {
        when(userMapper.selectOne(any())).thenReturn(normalUser());
        when(riskService.check(any(RiskContext.class)))
                .thenReturn(RiskResult.reject(ErrorCode.BLACKLISTED, "用户黑名单"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> authService.login(new LoginRequest("alice", "pass123"), "127.0.0.1", null));
        assertEquals(20005, e.getErrorCode().getCode());
        verify(riskService).record(any(RiskContext.class), any(RiskResult.class));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void logoutShouldDeleteSession() {
        authService.logout("10001");
        verify(sessionService).deleteSession("10001");
    }

    @Test
    void getSessionShouldReturnSession() {
        Map<Object, Object> session = new HashMap<>();
        session.put("userId", "10001");
        session.put("username", "alice");
        session.put("tokenVersion", "1");
        session.put("loginTime", "1000");
        session.put("lastActiveTime", "2000");
        session.put("device", "device-1");
        when(sessionService.getSession("10001")).thenReturn(session);

        SessionResponse response = authService.getSession("10001");
        assertEquals("10001", response.getUserId());
        assertEquals("device-1", response.getDevice());
        verify(sessionService).touch("10001");
    }

    @Test
    void getSessionShouldRejectWhenExpired() {
        when(sessionService.getSession("10001")).thenReturn(Map.of());
        BusinessException e = assertThrows(BusinessException.class, () -> authService.getSession("10001"));
        assertEquals(20001, e.getErrorCode().getCode());
    }

    @Test
    void should_pass_through_frozen_session_fields_when_session_exists() {
        // Arrange
        Map<Object, Object> session = new HashMap<>();
        session.put("userId", "10001");
        session.put("username", "alice");
        session.put("tokenVersion", "2");
        session.put("loginTime", "1111");
        session.put("lastActiveTime", "2222");
        session.put("device", "device-x");
        when(sessionService.getSession("10001")).thenReturn(session);

        // Act
        SessionResponse response = authService.getSession("10001");

        // Assert
        assertThat(response.getUserId()).isEqualTo("10001");
        assertThat(response.getUsername()).isEqualTo("alice");
        assertThat(response.getTokenVersion()).isEqualTo("2");
        assertThat(response.getLoginTime()).isEqualTo(1111L);
        assertThat(response.getLastActiveTime()).isEqualTo(2222L);
        assertThat(response.getDevice()).isEqualTo("device-x");
        verify(sessionService).touch("10001");
    }
}
