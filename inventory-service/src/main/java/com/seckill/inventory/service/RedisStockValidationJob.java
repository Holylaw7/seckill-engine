package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import com.seckill.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6.7 Redis 生产一致性校验 Job（运维/调度平台调用，不自动定时执行）。
 *
 * <p>校验：</p>
 * <ul>
 *   <li>Redis global == SUM(Redis bucket)；</li>
 *   <li>inventory.total == SUM(bucket.total)；</li>
 *   <li>inventory.available + locked == total；</li>
 *   <li>Redis global == inventory.available（最终一致性口径）。</li>
 * </ul>
 *
 * <p>失败时禁止 Canary 升级（由 CanaryHealthEvaluator inventory_diff != 0 拦截）。</p>
 */
@Service
@RequiredArgsConstructor
public class RedisStockValidationJob {

    private static final String REDIS_STOCK_PREFIX = "seckill:stock:";
    private static final String REDIS_BUCKET_PREFIX = "seckill:stock:bucket:";

    private final InventoryBucketMapper bucketMapper;
    private final InventoryMapper inventoryMapper;
    private final StringRedisTemplate redisTemplate;

    public RedisStockValidationReport validate(Long skuId) {
        List<String> diffs = new ArrayList<>();
        List<InventoryBucket> buckets = bucketMapper.selectBySku(skuId);

        Long redisGlobal = readLong(REDIS_STOCK_PREFIX + skuId);
        if (redisGlobal == null) {
            diffs.add("Redis 全局库存缺失");
        }

        long redisBucketSum = 0;
        for (InventoryBucket bucket : buckets) {
            Long bucketValue = readLong(REDIS_BUCKET_PREFIX + skuId + ":" + bucket.getBucketNo());
            if (bucketValue == null) {
                diffs.add("Redis 分桶缺失 bucket_no=" + bucket.getBucketNo());
            } else {
                redisBucketSum += bucketValue;
            }
        }

        Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            diffs.add("inventory 汇总行不存在");
        }

        long bucketTotalSum = buckets.stream().mapToLong(InventoryBucket::getTotalStock).sum();
        if (inventory != null && inventory.getTotalStock().longValue() != bucketTotalSum) {
            diffs.add("inventory.total(" + inventory.getTotalStock()
                    + ") != SUM(bucket.total)(" + bucketTotalSum + ")");
        }
        if (inventory != null
                && inventory.getAvailableStock().longValue() + inventory.getLockedStock().longValue()
                != inventory.getTotalStock().longValue()) {
            diffs.add("inventory 不变量破坏: available+locked != total");
        }
        if (redisGlobal != null && redisGlobal != redisBucketSum) {
            diffs.add("Redis global(" + redisGlobal
                    + ") != SUM(redis bucket)(" + redisBucketSum + ")");
        }
        if (inventory != null && redisGlobal != null
                && inventory.getAvailableStock().longValue() != redisGlobal) {
            diffs.add("inventory.available(" + inventory.getAvailableStock()
                    + ") != Redis global(" + redisGlobal + ")");
        }
        return new RedisStockValidationReport(
                skuId, redisGlobal, redisBucketSum,
                inventory == null ? null : inventory.getTotalStock().longValue(),
                inventory == null ? null : inventory.getAvailableStock().longValue(),
                inventory == null ? null : inventory.getLockedStock().longValue(),
                bucketTotalSum, diffs);
    }

    private Long readLong(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? null : Long.parseLong(value);
    }

    public record RedisStockValidationReport(Long skuId, Long redisGlobal, Long redisBucketSum,
                                             Long mysqlTotal, Long mysqlAvailable, Long mysqlLocked,
                                             Long bucketTotalSum, List<String> diffs) {

        public boolean isPass() {
            return diffs.isEmpty();
        }
    }
}
