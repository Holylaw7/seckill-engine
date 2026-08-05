package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.ReconcileReport;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.InventoryMapper;
import com.seckill.inventory.mapper.StockFlowMapper;
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账修复框架（只负责库存事实核对；订单/支付对账由其他服务负责）。
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final InventoryMapper inventoryMapper;
    private final StockFlowMapper stockFlowMapper;
    private final StockFlowService stockFlowService;
    private final MeterRegistry meterRegistry;

    public ReconcileReport check(Long skuId) {
        List<String> issues = new ArrayList<>();
        Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            return new ReconcileReport(skuId, null, null, null, 0L, 0L,
                    List.of("库存事实不存在"));
        }
        int total = inventory.getTotalStock();
        int locked = inventory.getLockedStock();
        int available = inventory.getAvailableStock();
        if (total != locked + available) {
            issues.add("不变量被破坏：total(" + total + ") != locked(" + locked + ") + available(" + available + ")");
        }
        if (locked < 0) {
            issues.add("locked_stock 为负");
        }
        if (available < 0) {
            issues.add("available_stock 为负");
        }
        long deductCount = stockFlowMapper.selectCount(new LambdaQueryWrapper<StockFlow>()
                .eq(StockFlow::getSkuId, skuId)
                .eq(StockFlow::getChangeType, InventoryConstants.FLOW_TYPE_DEDUCT));
        long recoverCount = stockFlowMapper.selectCount(new LambdaQueryWrapper<StockFlow>()
                .eq(StockFlow::getSkuId, skuId)
                .eq(StockFlow::getChangeType, InventoryConstants.FLOW_TYPE_RECOVER));
        if (recoverCount > deductCount) {
            issues.add("RECOVER 流水数(" + recoverCount + ") 大于 DEDUCT 流水数(" + deductCount + ")");
        }
        return new ReconcileReport(skuId, total, locked, available, deductCount, recoverCount, issues);
    }

    /**
     * 修复可用库存（REPAIR 流水 + CAS；目标值必须在 [0, total]）。
     */
    @Transactional
    public String repair(Long skuId, int targetAvailable, String reason, Long operatorId) {
        for (int attempt = 0; attempt < InventoryConstants.CAS_MAX_RETRY; attempt++) {
            Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getSkuId, skuId));
            if (inventory == null) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存事实不存在");
            }
            if (targetAvailable < 0 || targetAvailable > inventory.getTotalStock()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "targetAvailable 必须在 [0, " + inventory.getTotalStock() + "] 区间");
            }
            int before = inventory.getAvailableStock();
            int updated = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .eq(Inventory::getSkuId, skuId)
                    .eq(Inventory::getVersion, inventory.getVersion())
                    .set(Inventory::getAvailableStock, targetAvailable)
                    .set(Inventory::getVersion, inventory.getVersion() + 1));
            if (updated == 1) {
                String repairNo = "REPAIR-" + skuId + "-" + System.currentTimeMillis();
                Counter.builder("inventory_repair_total").tag("application", "inventory-service")
                        .register(meterRegistry).increment();
                return stockFlowService.createFlow(InventoryConstants.FLOW_TYPE_REPAIR,
                        InventoryConstants.BIZ_TYPE_MANUAL, repairNo, skuId,
                        targetAvailable - before, before, targetAvailable, operatorId, reason);
            }
        }
        throw new BusinessException(ErrorCode.INVENTORY_ERROR, "修复冲突，请重试");
    }
}
