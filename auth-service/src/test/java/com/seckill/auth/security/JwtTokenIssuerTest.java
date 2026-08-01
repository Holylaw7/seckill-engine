package com.seckill.auth.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenIssuerTest {

    private static final String SECRET = "test-secret";

    @Test
    void issueAndParseShouldRoundTrip() {
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER", "ADMIN"), SECRET, 120);
        Optional<JwtTokenIssuer.Claims> claims = JwtTokenIssuer.parse(token, SECRET);
        assertTrue(claims.isPresent());
        assertEquals("10001", claims.get().userId());
        assertEquals("alice", claims.get().username());
        assertTrue(claims.get().roles().containsAll(List.of("USER", "ADMIN")));
        assertTrue(claims.get().expiresAt() > claims.get().issuedAt());
        assertTrue(claims.get().jti() != null && !claims.get().jti().isBlank());
    }

    @Test
    void jtiShouldBeUnique() {
        String token1 = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), SECRET, 120);
        String token2 = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), SECRET, 120);
        assertNotEquals(
                JwtTokenIssuer.parse(token1, SECRET).orElseThrow().jti(),
                JwtTokenIssuer.parse(token2, SECRET).orElseThrow().jti());
    }

    @Test
    void tamperedTokenShouldBeRejected() {
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), SECRET, 120);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertTrue(JwtTokenIssuer.parse(tampered, SECRET).isEmpty());
    }

    @Test
    void wrongSecretShouldBeRejected() {
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), "other-secret", 120);
        assertTrue(JwtTokenIssuer.parse(token, SECRET).isEmpty());
    }

    @Test
    void expiredTokenShouldBeRejected() {
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER"), SECRET, -1);
        assertTrue(JwtTokenIssuer.parse(token, SECRET).isEmpty());
    }

    @Test
    void should_include_all_required_claims_when_issue_token() {
        // Arrange
        long before = System.currentTimeMillis() / 1000;

        // Act
        String token = JwtTokenIssuer.issue("10001", "alice", List.of("USER", "ADMIN"), SECRET, 120);
        JwtTokenIssuer.Claims claims = JwtTokenIssuer.parse(token, SECRET).orElseThrow();
        long after = System.currentTimeMillis() / 1000;

        // Assert
        assertThat(claims.userId()).isEqualTo("10001");
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.issuedAt()).isBetween(before, after);
        assertThat(claims.expiresAt() - claims.issuedAt()).isEqualTo(120 * 60L);
        assertThat(claims.jti()).isNotBlank();
        assertThat(UUID.fromString(claims.jti())).isNotNull();
        assertThat(claims.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void should_reject_token_when_required_claim_missing() throws Exception {
        // Arrange
        long now = System.currentTimeMillis() / 1000;
        long exp = now + 7200;
        String jti = UUID.randomUUID().toString();
        String missingSub = "{\"username\":\"alice\",\"iat\":" + now + ",\"exp\":" + exp
                + ",\"jti\":\"" + jti + "\",\"roles\":\"USER\"}";
        String missingExp = "{\"sub\":\"10001\",\"username\":\"alice\",\"iat\":" + now
                + ",\"jti\":\"" + jti + "\",\"roles\":\"USER\"}";
        String missingUsername = "{\"sub\":\"10001\",\"iat\":" + now + ",\"exp\":" + exp
                + ",\"jti\":\"" + jti + "\",\"roles\":\"USER\"}";
        String missingJti = "{\"sub\":\"10001\",\"username\":\"alice\",\"iat\":" + now
                + ",\"exp\":" + exp + ",\"roles\":\"USER\"}";

        // Act & Assert
        assertThat(JwtTokenIssuer.parse(createToken(missingSub, SECRET), SECRET)).isEmpty();
        assertThat(JwtTokenIssuer.parse(createToken(missingExp, SECRET), SECRET)).isEmpty();
        assertThat(JwtTokenIssuer.parse(createToken(missingUsername, SECRET), SECRET)).isEmpty();
        assertThat(JwtTokenIssuer.parse(createToken(missingJti, SECRET), SECRET)).isEmpty();
    }

    private static String createToken(String payloadJson, String secret) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        Mac mac = Mac.getInstance(JwtTokenIssuer.ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), JwtTokenIssuer.ALGORITHM));
        String signature = encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }
}
