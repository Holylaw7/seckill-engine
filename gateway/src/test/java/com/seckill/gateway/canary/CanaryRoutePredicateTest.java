package com.seckill.gateway.canary;

import com.seckill.gateway.config.CanaryTrafficProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 Canary 决策单元测试：粘性、边界、header 覆盖、禁用降级。
 */
class CanaryRoutePredicateTest {

    private CanaryTrafficProperties props(int weight) {
        CanaryTrafficProperties properties = new CanaryTrafficProperties();
        properties.setEnabled(true);
        properties.setWeight(weight);
        properties.setVersion("RC1");
        return properties;
    }

    @Test
    void sameUserShouldAlwaysTakeSameRoute() {
        CanaryTrafficProperties properties = props(50);
        boolean first = CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", null);
        for (int i = 0; i < 100; i++) {
            assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-" + i, null))
                    .isEqualTo(first);
        }
    }

    @Test
    void weightBoundaryShouldBeStable() {
        CanaryTrafficProperties properties = props(0);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", null)).isFalse();
        properties.setWeight(100);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", null)).isTrue();
        properties.setWeight(5);
        Set<Boolean> results = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            results.add(CanaryRoutePredicate.shouldCanary(
                    properties, "u" + i, "req-" + i, null));
        }
        assertThat(results).contains(true, false);
    }

    @Test
    void disabledOrWeightZeroShouldReturnStable() {
        CanaryTrafficProperties properties = props(50);
        properties.setEnabled(false);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", null)).isFalse();
        properties.setEnabled(true);
        properties.setWeight(0);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", null)).isFalse();
    }

    @Test
    void explicitCanaryHeaderShouldOverrideHash() {
        CanaryTrafficProperties properties = props(5);
        // header 值等于 version → 强制 canary（即使 hash 落在 stable）
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", "RC1")).isTrue();
        // 其他 header 值不改变哈希决策
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", "stable")).isFalse();
        // canary 未启用时 header 不生效（避免绕过灰度开关）
        properties.setEnabled(false);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "10001", "req-1", "RC1")).isFalse();
    }

    @Test
    void missingUserAndRequestIdShouldFallbackToStable() {
        CanaryTrafficProperties properties = props(50);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, null, null, null)).isFalse();
        assertThat(CanaryRoutePredicate.shouldCanary(properties, "", " ", null)).isFalse();
        // weight=100 全量放行，无用户标识也进入 canary
        properties.setWeight(100);
        assertThat(CanaryRoutePredicate.shouldCanary(properties, null, null, null)).isTrue();
    }
}
