package com.seckill.gateway.canary;

import com.seckill.gateway.config.CanaryTrafficProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Phase 6.7 Canary 路由决策（纯逻辑，可单测）。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>canary 未启用或 weight&lt;=0 → stable；weight&gt;=100 → canary；</li>
 *   <li>X-Canary 头等于 version → 强制 canary（调试/压测）；</li>
 *   <li>否则 stickyBucket(userId) 或 stickyBucket(requestId) % 100 &lt; weight → canary。</li>
 * </ol>
 *
 * <p>禁止随机切流：同一 userId/requestId 哈希恒定，同用户始终同一路由。</p>
 */
public final class CanaryRoutePredicate {

    private CanaryRoutePredicate() {
    }

    public static boolean shouldCanary(CanaryTrafficProperties properties,
                                       String userId, String requestId, String canaryHeaderValue) {
        if (properties == null || !properties.isEnabled() || properties.getWeight() <= 0) {
            return false;
        }
        if (properties.getWeight() >= 100) {
            return true;
        }
        if (canaryHeaderValue != null && properties.getVersion().equals(canaryHeaderValue)) {
            return true;
        }
        String key = firstNonBlank(userId, requestId);
        if (key == null) {
            return false;
        }
        return stickyBucket(key) % 100 < properties.getWeight();
    }

    /** 稳定哈希（SHA-256 取前 8 字节），同 key 恒同 bucket。 */
    public static int stickyBucket(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return (int) (Math.floorMod(value, 100));
        } catch (Exception e) {
            // SHA-256 不可用时退化为字符串 hashCode（仍确定）
            return Math.floorMod(key.hashCode(), 100);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
