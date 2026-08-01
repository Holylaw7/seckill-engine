package com.seckill.seckill.mq;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.dto.SeckillOrderMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RocketMqProducerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RocketMqProducer producer;

    @BeforeEach
    void setUp() {
        SeckillProperties properties = new SeckillProperties();
        properties.setFlowKeyTtlSeconds(86400L);
        producer = new RocketMqProducer(rocketMQTemplate, redisTemplate, properties);
    }

    private SeckillOrderMessage message() {
        return new SeckillOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "123", 1000L, 1, null);
    }

    @Test
    void firstSendShouldUseTransactionMessage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("seckill:flow:123"), eq("1"), any(Duration.class))).thenReturn(true);

        assertTrue(producer.sendCreateOrder(message()));
        verify(rocketMQTemplate).sendMessageInTransaction(
                eq("seckill-order-tx:CREATE_ORDER"), any(), any());
    }

    @Test
    void duplicateSendShouldBeIdempotent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("seckill:flow:123"), eq("1"), any(Duration.class))).thenReturn(false);

        assertTrue(producer.sendCreateOrder(message()));
        verify(rocketMQTemplate, never()).sendMessageInTransaction(anyString(), any(), any());
    }

    @Test
    void sendFailureShouldClearFlowKeyAndThrowBusy() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("seckill:flow:123"), eq("1"), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).sendMessageInTransaction(anyString(), any(), any());

        BusinessException e = assertThrows(BusinessException.class,
                () -> producer.sendCreateOrder(message()));
        assertEquals(ErrorCode.SECKILL_BUSY, e.getErrorCode());
        verify(redisTemplate).delete("seckill:flow:123");
    }
}
