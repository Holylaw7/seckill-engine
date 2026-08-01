package com.seckill.seckill.mq;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.constant.SeckillConstants;
import com.seckill.seckill.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒杀事务消息 Producer（seckill-order-tx / CREATE_ORDER，messageId 全链路冻结）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RocketMqProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SeckillProperties properties;

    public boolean sendCreateOrder(SeckillOrderMessage message) {
        String flowKey = SeckillConstants.FLOW_PREFIX + message.getOrderId();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(
                flowKey, "1", Duration.ofSeconds(properties.getFlowKeyTtlSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            // 同一 orderId 已发送过：幂等成功
            return true;
        }
        try {
            Message<String> mqMessage = MessageBuilder
                    .withPayload(JsonUtils.toJson(message))
                    .setHeader(RocketMQHeaders.KEYS, message.getOrderId())
                    .build();
            org.apache.rocketmq.client.producer.TransactionSendResult txResult = rocketMQTemplate.sendMessageInTransaction(
                    SeckillConstants.MQ_TOPIC + ":" + SeckillConstants.MQ_TAG_CREATE_ORDER,
                    mqMessage, message);
            log.info("seckill tx message sent, orderId={}, messageId={}, txState={}, sendStatus={}, queue={}",
                    message.getOrderId(), message.getMessageId(), txResult.getLocalTransactionState(),
                    txResult.getSendStatus(), txResult.getMessageQueue());
            return true;
        } catch (Exception e) {
            log.error("seckill order message send failed, orderId={}, messageId={}, error=",
                    message.getOrderId(), message.getMessageId(), e);
            redisTemplate.delete(flowKey);
            throw new BusinessException(ErrorCode.SECKILL_BUSY, "秒杀消息发送失败");
        }
    }
}
