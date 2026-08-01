package com.seckill.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（密钥禁止代码硬编码；测试/生产由 Nacos 下发）。
 */
@Data
@ConfigurationProperties(prefix = "seckill.auth.jwt")
public class JwtProperties {

    /** HMAC 密钥（开发环境 application.yml，测试/生产 Nacos） */
    private String secret = "";

    /** access token 有效期（分钟），默认 120 */
    private int expireMinutes = 120;
}
