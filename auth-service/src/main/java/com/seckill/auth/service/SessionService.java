package com.seckill.auth.service;

import com.seckill.auth.config.JwtProperties;
import com.seckill.auth.constant.AuthConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话服务：auth:session:{userId}（Hash，字段冻结）。
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public void createSession(String userId, String username, String ip, String device) {
        String key = AuthConstants.SESSION_PREFIX + userId;
        Map<String, String> fields = new HashMap<>();
        fields.put("userId", userId);
        fields.put("username", username);
        fields.put("tokenVersion", "1");
        long now = System.currentTimeMillis();
        fields.put("loginTime", String.valueOf(now));
        fields.put("lastActiveTime", String.valueOf(now));
        fields.put("device", device == null ? "" : device);
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, Duration.ofMinutes(jwtProperties.getExpireMinutes()));
    }

    public Map<Object, Object> getSession(String userId) {
        return redisTemplate.opsForHash().entries(AuthConstants.SESSION_PREFIX + userId);
    }

    public void deleteSession(String userId) {
        redisTemplate.delete(AuthConstants.SESSION_PREFIX + userId);
    }

    public void touch(String userId) {
        redisTemplate.opsForHash().put(
                AuthConstants.SESSION_PREFIX + userId, "lastActiveTime", String.valueOf(System.currentTimeMillis()));
    }
}
