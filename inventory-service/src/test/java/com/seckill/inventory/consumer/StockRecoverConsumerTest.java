package com.seckill.inventory.consumer;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
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

    @Test
    void should_propagate_when_locked_stock_insufficient() {
        // Arrange
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "CANCEL", 1000L);
        doThrow(new BusinessException(ErrorCode.INVENTORY_ERROR, "无可回补的锁定库存"))
                .when(inventoryService).recoverStock("SO123", 20001L, 1, "CANCEL");

        // Act
        BusinessException e = assertThrows(BusinessException.class,
                () -> newConsumer().onMessage(JsonUtils.toJson(message)));

        // Assert
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
        verify(recoverClient, never()).recover(any(RecoverRequest.class));
    }

    @Test
    void should_ignore_duplicate_recover_when_request_id_exists() {
        // Arrange
        StockRecoverMessage message = new StockRecoverMessage(
                "msg-002", "SO123", 10001L, 20001L, 30001L, 1, "CANCEL", 1000L);
        String payload = JsonUtils.toJson(message);
        // 冻结契约：requestId = stock_flow.flow_no，重复消息返回同一 flowNo（service 幂等，不重复改库存/不重复流水）
        when(inventoryService.recoverStock("SO123", 20001L, 1, "CANCEL")).thenReturn("SF1");
        when(recoverClient.recover(any(RecoverRequest.class))).thenReturn(true);

        // Act
        newConsumer().onMessage(payload);
        newConsumer().onMessage(payload);

        // Assert
        ArgumentCaptor<RecoverRequest> captor = ArgumentCaptor.forClass(RecoverRequest.class);
        verify(recoverClient, times(2)).recover(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(RecoverRequest::getRequestId)
                .containsOnly("SF1");
    }
}
