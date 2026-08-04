package com.seckill.gateway.blacklist;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 黑名单实现。
 *
 * <p>key（冻结，对齐 Redis 设计）：</p>
 * <ul>
 *   <li>IP：{@code risk:blacklist:ip:{ip}}</li>
 *   <li>用户：{@code risk:blacklist:user:{userId}}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisBlacklistService implements BlacklistService {

    private static final String IP_PREFIX = "risk:blacklist:ip:";
    private static final String USER_PREFIX = "risk:blacklist:user:";

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Boolean> isIpBlocked(String ip) {
        return redisTemplate.opsForValue().get(IP_PREFIX + ip)
                .map(value -> true)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> isUserBlocked(String userId) {
        return redisTemplate.opsForValue().get(USER_PREFIX + userId)
                .map(value -> true)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> isBlocked(String ip, String userId) {
        List<String> keys = new ArrayList<>(2);
        if (ip != null) {
            keys.add(IP_PREFIX + ip);
        }
        if (userId != null) {
            keys.add(USER_PREFIX + userId);
        }
        if (keys.isEmpty()) {
            return Mono.just(false);
        }
        return redisTemplate.opsForValue().multiGet(keys)
                .map(values -> values.stream().anyMatch(value -> value != null))
                .defaultIfEmpty(false);
    }
}
