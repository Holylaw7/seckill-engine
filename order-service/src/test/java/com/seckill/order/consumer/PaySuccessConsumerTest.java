package com.seckill.order.consumer;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.dto.PaySuccessMessage;
import com.seckill.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PaySuccessConsumerTest {

    @Mock
    private OrderService orderService;

    private PaySuccessConsumer newConsumer() {
        return new PaySuccessConsumer(orderService);
    }

    private String payload() {
        return JsonUtils.toJson(new PaySuccessMessage(
                "pay-msg-001", "P-001", "O-001", 10001L,
                new BigDecimal("99.00"), "TXN-001", System.currentTimeMillis()));
    }

    @Test
    void onMessageShouldDelegateToOrderService() {
        newConsumer().onMessage(payload());
        verify(orderService).processPaySuccess(any(PaySuccessMessage.class));
    }

    @Test
    void businessErrorShouldBeAcknowledged() {
        doThrow(new BusinessException(ErrorCode.PARAM_ERROR, "invalid pay event"))
                .when(orderService).processPaySuccess(any());

        newConsumer().onMessage(payload());
    }

    @Test
    void runtimeErrorShouldPropagateForRetry() {
        doThrow(new IllegalStateException("db down"))
                .when(orderService).processPaySuccess(any());

        assertThrows(IllegalStateException.class, () -> newConsumer().onMessage(payload()));
    }
}
