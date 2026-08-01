package com.seckill.inventory.dto;

import java.util.List;

public record ReconcileReport(
        Long skuId,
        Integer totalStock,
        Integer lockedStock,
        Integer availableStock,
        Long deductCount,
        Long recoverCount,
        List<String> issues) {
}
