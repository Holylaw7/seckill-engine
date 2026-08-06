package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.inventory.service.RedisStockValidationJob;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.8 Task 7 Production Full Rollback Drill：
 * <ol>
 *   <li>Gateway 100% → 0% 全量回滚，RTO &lt; 5min，流量恢复 stable；</li>
 *   <li>Inventory：8 桶迁移 + Redis 预热 → DEDUCT + 重复消息幂等 → CANCEL RECOVER →
 *       Redis global == SUM(bucket) == MySQL available；</li>
 *   <li>MQ：重复投递只产生一次效果（consumer 重启场景由 BackupRecoveryDrillIT 覆盖）。</li>
 * </ol>
 */
@Tag("integration")
class ProductionFullRollbackDrillIT extends IntegrationTestBase {

    private static final String TOKEN = "full-rollback-token";
    private static final long SKU_ID = 99001L;
    private static final RestTemplate REST = new RestTemplate();

    @Test
    void fullRollbackShouldRestoreGatewayAndKeepInventoryConsistent() throws Exception {
        gatewayRollbackDrill();
        inventoryAndMqRollbackDrill();
    }

    private static void gatewayRollbackDrill() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String canary = exchange.getRequestHeaders().getFirst("X-Canary-Version");
            byte[] body = ("{\"code\":0,\"canary\":\"" + (canary == null ? "none" : canary) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        try {
            ServiceLauncher.RunningService gateway = startGateway(stub.getAddress().getPort());
            try {
                String base = "http://localhost:" + gateway.port();
                // 100%：全部 RC1
                setWeight(base, 100);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"RC1\"");

                // 全量回滚 0%，测量 RTO
                long start = System.currentTimeMillis();
                setWeight(base, 0);
                assertThat(getWithUser(base, "10001")).contains("\"canary\":\"stable\"");
                long rtoMs = System.currentTimeMillis() - start;
                assertThat(rtoMs).isLessThan(5 * 60_000L);
                for (String userId : List.of("20002", "30003", "40004", "50005")) {
                    assertThat(getWithUser(base, userId)).contains("\"canary\":\"stable\"");
                }
            } finally {
                gateway.stop();
            }
        } finally {
            stub.stop(0);
        }
    }

    private static void inventoryAndMqRollbackDrill() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=8");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(SKU_ID, 1000, 8, false);
            redisSet("seckill:stock:" + SKU_ID, "1000");
            for (int i = 0; i < 8; i++) {
                redisSet("seckill:stock:bucket:" + SKU_ID + ":" + i, "125");
            }

            String orderId = "FR-ORDER-1";
            String body = TestHttp.createOrderMessageJson("FR-MSG-1", 990001L, 99001L,
                    SKU_ID, orderId, 1, 9900L, "full-rollback", 0);
            sendCreate(body);
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1));

            // MQ 幂等：重复投递只生效一次
            sendCreate(body);
            sendCreate(body);
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1));
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                    + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'")).isEqualTo(1);

            // 回补：CANCEL_ORDER → RECOVER 流水，Redis/MySQL 恢复一致
            sendCancel(TestHttp.cancelOrderMessageJson("FR-CANCEL-1", orderId,
                    990001L, 99001L, SKU_ID, 1, "CANCEL"));
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                            + "WHERE change_type='RECOVER' AND sku_id=" + SKU_ID)).isEqualTo(1));

            RedisStockValidationJob job =
                    inventory.context().getBean(RedisStockValidationJob.class);
            RedisStockValidationReport report = job.validate(SKU_ID);
            assertThat(report.isPass()).as("diffs=%s", report.diffs()).isTrue();
            assertThat(report.redisGlobal()).isEqualTo(1000L);
            assertThat(report.redisBucketSum()).isEqualTo(1000L);
            assertThat(report.mysqlAvailable()).isEqualTo(1000L);
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
            cleanRedis("seckill:*");
        }
    }

    private static ServiceLauncher.RunningService startGateway(int stubPort) {
        return ServiceLauncher.start(com.seckill.gateway.GatewayApplication.class, "gateway",
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
                        "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + stubPort,
                        "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/payments/callback/**"));
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
        assertThat(response.getBody()).contains("\"weight\":" + weight);
    }

    private static void sendCreate(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }

    private static void sendCancel(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", body);
    }
}
