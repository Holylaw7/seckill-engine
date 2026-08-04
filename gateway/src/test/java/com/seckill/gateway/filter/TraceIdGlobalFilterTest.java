package com.seckill.gateway.filter;

import com.seckill.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@org.junit.jupiter.api.Tag("unit")
class TraceIdGlobalFilterTest {

    private final TraceIdGlobalFilter filter =
            new TraceIdGlobalFilter(GatewayTestProfiles.disabledRecorder());

    @Test
    void shouldGenerateTraceIdWhenMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        filter.filter(exchange, e -> Mono.empty()).block();

        String traceId = exchange.getResponse().getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertEquals(32, traceId.length());
    }

    @Test
    void shouldReuseValidIncomingTraceId() {
        String incoming = "a".repeat(32);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute")
                        .header(GatewayConstants.TRACE_ID_HEADER, incoming));
        filter.filter(exchange, e -> Mono.empty()).block();

        assertEquals(incoming, exchange.getResponse().getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER));
    }
}
