package com.seckill.seckill.service;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.config.SeckillShardingProperties;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.entity.SeckillSku;
import com.seckill.seckill.mapper.SeckillSkuMapper;
import com.seckill.seckill.mq.RocketMqProducer;
import com.seckill.seckill.redis.StockDeductResult;
import com.seckill.seckill.redis.StockService;
import com.seckill.seckill.risk.RiskCheckClient;
import com.seckill.seckill.service.impl.SeckillServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class SeckillServiceImplTest {

    @Mock
    private SessionCacheService sessionCacheService;
    @Mock
    private StockService stockService;
    @Mock
    private SeckillSkuMapper skuMapper;
    @Mock
    private RocketMqProducer mqProducer;
    @Mock
    private RiskCheckClient riskCheckClient;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private SeckillProperties properties;
    private SeckillServiceImpl seckillService;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.setWorkerId(2L);
        properties.setFlowKeyTtlSeconds(86400L);
        seckillService = new SeckillServiceImpl(
                sessionCacheService, stockService, skuMapper, mqProducer, riskCheckClient,
                snowflakeIdGenerator, properties, new SeckillShardingProperties());
    }

    private void mockSku() {
        SeckillSku sku = new SeckillSku();
        sku.setId(40001L);
        sku.setSessionId(30001L);
        sku.setSkuId(20001L);
        sku.setPrice(new BigDecimal("99.00"));
        when(skuMapper.selectOne(any())).thenReturn(sku);
    }

    private SessionCacheService.SessionCache readySession(long startOffset, long endOffset) {
        long now = System.currentTimeMillis();
        return new SessionCacheService.SessionCache(
                30001L, 1L, "test-session", now + startOffset, now + endOffset, "READY", 1);
    }

    private ExecuteRequest request() {
        ExecuteRequest request = new ExecuteRequest();
        request.setSessionId(30001L);
        request.setSkuId(20001L);
        request.setQuantity(1);
        return request;
    }

    @Test
    void executeShouldSucceedAndSendMessage() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        mockSku();
        when(stockService.preDeduct(eq("20001"), eq("10001"), eq(1), any(Long.class)))
                .thenReturn(StockDeductResult.SUCCESS);
        when(snowflakeIdGenerator.nextId()).thenReturn(123L);
        when(mqProducer.sendCreateOrder(any(SeckillOrderMessage.class))).thenReturn(true);

        ExecuteResponse response = seckillService.execute(10001L, "127.0.0.1", request());

        assertEquals("SUCCESS", response.getResult());
        assertEquals("123", response.getOrderId());
        assertTrue(response.getPayDeadline() > System.currentTimeMillis());

        ArgumentCaptor<SeckillOrderMessage> captor = ArgumentCaptor.forClass(SeckillOrderMessage.class);
        verify(mqProducer).sendCreateOrder(captor.capture());
        SeckillOrderMessage message = captor.getValue();
        assertEquals(32, message.getMessageId().length());
        assertEquals("123", message.getOrderId());
        assertEquals(10001L, message.getUserId());
        assertEquals(20001L, message.getSkuId());
        assertEquals(30001L, message.getSessionId());
        assertEquals(1, message.getQuantity());
        assertEquals(9900L, message.getAmount());
    }

    @Test
    void notStartedShouldFailFast() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(60000L, 120000L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30001, e.getErrorCode().getCode());
        verify(stockService, never()).preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class));
    }

    @Test
    void endedShouldFailFast() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-120000L, -60000L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30002, e.getErrorCode().getCode());
    }

    @Test
    void notReadyShouldFailFast() {
        long now = System.currentTimeMillis();
        SessionCacheService.SessionCache session = new SessionCacheService.SessionCache(
                30001L, 1L, "test", now - 1000, now + 60000, "INIT", 1);
        when(sessionCacheService.getSession(30001L)).thenReturn(session);
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30003, e.getErrorCode().getCode());
    }

    @Test
    void stockEmptyShouldMapTo30004() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        mockSku();
        when(stockService.preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(StockDeductResult.STOCK_EMPTY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30004, e.getErrorCode().getCode());
        verify(mqProducer, never()).sendCreateOrder(any());
    }

    @Test
    void repeatBuyShouldMapTo30005() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        mockSku();
        when(stockService.preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(StockDeductResult.REPEAT_BUY);
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30005, e.getErrorCode().getCode());
        verify(mqProducer, never()).sendCreateOrder(any());
        verify(snowflakeIdGenerator, never()).nextId();
    }

    @Test
    void should_fail_fast_when_stock_not_ready() {
        // Arrange
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        mockSku();
        when(stockService.preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(StockDeductResult.NOT_READY);

        // Act
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));

        // Assert
        assertEquals(30003, e.getErrorCode().getCode());
        verify(mqProducer, never()).sendCreateOrder(any());
        verify(snowflakeIdGenerator, never()).nextId();
    }

    @Test
    void riskRejectShouldPropagate() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.RISK_REJECTED, "风控拦截"))
                .when(riskCheckClient).check(any());
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(20003, e.getErrorCode().getCode());
        verify(stockService, never()).preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class));
    }

    @Test
    void sendFailureShouldRecoverStockAndReturnBusy() {
        when(sessionCacheService.getSession(30001L)).thenReturn(readySession(-1000L, 60000L));
        mockSku();
        when(stockService.preDeduct(anyString(), anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(StockDeductResult.SUCCESS);
        when(snowflakeIdGenerator.nextId()).thenReturn(123L);
        when(mqProducer.sendCreateOrder(any(SeckillOrderMessage.class)))
                .thenThrow(new BusinessException(ErrorCode.SECKILL_BUSY, "秒杀消息发送失败"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request()));
        assertEquals(30006, e.getErrorCode().getCode());
        verify(stockService).recoverBucket(eq("20001"), eq("10001"), eq(1), eq(true), isNull());
    }

    @Test
    void invalidQuantityShouldReject() {
        ExecuteRequest request = request();
        request.setQuantity(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> seckillService.execute(10001L, "127.0.0.1", request));
        assertEquals(10001, e.getErrorCode().getCode());
    }
}
