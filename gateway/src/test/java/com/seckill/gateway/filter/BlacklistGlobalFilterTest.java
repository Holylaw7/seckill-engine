package com.seckill.gateway.filter;

import com.seckill.gateway.blacklist.BlacklistService;
import com.seckill.gateway.constant.GatewayConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.Tag("unit")
class BlacklistGlobalFilterTest {

    @Test
    void shouldRejectBlockedUser() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(any(), any())).thenReturn(Mono.just(true));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");
        filter.filter(exchange, e -> Mono.empty()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void shouldAllowWhenNotBlocked() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(any(), any())).thenReturn(Mono.just(false));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());

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
        when(service.isBlocked(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());

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
    void should_reject_when_ip_blocked() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(eq("203.0.113.5"), any())).thenReturn(Mono.just(true));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute")
                        .remoteAddress(new InetSocketAddress("203.0.113.5", 1234)));

        filter.filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void should_reject_when_user_blocked_and_ip_allowed() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(any(), any())).thenReturn(Mono.just(true));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");

        filter.filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void should_allow_when_user_and_ip_not_blocked() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(any(), any())).thenReturn(Mono.just(false));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");

        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }

    @Test
    void should_fail_open_when_user_check_errors() {
        BlacklistService service = mock(BlacklistService.class);
        when(service.isBlocked(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));
        BlacklistGlobalFilter filter = new BlacklistGlobalFilter(service, GatewayTestProfiles.disabledRecorder());
        AtomicBoolean passed = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/seckill/execute"));
        exchange.getAttributes().put(GatewayConstants.GATEWAY_USER_ID, "10001");

        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertThat(passed).isTrue();
    }
}
