package com.seckill.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.CallbackResult;
import com.seckill.payment.dto.PaySuccessMessage;
import com.seckill.payment.entity.PaymentCallbackLog;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentCallbackLogMapper;
import com.seckill.payment.mq.PaySuccessProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class CallbackHandlerTest {

    @Mock
    private PaymentChannelRouter channelRouter;
    @Mock
    private PaymentChannel paymentChannel;
    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentCallbackLogMapper callbackLogMapper;
    @Mock
    private PaySuccessProducer paySuccessProducer;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private CallbackHandler handler;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentCallbackLog.class);
    }

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties();
        properties.getCallback().setWindowSeconds(300);
        handler = new CallbackHandler(channelRouter, paymentService, callbackLogMapper,
                paySuccessProducer, snowflakeIdGenerator, properties);
    }

    private CallbackContext context(long timestamp) {
        return new CallbackContext("MOCK", "P1", "TXN001",
                new BigDecimal("99.00"), "SUCCESS", timestamp, "sig", "{}", "trace-1");
    }

    private PaymentOrder waitPayPayment() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setPaymentNo("P1");
        payment.setOrderNo("SO123");
        payment.setUserId(10001L);
        payment.setAmount(new BigDecimal("99.00"));
        payment.setStatus("WAIT_PAY");
        payment.setVersion(1);
        return payment;
    }

    @Test
    void callbackSuccessShouldUpdateAndPublish() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(paymentService.getByPaymentNo("P1")).thenReturn(waitPayPayment());
        when(paymentService.completePaySuccess(any(), any())).thenReturn(true);
        when(paySuccessProducer.send(any(PaySuccessMessage.class))).thenReturn(true);

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000));
        assertTrue(result.accepted());

        ArgumentCaptor<PaymentCallbackLog> logCaptor = ArgumentCaptor.forClass(PaymentCallbackLog.class);
        verify(callbackLogMapper).insert(logCaptor.capture());
        assertEquals(PaymentConstants.VERIFY_OK, logCaptor.getValue().getVerifyResult());

        ArgumentCaptor<PaySuccessMessage> msgCaptor = ArgumentCaptor.forClass(PaySuccessMessage.class);
        verify(paySuccessProducer).send(msgCaptor.capture());
        assertEquals("P1", msgCaptor.getValue().getPaymentNo());
        assertEquals("SO123", msgCaptor.getValue().getOrderNo());
        assertEquals("TXN001", msgCaptor.getValue().getTransactionNo());
    }

    @Test
    void verifyFailShouldReject() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(false);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000));
        assertFalse(result.accepted());
        verify(paySuccessProducer, never()).send(any());
    }

    @Test
    void timestampOutOfWindowShouldReject() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000 - 1000));
        assertFalse(result.accepted());
        verify(paySuccessProducer, never()).send(any());
    }

    @Test
    void duplicateTransactionShouldReturnSuccess() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        doThrow(new DuplicateKeyException("dup")).when(callbackLogMapper).insert(any(PaymentCallbackLog.class));

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000));
        assertTrue(result.accepted());
        verify(paymentService, never()).getByPaymentNo(any());
    }

    @Test
    void amountMismatchShouldReject() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        PaymentOrder payment = waitPayPayment();
        payment.setAmount(new BigDecimal("88.00"));
        when(paymentService.getByPaymentNo("P1")).thenReturn(payment);

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000));
        assertFalse(result.accepted());
        verify(paySuccessProducer, never()).send(any());
    }

    @Test
    void alreadySuccessShouldNotRepublish() {
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        PaymentOrder payment = waitPayPayment();
        payment.setStatus("PAY_SUCCESS");
        when(paymentService.getByPaymentNo("P1")).thenReturn(payment);

        CallbackResult result = handler.handle(context(System.currentTimeMillis() / 1000));
        assertTrue(result.accepted());
        verify(paySuccessProducer, never()).send(any());
    }

    @Test
    void should_not_publish_message_when_duplicate_callback_received() {
        // Arrange
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.verifyCallback(any())).thenReturn(true);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        when(paymentService.getByPaymentNo("P1")).thenReturn(waitPayPayment());
        when(paymentService.completePaySuccess(any(), any())).thenReturn(true);
        when(paySuccessProducer.send(any(PaySuccessMessage.class))).thenReturn(true);
        // 第一次落库成功；第二次相同 channelTransactionNo 触发 uk_callback_transaction 防重放
        when(callbackLogMapper.insert(any(PaymentCallbackLog.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("dup"));
        CallbackContext context = context(System.currentTimeMillis() / 1000);

        // Act
        CallbackResult first = handler.handle(context);
        CallbackResult second = handler.handle(context);

        // Assert
        assertTrue(first.accepted());
        assertTrue(second.accepted());
        verify(callbackLogMapper, times(2)).insert(any(PaymentCallbackLog.class));
        verify(paymentService, times(1)).getByPaymentNo("P1");
        verify(paymentService, times(1)).completePaySuccess(any(), any());
        verify(paySuccessProducer, times(1)).send(any(PaySuccessMessage.class));
    }
}
