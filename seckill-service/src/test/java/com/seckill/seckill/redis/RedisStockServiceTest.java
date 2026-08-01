package com.seckill.seckill.redis;

import com.seckill.seckill.redis.impl.RedisStockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStockServiceTest {

    @Mock
    private DefaultRedisScript<Long> deductScript;
    @Mock
    private DefaultRedisScript<Long> recoverScript;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisStockService service;

    @BeforeEach
    void setUp() {
        service = new RedisStockService(deductScript, recoverScript, redisTemplate);
    }

    @Test
    void preDeductShouldMapReturnCodes() {
        when(redisTemplate.execute(eq(deductScript), anyList(), any(), any())).thenReturn(1L);
        assertEquals(StockDeductResult.SUCCESS, service.preDeduct("20001", "10001", 1, 3600));

        when(redisTemplate.execute(eq(deductScript), anyList(), any(), any())).thenReturn(-1L);
        assertEquals(StockDeductResult.STOCK_EMPTY, service.preDeduct("20001", "10001", 1, 3600));

        when(redisTemplate.execute(eq(deductScript), anyList(), any(), any())).thenReturn(-2L);
        assertEquals(StockDeductResult.REPEAT_BUY, service.preDeduct("20001", "10001", 1, 3600));

        when(redisTemplate.execute(eq(deductScript), anyList(), any(), any())).thenReturn(-3L);
        assertEquals(StockDeductResult.NOT_READY, service.preDeduct("20001", "10001", 1, 3600));
    }

    @Test
    void preDeductShouldTreatNullAsNotReady() {
        when(redisTemplate.execute(eq(deductScript), anyList(), any(), any())).thenReturn(null);
        assertEquals(StockDeductResult.NOT_READY, service.preDeduct("20001", "10001", 1, 3600));
    }

    @Test
    void recoverShouldMapReturnCodes() {
        when(redisTemplate.execute(eq(recoverScript), anyList(), any(), any())).thenReturn(1L);
        assertEquals(StockRecoverResult.SUCCESS, service.recover("20001", "10001", 1, true));

        when(redisTemplate.execute(eq(recoverScript), anyList(), any(), any())).thenReturn(-4L);
        assertEquals(StockRecoverResult.OVER_TOTAL, service.recover("20001", "10001", 1, true));

        when(redisTemplate.execute(eq(recoverScript), anyList(), any(), any())).thenReturn(-3L);
        assertEquals(StockRecoverResult.NOT_READY, service.recover("20001", "10001", 1, true));
    }

    @Test
    void prepareShouldWriteTotalAndStock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service.prepare("20001", 1000);
        verify(valueOperations).set("seckill:stock:total:20001", "1000");
        verify(valueOperations).set("seckill:stock:20001", "1000");
    }

    @Test
    void isUserMarkedShouldCheckKey() {
        when(redisTemplate.hasKey("seckill:user:20001:10001")).thenReturn(true);
        assertTrue(service.isUserMarked("20001", "10001"));
        when(redisTemplate.hasKey("seckill:user:20001:10001")).thenReturn(false);
        assertFalse(service.isUserMarked("20001", "10001"));
    }
}
