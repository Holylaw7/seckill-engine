package com.seckill.gateway.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 测试用 JWT 生成工具（HS256，与 JwtTokenParser 对齐）。
 */
public final class TestTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private TestTokens() {
    }

    public static String create(String userId, long expEpochSeconds, String secret) throws Exception {
        String header = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = ENCODER.encodeToString(
                ("{\"sub\":\"" + userId + "\",\"exp\":" + expEpochSeconds + "}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance(JwtTokenParser.ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), JwtTokenParser.ALGORITHM));
        String signature = ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }
}
