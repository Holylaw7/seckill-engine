package com.seckill.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Phase 6.6 内部接口 Service ACL 签名工具（HMAC-SHA256）。
 * payload 约定：serviceName + ":" + timestamp + ":" + nonce（毫秒时间戳）。
 */
public final class InternalSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalSignature() {
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("internal signature failed", e);
        }
    }

    public static String payload(String serviceName, String timestamp, String nonce) {
        return serviceName + ":" + timestamp + ":" + nonce;
    }

    public static String sign(String secret, String serviceName, String timestamp, String nonce) {
        return sign(secret, payload(serviceName, timestamp, nonce));
    }

    public static boolean verify(String secret, String data, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(secret, data).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public static boolean verify(String secret, String serviceName, String timestamp,
                                 String nonce, String signature) {
        return verify(secret, payload(serviceName, timestamp, nonce), signature);
    }
}
