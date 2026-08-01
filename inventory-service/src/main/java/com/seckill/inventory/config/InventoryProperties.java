package com.seckill.inventory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * inventory-service 配置。
 */
@Data
@ConfigurationProperties(prefix = "seckill.inventory")
public class InventoryProperties {

    private long workerId = 3L;

    private final Recover recover = new Recover();

    @Data
    public static class Recover {
        /** seckill-service 内部回补接口地址 */
        private String baseUrl = "http://localhost:8082";
        private String path = "/api/v1/seckill/internal/stocks/recover";
    }
}
