package com.seckill.order.mq;

import com.seckill.order.dto.CancelOrderMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class CancelOrderProducerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private CancelOrderProducer newProducer() {
        return new CancelOrderProducer(rocketMQTemplate);
    }

    private CancelOrderMessage message() {
        return new CancelOrderMessage(
                "msg-cancel", "123", 10001L, 20001L, 30001L, 1, "TIMEOUT", 1000L);
    }

    @Test
    void sendSuccessShouldReturnTrue() {
        assertTrue(newProducer().send(message()));
        verify(rocketMQTemplate).syncSend(
                eq("seckill-order-tx:CANCEL_ORDER"), any(Message.class), eq(3000L));
    }

    @Test
    void sendFailureShouldReturnFalse() {
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSend(any(String.class), any(Message.class), any(Long.class));
        assertFalse(newProducer().send(message()));
    }
}
