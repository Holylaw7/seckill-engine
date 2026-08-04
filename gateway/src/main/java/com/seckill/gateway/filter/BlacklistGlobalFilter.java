package com.seckill.gateway.filter;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.gateway.blacklist.BlacklistService;
import com.seckill.gateway.constant.GatewayConstants;
import com.seckill.gateway.util.GatewayResponses;
import com.seckill.gateway.filter.GatewayProfileRecorder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 黑名单过滤器：IP 维度 + 用户维度（数据结构对齐 Redis 设计，Redis 异常时 fail-open）。
 */
@Component
@RequiredArgsConstructor
public class BlacklistGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BlacklistGlobalFilter.class);

    private final BlacklistService blacklistService;
    private final GatewayProfileRecorder profileRecorder;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = profileRecorder.enabled() ? System.nanoTime() : 0L;
        String ip = resolveIp(exchange);
        String userId = exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID);

        Mono<Boolean> ipCheck = ip == null ? Mono.just(false) : blacklistService.isIpBlocked(ip);
        Mono<Boolean> userCheck = userId == null ? Mono.just(false) : blacklistService.isUserBlocked(userId);

        return Mono.zip(ipCheck, userCheck, (ipBlocked, userBlocked) -> ipBlocked || userBlocked)
                .onErrorResume(e -> {
                    log.warn("blacklist check failed, fail-open, traceId={}",
                            exchange.getRequest().getHeaders().getFirst(GatewayConstants.TRACE_ID_HEADER), e);
                    return Mono.just(false);
                })
                .flatMap(blocked -> blocked
                        ? GatewayResponses.writeJson(exchange, HttpStatus.FORBIDDEN, Result.error(ErrorCode.BLACKLISTED))
                        : chain.filter(exchange).doOnSubscribe(signal ->
                                profileRecorder.record(exchange, "blacklist", start)));
    }

    private static String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return GatewayConstants.BLACKLIST_FILTER_ORDER;
    }
}
