package com.seckill.inventory.dto;

import java.util.List;

/**
 * 分桶对账报告（Phase 6.2）：桶内/汇总/Redis 一致性检查结果，不含自动修复。
 */
public record BucketReconcileReport(
        Long skuId,
        int bucketCount,
        int totalStock,
        int lockedStock,
        int availableStock,
        Long redisStock,
        List<String> issues) {

    public boolean isPass() {
        return issues.isEmpty();
    }
}
