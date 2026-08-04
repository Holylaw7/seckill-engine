package com.seckill.seckill.redis;

/**
 * 分桶预扣结果（Phase 6.2）：结果 + Lua 选定的桶号（业务层禁止猜测）。
 */
public record BucketDeductResult(StockDeductResult result, Integer bucketNo) {

    public static BucketDeductResult of(StockDeductResult result, Integer bucketNo) {
        return new BucketDeductResult(result, bucketNo);
    }
}
