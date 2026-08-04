package com.seckill.seckill.service;

import com.seckill.seckill.entity.SeckillSession;
import com.seckill.seckill.mapper.SeckillSessionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class SessionCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SeckillSessionMapper sessionMapper;

    private SessionCacheService newService() {
        return new SessionCacheService(redisTemplate, sessionMapper);
    }

    @Test
    void cacheHitShouldNotTouchDb() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:session:30001")).thenReturn(Map.of(
                "id", "30001", "activityId", "1", "name", "test",
                "startTime", "1000", "endTime", "2000", "status", "READY", "limitPerUser", "1"));
        SessionCacheService.SessionCache cache = newService().getSession(30001L);
        assertEquals(30001L, cache.id());
        assertEquals("READY", cache.status());
        assertEquals(2000L, cache.endTime());
        verify(sessionMapper, never()).selectById(any());
    }

    @Test
    void cacheMissShouldLoadAndWriteCache() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:session:30001")).thenReturn(Map.of());

        SeckillSession session = new SeckillSession();
        session.setId(30001L);
        session.setActivityId(1L);
        session.setSessionName("test");
        session.setStartTime(LocalDateTime.now().minusMinutes(1));
        session.setEndTime(LocalDateTime.now().plusMinutes(30));
        session.setStatus("READY");
        session.setLimitPerUser(1);
        when(sessionMapper.selectById(30001L)).thenReturn(session);

        SessionCacheService.SessionCache cache = newService().getSession(30001L);
        assertEquals("test", cache.name());
        assertEquals("READY", cache.status());
        verify(hashOperations).putAll(anyString(), anyMap());
        verify(redisTemplate).expire(anyString(), any());
    }

    @Test
    void missingSessionShouldReturnNull() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("seckill:session:30001")).thenReturn(Map.of());
        when(sessionMapper.selectById(30001L)).thenReturn(null);
        assertNull(newService().getSession(30001L));
    }

    @Test
    void invalidateShouldDeleteKey() {
        newService().invalidate(30001L);
        verify(redisTemplate).delete("seckill:session:30001");
    }
}
