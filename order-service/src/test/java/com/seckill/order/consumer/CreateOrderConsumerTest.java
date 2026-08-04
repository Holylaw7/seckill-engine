package com.seckill.order.consumer;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.order.client.PreDeductConfirmClient;
import com.seckill.order.dto.CreateOrderMessage;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class CreateOrderConsumerTest {

    @Mock
    private OrderService orderService;
    @Mock
    private PreDeductConfirmClient preDeductConfirmClient;

    private CreateOrderConsumer newConsumer() {
        return new CreateOrderConsumer(orderService, preDeductConfirmClient);
    }

    private String payload() {
        CreateOrderMessage message = new CreateOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "123", 1000L, 1, 9900L, null);
        return JsonUtils.toJson(message);
    }

    @Test
    void onMessageShouldCreateOrderAndConfirm() {
        when(orderService.createOrder(any(CreateOrderMessage.class))).thenReturn(new SeckillOrder());
        when(preDeductConfirmClient.confirm("msg-001", "123")).thenReturn(true);

        newConsumer().onMessage(payload());
        verify(preDeductConfirmClient).confirm("msg-001", "123");
    }

    @Test
    void confirmFailureShouldNotBreakConsumption() {
        when(orderService.createOrder(any(CreateOrderMessage.class))).thenReturn(new SeckillOrder());
        when(preDeductConfirmClient.confirm("msg-001", "123")).thenReturn(false);

        newConsumer().onMessage(payload());
    }

    @Test
    void duplicateMessageShouldSkipConfirm() {
        when(orderService.createOrder(any(CreateOrderMessage.class))).thenReturn(null);
        newConsumer().onMessage(payload());
        verify(preDeductConfirmClient, never()).confirm(any(), any());
    }

    @Test
    void businessErrorShouldBeSwallowed() {
        doThrow(new BusinessException(ErrorCode.PARAM_ERROR, "消息缺少金额快照"))
                .when(orderService).createOrder(any());
        newConsumer().onMessage(payload());
    }

    @Test
    void runtimeErrorShouldPropagateForRetry() {
        doThrow(new RuntimeException("db down")).when(orderService).createOrder(any());
        assertThrows(RuntimeException.class, () -> newConsumer().onMessage(payload()));
    }
}
