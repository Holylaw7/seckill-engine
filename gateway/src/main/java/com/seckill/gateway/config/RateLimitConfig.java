package com.seckill.gateway.config;

import com.seckill.gateway.constant.GatewayConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流 KeyResolver（支持 IP / 用户 / 接口维度，秒杀路由使用组合维度）。
 */
@Configuration
public class RateLimitConfig {

    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + resolveIp(exchange) + ":api:" + resolvePath(exchange));
    }

    @Bean("userKeyResolver")
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID);
            String key = userId != null ? "user:" + userId : "anonymous";
            return Mono.just(key + ":api:" + resolvePath(exchange));
        };
    }

    @Bean("apiKeyResolver")
    public KeyResolver apiKeyResolver() {
        return exchange -> Mono.just("api:" + resolvePath(exchange));
    }

    /**
     * 秒杀路由使用的组合维度：优先 user+api，未登录回退 ip+api。
     * @Primary：SCG 4.x RequestRateLimiterGatewayFilterFactory 构造注入要求唯一 KeyResolver，
     * 未标记主 Bean 时网关启动失败（Phase 6.1 发现）。
     */
    @Primary
    @Bean("rateLimitKeyResolver")
    public KeyResolver rateLimitKeyResolver() {
        return exchange -> {
            String userId = exchange.getAttribute(GatewayConstants.GATEWAY_USER_ID);
            String dimension = userId != null ? "user:" + userId : "ip:" + resolveIp(exchange);
            return Mono.just(dimension + ":api:" + resolvePath(exchange));
        };
    }

    private static String resolvePath(ServerWebExchange exchange) {
        return exchange.getRequest().getURI().getPath();
    }

    private static String resolveIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        return address.getAddress().getHostAddress();
    }
}
