package com.seckill.integration.chaos;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.common.exception.BusinessException;
import com.seckill.gateway.GatewayApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.4 H-05 生产就绪故障演练：
 * F-01 Gateway 实例关闭 → 流量迁移（双实例存活验证）
 * F-02 Redis blacklist 不可用 → fail-open
 * F-03 MQ consumer crash → 重复投递只生效一次
 * F-04 Inventory 节点异常 → 恢复后继续消费
 * 仅 chaos 标签执行。
 */
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionReadinessChaosIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessChaosIT.class);

    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";
    private static final String PASSWORD = "Test@123";
    private static final long SESSION_ID = 34001L;
    private static final long SKU_F01 = 24001L;
    private static final long SKU_F02 = 24002L;
    private static final long SKU_F03 = 24003L;
    private static final long SKU_F04 = 24004L;
    private static final long USER = 24011L;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService GATEWAY_A;
    private static ServiceLauncher.RunningService GATEWAY_B;
    private static ServiceLauncher.RunningService INVENTORY;

    @AfterAll
    static void tearDown() throws Exception {
        ServiceLauncher.RunningService[] services =
                {GATEWAY_B, GATEWAY_A, INVENTORY, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    @BeforeEach
    void reset() throws Exception {
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id IN ("
                + SKU_F01 + "," + SKU_F02 + "," + SKU_F03 + "," + SKU_F04 + ")");
        execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id IN ("
                + SKU_F01 + "," + SKU_F02 + "," + SKU_F03 + "," + SKU_F04 + ")");
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id IN ("
                + SKU_F01 + "," + SKU_F02 + "," + SKU_F03 + "," + SKU_F04 + ")");
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id=" + USER);
    }

    @Test
    @Order(1)
    void f01_gatewayInstanceDownShouldMigrateTraffic() throws Exception {
        startCore();
        GATEWAY_A = startGateway("gw-a");
        String token = login();
        String baseA = "http://localhost:" + GATEWAY_A.port();

        assertThat(execute(baseA, token)).isNotNull();

        GATEWAY_A.stop();
        GATEWAY_A = null;
        // 实例下线后由替换实例接管流量（顺序启动，避免同 JVM 双 Netty DNS 冲突）
        GATEWAY_B = startGateway("gw-b");
        String baseB = "http://localhost:" + GATEWAY_B.port();
        assertThat(execute(baseB, token)).isNotNull();
        log.info("F-01 pass: gateway A down, traffic served by replacement gateway B");
    }

    @Test
    @Order(2)
    void f02_redisBlacklistUnavailableShouldFailOpen() throws Exception {
        startCore();
        GATEWAY_A = startGateway("gw-a");
        String token = login();
        String base = "http://localhost:" + GATEWAY_A.port();

        REDIS.stop();
        try {
            ResponseEntity<String> response = TestHttp.executeRawNoError(
                    base, USER, SESSION_ID, SKU_F02, 1, "chaos-f02-" + System.nanoTime(), token);
            // fail-open：网关不返回 403（黑名单拒绝），请求穿透到后端（后端 Redis 不可用返回业务错误）
            assertThat(response.getStatusCode().value()).isNotEqualTo(403);
            log.info("F-02 pass: blacklist fail-open, status={}", response.getStatusCode().value());
        } finally {
            REDIS.start();
            awaitRedisReady(Duration.ofSeconds(60));
        }
    }

    @Test
    @Order(3)
    void f03_mqConsumerCrashDuplicateShouldApplyOnce() throws Exception {
        TestDataHelper.resetInventory(SKU_F03, 100, 100, 0);
        INVENTORY = startInventory(SKU_F03);
        String orderId = "F03-ORDER-1";
        String body = TestHttp.createOrderMessageJson("F03-MSG-1", USER, SESSION_ID,
                SKU_F03, orderId, 1, 9900L, "chaos-f03", 0);
        sendCreate(body);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));

        INVENTORY.stop();
        INVENTORY = null;
        sendCreate(body);
        INVENTORY = startInventory(SKU_F03);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));
        sendCreate(body);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));
        log.info("F-03 pass: consumer crash redelivery applies once");
    }

    @Test
    @Order(4)
    void f04_inventoryNodeDownShouldRecoverAfterRestart() throws Exception {
        TestDataHelper.resetInventory(SKU_F04, 100, 100, 0);
        INVENTORY = startInventory(SKU_F04);
        String orderId = "F04-ORDER-1";
        INVENTORY.stop();
        INVENTORY = null;
        String body = TestHttp.createOrderMessageJson("F04-MSG-1", USER, SESSION_ID,
                SKU_F04, orderId, 1, 9900L, "chaos-f04", 0);
        sendCreate(body);

        INVENTORY = startInventory(SKU_F04);
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F04)).isEqualTo(1));
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                + "WHERE sku_id=" + SKU_F04 + " AND bucket_no=0")).isEqualTo(1);
        log.info("F-04 pass: inventory node recovered and consumed backlog");
    }

    private static void startCore() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_F01, 1000, "READY", "99.00");
        TestDataHelper.seedSeckill(SESSION_ID, SKU_F02, 1000, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_F01, 1000, 1000, 0);
        TestDataHelper.resetInventory(SKU_F02, 1000, 1000, 0);
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ("
                + USER + ",'chaosuser" + USER + "','" + hash + "',1,'USER')");
        AUTH = ServiceSupport.start(AuthApplication.class, "auth", "seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=" + JWT_SECRET);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--spring.data.redis.timeout=3s");
        redisSet("seckill:stock:" + SKU_F01, "1000");
        redisSet("seckill:stock:total:" + SKU_F01, "1000");
        redisSet("seckill:stock:" + SKU_F02, "1000");
        redisSet("seckill:stock:total:" + SKU_F02, "1000");
    }

    private static ServiceLauncher.RunningService startGateway(String name) throws Exception {
        List<String> args = List.of(
                "--server.port=0",
                "--spring.application.name=gateway-" + name,
                "--spring.main.web-application-type=reactive",
                "--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "--spring.data.redis.host=" + redisHost(),
                "--spring.data.redis.port=" + redisPort(),
                "--spring.data.redis.timeout=3s",
                "--seckill.gateway.jwt.secret=" + JWT_SECRET,
                "--spring.cloud.gateway.routes[0].id=auth-service",
                "--spring.cloud.gateway.routes[0].uri=http://127.0.0.1:" + AUTH.port(),
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**",
                "--spring.cloud.gateway.routes[1].id=seckill-service",
                "--spring.cloud.gateway.routes[1].uri=http://127.0.0.1:" + SECKILL.port(),
                "--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**",
                "--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter",
                "--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@rateLimitKeyResolver}",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=10000",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=20000",
                "--spring.cloud.gateway.routes[2].id=order-service",
                "--spring.cloud.gateway.routes[2].uri=http://127.0.0.1:1",
                "--spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/orders/**",
                "--spring.cloud.gateway.routes[3].id=payment-service",
                "--spring.cloud.gateway.routes[3].uri=http://127.0.0.1:1",
                "--spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**");
        return ServiceLauncher.start(GatewayApplication.class, name, args);
    }

    private static ServiceLauncher.RunningService startInventory(long skuId) throws Exception {
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.mq.consumer.threads=8",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=4");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(skuId, 100, 4, false);
        } catch (BusinessException e) {
            // 已存在分桶（重启场景）：容忍，继续使用既有分桶
            log.info("inventory restart: existing buckets reused, skuId={}", skuId);
        }
        return inventory;
    }

    private static String login() {
        Result<LoginResponse> login = TestHttp.login(
                "http://localhost:" + GATEWAY_A.port(), "chaosuser" + USER, PASSWORD);
        assertThat(login).isNotNull();
        assertThat(login.getCode()).isZero();
        return login.getData().getToken();
    }

    private static Result<ExecuteResponse> execute(String base, String token) {
        return TestHttp.executeWithAuth(base, USER, SESSION_ID, SKU_F01, 1,
                "chaos-" + System.nanoTime(), token);
    }

    private static void sendCreate(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }
}
