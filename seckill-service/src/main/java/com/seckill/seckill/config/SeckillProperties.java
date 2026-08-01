package com.seckill.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * seckill-service 配置（冻结项：flow-key-ttl-seconds=86400）。
 */
@Data
@ConfigurationProperties(prefix = "seckill.core")
public class SeckillProperties {

    private long workerId = 2L;

    /** seckill:flow:{orderId} TTL（秒），冻结 86400 */
    private long flowKeyTtlSeconds = 86400L;

    private final RiskCheck riskCheck = new RiskCheck();

    @Data
    public static class RiskCheck {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:8081";
        private boolean failOpen = true;
    }
}
