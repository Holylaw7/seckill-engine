package com.seckill.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 内部接口 Service ACL 配置（Phase 6.6）：服务名 → 共享密钥；admin 密钥单独管理。
 */
@Data
@ConfigurationProperties(prefix = "seckill.internal-auth")
public class InternalAuthProperties {

    private boolean enabled = true;

    /** 时间窗（毫秒），默认 300s */
    private long windowMillis = 300_000L;

    /** 服务密钥表：serviceName -> secret */
    private Map<String, String> clients = new HashMap<>();

    /** 管理端密钥（repair/syncSummary 等管理操作） */
    private String adminSecret = "";
}
