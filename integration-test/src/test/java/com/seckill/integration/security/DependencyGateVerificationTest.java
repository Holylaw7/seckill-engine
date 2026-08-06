package com.seckill.integration.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.9 依赖安全门禁静态验证：CI workflow 必须包含 dependency-scan 且 CVSS>=7 FAIL。
 * 单元级校验（不启动中间件）。
 */
@Tag("unit")
class DependencyGateVerificationTest {

    @Test
    void ciShouldEnforceDependencyScanGate() throws Exception {
        String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
        assertThat(ci).contains("dependency-scan");
        assertThat(ci).contains("org.owasp:dependency-check-maven");
        assertThat(ci).contains("-DfailBuildOnCVSS=7");
        assertThat(ci).contains("release-gate");
        assertThat(ci).contains("needs: [quality-check, dependency-scan]");
    }
}
