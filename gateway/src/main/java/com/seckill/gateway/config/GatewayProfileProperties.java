package com.seckill.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 逐 Filter 性能采集（Phase 6.3，默认关闭；仅用于压测 Profiling，不影响业务语义）。
 */
@Data
@ConfigurationProperties(prefix = "seckill.gateway.profile")
public class GatewayProfileProperties {

    /** 是否开启逐 Filter 计时（默认 false） */
    private boolean enabled = false;
}
