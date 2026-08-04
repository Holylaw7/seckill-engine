package com.seckill.payment.mq;

import com.seckill.payment.dto.PaySuccessMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PaySuccessProducerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private PaySuccessProducer newProducer() {
        return new PaySuccessProducer(rocketMQTemplate);
    }

    private PaySuccessMessage message() {
        return new PaySuccessMessage("msg-1", "P1", "SO123", 10001L,
                new BigDecimal("99.00"), "TXN001", 1000L);
    }

    @Test
    void sendSuccessShouldReturnTrue() {
        assertTrue(newProducer().send(message()));
        verify(rocketMQTemplate).syncSend(
                eq("seckill-order-tx:PAY_SUCCESS"), any(Message.class), eq(3000L));
    }

    @Test
    void sendFailureShouldReturnFalse() {
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSend(any(String.class), any(Message.class), any(Long.class));
        assertFalse(newProducer().send(message()));
    }
}
