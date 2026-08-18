package com.seckill.order.consumer;

import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.RefundSuccessMessage;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 退款成功事件消费者：订单状态由 order-service 在本地事务内流转到 REFUND。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = OrderConstants.MQ_TOPIC,
        selectorExpression = OrderConstants.TAG_REFUND_SUCCESS,
        consumerGroup = OrderConstants.REFUND_SUCCESS_CONSUMER_GROUP)
public class RefundSuccessConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String payload) {
        RefundSuccessMessage message = JsonUtils.fromJson(payload, RefundSuccessMessage.class);
        try {
            orderService.processRefundSuccess(message);
        } catch (BusinessException e) {
            log.error("refund success business validation failed, refundNo={}, orderNo={}, code={}, message={}",
                    message == null ? null : message.getRefundNo(),
                    message == null ? null : message.getOrderNo(),
                    e.getErrorCode().getCode(), e.getMessage());
        }
        // RuntimeException 不吞掉，交给 RocketMQ 重试机制处理数据库/基础设施故障。
    }
}
