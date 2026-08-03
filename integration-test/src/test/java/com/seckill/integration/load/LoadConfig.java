package com.seckill.integration.load;

import java.time.Duration;

/**
 * 压测运行配置。
 */
public record LoadConfig(
        int concurrency,
        int totalRequests,
        Duration timeout) {

    public LoadConfig {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        if (totalRequests <= 0) {
            throw new IllegalArgumentException("totalRequests must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
