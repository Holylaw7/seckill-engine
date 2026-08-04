package com.seckill.seckill.controller;

import com.seckill.common.exception.GlobalExceptionHandler;
import com.seckill.seckill.config.SeckillProperties;
import com.seckill.seckill.redis.StockRecoverResult;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@org.junit.jupiter.api.Tag("unit")
class StockRecoverInternalControllerTest {

    @Mock
    private StockService stockService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SeckillProperties properties = new SeckillProperties();
        properties.setFlowKeyTtlSeconds(86400L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new StockRecoverInternalController(stockService, redisTemplate, properties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String body() {
        return "{\"requestId\":\"SF1\",\"skuId\":20001,\"sessionId\":30001,\"recoverCount\":1}";
    }

    @Test
    void recover_should_return_success() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(stockService.recoverBucket(anyString(), anyString(), any(Integer.class),
                any(Boolean.class), org.mockito.ArgumentMatchers.nullable(Integer.class)))
                .thenReturn(StockRecoverResult.SUCCESS);

        mockMvc.perform(post("/api/v1/seckill/internal/stocks/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(stockService).recoverBucket("20001", "0", 1, false, null);
    }

    @Test
    void recover_should_be_idempotent_for_same_request_id() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        mockMvc.perform(post("/api/v1/seckill/internal/stocks/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(stockService, never()).recoverBucket(anyString(), anyString(), any(Integer.class),
                any(Boolean.class), org.mockito.ArgumentMatchers.nullable(Integer.class));
    }

    @Test
    void recover_should_fail_when_redis_recover_not_ready() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(stockService.recoverBucket(anyString(), anyString(), any(Integer.class),
                any(Boolean.class), org.mockito.ArgumentMatchers.nullable(Integer.class)))
                .thenReturn(StockRecoverResult.NOT_READY);

        mockMvc.perform(post("/api/v1/seckill/internal/stocks/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(30007));
        verify(redisTemplate).delete("seckill:recover:SF1");
    }

    @Test
    void recover_should_reject_when_recover_count_missing() throws Exception {
        mockMvc.perform(post("/api/v1/seckill/internal/stocks/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"SF1\",\"skuId\":20001,\"sessionId\":30001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }
}
