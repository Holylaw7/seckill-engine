package com.seckill.seckill.redis;

/**
 * 热点库存服务（Redis 热点层，禁止触碰库存数据库）。
 */
public interface StockService {

    StockDeductResult preDeduct(String skuId, String userId, int quantity, long userKeyTtlSeconds);

    /**
     * 分桶预扣（Lua v2）：全局 + 目标桶原子扣减，返回 Lua 选定的桶号。
     */
    BucketDeductResult preDeductBucket(String skuId, String userId, int quantity,
                                       long userKeyTtlSeconds, int bucketCount, long counterTtlSeconds);

    StockRecoverResult recover(String skuId, String userId, int quantity, boolean removeUserMark);

    /**
     * 分桶回补：全局 + 桶原子回补；bucketNo 为空时回退单桶路径。
     */
    StockRecoverResult recoverBucket(String skuId, String userId, int quantity,
                                     boolean removeUserMark, Integer bucketNo);

    void prepare(String skuId, int totalStock);

    /** 预热分桶库存 key（Phase 6.2，迁移后调用） */
    void prepareBucket(String skuId, int bucketNo, int bucketTotal);

    boolean isUserMarked(String skuId, String userId);
}
