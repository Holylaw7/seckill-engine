package com.seckill.order.mq;

import com.seckill.common.util.JsonUtils;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.dto.CancelOrderMessage;
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
public class CancelOrderProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public boolean send(CancelOrderMessage message) {
        try {
            Message<String> mqMessage = MessageBuilder
                    .withPayload(JsonUtils.toJson(message))
                    .setHeader(RocketMQHeaders.KEYS, message.getOrderId())
                    .build();
            rocketMQTemplate.syncSend(
                    OrderConstants.MQ_TOPIC + ":" + OrderConstants.TAG_CANCEL_ORDER,
                    mqMessage, 3000L);
            return true;
        } catch (Exception e) {
            log.warn("cancel order message send failed, orderId={}, error={}",
                    message.getOrderId(), e.getMessage());
            return false;
        }
    }
}
