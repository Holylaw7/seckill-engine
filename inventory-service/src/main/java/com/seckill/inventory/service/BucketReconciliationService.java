package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.dto.BucketReconcileReport;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import com.seckill.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 分桶对账（Phase 6.2）：
 * 检查桶内不变量、SKU 汇总、Redis 全局库存；只输出 diff report，不自动修复。
 * syncSummary 为显式汇总刷新（运维/测试调用，非自动）。
 */
@Service
@RequiredArgsConstructor
public class BucketReconciliationService {

    private static final String REDIS_STOCK_PREFIX = "seckill:stock:";

    private final InventoryBucketMapper bucketMapper;
    private final InventoryMapper inventoryMapper;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public BucketReconcileReport check(Long skuId) {
        List<InventoryBucket> buckets = bucketMapper.selectBySku(skuId);
        List<String> issues = new ArrayList<>();
        int sumTotal = 0;
        int sumLocked = 0;
        int sumAvailable = 0;
        for (InventoryBucket bucket : buckets) {
            if (bucket.getTotalStock() != bucket.getLockedStock() + bucket.getAvailableStock()) {
                issues.add("bucket " + bucket.getBucketNo() + " 不变量破坏: total(" + bucket.getTotalStock()
                        + ") != locked(" + bucket.getLockedStock() + ") + available("
                        + bucket.getAvailableStock() + ")");
            }
            if (bucket.getAvailableStock() < 0 || bucket.getLockedStock() < 0) {
                issues.add("bucket " + bucket.getBucketNo() + " 存在负库存");
            }
            sumTotal += bucket.getTotalStock();
            sumLocked += bucket.getLockedStock();
            sumAvailable += bucket.getAvailableStock();
        }

        Inventory summary = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (summary != null) {
            checkSummary(summary, sumTotal, sumLocked, sumAvailable, issues);
        } else if (!buckets.isEmpty()) {
            issues.add("inventory 汇总行不存在");
        }

        Long redisStock = redisStock(skuId);
        if (redisStock != null && redisStock != sumAvailable) {
            issues.add("Redis 全局库存(" + redisStock + ") != SUM(bucket.available)(" + sumAvailable + ")");
        }
        if (!issues.isEmpty()) {
            Counter.builder("reconcile_diff_total").tag("application", "inventory-service")
                    .register(meterRegistry).increment(issues.size());
        }
        return new BucketReconcileReport(skuId, buckets.size(), sumTotal, sumLocked,
                sumAvailable, redisStock, issues);
    }

    /**
     * 显式刷新 inventory 汇总行为分桶 SUM（供运维/测试调用；不自动触发）。
     */
    @Transactional
    public BucketReconcileReport syncSummary(Long skuId) {
        List<InventoryBucket> buckets = bucketMapper.selectBySku(skuId);
        if (buckets.isEmpty()) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR, "SKU 尚未分桶，无法同步汇总");
        }
        int sumTotal = 0;
        int sumLocked = 0;
        int sumAvailable = 0;
        for (InventoryBucket bucket : buckets) {
            sumTotal += bucket.getTotalStock();
            sumLocked += bucket.getLockedStock();
            sumAvailable += bucket.getAvailableStock();
        }
        Inventory summary = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (summary == null) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR, "inventory 汇总行不存在");
        }
        inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId)
                .eq(Inventory::getVersion, summary.getVersion())
                .set(Inventory::getTotalStock, sumTotal)
                .set(Inventory::getLockedStock, sumLocked)
                .set(Inventory::getAvailableStock, sumAvailable)
                .set(Inventory::getVersion, summary.getVersion() + 1));
        return check(skuId);
    }

    private static void checkSummary(Inventory summary, int sumTotal, int sumLocked, int sumAvailable,
                                     List<String> issues) {
        if (summary.getTotalStock() != sumTotal) {
            issues.add("inventory.total(" + summary.getTotalStock() + ") != SUM(bucket.total)(" + sumTotal + ")");
        }
        if (summary.getLockedStock() != sumLocked) {
            issues.add("inventory.locked(" + summary.getLockedStock() + ") != SUM(bucket.locked)(" + sumLocked + ")");
        }
        if (summary.getAvailableStock() != sumAvailable) {
            issues.add("inventory.available(" + summary.getAvailableStock() + ") != SUM(bucket.available)("
                    + sumAvailable + ")");
        }
    }

    private Long redisStock(Long skuId) {
        try {
            String value = redisTemplate.opsForValue().get(REDIS_STOCK_PREFIX + skuId);
            return value == null ? null : Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }
}
