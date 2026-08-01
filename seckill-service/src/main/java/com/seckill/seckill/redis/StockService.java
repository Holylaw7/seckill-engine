package com.seckill.seckill.redis;

/**
 * 热点库存服务（Redis 热点层，禁止触碰库存数据库）。
 */
public interface StockService {

    StockDeductResult preDeduct(String skuId, String userId, int quantity, long userKeyTtlSeconds);

    StockRecoverResult recover(String skuId, String userId, int quantity, boolean removeUserMark);

    void prepare(String skuId, int totalStock);

    boolean isUserMarked(String skuId, String userId);
}
