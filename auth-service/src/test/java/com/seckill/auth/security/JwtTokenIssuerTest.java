package com.seckill.auth.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
}
