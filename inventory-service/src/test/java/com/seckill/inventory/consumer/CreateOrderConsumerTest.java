package com.seckill.inventory.consumer;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.util.JsonUtils;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class CreateOrderConsumerTest {

    @Mock
    private InventoryService inventoryService;

    private CreateOrderConsumer newConsumer() {
        return new CreateOrderConsumer(inventoryService);
    }

    @Test
    void onMessageShouldConfirmDeduct() {
        CreateOrderMessage message = new CreateOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "SO123", 1000L, 1, null);
        newConsumer().onMessage(JsonUtils.toJson(message));

        ArgumentCaptor<CreateOrderMessage> captor = ArgumentCaptor.forClass(CreateOrderMessage.class);
        verify(inventoryService).confirmDeduct(captor.capture());
        assertEquals("SO123", captor.getValue().getOrderId());
    }

    @Test
    void businessErrorShouldBeSwallowed() {
        doThrow(new BusinessException(ErrorCode.INVENTORY_ERROR, "事实库存不足"))
                .when(inventoryService).confirmDeduct(any());
        CreateOrderMessage message = new CreateOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "SO123", 1000L, 1, null);
        newConsumer().onMessage(JsonUtils.toJson(message));
    }

    @Test
    void runtimeErrorShouldPropagateForRetry() {
        doThrow(new RuntimeException("db down")).when(inventoryService).confirmDeduct(any());
        CreateOrderMessage message = new CreateOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "SO123", 1000L, 1, null);
        assertThrows(RuntimeException.class,
                () -> newConsumer().onMessage(JsonUtils.toJson(message)));
    }
}
