package com.seckill.inventory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * inventory-service 配置。
 */
@Data
@ConfigurationProperties(prefix = "seckill.inventory")
public class InventoryProperties {

    private long workerId = 3L;

    private final Recover recover = new Recover();
    private final ConsistencyCheck consistencyCheck = new ConsistencyCheck();

    @Data
    public static class Recover {
        /** seckill-service 内部回补接口地址 */
        private String baseUrl = "http://localhost:8082";
        private String path = "/api/v1/seckill/internal/stocks/recover";
    }

    /** Phase 6.8 生产 Redis 一致性校验调度配置（默认关闭，生产由配置中心开启）。 */
    @Data
    public static class ConsistencyCheck {
        /** 是否启用 1 分钟周期校验 */
        private boolean enabled = false;

        /** 调度间隔（毫秒），默认 1 分钟 */
        private long intervalMs = 60_000L;

        /** 需要校验的 SKU 列表（逗号分隔） */
        private List<Long> skuIds = new java.util.ArrayList<>();
    }
}
