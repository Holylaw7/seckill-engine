package com.seckill.inventory.consumer;

import com.seckill.common.util.JsonUtils;
import com.seckill.inventory.client.RecoverClient;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.RecoverRequest;
import com.seckill.inventory.dto.StockRecoverMessage;
import com.seckill.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * STOCK_RECOVER / CANCEL_ORDER 消费：MySQL 事实优先，Redis 恢复失败进入告警 + repair。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = InventoryConstants.MQ_TOPIC,
        selectorExpression = InventoryConstants.TAG_STOCK_RECOVER + " || " + InventoryConstants.TAG_CANCEL_ORDER,
        consumerGroup = InventoryConstants.CONSUMER_GROUP_RECOVER)
public class StockRecoverConsumer implements RocketMQListener<String> {

    private final InventoryService inventoryService;
    private final RecoverClient recoverClient;

    @Override
    public void onMessage(String payload) {
        StockRecoverMessage message = JsonUtils.fromJson(payload, StockRecoverMessage.class);
        String bizType = mapReason(message.getReason());
        int quantity = message.getQuantity() == null ? 1 : message.getQuantity();

        String flowNo = inventoryService.recoverStock(
                message.getOrderId(), message.getSkuId(), quantity, bizType);
        Integer bucketNo = inventoryService.findDeductBucketNo(message.getOrderId());

        boolean recovered = recoverClient.recover(new RecoverRequest(
                flowNo, message.getSkuId(), message.getSessionId(), quantity, bucketNo));
        if (!recovered) {
            // 冻结策略：MySQL 成功优先，Redis 失败进入告警 + repair 流程
            log.error("redis recover failed, orderId={}, flowNo={}, skuId={} -> repair flow",
                    message.getOrderId(), flowNo, message.getSkuId());
        }
    }

    private static String mapReason(String reason) {
        if (InventoryConstants.REASON_TIMEOUT.equalsIgnoreCase(reason)) {
            return InventoryConstants.BIZ_TYPE_TIMEOUT;
        }
        if (InventoryConstants.REASON_REFUND.equalsIgnoreCase(reason)) {
            return InventoryConstants.BIZ_TYPE_REFUND;
        }
        return InventoryConstants.BIZ_TYPE_CANCEL;
    }
}
