package com.seckill.gateway.filter;

import com.seckill.gateway.blacklist.BlacklistService;
import com.seckill.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlacklistGlobalFilterTest {

    @Test
    void shouldRejectBlockedUser() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isUserBlocked("10001")).thenReturn(Mono.just(true));
        when(service.isIpBlocked(anyString())).thenReturn(Mono.just(false));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");
        filter.filter(exchange, e -> Mono.empty()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldAllowWhenNotBlocked() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isIpBlocked(anyString())).thenReturn(Mono.just(false));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service);

        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(passed.get());
    }

    @Test
    void shouldFailOpenWhenRedisUnavailable() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isIpBlocked(anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service);

        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(passed.get());
    }
}
