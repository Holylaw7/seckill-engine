package com.seckill.gateway.filter;

import com.seckill.gateway.config.JwtProperties;
import com.seckill.gateway.constant.GatewayConstants;
import com.seckill.gateway.security.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class JwtAuthGlobalFilterTest {

    private static final String SECRET = "test-secret";

    private JwtAuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        filter = new JwtAuthGlobalFilter(properties);
    }

    @Test
    void shouldPassWhitelistWithoutToken() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login"));
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        assertTrue(passed.get());
    }

    @Test
    void shouldRejectProtectedPathWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/session/1"));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldPassAndPropagateUserIdWithValidToken() throws Exception {
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String token = TestTokens.create("10001", exp, SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/session/1")
                        .header("Authorization", "Bearer " + token));
        filter.filter(exchange, e -> Mono.empty()).block();

        assertEquals("10001", exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID));
        assertEquals("10001", exchange.getRequest().getHeaders().getFirst(GatewayConstants.USER_ID_HEADER));
    }

    @Test
    void shouldRejectInvalidToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/session/1")
                        .header("Authorization", "Bearer invalid"));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertNull(exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID));
    }

    @Test
    void should_reject_and_not_propagate_user_when_sub_missing() throws Exception {
        // Arrange
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String token = TestTokens.createWithPayload(
                "{\"exp\":" + exp + ",\"roles\":[\"USER\"]}", SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/session/1")
                        .header("Authorization", "Bearer " + token));

        // Act
        filter.filter(exchange, e -> Mono.empty()).block();

        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertNull(exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID));
        assertThat(exchange.getRequest().getHeaders().getFirst(GatewayConstants.USER_ID_HEADER)).isNull();
    }

    @Test
    void should_reject_when_exp_missing() throws Exception {
        // Arrange
        String token = TestTokens.createWithPayload(
                "{\"sub\":\"10001\",\"roles\":[\"USER\"]}", SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/session/1")
                        .header("Authorization", "Bearer " + token));

        // Act
        filter.filter(exchange, e -> Mono.empty()).block();

        // Assert
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertNull(exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID));
    }
}
