package com.seckill.inventory.client;

import com.seckill.inventory.dto.RecoverRequest;

/**
 * Redis 恢复客户端（调用 seckill-service 内部接口，本服务不直接操作 Redis）。
 */
public interface RecoverClient {

    boolean recover(RecoverRequest request);
}
