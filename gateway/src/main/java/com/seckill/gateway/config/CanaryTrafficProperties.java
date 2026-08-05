package com.seckill.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 6.7 Canary 流量配置。
 *
 * <p>weight 语义：hash(userId)%100 &lt; weight 进入 canary 版本；
 * 同用户哈希固定，天然 sticky，禁止随机切流。</p>
 */
@Data
@ConfigurationProperties(prefix = "seckill.gateway.canary")
public class CanaryTrafficProperties {

    /** 是否启用 Canary 决策（默认关闭，行为等价旧网关） */
    private boolean enabled = false;

    /** canary 流量权重 0-100（0=全部 stable，100=全部 canary） */
    private int weight = 0;

    /** canary 版本标识（路由/观测标签） */
    private String version = "RC1";

    /** 用户标识 header（粘性路由依据） */
    private String userIdHeader = "X-User-Id";

    /** 请求标识 header（无用户标识时的降级依据） */
    private String requestIdHeader = "X-Request-Id";

    /** 显式 canary header：值等于 version 时强制进入 canary（调试/压测用） */
    private String canaryHeader = "X-Canary";

    /** 是否开放动态权重控制端点（生产默认 false；演练/运维平台按需开启） */
    private boolean controlEnabled = false;

    /** 控制端点令牌（controlEnabled=true 时必填，禁止空令牌开放） */
    private String controlToken = "";
}
