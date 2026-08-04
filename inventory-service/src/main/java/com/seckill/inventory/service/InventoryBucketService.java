package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.config.InventoryShardingProperties;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存分桶扣减/恢复（Phase 6.2）：
 * 行锁只作用于目标分桶行，禁止同时锁 inventory 汇总行（否则热点回归）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryBucketService {

    private final InventoryBucketMapper bucketMapper;
    private final StockFlowService stockFlowService;
    private final InventoryShardingProperties properties;

    /**
     * 分桶 DEDUCT：幂等检查 → 定位桶 → 桶行 FOR UPDATE → CAS → 流水（记录 bucket_no）。
     */
    @Transactional
    public boolean deduct(CreateOrderMessage message) {
        String orderId = message.getOrderId();
        if (stockFlowService.existsByBiz(InventoryConstants.BIZ_TYPE_ORDER, orderId)) {
            return false;
        }
        int quantity = message.getQuantity() == null ? 1 : message.getQuantity();
        int bucketNo = resolveBucketNo(message.getSkuId(), orderId, message.getBucketNo());
        for (int attempt = 0; attempt < InventoryConstants.CAS_MAX_RETRY; attempt++) {
            InventoryBucket bucket = bucketMapper.selectBySkuAndBucketForUpdate(
                    message.getSkuId(), bucketNo);
            if (bucket == null) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存桶不存在");
            }
            // 幂等重查（持有行锁后）：并发重复消息在此被串行化，防止二次扣减
            if (stockFlowService.existsByBiz(InventoryConstants.BIZ_TYPE_ORDER, orderId)) {
                return false;
            }
            if (bucket.getAvailableStock() < quantity) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "桶内事实库存不足");
            }
            int before = bucket.getAvailableStock();
            int after = before - quantity;
            int updated = bucketMapper.update(null, new LambdaUpdateWrapper<InventoryBucket>()
                    .eq(InventoryBucket::getSkuId, message.getSkuId())
                    .eq(InventoryBucket::getBucketNo, bucketNo)
                    .eq(InventoryBucket::getVersion, bucket.getVersion())
                    .ge(InventoryBucket::getAvailableStock, quantity)
                    .set(InventoryBucket::getAvailableStock, after)
                    .set(InventoryBucket::getLockedStock, bucket.getLockedStock() + quantity)
                    .set(InventoryBucket::getVersion, bucket.getVersion() + 1));
            if (updated == 1) {
                stockFlowService.createFlow(InventoryConstants.FLOW_TYPE_DEDUCT,
                        InventoryConstants.BIZ_TYPE_ORDER, orderId, message.getSkuId(),
                        -quantity, before, after, null, "CREATE_ORDER", bucketNo);
                return true;
            }
        }
        throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存更新冲突，请对账");
    }

    /**
     * 分桶 RECOVER：幂等检查 → 从 DEDUCT 流水定位桶 → 桶行 FOR UPDATE → CAS → 流水（记录 bucket_no）。
     */
    @Transactional
    public String recover(String orderId, Long skuId, int quantity, String bizType) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回补数量必须大于0");
        }
        StockFlow existing = stockFlowService.findByBiz(bizType, orderId);
        if (existing != null) {
            return existing.getFlowNo();
        }
        Integer bucketNo = findDeductBucketNo(orderId);
        if (bucketNo == null) {
            throw new BusinessException(ErrorCode.INVENTORY_ERROR, "DEDUCT 流水缺少桶定位");
        }
        for (int attempt = 0; attempt < InventoryConstants.CAS_MAX_RETRY; attempt++) {
            InventoryBucket bucket = bucketMapper.selectBySkuAndBucketForUpdate(skuId, bucketNo);
            if (bucket == null) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存桶不存在");
            }
            // 幂等重查（持有行锁后）：并发重复恢复消息只生效一次
            StockFlow duplicate = stockFlowService.findByBiz(bizType, orderId);
            if (duplicate != null) {
                return duplicate.getFlowNo();
            }
            if (bucket.getLockedStock() < quantity) {
                throw new BusinessException(ErrorCode.INVENTORY_ERROR, "无可回补的锁定库存");
            }
            int before = bucket.getAvailableStock();
            int after = before + quantity;
            int updated = bucketMapper.update(null, new LambdaUpdateWrapper<InventoryBucket>()
                    .eq(InventoryBucket::getSkuId, skuId)
                    .eq(InventoryBucket::getBucketNo, bucketNo)
                    .eq(InventoryBucket::getVersion, bucket.getVersion())
                    .ge(InventoryBucket::getLockedStock, quantity)
                    .set(InventoryBucket::getLockedStock, bucket.getLockedStock() - quantity)
                    .set(InventoryBucket::getAvailableStock, after)
                    .set(InventoryBucket::getVersion, bucket.getVersion() + 1));
            if (updated == 1) {
                return stockFlowService.createFlow(InventoryConstants.FLOW_TYPE_RECOVER,
                        bizType, orderId, skuId, quantity, before, after, null,
                        "STOCK_RECOVER", bucketNo);
            }
        }
        throw new BusinessException(ErrorCode.INVENTORY_ERROR, "库存更新冲突，请对账");
    }

    /**
     * 旧消息（无 bucketNo）兼容：
     * 单桶直接回退桶 0；多桶按 orderId 均匀路由（仅限旧准入消息，新路径 bucket 来自 Lua）。
     */
    private int resolveBucketNo(Long skuId, String orderId, Integer messageBucketNo) {
        if (messageBucketNo != null) {
            return messageBucketNo;
        }
        int bucketCount = properties.getBucketCount();
        if (bucketCount <= 1) {
            return 0;
        }
        return Math.floorMod(orderId.hashCode(), bucketCount);
    }

    private Integer findDeductBucketNo(String orderId) {
        StockFlow flow = stockFlowService.findByBiz(InventoryConstants.BIZ_TYPE_ORDER, orderId);
        return flow == null ? null : flow.getBucketNo();
    }
}
