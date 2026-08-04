package com.seckill.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.common.util.JsonUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 基础解析（HS256）：解析 + HMAC 验签 + 过期校验，提取 userId。
 */
public final class JwtTokenParser {

    public static final String ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    /** Phase 6.3：复用 Mac 实例与 Key 对象（仅解析器缓存，禁止缓存 claims/权限/秒杀资格） */
    private static final ThreadLocal<Mac> MAC_CACHE = ThreadLocal.withInitial(() -> {
        try {
            return Mac.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    });
    private static final Map<String, SecretKeySpec> KEY_CACHE = new ConcurrentHashMap<>();

    private JwtTokenParser() {
    }

    public record Claims(String userId, long expiresAt) {
    }

    public static Optional<Claims> parse(String token, String secret) {
        if (token == null || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = URL_ENCODER.encodeToString(hmacSha256(signingInput, secret));
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return Optional.empty();
            }

            JsonNode payload = JsonUtils.fromJson(decodeUtf8(parts[1]), JsonNode.class);
            String userId = payload.path("sub").asText(null);
            long expiresAt = payload.path("exp").asLong(0L);
            if (userId == null || expiresAt <= 0L) {
                return Optional.empty();
            }
            if (expiresAt * 1000L <= System.currentTimeMillis()) {
                return Optional.empty();
            }
            return Optional.of(new Claims(userId, expiresAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static byte[] hmacSha256(String data, String secret) throws Exception {
        Mac mac = MAC_CACHE.get();
        mac.init(KEY_CACHE.computeIfAbsent(secret,
                value -> new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), ALGORITHM)));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeUtf8(String part) {
        return new String(URL_DECODER.decode(part), StandardCharsets.UTF_8);
    }
}
