package com.seckill.seckill.redis.impl;

import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.redis.BucketDeductResult;
import com.seckill.seckill.redis.StockDeductResult;
import com.seckill.seckill.redis.StockRecoverResult;
import com.seckill.seckill.redis.StockService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisStockService implements StockService {

    private final DefaultRedisScript<Long> deductScript;
    private final DefaultRedisScript<Long> recoverScript;
    private final DefaultRedisScript<String> deductBucketScript;
    private final DefaultRedisScript<String> recoverBucketScript;
    private final StringRedisTemplate redisTemplate;

    public RedisStockService(@Qualifier("seckillDeductScript") DefaultRedisScript<Long> deductScript,
                             @Qualifier("seckillRecoverScript") DefaultRedisScript<Long> recoverScript,
                             @Qualifier("seckillDeductBucketScript") DefaultRedisScript<String> deductBucketScript,
                             @Qualifier("seckillRecoverBucketScript") DefaultRedisScript<String> recoverBucketScript,
                             StringRedisTemplate redisTemplate) {
        this.deductScript = deductScript;
        this.recoverScript = recoverScript;
        this.deductBucketScript = deductBucketScript;
        this.recoverBucketScript = recoverBucketScript;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public BucketDeductResult preDeductBucket(String skuId, String userId, int quantity,
                                              long userKeyTtlSeconds, int bucketCount,
                                              long counterTtlSeconds) {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(stockKey(skuId));
        keys.add(userKey(skuId, userId));
        keys.add(rrKey(skuId));
        for (int i = 0; i < bucketCount; i++) {
            keys.add(bucketKey(skuId, i));
        }
        String result = redisTemplate.execute(deductBucketScript, keys,
                String.valueOf(quantity), String.valueOf(userKeyTtlSeconds),
                String.valueOf(bucketCount), String.valueOf(counterTtlSeconds));
        if (result != null && result.startsWith("SUCCESS:")) {
            return BucketDeductResult.of(StockDeductResult.SUCCESS,
                    Integer.valueOf(result.substring("SUCCESS:".length())));
        }
        return BucketDeductResult.of(switch (result == null ? "" : result) {
            case "STOCK_EMPTY" -> StockDeductResult.STOCK_EMPTY;
            case "REPEAT_BUY" -> StockDeductResult.REPEAT_BUY;
            default -> StockDeductResult.NOT_READY;
        }, null);
    }

    @Override
    public StockDeductResult preDeduct(String skuId, String userId, int quantity, long userKeyTtlSeconds) {
        Long result = redisTemplate.execute(
                deductScript,
                List.of(stockKey(skuId), userKey(skuId, userId)),
                String.valueOf(quantity), String.valueOf(userKeyTtlSeconds));
        return switch (result == null ? -99 : result.intValue()) {
            case 1 -> StockDeductResult.SUCCESS;
            case -1 -> StockDeductResult.STOCK_EMPTY;
            case -2 -> StockDeductResult.REPEAT_BUY;
            default -> StockDeductResult.NOT_READY;
        };
    }

    @Override
    public StockRecoverResult recover(String skuId, String userId, int quantity, boolean removeUserMark) {
        Long result = redisTemplate.execute(
                recoverScript,
                List.of(stockKey(skuId), totalKey(skuId), userKey(skuId, userId)),
                String.valueOf(quantity), removeUserMark ? "1" : "0");
        return switch (result == null ? -99 : result.intValue()) {
            case 1 -> StockRecoverResult.SUCCESS;
            case -4 -> StockRecoverResult.OVER_TOTAL;
            default -> StockRecoverResult.NOT_READY;
        };
    }

    @Override
    public StockRecoverResult recoverBucket(String skuId, String userId, int quantity,
                                            boolean removeUserMark, Integer bucketNo) {
        if (bucketNo == null) {
            return recover(skuId, userId, quantity, removeUserMark);
        }
        String result = redisTemplate.execute(recoverBucketScript,
                List.of(stockKey(skuId), totalKey(skuId), bucketKey(skuId, bucketNo),
                        userKey(skuId, userId)),
                String.valueOf(quantity), removeUserMark ? "1" : "0");
        return switch (result == null ? "" : result) {
            case "OVER_TOTAL" -> StockRecoverResult.OVER_TOTAL;
            case "NOT_READY" -> StockRecoverResult.NOT_READY;
            default -> StockRecoverResult.SUCCESS;
        };
    }

    @Override
    public void prepare(String skuId, int totalStock) {
        redisTemplate.opsForValue().set(totalKey(skuId), String.valueOf(totalStock));
        redisTemplate.opsForValue().set(stockKey(skuId), String.valueOf(totalStock));
    }

    @Override
    public void prepareBucket(String skuId, int bucketNo, int bucketTotal) {
        redisTemplate.opsForValue().set(bucketKey(skuId, bucketNo), String.valueOf(bucketTotal));
    }

    @Override
    public boolean isUserMarked(String skuId, String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(userKey(skuId, userId)));
    }

    private String stockKey(String skuId) {
        return SeckillConstants.STOCK_PREFIX + skuId;
    }

    private String totalKey(String skuId) {
        return SeckillConstants.STOCK_TOTAL_PREFIX + skuId;
    }

    private String bucketKey(String skuId, int bucketNo) {
        return SeckillConstants.STOCK_BUCKET_PREFIX + skuId + ":" + bucketNo;
    }

    private String rrKey(String skuId) {
        return SeckillConstants.STOCK_RR_PREFIX + skuId;
    }

    private String userKey(String skuId, String userId) {
        return SeckillConstants.USER_PREFIX + skuId + ":" + userId;
    }
}
