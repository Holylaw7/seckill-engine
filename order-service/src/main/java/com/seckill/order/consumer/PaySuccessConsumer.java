package com.seckill.order.consumer;

import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.PaySuccessMessage;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 支付成功事件消费者：订单状态由 order-service 在本地事务内完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = OrderConstants.MQ_TOPIC,
        selectorExpression = OrderConstants.TAG_PAY_SUCCESS,
        consumerGroup = OrderConstants.PAY_SUCCESS_CONSUMER_GROUP)
public class PaySuccessConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String payload) {
        PaySuccessMessage message = JsonUtils.fromJson(payload, PaySuccessMessage.class);
        try {
            orderService.processPaySuccess(message);
        } catch (BusinessException e) {
            // 消息字段或业务数据不合法：记录并 ACK，避免 poison message 无限重试。
            log.error("pay success business validation failed, paymentNo={}, orderNo={}, code={}, message={}",
                    message == null ? null : message.getPaymentNo(),
                    message == null ? null : message.getOrderNo(),
                    e.getErrorCode().getCode(), e.getMessage());
        }
        // RuntimeException 不吞掉，交给 RocketMQ 重试机制处理数据库/基础设施故障。
    }
}
