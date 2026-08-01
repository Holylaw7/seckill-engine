package com.seckill.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 校验配置（基础版：网关解析 + HMAC-SHA256 验签）。
 */
@Data
@ConfigurationProperties(prefix = "seckill.gateway.jwt")
public class JwtProperties {

    /** 是否启用 JWT 解析入口 */
    private boolean enabled = true;

    /** HMAC 密钥（生产环境由配置中心下发，禁止硬编码） */
    private String secret = "";
}
