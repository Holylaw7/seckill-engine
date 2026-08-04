package com.seckill.gateway.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6.3 JWT 解析性能：10000 次解析 p99 < 5ms，且语义不变。
 */
@Tag("unit")
class JwtPerformanceTest {

    private static final String SECRET = "seckill-engine-dev-secret-change-me";

    @Test
    void shouldParse10000TokensUnder5msP99() throws Exception {
        List<Long> latencies = new ArrayList<>();
        String token = signToken("10001", System.currentTimeMillis() / 1000 + 3600);
        for (int i = 0; i < 10_000; i++) {
            long start = System.nanoTime();
            Optional<JwtTokenParser.Claims> claims = JwtTokenParser.parse(token, SECRET);
            long costUs = (System.nanoTime() - start) / 1000;
            latencies.add(costUs);
            assertTrue(claims.isPresent());
            assertEquals("10001", claims.get().userId());
        }
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(99.0 / 100.0 * sorted.length) - 1;
        double p99Ms = sorted[Math.max(0, index)] / 1000.0;
        assertTrue(p99Ms < 5.0, "jwt parse p99 must be < 5ms, actual=" + p99Ms + "ms");
    }

    private static String signToken(String userId, long expiresAt) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\"}");
        String payload = base64Url("{\"sub\":\"" + userId + "\",\"exp\":" + expiresAt + "}");
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
