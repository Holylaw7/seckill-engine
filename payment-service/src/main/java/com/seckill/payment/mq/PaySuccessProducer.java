package com.seckill.payment.mq;

import com.seckill.common.util.JsonUtils;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.PaySuccessMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaySuccessProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public boolean send(PaySuccessMessage message) {
        try {
            Message<String> mqMessage = MessageBuilder
                    .withPayload(JsonUtils.toJson(message))
                    .setHeader(RocketMQHeaders.KEYS, message.getPaymentNo())
                    .build();
            rocketMQTemplate.syncSend(
                    PaymentConstants.MQ_TOPIC + ":" + PaymentConstants.TAG_PAY_SUCCESS,
                    mqMessage, 3000L);
            return true;
        } catch (Exception e) {
            log.warn("pay success message send failed, paymentNo={}, error={}",
                    message.getPaymentNo(), e.getMessage());
            return false;
        }
    }
}
