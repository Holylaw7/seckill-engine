package com.seckill.auth.service;

import com.seckill.auth.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
}
