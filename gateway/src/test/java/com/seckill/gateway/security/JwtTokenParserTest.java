package com.seckill.gateway.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
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

    @Test
    void should_reject_when_sub_missing() throws Exception {
        // Arrange
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String token = TestTokens.createWithPayload(
                "{\"exp\":" + exp + ",\"roles\":[\"USER\"]}", SECRET);

        // Act
        Optional<JwtTokenParser.Claims> claims = JwtTokenParser.parse(token, SECRET);

        // Assert
        assertThat(claims).isEmpty();
    }

    @Test
    void should_reject_when_exp_missing() throws Exception {
        // Arrange
        String token = TestTokens.createWithPayload(
                "{\"sub\":\"10001\",\"roles\":[\"USER\"]}", SECRET);

        // Act
        Optional<JwtTokenParser.Claims> claims = JwtTokenParser.parse(token, SECRET);

        // Assert
        assertThat(claims).isEmpty();
    }

    @Test
    void should_authenticate_when_roles_missing() throws Exception {
        // Arrange
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        // 冻结契约：roles 非网关认证必需字段，管理接口权限由 auth-service 校验
        String token = TestTokens.createWithPayload(
                "{\"sub\":\"10001\",\"exp\":" + exp + "}", SECRET);

        // Act
        Optional<JwtTokenParser.Claims> claims = JwtTokenParser.parse(token, SECRET);

        // Assert
        assertThat(claims).isPresent();
        assertThat(claims.get().userId()).isEqualTo("10001");
    }
}
