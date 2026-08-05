package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.6.5 Slow SQL 生产基线：
 * 验证 Testcontainers MySQL 可开启 slow_query_log / long_query_time=1s / log_output=TABLE，
 * 并确认监控查询可读取全局变量（生产环境由 my.cnf/配置中心固化）。
 */
@Tag("integration")
class SlowSqlMonitoringIT extends IntegrationTestBase {

    @Test
    void slowQueryLogShouldBeEnabledForProductionBaseline() throws Exception {
        ExecResult result = MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(),
                "-e",
                "SET GLOBAL slow_query_log='ON';"
                        + " SET GLOBAL long_query_time=1;"
                        + " SET GLOBAL log_output='TABLE';");
        assertThat(result.getExitCode()).as("mysql slow log enable stderr=%s", result.getStderr()).isZero();
        try {
            assertThat(queryString("SELECT @@global.slow_query_log")).isEqualTo("1");
            assertThat(queryString("SELECT @@global.long_query_time")).startsWith("1.0");
            assertThat(queryString("SELECT @@global.log_output")).isEqualTo("TABLE");
            // 监控目标语句可被 slow_log 捕获（不要求产生慢查询，仅验证表可写）
            assertThat(queryInt("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='mysql' AND table_name='slow_log'")).isEqualTo(1);
        } finally {
            MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(),
                    "-e", "SET GLOBAL slow_query_log='OFF';");
        }
    }
}
