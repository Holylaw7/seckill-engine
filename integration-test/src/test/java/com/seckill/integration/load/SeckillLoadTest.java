package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * L-01 秒杀接口压力测试：
 * 库存 100 / 并发 1000 / 请求 1000，真实 HTTP + 真实登录 JWT + 真实中间件。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class SeckillLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(SeckillLoadTest.class);

    private static final int STOCK = 100;
    private static final int CONCURRENCY = 1000;
    private static final int TOTAL_REQUESTS = 1000;
    private static final String PASSWORD = "Test@123";
    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";

    /** 每次运行独立 namespace（基于运行时刻生成） */
    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 4_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 8_000_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        AUTH = ServiceSupport.start(AuthApplication.class, "auth", "seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=" + JWT_SECRET);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--server.tomcat.threads.max=1000",
                "--server.tomcat.accept-count=1000");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        seedLoadUsers();
    }

    @AfterAll
    static void tearDown() throws Exception {
        cleanupNamespace();
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @Test
    void l01_should_pressure_seckill_api_without_oversell() throws Exception {
        String[] tokens = loginAllUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        Map<String, Integer> errorCodes = new ConcurrentHashMap<>();
        LoadMetrics metrics;
        ResourceMonitor.Sample resourceSample;
        try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(1),
                MYSQL.getContainerId(), REDIS.getContainerId(), ROCKETMQ.getContainerId())) {
            metrics = LoadTestExecutor.run(
                    new LoadConfig(CONCURRENCY, TOTAL_REQUESTS, Duration.ofMinutes(5)),
                    index -> {
                        long userId = BASE_USER + index;
                        String traceId = "load-l01-" + RUN_ID + "-" + index;
                        int code;
                        try {
                            Result<ExecuteResponse> result = TestHttp.executeWithAuth(
                                    "http://localhost:" + SECKILL.port(), userId, SESSION_ID, SKU_ID, 1,
                                    traceId, tokens[index]);
                            code = result == null ? -1 : result.getCode();
                        } catch (Exception e) {
                            // 连接拒绝/超时等传输级异常按其他异常分类
                            code = -1;
                        }
                        errorCodes.merge(String.valueOf(code), 1, Integer::sum);
                        return code == 0;
                    });
            // 等待至少一个资源采样（JVM 指标必有；Docker 指标不可用时为 -1）
            await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
            resourceSample = monitor.latest();
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("errorCodeDistribution", new TreeMap<>(errorCodes));
        if (resourceSample != null) {
            extra.put("resource", Map.of(
                    "cpuPercent", resourceSample.cpuPercent(),
                    "memoryBytes", resourceSample.memoryBytes(),
                    "heapUsedBytes", resourceSample.heapUsedBytes(),
                    "threadCount", resourceSample.threadCount(),
                    "gcCount", resourceSample.gcCount(),
                    "gcTimeMs", resourceSample.gcTimeMs()));
        }
        LoadReport.writeJson("L-01", metrics, extra, LoadReport.defaultDir());
        LoadReport.writeCsv("L-01", metrics, LoadReport.defaultDir());
        log.info("L-01 result: total={}, success={}, fail={}, qps={}, avgRT={}ms, p50={}ms, p95={}ms, p99={}ms, codes={}",
                metrics.totalRequests(), metrics.successCount(), metrics.failureCount(),
                String.format("%.2f", metrics.qps()), String.format("%.2f", metrics.avgRtMs()),
                String.format("%.2f", metrics.p50Ms()), String.format("%.2f", metrics.p95Ms()),
                String.format("%.2f", metrics.p99Ms()), errorCodes);

        int success = metrics.successCount();
        assertThat(success).isLessThanOrEqualTo(STOCK);
        assertThat(metrics.failureCount()).isGreaterThanOrEqualTo(TOTAL_REQUESTS - STOCK);
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - success));

        // MQ 消费 60s 内收敛（Awaitility，禁止 sleep）
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(success);
            assertThat(TestDataHelper.countPreDeduct(SESSION_ID, SKU_ID)).isEqualTo(success);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(success);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - success);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(success);
        });
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
        assertThat(countRedisKeys("seckill:user:" + SKU_ID + ":*")).isEqualTo(success);
        assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(success);
    }

    private static String[] loginAllUsers() throws Exception {
        String[] tokens = new String[TOTAL_REQUESTS];
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            Result<LoginResponse> result = TestHttp.login("http://localhost:" + AUTH.port(),
                    "loaduser" + (BASE_USER + i), PASSWORD);
            if (result == null || result.getCode() != 0 || result.getData() == null
                    || result.getData().getToken() == null) {
                throw new IllegalStateException("load user login failed at index " + i + ": "
                        + (result == null ? "null" : result.getCode()));
            }
            tokens[i] = result.getData().getToken();
        }
        return tokens;
    }

    private static void seedLoadUsers() throws Exception {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TOTAL_REQUESTS));
        StringBuilder sql = new StringBuilder(
                "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ");
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('(').append(BASE_USER + i)
                    .append(",'loaduser").append(BASE_USER + i)
                    .append("','").append(hash)
                    .append("',1,'USER')");
        }
        execute(sql.toString());
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + TOTAL_REQUESTS));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TOTAL_REQUESTS));
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
