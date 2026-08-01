package com.seckill.seckill.service;

import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.entity.SeckillSession;
import com.seckill.seckill.mapper.SeckillSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 场次缓存（Cache Aside）：Redis Hash 优先，DB 兜底。
 */
@Service
@RequiredArgsConstructor
public class SessionCacheService {

    private final StringRedisTemplate redisTemplate;
    private final SeckillSessionMapper sessionMapper;

    public SessionCache getSession(Long sessionId) {
        Map<Object, Object> cached = redisTemplate.opsForHash()
                .entries(SeckillConstants.SESSION_PREFIX + sessionId);
        if (!cached.isEmpty()) {
            return fromHash(cached);
        }
        SeckillSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return null;
        }
        SessionCache cache = toCache(session);
        writeCache(cache);
        return cache;
    }

    public void invalidate(Long sessionId) {
        redisTemplate.delete(SeckillConstants.SESSION_PREFIX + sessionId);
    }

    private void writeCache(SessionCache cache) {
        String key = SeckillConstants.SESSION_PREFIX + cache.id();
        Map<String, String> fields = new HashMap<>();
        fields.put("id", String.valueOf(cache.id()));
        fields.put("activityId", String.valueOf(cache.activityId()));
        fields.put("name", cache.name());
        fields.put("startTime", String.valueOf(cache.startTime()));
        fields.put("endTime", String.valueOf(cache.endTime()));
        fields.put("status", cache.status());
        fields.put("limitPerUser", String.valueOf(cache.limitPerUser()));
        redisTemplate.opsForHash().putAll(key, fields);
        long ttlSeconds = Math.max(3600L,
                (cache.endTime() + SeckillConstants.USER_MARK_EXTRA_MILLIS - System.currentTimeMillis()) / 1000);
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    private static SessionCache toCache(SeckillSession session) {
        return new SessionCache(
                session.getId(),
                session.getActivityId(),
                session.getSessionName(),
                toMillis(session.getStartTime()),
                toMillis(session.getEndTime()),
                session.getStatus(),
                session.getLimitPerUser());
    }

    private static SessionCache fromHash(Map<Object, Object> hash) {
        return new SessionCache(
                Long.valueOf(String.valueOf(hash.get("id"))),
                Long.valueOf(String.valueOf(hash.get("activityId"))),
                String.valueOf(hash.get("name")),
                Long.parseLong(String.valueOf(hash.get("startTime"))),
                Long.parseLong(String.valueOf(hash.get("endTime"))),
                String.valueOf(hash.get("status")),
                Integer.parseInt(String.valueOf(hash.get("limitPerUser"))));
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public record SessionCache(Long id, Long activityId, String name,
                               long startTime, long endTime, String status, int limitPerUser) {
    }
}
