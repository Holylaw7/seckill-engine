package com.seckill.order.consumer;

import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.client.PreDeductConfirmClient;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.CreateOrderMessage;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = OrderConstants.MQ_TOPIC,
        selectorExpression = OrderConstants.TAG_CREATE_ORDER,
        consumerGroup = OrderConstants.CONSUMER_GROUP)
public class CreateOrderConsumer implements RocketMQListener<String> {

    private final OrderService orderService;
    private final PreDeductConfirmClient preDeductConfirmClient;

    @Override
    public void onMessage(String payload) {
        CreateOrderMessage message = null;
        try {
            message = JsonUtils.fromJson(payload, CreateOrderMessage.class);
            SeckillOrder order = orderService.createOrder(message);
            if (order != null) {
                boolean confirmed = preDeductConfirmClient.confirm(
                        message.getMessageId(), message.getOrderId());
                if (!confirmed) {
                    log.warn("pre-deduct confirm failed, messageId={}, orderId={} -> 对账兜底",
                            message.getMessageId(), message.getOrderId());
                }
            }
        } catch (BusinessException e) {
            // JSON/业务数据异常：告警并 ACK，避免 poison message 无限重试。
            log.error("order create failed, orderId={}, code={}, message={}",
                    message == null ? null : message.getOrderId(),
                    e.getErrorCode().getCode(), e.getMessage());
        }
    }
}
