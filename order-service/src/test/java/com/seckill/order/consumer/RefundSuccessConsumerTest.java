package com.seckill.order.consumer;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.dto.RefundSuccessMessage;
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
class RefundSuccessConsumerTest {

    @Mock
    private OrderService orderService;

    private String payload() {
        return JsonUtils.toJson(new RefundSuccessMessage(
                "refund-success:R1", "R1", "P1", "SO123", 10001L,
                new BigDecimal("99.00"), "MOCK-R-R1", System.currentTimeMillis()));
    }

    @Test
    void onMessageShouldDelegateToOrderService() {
        new RefundSuccessConsumer(orderService).onMessage(payload());
        verify(orderService).processRefundSuccess(any(RefundSuccessMessage.class));
    }

    @Test
    void businessErrorShouldBeAcknowledged() {
        doThrow(new BusinessException(ErrorCode.PARAM_ERROR, "invalid refund event"))
                .when(orderService).processRefundSuccess(any());

        new RefundSuccessConsumer(orderService).onMessage(payload());
    }

    @Test
    void runtimeErrorShouldPropagateForRetry() {
        doThrow(new IllegalStateException("order not ready"))
                .when(orderService).processRefundSuccess(any());

        assertThrows(IllegalStateException.class,
                () -> new RefundSuccessConsumer(orderService).onMessage(payload()));
    }
}
