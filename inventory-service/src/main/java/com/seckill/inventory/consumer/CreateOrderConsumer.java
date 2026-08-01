package com.seckill.inventory.consumer;

import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * CREATE_ORDER 消费组（冻结：inventory-consumer，与 order-service 独立消费）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = InventoryConstants.MQ_TOPIC,
        selectorExpression = InventoryConstants.TAG_CREATE_ORDER,
        consumerGroup = InventoryConstants.CONSUMER_GROUP)
public class CreateOrderConsumer implements RocketMQListener<String> {

    private final InventoryService inventoryService;

    @Override
    public void onMessage(String payload) {
        CreateOrderMessage message = JsonUtils.fromJson(payload, CreateOrderMessage.class);
        try {
            inventoryService.confirmDeduct(message);
        } catch (BusinessException e) {
            // 数据异常（事实不足/不存在/冲突）：冻结差异 + 告警，不重试避免死循环
            log.error("inventory confirm failed, orderId={}, code={}, message={}",
                    message.getOrderId(), e.getErrorCode().getCode(), e.getMessage());
        }
    }
}
