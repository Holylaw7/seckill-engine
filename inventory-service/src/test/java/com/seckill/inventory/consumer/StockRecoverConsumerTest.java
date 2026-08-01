package com.seckill.inventory.consumer;

import com.seckill.common.util.JsonUtils;
import com.seckill.inventory.client.RecoverClient;
import com.seckill.inventory.dto.RecoverRequest;
import com.seckill.inventory.dto.StockRecoverMessage;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockRecoverConsumerTest {

    @Mock
    private InventoryService inventoryService;
    @Mock
    private RecoverClient recoverClient;

    private StockRecoverConsumer newConsumer() {
        return new StockRecoverConsumer(inventoryService, recoverClient);
    }

    @Test
    void onMessageShouldRecoverAndCallClient() {
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "CANCEL", 1000L);
        when(inventoryService.recoverStock("SO123", 20001L, 1, "CANCEL")).thenReturn("SF1");
        when(recoverClient.recover(any(RecoverRequest.class))).thenReturn(true);

        newConsumer().onMessage(JsonUtils.toJson(message));

        ArgumentCaptor<RecoverRequest> captor = ArgumentCaptor.forClass(RecoverRequest.class);
        verify(recoverClient).recover(captor.capture());
        assertEquals("SF1", captor.getValue().getRequestId());
        assertEquals(20001L, captor.getValue().getSkuId());
        assertEquals(30001L, captor.getValue().getSessionId());
        assertEquals(1, captor.getValue().getRecoverCount());
    }

    @Test
    void timeoutReasonShouldMapToTimeoutBizType() {
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "TIMEOUT", 1000L);
        when(inventoryService.recoverStock("SO123", 20001L, 1, "TIMEOUT")).thenReturn("SF1");
        when(recoverClient.recover(any(RecoverRequest.class))).thenReturn(true);

        newConsumer().onMessage(JsonUtils.toJson(message));
        verify(inventoryService).recoverStock("SO123", 20001L, 1, "TIMEOUT");
    }

    @Test
    void clientFailureShouldNotBreakConsumption() {
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "CANCEL", 1000L);
        when(inventoryService.recoverStock("SO123", 20001L, 1, "CANCEL")).thenReturn("SF1");
        when(recoverClient.recover(any(RecoverRequest.class))).thenReturn(false);

        newConsumer().onMessage(JsonUtils.toJson(message));
    }

    @Test
    void runtimeErrorShouldPropagateForRetry() {
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "CANCEL", 1000L);
        doThrow(new RuntimeException("db down"))
                .when(inventoryService).recoverStock("SO123", 20001L, 1, "CANCEL");
        assertThrows(RuntimeException.class,
                () -> newConsumer().onMessage(JsonUtils.toJson(message)));
    }
}
