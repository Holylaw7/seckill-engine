package com.seckill.seckill.controller;

import com.seckill.common.exception.GlobalExceptionHandler;
import com.seckill.common.result.PageResult;
import com.seckill.seckill.dto.ExecuteRequest;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.dto.ResultQueryResponse;
import com.seckill.seckill.entity.SeckillSession;
import com.seckill.seckill.mapper.SeckillSessionMapper;
import com.seckill.seckill.service.ResultQueryService;
import com.seckill.seckill.service.SeckillService;
import com.seckill.seckill.service.SessionCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class SeckillControllerTest {

    @Mock
    private SeckillService seckillService;
    @Mock
    private ResultQueryService resultQueryService;
    @Mock
    private SessionCacheService sessionCacheService;
    @Mock
    private SeckillSessionMapper sessionMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SeckillController controller = new SeckillController(
                seckillService, resultQueryService, sessionCacheService, sessionMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void execute_should_return_order_id_when_success() throws Exception {
        when(seckillService.execute(anyLong(), any(String.class), any(ExecuteRequest.class)))
                .thenReturn(new ExecuteResponse("SUCCESS", "123", 0L));

        mockMvc.perform(post("/api/v1/seckill/execute")
                        .header("X-User-Id", "10001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":30001,\"skuId\":20001,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderId").value("123"));
    }

    @Test
    void execute_should_reject_when_user_header_missing() throws Exception {
        mockMvc.perform(post("/api/v1/seckill/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":30001,\"skuId\":20001,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20001));
    }

    @Test
    void result_should_return_query_response() throws Exception {
        when(resultQueryService.query(anyLong(), anyLong(), anyLong()))
                .thenReturn(new ResultQueryResponse("SUCCESS", "123", 0L));

        mockMvc.perform(get("/api/v1/seckill/result")
                        .param("sessionId", "30001")
                        .param("skuId", "20001")
                        .header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void session_should_return_cache_when_exists() throws Exception {
        when(sessionCacheService.getSession(30001L)).thenReturn(new SessionCacheService.SessionCache(
                30001L, 1L, "test", 1000L, 2000L, "READY", 1));

        mockMvc.perform(get("/api/v1/seckill/session/30001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void session_should_return_not_found_when_missing() throws Exception {
        when(sessionCacheService.getSession(30001L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/seckill/session/30001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void sessions_should_return_page() throws Exception {
        SeckillSession session = new SeckillSession();
        session.setId(30001L);
        session.setStatus("READY");
        PageResult<SeckillSession> page = PageResult.of(List.of(session), 1L, 1, 10);
        when(sessionMapper.selectPage(any(), any())).thenReturn(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<SeckillSession>()
                        .setRecords(List.of(session)).setTotal(1));

        mockMvc.perform(get("/api/v1/seckill/sessions")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }
}
