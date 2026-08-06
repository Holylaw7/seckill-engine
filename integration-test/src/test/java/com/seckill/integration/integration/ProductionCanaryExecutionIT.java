package com.seckill.integration.integration;

import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.common.canary.GaReleaseDecision;
import com.seckill.gateway.canary.ProductionCanaryExecutor;
import com.seckill.gateway.canary.ProductionCanaryExecutor.ExecutionResult;
import com.seckill.gateway.canary.ProductionCanaryExecutor.StageObservation;
import com.seckill.gateway.canary.ProductionCanaryManager;
import com.seckill.gateway.canary.ProductionCanaryManager.Stage;
import com.seckill.gateway.config.CanaryTrafficProperties;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.config.InventoryShardingProperties;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.inventory.service.RedisStockValidationJob;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.9 Task 1/2 Production Canary Execution：
 * Stage 0 配置 + Redis 一致性 + 依赖门禁检查 → 流量切换验证 → 发布执行器
 * 5%→25%→50%→100%→GA；CRITICAL 场景产出 RC1 STABLE 决策。
 */
@Tag("integration")
class ProductionCanaryExecutionIT extends IntegrationTestBase {

    private static final long SKU_ID = 99501L;
    private static final String TOKEN = "execution-token";
    private static final RestTemplate REST = new RestTemplate();

    @Test
    void canaryExecutionShouldReachGaWhenAllGatesPass() throws Exception {
        // ===== Stage 0：配置检查（sharding=8、internal-auth、monitoring）=====
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=8");
        try {
            InventoryShardingProperties sharding =
                    inventory.context().getBean(InventoryShardingProperties.class);
            assertThat(sharding.isEnabled()).isTrue();
            assertThat(sharding.getBucketCount()).isEqualTo(8);
            assertThat(inventory.context()
                    .getBean(com.seckill.inventory.config.InternalAuthProperties.class)
                    .isEnabled()).isTrue();

            // Stage 0：Redis 一致性（8 桶预热 → validate PASS）
            TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(SKU_ID, 1000, 8, false);
            redisSet("seckill:stock:" + SKU_ID, "1000");
            for (int i = 0; i < 8; i++) {
                redisSet("seckill:stock:bucket:" + SKU_ID + ":" + i, "125");
            }
            RedisStockValidationReport report =
                    inventory.context().getBean(RedisStockValidationJob.class).validate(SKU_ID);
            assertThat(report.isPass()).as("stage0 redis diffs=%s", report.diffs()).isTrue();

            // Stage 0：依赖门禁（CI 配置静态断言）
            String ci = Files.readString(Path.of("../.github/workflows/ci.yml"));
            assertThat(ci).contains("dependency-scan", "failBuildOnCVSS=7");
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
            cleanRedis("seckill:*");
        }

        // ===== 流量切换验证：100% RC1 → 0% stable =====
        trafficSwitchDrill();

        // ===== 发布执行器：5 阶段健康全 PASS → GA =====
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);
        ExecutionResult result = executor.execute(List.of(), observations(0, 0));
        assertThat(result.completed()).as("failures=%s", result.failures()).isTrue();
        assertThat(result.finalStage()).isEqualTo(Stage.GA);

        // ===== GA 决策：全部门禁 PASS → GA READY =====
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canary-5", true);
        gates.put("canary-25", true);
        gates.put("canary-50", true);
        gates.put("100-validation", true);
        gates.put("rollback", true);
        gates.put("dependency-scan", true);
        assertThat(GaReleaseDecision.evaluate(gates).isGaReady()).isTrue();
    }

    @Test
    void criticalHealthShouldKeepRcStable() {
        ProductionCanaryManager manager =
                new ProductionCanaryManager(new CanaryTrafficProperties());
        ProductionCanaryExecutor executor = new ProductionCanaryExecutor(manager);
        // 25% 阶段 CRITICAL（oversell=2）
        ExecutionResult result = executor.execute(List.of(), observations(2, 0));
        assertThat(result.completed()).isFalse();
        assertThat(manager.stage()).isEqualTo(Stage.INIT);

        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canary-5", true);
        gates.put("canary-25", false);
        gates.put("oversell-0", false);
        assertThat(GaReleaseDecision.evaluate(gates).isGaReady()).isFalse();
        assertThat(GaReleaseDecision.evaluate(gates).failures())
                .contains("canary-25", "oversell-0");
    }

    private static List<StageObservation> observations(long oversellAt25, long dlq) {
        return List.of(
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, dlq)),
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, oversellAt25, 0, 0, dlq)),
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, dlq)),
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, dlq)),
                new StageObservation(window(), HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, dlq)));
    }

    private static CanaryObservationWindow window() {
        Instant now = Instant.now();
        return new CanaryObservationWindow(now.minusSeconds(31 * 60), now, 5,
                12000, 12000, 0, 300, 5, 0, "NONE");
    }

    private static void trafficSwitchDrill() throws Exception {
        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String canary = exchange.getRequestHeaders().getFirst("X-Canary-Version");
            byte[] body = ("{\"code\":0,\"canary\":\"" + (canary == null ? "none" : canary) + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        try {
            ServiceLauncher.RunningService gateway = ServiceLauncher.start(
                    com.seckill.gateway.GatewayApplication.class, "gateway",
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
                            "--seckill.gateway.canary.enabled=true",
                            "--seckill.gateway.canary.weight=100",
                            "--seckill.gateway.canary.version=RC1",
                            "--seckill.gateway.canary.control-enabled=true",
                            "--seckill.gateway.canary.control-token=" + TOKEN,
                            "--spring.cloud.gateway.routes[0].id=drill-stub",
                            "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + stub.getAddress().getPort(),
                            "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/payments/callback/**"));
            try {
                String base = "http://localhost:" + gateway.port();
                setWeight(base, 100);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"RC1\"");
                setWeight(base, 0);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"stable\"");
            } finally {
                gateway.stop();
            }
        } finally {
            stub.stop(0);
        }
    }

    private static String getWithUser(String base, String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        ResponseEntity<String> response = REST.exchange(
                base + "/api/v1/payments/callback/drill-" + userId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private static void setWeight(String base, int weight) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Canary-Token", TOKEN);
        ResponseEntity<String> response = REST.exchange(
                base + "/actuator/canary", HttpMethod.POST,
                new HttpEntity<>("{\"weight\":" + weight + "}", headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
