package com.seckill.inventory.service;

import com.seckill.inventory.dto.CreateOrderMessage;

/**
 * 库存事实服务（Redis 热点库存不在此操作）。
 */
public interface InventoryService {

    /**
     * 库存确认：DEDUCT 流水 + inventory CAS（available-1 / locked+1）。
     *
     * @return true=本次确认，false=已确认过（幂等）
     */
    boolean confirmDeduct(CreateOrderMessage message);

    /**
     * 回补：RECOVER 流水 + inventory CAS（locked-1 / available+1）。
     *
     * @return 流水号（= Redis 回补接口 requestId）
     */
    String recoverStock(String orderId, Long skuId, int quantity, String bizType);

    /**
     * 查询 DEDUCT 流水命中的桶号（分桶恢复定位；单桶/旧路径返回 null）。
     */
    Integer findDeductBucketNo(String orderId);
}
