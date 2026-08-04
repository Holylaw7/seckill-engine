package com.seckill.payment.channel;

import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.PayChannelRequest;
import com.seckill.payment.dto.RefundChannelRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class MockPaymentChannelTest {

    private static final String SECRET = "mock-channel-secret";

    private MockPaymentChannel newChannel(boolean refundFail) {
        PaymentProperties properties = new PaymentProperties();
        properties.getChannel().getMock().setSecret(SECRET);
        properties.getChannel().getMock().setRefundFail(refundFail);
        return new MockPaymentChannel(properties);
    }

    private CallbackContext context(String signature, long timestamp) {
        return new CallbackContext("MOCK", "P1", "TXN001",
                new BigDecimal("99.00"), "SUCCESS", timestamp, signature, "{}", null);
    }

    @Test
    void createPayShouldReturnParams() {
        PayChannelRequest request = new PayChannelRequest("P1", "SO123", 10001L, new BigDecimal("99.00"));
        assertEquals("mock://pay/P1", newChannel(false).createPay(request).params().get("payUrl"));
    }

    @Test
    void verifyCallbackShouldAcceptValidSignature() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String canonical = "P1|TXN001|99.00|" + timestamp;
        String signature = sign(canonical, SECRET);
        assertTrue(newChannel(false).verifyCallback(context(signature, timestamp)));
    }

    @Test
    void verifyCallbackShouldRejectTamperedSignature() throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String canonical = "P1|TXN001|99.00|" + timestamp;
        String signature = sign(canonical, SECRET);
        String tampered = signature.substring(0, signature.length() - 1) + (signature.endsWith("a") ? "b" : "a");
        assertFalse(newChannel(false).verifyCallback(context(tampered, timestamp)));
    }

    @Test
    void refundShouldRespectConfig() {
        RefundChannelRequest request = new RefundChannelRequest("R1", "P1", new BigDecimal("99.00"));
        assertTrue(newChannel(false).refund(request).success());
        assertFalse(newChannel(true).refund(request).success());
    }

    private static String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
