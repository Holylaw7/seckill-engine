package com.seckill.integration.integration;

import com.seckill.common.result.Result;
import com.seckill.gateway.GatewayApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.service.InventoryService;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.inventory.service.ReconciliationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.integration.support.TestDataHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.5 RC-03 可观测性冒烟：
 * 验证 Gateway 与 Inventory 的 Prometheus 指标真实存在。
 */
@Tag("integration")
class ObservabilitySmokeIT extends IntegrationTestBase {

    @Test
    void gatewayMetricsShouldBeExposed() throws Exception {
        ServiceLauncher.RunningService gateway = ServiceLauncher.start(GatewayApplication.class, "gateway",
                List.of(
                        "--server.port=0",
                        "--spring.application.name=gateway",
                        "--spring.main.web-application-type=reactive",
                        "--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                        "--spring.data.redis.host=" + redisHost(),
                        "--spring.data.redis.port=" + redisPort(),
                        "--seckill.gateway.jwt.secret=seckill-engine-dev-secret-change-me",
                        "--spring.cloud.gateway.routes[0].id=seckill-service",
                        "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:1",
                        "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/seckill/**"));
        try {
            String base = "http://localhost:" + gateway.port();
            // GET 秒杀路由：JWT 过滤器 401，全局 Filter 链执行并计数
            TestHttp.getRaw(base + "/api/v1/seckill/execute");
            String prometheus = TestHttp.getRaw(base + "/actuator/prometheus");
            assertThat(prometheus).contains("gateway_request_total");
            assertThat(prometheus).contains("gateway_request_duration");
            assertThat(TestHttp.getRaw(base + "/actuator/health")).contains("\"UP\"");
        } finally {
            gateway.stop();
        }
    }

    @Test
    void inventoryMetricsShouldBeExposed() throws Exception {
        long skuId = 90001L;
        TestDataHelper.resetInventory(skuId, 100, 100, 0);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=4");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(skuId, 100, 4, false);
            InventoryService inventoryService = inventory.context().getBean(InventoryService.class);
            CreateOrderMessage message = new CreateOrderMessage(
                    "obs-msg-1", 900001L, skuId, 90001L, "OBS-ORDER-1", 1000L, 1, null, 0);
            assertThat(inventoryService.confirmDeduct(message)).isTrue();
            ReconciliationService reconciliationService =
                    inventory.context().getBean(ReconciliationService.class);
            assertThat(reconciliationService.repair(skuId, 99, "obs-repair-drill", null)).isNotBlank();
            String prometheus = TestHttp.getRaw("http://localhost:" + inventory.port()
                    + "/actuator/prometheus");
            assertThat(prometheus).contains("inventory_deduct_success_total");
            assertThat(prometheus).contains("inventory_deduct_duration_seconds");
            assertThat(prometheus).contains("inventory_repair_total");
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + skuId);
        }
    }

    @Test
    void seckillMetricsShouldBeExposed() throws Exception {
        long skuId = 90002L;
        TestDataHelper.seedSeckill(90002L, skuId, 100, "READY", "99.00");
        TestDataHelper.resetInventory(skuId, 100, 100, 0);
        ServiceLauncher.RunningService seckill = ServiceSupport.start(
                SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        try {
            redisSet("seckill:stock:" + skuId, "100");
            redisSet("seckill:stock:total:" + skuId, "100");
            Result<ExecuteResponse> result = TestHttp.execute(
                    "http://localhost:" + seckill.port(), 900002L, 90002L, skuId, 1,
                    "obs-seckill-" + System.nanoTime());
            assertThat(result).isNotNull();
            assertThat(result.getCode()).isZero();
            String prometheus = TestHttp.getRaw("http://localhost:" + seckill.port()
                    + "/actuator/prometheus");
            assertThat(prometheus).contains("seckill_success_total");
        } finally {
            seckill.stop();
            execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=90002");
            execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=90002");
            execute("DELETE FROM seckill_seckill.seckill_session WHERE id=90002");
            cleanRedis("seckill:*");
        }
    }
}
