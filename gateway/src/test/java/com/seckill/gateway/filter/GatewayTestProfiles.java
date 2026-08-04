package com.seckill.gateway.filter;

import com.seckill.gateway.config.GatewayProfileProperties;

/**
 * 网关过滤器单测辅助：默认关闭的 ProfileRecorder（Profiling 不影响业务测试）。
 */
public final class GatewayTestProfiles {

    private GatewayTestProfiles() {
    }

    public static GatewayProfileRecorder disabledRecorder() {
        GatewayProfileProperties properties = new GatewayProfileProperties();
        properties.setEnabled(false);
        return new GatewayProfileRecorder(properties);
    }
}
