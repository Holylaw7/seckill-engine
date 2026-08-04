package com.seckill.inventory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.InventoryMapper;
import com.seckill.inventory.service.InventoryService;
import com.seckill.inventory.service.StockFlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final StockFlowService stockFlowService;

    @Override
    @Transactional
    public boolean confirmDeduct(CreateOrderMessage message) {
        String orderId = message.getOrderId();
        if (stockFlowService.existsByBiz(InventoryConstants.BIZ_TYPE_ORDER, orderId)) {
            return false;
        }
        int quantity = message.getQuantity() == null ? 1 : message.getQuantity();
        for (int attempt = 0; attempt < InventoryConstants.CAS_MAX_RETRY; attempt++) {
            Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getSkuId, message.getSkuId())
                    .last("FOR UPDATE"));
            if (inventory == null) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存事实不存在");
            }
            if (inventory.getAvailableStock() < quantity) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "事实库存不足");
            }
            int before = inventory.getAvailableStock();
            int after = before - quantity;
            int updated = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .eq(Inventory::getSkuId, message.getSkuId())
                    .eq(Inventory::getVersion, inventory.getVersion())
                    .ge(Inventory::getAvailableStock, quantity)
                    .set(Inventory::getAvailableStock, after)
                    .set(Inventory::getLockedStock, inventory.getLockedStock() + quantity)
                    .set(Inventory::getVersion, inventory.getVersion() + 1));
            if (updated == 1) {
                stockFlowService.createFlow(InventoryConstants.FLOW_TYPE_DEDUCT,
                        InventoryConstants.BIZ_TYPE_ORDER, orderId, message.getSkuId(),
                        -quantity, before, after, null, "CREATE_ORDER");
                return true;
            }
        }
        throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存更新冲突，请对账");
    }

    @Override
    @Transactional
    public String recoverStock(String orderId, Long skuId, int quantity, String bizType) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回补数量必须大于0");
        }
        StockFlow existing = stockFlowService.findByBiz(bizType, orderId);
        if (existing != null) {
            return existing.getFlowNo();
        }
        for (int attempt = 0; attempt < InventoryConstants.CAS_MAX_RETRY; attempt++) {
            Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getSkuId, skuId)
                    .last("FOR UPDATE"));
            if (inventory == null) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存事实不存在");
            }
            if (inventory.getLockedStock() < quantity) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "无可回补的锁定库存");
            }
            int before = inventory.getAvailableStock();
            int after = before + quantity;
            int updated = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .eq(Inventory::getSkuId, skuId)
                    .eq(Inventory::getVersion, inventory.getVersion())
                    .ge(Inventory::getLockedStock, quantity)
                    .set(Inventory::getLockedStock, inventory.getLockedStock() - quantity)
                    .set(Inventory::getAvailableStock, after)
                    .set(Inventory::getVersion, inventory.getVersion() + 1));
            if (updated == 1) {
                return stockFlowService.createFlow(InventoryConstants.FLOW_TYPE_RECOVER,
                        bizType, orderId, skuId, quantity, before, after, null, "STOCK_RECOVER");
            }
        }
        throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存更新冲突，请对账");
    }
}
