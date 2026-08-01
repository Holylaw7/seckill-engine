package com.seckill.payment.channel;

import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.PayChannelRequest;
import com.seckill.payment.dto.PayChannelResult;
import com.seckill.payment.dto.RefundChannelRequest;
import com.seckill.payment.dto.RefundChannelResult;

/**
 * 支付渠道抽象（冻结）：createPay / verifyCallback / query / refund。
 */
public interface PaymentChannel {

    String channelName();

    PayChannelResult createPay(PayChannelRequest request);

    boolean verifyCallback(CallbackContext context);

    String query(PayChannelRequest request);

    RefundChannelResult refund(RefundChannelRequest request);
}
