package com.seckill.seckill.redis.impl;

import com.seckill.seckill.constant.SeckillConstants;
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
    private final StringRedisTemplate redisTemplate;

    public RedisStockService(@Qualifier("seckillDeductScript") DefaultRedisScript<Long> deductScript,
                             @Qualifier("seckillRecoverScript") DefaultRedisScript<Long> recoverScript,
                             StringRedisTemplate redisTemplate) {
        this.deductScript = deductScript;
        this.recoverScript = recoverScript;
        this.redisTemplate = redisTemplate;
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
    public void prepare(String skuId, int totalStock) {
        redisTemplate.opsForValue().set(totalKey(skuId), String.valueOf(totalStock));
        redisTemplate.opsForValue().set(stockKey(skuId), String.valueOf(totalStock));
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

    private String userKey(String skuId, String userId) {
        return SeckillConstants.USER_PREFIX + skuId + ":" + userId;
    }
}
