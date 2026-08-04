package com.seckill.seckill.controller;

import com.seckill.common.exception.GlobalExceptionHandler;
import com.seckill.seckill.service.PreDeductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PreDeductInternalControllerTest {

    @Mock
    private PreDeductService preDeductService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PreDeductInternalController(preDeductService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void confirm_should_return_success() throws Exception {
        when(preDeductService.confirm(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/v1/seckill/internal/pre-deducts/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"m1\",\"orderId\":\"SO1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(preDeductService).confirm("m1", "SO1");
    }

    @Test
    void confirm_should_reject_when_message_id_missing() throws Exception {
        mockMvc.perform(post("/api/v1/seckill/internal/pre-deducts/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"SO1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }
}
