package com.seckill.seckill.mq;

import com.seckill.common.util.JsonUtils;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.redis.StockService;
import com.seckill.seckill.service.PreDeductService;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class TransactionListenerImplTest {

    @Mock
    private PreDeductService preDeductService;
    @Mock
    private StockService stockService;

    private TransactionListenerImpl listener;

    @BeforeEach
    void setUp() {
        listener = new TransactionListenerImpl(preDeductService, stockService);
    }

    private Message<String> message() {
        SeckillOrderMessage payload = new SeckillOrderMessage(
                "msg-001", 10001L, 20001L, 30001L, "123", 1000L, 1, null, 9900L);
        return MessageBuilder.withPayload(JsonUtils.toJson(payload)).build();
    }

    @Test
    void localTransactionSuccessShouldCommit() {
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message(), null);
        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        verify(preDeductService).createInitial(any(SeckillOrderMessage.class));
        verify(preDeductService).markTxSuccess("msg-001");
        verify(stockService, never()).recover(anyString(), anyString(), any(Integer.class), any(Boolean.class));
    }

    @Test
    void localTransactionFailureShouldRollbackAndRecover() {
        doThrow(new RuntimeException("db down")).when(preDeductService).createInitial(any());
        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message(), null);
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state);
        verify(preDeductService).markTxFail("msg-001");
        verify(preDeductService).markRecovered("msg-001");
        verify(stockService).recover(eq("20001"), eq("10001"), eq(1), eq(true));
    }

    @Test
    void duplicateWithSuccessStatusShouldCommit() {
        doThrow(new DuplicateKeyException("dup")).when(preDeductService).createInitial(any());
        when(preDeductService.findTxStatus("msg-001")).thenReturn("SUCCESS");
        assertEquals(RocketMQLocalTransactionState.COMMIT,
                listener.executeLocalTransaction(message(), null));
    }

    @Test
    void duplicateWithFailStatusShouldRollback() {
        doThrow(new DuplicateKeyException("dup")).when(preDeductService).createInitial(any());
        when(preDeductService.findTxStatus("msg-001")).thenReturn("FAIL");
        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(message(), null));
    }

    @Test
    void checkShouldMapThreeStates() {
        when(preDeductService.findTxStatus("msg-001")).thenReturn("SUCCESS");
        assertEquals(RocketMQLocalTransactionState.COMMIT, listener.checkLocalTransaction(message()));

        when(preDeductService.findTxStatus("msg-001")).thenReturn("FAIL");
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.checkLocalTransaction(message()));

        when(preDeductService.findTxStatus("msg-001")).thenReturn("INIT");
        assertEquals(RocketMQLocalTransactionState.UNKNOWN, listener.checkLocalTransaction(message()));

        when(preDeductService.findTxStatus("msg-001")).thenReturn(null);
        assertEquals(RocketMQLocalTransactionState.UNKNOWN, listener.checkLocalTransaction(message()));
    }
}
