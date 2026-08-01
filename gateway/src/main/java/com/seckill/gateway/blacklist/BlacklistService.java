package com.seckill.gateway.blacklist;

import reactor.core.publisher.Mono;

/**
 * 黑名单服务接口（数据结构预留，支持 Redis 实现与后续扩展）。
 */
public interface BlacklistService {

    Mono<Boolean> isIpBlocked(String ip);

    Mono<Boolean> isUserBlocked(String userId);
}
