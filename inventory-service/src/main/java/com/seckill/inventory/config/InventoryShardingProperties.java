package com.seckill.inventory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 库存分桶配置（Phase 6.2）：默认关闭且 bucket-count=1，行为等价旧系统。
 */
@Data
@ConfigurationProperties(prefix = "inventory.sharding")
public class InventoryShardingProperties {

    /** 是否启用分桶扣减/恢复（默认 false） */
    private boolean enabled = false;

    /** 桶数（默认 1，等价单桶） */
    private int bucketCount = 1;
}
