package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import com.seckill.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 库存分桶迁移服务（Phase 6.2.1）：
 * 将单 SKU 库存拆分为 N 个分桶行，支持 dry-run（只读计划，不写库）。
 * 规则：sum(bucket.total) == inventory.total。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryBucketMigrationService {

    private final InventoryBucketMapper bucketMapper;
    private final InventoryMapper inventoryMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 分桶迁移（dryRun=true 时只输出计划，不写库）。
     *
     * @return 迁移前后桶状态与差异
     */
    @Transactional
    public BucketMigrationResult migrate(Long skuId, int totalStock, int bucketCount, boolean dryRun) {
        if (skuId == null || bucketCount <= 0 || totalStock < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "skuId/bucketCount/totalStock 非法");
        }
        Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存事实不存在，无法分桶");
        }
        if (inventory.getTotalStock() != totalStock) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR,
                    "inventory.total(" + inventory.getTotalStock() + ") 与输入 totalStock(" + totalStock + ") 不一致");
        }

        List<InventoryBucket> existing = bucketMapper.selectBySku(skuId);
        List<BucketState> before = toStates(existing);
        List<BucketState> after = plannedStates(totalStock, bucketCount);
        List<String> diff = diff(before, after);

        if (dryRun) {
            log.info("bucket migration dry-run, skuId={}, total={}, bucketCount={}, diff={}",
                    skuId, totalStock, bucketCount, diff);
            return new BucketMigrationResult(skuId, totalStock, bucketCount, before, after, diff);
        }

        // 已存在且与计划不一致：禁止覆盖（人工介入）
        if (!before.isEmpty() && !before.equals(after)) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR,
                    "已存在分桶且与计划不一致，禁止覆盖，请先人工对账");
        }
        for (BucketState state : after) {
            if (exists(existing, state.bucketNo())) {
                continue;
            }
            InventoryBucket bucket = new InventoryBucket();
            bucket.setId(snowflakeIdGenerator.nextId());
            bucket.setSkuId(skuId);
            bucket.setBucketNo(state.bucketNo());
            bucket.setTotalStock(state.total());
            bucket.setLockedStock(0);
            bucket.setAvailableStock(state.total());
            bucket.setVersion(0);
            bucketMapper.insert(bucket);
        }
        log.info("bucket migration done, skuId={}, bucketCount={}", skuId, bucketCount);
        return new BucketMigrationResult(skuId, totalStock, bucketCount,
                before, bucketMapper.selectBySku(skuId).stream()
                .map(InventoryBucketMigrationService::toState).toList(), diff);
    }

    private static boolean exists(List<InventoryBucket> buckets, int bucketNo) {
        return buckets.stream().anyMatch(bucket -> bucket.getBucketNo() == bucketNo);
    }

    private static List<BucketState> plannedStates(int totalStock, int bucketCount) {
        int base = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;
        List<BucketState> states = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            int bucketTotal = base + (i < remainder ? 1 : 0);
            states.add(new BucketState(i, bucketTotal, 0, bucketTotal));
        }
        return states;
    }

    private static List<BucketState> toStates(List<InventoryBucket> buckets) {
        return buckets.stream().map(InventoryBucketMigrationService::toState).toList();
    }

    private static BucketState toState(InventoryBucket bucket) {
        return new BucketState(bucket.getBucketNo(), bucket.getTotalStock(),
                bucket.getLockedStock(), bucket.getAvailableStock());
    }

    private static List<String> diff(List<BucketState> before, List<BucketState> after) {
        List<String> lines = new ArrayList<>();
        for (BucketState state : after) {
            BucketState old = before.stream()
                    .filter(item -> item.bucketNo() == state.bucketNo())
                    .findFirst().orElse(null);
            if (old == null) {
                lines.add("bucket " + state.bucketNo() + ": 新增 total=" + state.total());
            } else if (!old.equals(state)) {
                lines.add("bucket " + state.bucketNo() + ": " + old + " -> " + state);
            }
        }
        if (lines.isEmpty()) {
            lines.add("无差异（已迁移或计划一致）");
        }
        return lines;
    }

    /** 桶状态（迁移前后对比） */
    public record BucketState(int bucketNo, int total, int locked, int available) {
    }

    /** 迁移结果：before / after / diff */
    public record BucketMigrationResult(Long skuId, int totalStock, int bucketCount,
                                        List<BucketState> before, List<BucketState> after,
                                        List<String> diff) {
    }
}
