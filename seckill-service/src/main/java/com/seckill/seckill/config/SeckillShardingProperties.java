package com.seckill.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 库存分桶配置（Phase 6.2，seckill 侧准入）：默认关闭且桶数=1，行为等价旧系统。
 */
@Data
@ConfigurationProperties(prefix = "inventory.sharding")
public class SeckillShardingProperties {

    /** 是否启用分桶准入（Lua v2） */
    private boolean enabled = false;

    /** 桶数（默认 1） */
    private int bucketCount = 1;
}
