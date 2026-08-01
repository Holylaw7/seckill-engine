package com.seckill.payment.channel;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentChannelRouter {

    private final Map<String, PaymentChannel> channels;

    public PaymentChannelRouter(List<PaymentChannel> channelList) {
        this.channels = channelList.stream()
                .collect(Collectors.toMap(PaymentChannel::channelName, Function.identity()));
    }

    public PaymentChannel getChannel(String channelName) {
        PaymentChannel channel = channels.get(channelName);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的支付渠道: " + channelName);
        }
        return channel;
    }
}
