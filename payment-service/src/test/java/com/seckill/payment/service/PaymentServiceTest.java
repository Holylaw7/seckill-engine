package com.seckill.payment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.payment.dto.PayChannelRequest;
import com.seckill.payment.dto.PayChannelResult;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentOrderMapper;
import com.seckill.payment.state.PaymentStateMachine;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PaymentServiceTest {

    @Mock
    private PaymentOrderMapper paymentOrderMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private PaymentChannelRouter channelRouter;
    @Mock
    private PaymentChannel paymentChannel;

    private PaymentService paymentService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentOrder.class);
    }

    @BeforeEach
    void setUp() {
        PaymentStateMachine stateMachine = new PaymentStateMachine(paymentOrderMapper);
        paymentService = new PaymentService(
                paymentOrderMapper, snowflakeIdGenerator, stateMachine, channelRouter);
    }

    private CreatePayRequest request() {
        CreatePayRequest request = new CreatePayRequest();
        request.setOrderNo("SO123");
        request.setUserId(10001L);
        request.setAmount(new BigDecimal("99.00"));
        request.setChannel("MOCK");
        return request;
    }

    @Test
    void createPaymentShouldInsertAndTransition() {
        when(paymentOrderMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L, 2L);
        when(paymentOrderMapper.update(isNull(), any())).thenReturn(1);
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.createPay(any(PayChannelRequest.class)))
                .thenReturn(new PayChannelResult(Map.of("payUrl", "mock://pay/P2")));

        CreatePayResponse response = paymentService.createPayment(request());
        assertEquals("P2", response.getPaymentNo());
        assertEquals("mock://pay/P2", response.getChannelParams().get("payUrl"));

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderMapper).insert(captor.capture());
        assertEquals("CREATE", captor.getValue().getStatus());
        assertEquals("SO123", captor.getValue().getActiveOrderKey());
        assertEquals(new BigDecimal("99.00"), captor.getValue().getAmount());
    }

    @Test
    void createPaymentDuplicateActiveKeyShouldReturnExisting() {
        PaymentOrder existing = new PaymentOrder();
        existing.setPaymentNo("P1");
        existing.setOrderNo("SO123");
        existing.setChannel("MOCK");
        existing.setAmount(new BigDecimal("99.00"));
        existing.setUserId(10001L);
        when(paymentOrderMapper.selectOne(any())).thenReturn(existing);
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.createPay(any(PayChannelRequest.class)))
                .thenReturn(new PayChannelResult(Map.of("payUrl", "mock://pay/P1")));

        CreatePayResponse response = paymentService.createPayment(request());
        assertEquals("P1", response.getPaymentNo());
        verify(paymentOrderMapper, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void createPaymentInsertConflictShouldFallback() {
        PaymentOrder existing = new PaymentOrder();
        existing.setPaymentNo("P9");
        existing.setOrderNo("SO123");
        existing.setChannel("MOCK");
        existing.setAmount(new BigDecimal("99.00"));
        existing.setUserId(10001L);
        when(paymentOrderMapper.selectOne(any())).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("dup")).when(paymentOrderMapper).insert(any(PaymentOrder.class));
        when(channelRouter.getChannel("MOCK")).thenReturn(paymentChannel);
        when(paymentChannel.createPay(any(PayChannelRequest.class)))
                .thenReturn(new PayChannelResult(Map.of("payUrl", "mock://pay/P9")));

        assertEquals("P9", paymentService.createPayment(request()).getPaymentNo());
    }

    @Test
    void queryPaymentWrongOwnerShouldThrow() {
        PaymentOrder payment = new PaymentOrder();
        payment.setPaymentNo("P1");
        payment.setUserId(10001L);
        when(paymentOrderMapper.selectOne(any())).thenReturn(payment);
        BusinessException e = assertThrows(BusinessException.class,
                () -> paymentService.queryPayment(99999L, "P1"));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
    }
}
