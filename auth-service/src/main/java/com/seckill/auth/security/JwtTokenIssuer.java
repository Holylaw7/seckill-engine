package com.seckill.auth.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.common.util.JsonUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 签发与解析（HS256）。
 *
 * <p>Payload 冻结：sub / username / iat / exp / jti / roles。</p>
 */
public final class JwtTokenIssuer {

    public static final String ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private JwtTokenIssuer() {
    }

    public record Claims(String userId, String username, long issuedAt, long expiresAt, String jti, List<String> roles) {
    }

    public static String issue(String userId, String username, List<String> roles, String secret, int expireMinutes) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("username", username);
        payload.put("iat", nowSeconds);
        payload.put("exp", nowSeconds + (long) expireMinutes * 60);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("roles", String.join(",", roles));
        try {
            String header = URL_ENCODER.encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = URL_ENCODER.encodeToString(
                    JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + body;
            String signature = URL_ENCODER.encodeToString(hmacSha256(signingInput, secret));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("jwt issue failed", e);
        }
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
            String expected = URL_ENCODER.encodeToString(hmacSha256(signingInput, secret));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }

            JsonNode payload = JsonUtils.fromJson(
                    new String(URL_DECODER.decode(parts[1]), StandardCharsets.UTF_8), JsonNode.class);
            String userId = payload.path("sub").asText(null);
            String username = payload.path("username").asText(null);
            long issuedAt = payload.path("iat").asLong(0L);
            long expiresAt = payload.path("exp").asLong(0L);
            String jti = payload.path("jti").asText(null);
            String rolesText = payload.path("roles").asText("");
            if (userId == null || username == null || expiresAt <= 0L || jti == null) {
                return Optional.empty();
            }
            if (expiresAt * 1000L <= System.currentTimeMillis()) {
                return Optional.empty();
            }
            List<String> roles = new ArrayList<>();
            if (!rolesText.isBlank()) {
                for (String role : rolesText.split(",")) {
                    String trimmed = role.trim();
                    if (!trimmed.isEmpty()) {
                        roles.add(trimmed);
                    }
                }
            }
            return Optional.of(new Claims(userId, username, issuedAt, expiresAt, jti, roles));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static byte[] hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
