package com.seckill.payment.mq;

import com.seckill.common.util.JsonUtils;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.RefundSuccessMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 退款成功事件发布器。
 *
 * <p>消息 ID 使用 refundNo 派生，补偿重发时保持稳定，订单域以 refundNo 做最终幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundSuccessProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public boolean send(RefundSuccessMessage message) {
        try {
            Message<String> mqMessage = MessageBuilder
                    .withPayload(JsonUtils.toJson(message))
                    .setHeader(RocketMQHeaders.KEYS, message.getRefundNo())
                    .build();
            rocketMQTemplate.syncSend(
                    PaymentConstants.MQ_TOPIC + ":" + PaymentConstants.TAG_REFUND_SUCCESS,
                    mqMessage, 3000L);
            return true;
        } catch (Exception e) {
            log.warn("refund success message send failed, refundNo={}, paymentNo={}, error={}",
                    message == null ? null : message.getRefundNo(),
                    message == null ? null : message.getPaymentNo(),
                    e.getMessage());
            return false;
        }
    }
}
