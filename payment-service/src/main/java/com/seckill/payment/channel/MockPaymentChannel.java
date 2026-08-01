package com.seckill.payment.channel;

import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.PayChannelRequest;
import com.seckill.payment.dto.PayChannelResult;
import com.seckill.payment.dto.RefundChannelRequest;
import com.seckill.payment.dto.RefundChannelResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Mock 渠道：HMAC-SHA256 验签，可配置退款失败。
 */
@Component
@RequiredArgsConstructor
public class MockPaymentChannel implements PaymentChannel {

    private static final String ALGORITHM = "HmacSHA256";

    private final PaymentProperties properties;

    @Override
    public String channelName() {
        return PaymentConstants.CHANNEL_MOCK;
    }

    @Override
    public PayChannelResult createPay(PayChannelRequest request) {
        return new PayChannelResult(Map.of(
                "payUrl", "mock://pay/" + request.paymentNo(),
                "channel", PaymentConstants.CHANNEL_MOCK));
    }

    @Override
    public boolean verifyCallback(CallbackContext context) {
        if (context.signature() == null || context.signature().isBlank()) {
            return false;
        }
        String canonical = canonicalString(context);
        String expected = sign(canonical, properties.getChannel().getMock().getSecret());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                context.signature().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String query(PayChannelRequest request) {
        return PaymentConstants.CALLBACK_STATUS_SUCCESS;
    }

    @Override
    public RefundChannelResult refund(RefundChannelRequest request) {
        if (properties.getChannel().getMock().isRefundFail()) {
            return new RefundChannelResult(false, null);
        }
        return new RefundChannelResult(true, "MOCK-R-" + request.refundNo());
    }

    private static String canonicalString(CallbackContext context) {
        return context.paymentNo() + "|" + context.channelTransactionNo()
                + "|" + context.amount() + "|" + context.timestamp();
    }

    private static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("mock sign failed", e);
        }
    }
}
