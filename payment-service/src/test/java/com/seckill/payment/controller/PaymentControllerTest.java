package com.seckill.payment.controller;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.exception.GlobalExceptionHandler;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.payment.dto.PaymentQueryResponse;
import com.seckill.payment.dto.RefundRequest;
import com.seckill.payment.dto.RefundResult;
import com.seckill.payment.service.CallbackHandler;
import com.seckill.payment.service.PaymentService;
import com.seckill.payment.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private CallbackHandler callbackHandler;
    @Mock
    private RefundService refundService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PaymentController(paymentService, callbackHandler, refundService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_should_return_payment_no() throws Exception {
        when(paymentService.createPayment(any(CreatePayRequest.class)))
                .thenReturn(new CreatePayResponse("P1", Map.of("payUrl", "mock://pay/P1")));

        mockMvc.perform(post("/api/v1/payments/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"SO123\",\"userId\":10001,\"amount\":99.00,\"channel\":\"MOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.paymentNo").value("P1"));
    }

    @Test
    void query_should_return_status() throws Exception {
        when(paymentService.queryPayment(10001L, "P1"))
                .thenReturn(new PaymentQueryResponse("P1", "SO123", new BigDecimal("99.00"), "WAIT_PAY", null));

        mockMvc.perform(get("/api/v1/payments/P1").header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"));
    }

    @Test
    void query_should_return_not_found_when_missing() throws Exception {
        when(paymentService.queryPayment(10001L, "P1"))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/payments/P1").header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001));
    }

    @Test
    void query_should_reject_when_user_header_missing() throws Exception {
        mockMvc.perform(get("/api/v1/payments/P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(20001));
    }

    @Test
    void refund_should_return_refund_result() throws Exception {
        when(refundService.refund(any(RefundRequest.class)))
                .thenReturn(new RefundResult("R1", "REFUND_SUCCESS", "MOCK-R1"));

        mockMvc.perform(post("/api/v1/payments/P1/refund")
                        .header("X-User-Id", "10001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundNo\":\"R1\",\"paymentNo\":\"P1\",\"orderNo\":\"SO123\","
                                + "\"userId\":10001,\"amount\":99.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REFUND_SUCCESS"));
    }

    @Test
    void refund_should_reject_when_payment_no_mismatch() throws Exception {
        mockMvc.perform(post("/api/v1/payments/P1/refund")
                        .header("X-User-Id", "10001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundNo\":\"R1\",\"paymentNo\":\"P2\",\"orderNo\":\"SO123\","
                                + "\"userId\":10001,\"amount\":99.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }
}
