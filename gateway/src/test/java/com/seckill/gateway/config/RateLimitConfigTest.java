package com.seckill.gateway.config;

import com.seckill.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    @Test
    void compositeKeyShouldUseUserIdWhenPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");
        String key = config.rateLimitKeyResolver().resolve(exchange).block();
        assertEquals("user:10001:api:/api/v1/seckill/execute", key);
    }

    @Test
    void compositeKeyShouldFallbackToIp() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        String key = config.rateLimitKeyResolver().resolve(exchange).block();
        assertTrue(key.startsWith("ip:"));
        assertTrue(key.endsWith(":api:/api/v1/seckill/execute"));
    }

    @Test
    void apiKeyShouldUsePath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        assertEquals("api:/api/v1/seckill/execute", config.apiKeyResolver().resolve(exchange).block());
    }

    @Test
    void should_isolate_rate_limit_bucket_by_api_path() {
        // Arrange
        MockServerWebExchange seckill = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        MockServerWebExchange login = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login"));

        // Act & Assert
        assertThat(config.apiKeyResolver().resolve(seckill).block())
                .isNotEqualTo(config.apiKeyResolver().resolve(login).block());
        assertThat(config.ipKeyResolver().resolve(seckill).block())
                .isNotEqualTo(config.ipKeyResolver().resolve(login).block());
    }
}
