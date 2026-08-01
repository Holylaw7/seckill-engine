package com.seckill.gateway.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenParserTest {

    private static final String SECRET = "test-secret";

    @Test
    void shouldParseValidToken() throws Exception {
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        Optional<JwtTokenParser.Claims> claims = JwtTokenParser.parse(TestTokens.create("10001", exp, SECRET), SECRET);
        assertTrue(claims.isPresent());
        assertEquals("10001", claims.get().userId());
        assertEquals(exp, claims.get().expiresAt());
    }

    @Test
    void shouldRejectTamperedToken() throws Exception {
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String token = TestTokens.create("10001", exp, SECRET);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertTrue(JwtTokenParser.parse(tampered, SECRET).isEmpty());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        long exp = (System.currentTimeMillis() / 1000) - 3600;
        assertTrue(JwtTokenParser.parse(TestTokens.create("10001", exp, SECRET), SECRET).isEmpty());
    }

    @Test
    void shouldRejectMalformedToken() {
        assertTrue(JwtTokenParser.parse("not-a-jwt", SECRET).isEmpty());
        assertTrue(JwtTokenParser.parse("a.b", SECRET).isEmpty());
    }

    @Test
    void shouldRejectWrongSecret() throws Exception {
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        assertTrue(JwtTokenParser.parse(TestTokens.create("10001", exp, "other-secret"), SECRET).isEmpty());
    }
}
