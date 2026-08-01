package com.seckill.integration;

import com.seckill.integration.support.IntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成环境冒烟：真实 MySQL / Redis / RocketMQ 连通性（Phase 5.3.1）。
 */
@Tag("integration")
class IntegrationEnvironmentSmokeIT extends IntegrationTestBase {

    @Test
    void mysql_should_serve_five_service_schemas() throws Exception {
        // Act & Assert
        assertThat(queryInt("SELECT 1")).isEqualTo(1);
        for (String schema : SCHEMAS) {
            int tables = queryInt(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + schema + "'");
            assertThat(tables)
                    .as("schema %s should contain tables", schema)
                    .isPositive();
        }
    }

    @Test
    void redis_should_ping() {
        assertThat(redisPing()).isEqualTo("PONG");
    }

    @Test
    void rocketmq_should_accept_message() throws Exception {
        // 预热消息（自定义 tag，不会被业务消费组投递）：同时验证 Topic 可创建、消息可发送
        sendRocketMqMessage("seckill-order-tx", "TEST_WARMUP", "{\"warmup\":true}");
    }
}
