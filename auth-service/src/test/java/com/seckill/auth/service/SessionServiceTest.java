package com.seckill.auth.service;

import com.seckill.auth.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class SessionServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private SessionService newService() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret");
        jwtProperties.setExpireMinutes(120);
        return new SessionService(redisTemplate, jwtProperties);
    }

    @Test
    void createSessionShouldPutFieldsAndExpire() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        newService().createSession("10001", "alice", "127.0.0.1", "device-1");
        verify(hashOperations).putAll(eq("auth:session:10001"), anyMap());
        verify(redisTemplate).expire(eq("auth:session:10001"), any(Duration.class));
    }

    @Test
    void getSessionShouldReturnEntries() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> entries = Map.of("userId", "10001", "username", "alice");
        when(hashOperations.entries("auth:session:10001")).thenReturn(entries);
        assertEquals(entries, newService().getSession("10001"));
    }

    @Test
    void deleteSessionShouldRemoveKey() {
        newService().deleteSession("10001");
        verify(redisTemplate).delete("auth:session:10001");
    }

    @Test
    void touchShouldUpdateLastActiveTime() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        newService().touch("10001");
        verify(hashOperations).put(eq("auth:session:10001"), eq("lastActiveTime"), anyString());
    }

    @Test
    void should_create_complete_session_when_login_success() {
        // Arrange
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        // Act
        newService().createSession("10001", "alice", "127.0.0.1", "device-1");

        // Assert
        verify(hashOperations).putAll(eq("auth:session:10001"), fieldsCaptor.capture());
        verify(redisTemplate).expire(eq("auth:session:10001"), ttlCaptor.capture());
        Map<String, String> fields = fieldsCaptor.getValue();
        assertThat(fields).containsKeys(
                "userId", "username", "tokenVersion", "loginTime", "lastActiveTime", "device");
        assertThat(fields.get("userId")).isEqualTo("10001");
        assertThat(fields.get("username")).isEqualTo("alice");
        assertThat(fields.get("tokenVersion")).isEqualTo("1");
        assertThat(fields.get("device")).isEqualTo("device-1");
        assertThat(Long.parseLong(fields.get("loginTime"))).isPositive();
        assertThat(Long.parseLong(fields.get("lastActiveTime"))).isPositive();
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(120));
    }
}
