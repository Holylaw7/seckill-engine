package com.seckill.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("unit")
class RequestValidationGlobalFilterTest {

    private final RequestValidationGlobalFilter filter =
            new RequestValidationGlobalFilter(GatewayTestProfiles.disabledRecorder());

    @Test
    void shouldRejectPostWithoutJsonContentType() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/seckill/execute"));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldAllowPostWithJsonContentType() {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/seckill/execute")
                        .header("Content-Type", "application/json"));
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        assertTrue(passed.get());
    }

    @Test
    void shouldRejectOversizedBody() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/seckill/execute")
                        .header("Content-Type", "application/json")
                        .header("Content-Length", String.valueOf(70 * 1024)));
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
    }
}
