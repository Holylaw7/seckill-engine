package com.seckill.payment.channel;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PaymentChannelRouterTest {

    @Mock
    private PaymentChannel paymentChannel;

    @Test
    void getChannel_should_return_registered_channel() {
        when(paymentChannel.channelName()).thenReturn("MOCK");
        PaymentChannelRouter router = new PaymentChannelRouter(List.of(paymentChannel));
        assertSame(paymentChannel, router.getChannel("MOCK"));
    }

    @Test
    void getChannel_should_reject_unknown_channel() {
        when(paymentChannel.channelName()).thenReturn("MOCK");
        PaymentChannelRouter router = new PaymentChannelRouter(List.of(paymentChannel));
        BusinessException e = assertThrows(BusinessException.class,
                () -> router.getChannel("WECHAT"));
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
    }
}
