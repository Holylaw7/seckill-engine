package com.seckill.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.dto.RefundChannelRequest;
import com.seckill.payment.dto.RefundChannelResult;
import com.seckill.payment.dto.RefundRequest;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.entity.PaymentRefund;
import com.seckill.payment.mapper.PaymentRefundMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class RefundServiceTest {

    @Mock
    private PaymentRefundMapper refundMapper;
    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentChannelRouter channelRouter;
    @Mock
    private PaymentChannel paymentChannel;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private RefundService refundService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentRefund.class);
    }

    @BeforeEach
    void setUp() {
        refundService = new RefundService(
                refundMapper, paymentService, channelRouter, snowflakeIdGenerator);
    }

    private RefundRequest request() {
        RefundRequest request = new RefundRequest();
        request.setRefundNo("R1");
        request.setPaymentNo("P1");
        request.setOrderNo("SO123");
        request.setUserId(10001L);
        request.setAmount(new BigDecimal("99.00"));
        return request;
    }

    private PaymentOrder paySuccessPayment() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setPaymentNo("P1");
        payment.setOrderNo("SO123");
        payment.setUserId(10001L);
        payment.setAmount(new BigDecimal("99.00"));
        payment.setChannel("MOCK");
        payment.setStatus("PAY_SUCCESS");
        payment.setVersion(1);
        return payment;
    }

    @Test
    void refundSuccessShouldUpdateBoth() {
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(paymentService.getByPaymentNo("P1")).thenReturn(paySuccessPayment());
        when(paymentService.startRefund(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.refund(any(RefundChannelRequest.class)))
                .thenReturn(new RefundChannelResult(true, "MOCK-R-R1"));
        when(paymentService.completeRefundSuccess(any())).thenReturn(true);

        var result = refundService.refund(request());
        assertEquals("REFUND_SUCCESS", result.getStatus());
        assertEquals("MOCK-R-R1", result.getChannelRefundNo());
        verify(paymentService).completeRefundSuccess(any());
    }

    @Test
    void refundDuplicateShouldReturnExisting() {
        PaymentRefund existing = new PaymentRefund();
        existing.setRefundNo("R1");
        existing.setStatus("REFUNDING");
        when(refundMapper.selectOne(any())).thenReturn(existing);

        var result = refundService.refund(request());
        assertEquals("REFUNDING", result.getStatus());
        verify(refundMapper, never()).insert(any(PaymentRefund.class));
    }

    @Test
    void refundPaymentNotFoundShouldThrow() {
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(paymentService.getByPaymentNo("P1")).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> refundService.refund(request()));
        assertEquals(ErrorCode.PAYMENT_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void refundNotPaySuccessShouldThrow() {
        when(refundMapper.selectOne(any())).thenReturn(null);
        PaymentOrder payment = paySuccessPayment();
        payment.setStatus("PAY_FAILED");
        when(paymentService.getByPaymentNo("P1")).thenReturn(payment);

        BusinessException e = assertThrows(BusinessException.class,
                () -> refundService.refund(request()));
        assertEquals(ErrorCode.ORDER_STATUS_INVALID, e.getErrorCode());
    }

    @Test
    void channelRefundFailShouldMarkFailedForCompensation() {
        when(refundMapper.selectOne(any())).thenReturn(null);
        when(paymentService.getByPaymentNo("P1")).thenReturn(paySuccessPayment());
        when(paymentService.startRefund(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.refund(any(RefundChannelRequest.class)))
                .thenReturn(new RefundChannelResult(false, null));

        var result = refundService.refund(request());
        assertEquals("REFUND_FAILED", result.getStatus());
        verify(paymentService, never()).completeRefundSuccess(any());
    }

    @Test
    void retryRefundShouldRecoverFailed() {
        PaymentRefund refund = new PaymentRefund();
        refund.setRefundNo("R1");
        refund.setPaymentNo("P1");
        refund.setStatus("REFUND_FAILED");
        when(refundMapper.selectOne(any())).thenReturn(refund);
        when(paymentService.getByPaymentNo("P1")).thenReturn(paySuccessPayment());
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.refund(any(RefundChannelRequest.class)))
                .thenReturn(new RefundChannelResult(true, "MOCK-R-R1"));

        var result = refundService.retryRefund("R1");
        assertEquals("REFUND_SUCCESS", result.getStatus());
    }
}
