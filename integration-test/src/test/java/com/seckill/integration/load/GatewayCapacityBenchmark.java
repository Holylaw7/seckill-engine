package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.gateway.GatewayApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.3 Gateway 容量模型（GATEWAY_CAPACITY）：
 * 预登录 token + 真实 Gateway execute，并发 1000/3000/5000/10000，
 * 输出 QPS / p50/p95/p99 / 错误率 / 资源（CPU/heap/thread/GC）。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class GatewayCapacityBenchmark extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(GatewayCapacityBenchmark.class);

    private static final int[] DEFAULT_CONCURRENCIES = {1000, 3000, 5000, 10000};
    private static final int STOCK = 100000;
    private static final String PASSWORD = "Test@123";
    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_200_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_200_000_000L + (RUN_ID % 100_000L) * 1000L;
    private static final int MAX_USERS = 10000;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService GATEWAY;
    private static String gatewayBaseUrl;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        AUTH = ServiceSupport.start(AuthApplication.class, "auth", "seckill_auth", "auth-service",
                "--seckill.auth.jwt.secret=" + JWT_SECRET);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--server.tomcat.threads.max=1000",
                "--server.tomcat.accept-count=20000");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        GATEWAY = ServiceLauncher.start(GatewayApplication.class, "gateway", gatewayArgs());
        gatewayBaseUrl = "http://localhost:" + GATEWAY.port();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
    }

    @AfterAll
    static void tearDown() throws Exception {
        ServiceLauncher.RunningService[] services = {GATEWAY, INVENTORY, ORDER, SECKILL, AUTH};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        cleanupNamespace();
    }

    @Test
    void capacityModel() throws Exception {
        String[] tokens = new String[MAX_USERS];
        for (int i = 0; i < MAX_USERS; i++) {
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl, "loaduser" + (BASE_USER + i), PASSWORD);
            assertThat(login).isNotNull();
            assertThat(login.getCode()).isZero();
            tokens[i] = login.getData().getToken();
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        int[] concurrencies = concurrencySet();
        for (int concurrency : concurrencies) {
            int total = concurrency;
            Map<String, Integer> codes = new ConcurrentHashMap<>();
            ResourceMonitor.Sample resourceSample;
            LoadMetrics metrics;
            try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(1),
                    MYSQL.getContainerId(), REDIS.getContainerId(), ROCKETMQ.getContainerId())) {
                metrics = LoadTestExecutor.run(
                        new LoadConfig(concurrency, total, Duration.ofMinutes(10)),
                        index -> {
                            int userIndex = index % MAX_USERS;
                            long userId = BASE_USER + userIndex;
                            Result<ExecuteResponse> result;
                            try {
                                result = TestHttp.executeWithAuth(gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                                        "cap-" + RUN_ID + "-" + index, tokens[userIndex]);
                            } catch (Exception e) {
                                codes.merge("EXCEPTION", 1, Integer::sum);
                                return false;
                            }
                            int code = result == null ? -1 : result.getCode();
                            codes.merge(String.valueOf(code), 1, Integer::sum);
                            return code == 0;
                        });
                await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
                resourceSample = monitor.latest();
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("concurrency", concurrency);
            report.put("total", total);
            report.put("qps", round(metrics.qps()));
            report.put("p50Ms", round(metrics.p50Ms()));
            report.put("p95Ms", round(metrics.p95Ms()));
            report.put("p99Ms", round(metrics.p99Ms()));
            report.put("success", metrics.successCount());
            report.put("failed", metrics.failureCount());
            report.put("errorRate", round(metrics.failureCount() * 100.0 / total));
            report.put("codeDistribution", new java.util.TreeMap<>(codes));
            if (resourceSample != null) {
                report.put("resource", Map.of(
                        "cpuPercent", resourceSample.cpuPercent(),
                        "memoryBytes", resourceSample.memoryBytes(),
                        "heapUsedBytes", resourceSample.heapUsedBytes(),
                        "threadCount", resourceSample.threadCount(),
                        "gcCount", resourceSample.gcCount(),
                        "gcTimeMs", resourceSample.gcTimeMs()));
            }
            reports.add(report);
            log.info("GATEWAY_CAPACITY c{}: qps={}, p50={}ms, p95={}ms, p99={}ms, errorRate={}%, codes={}",
                    concurrency, String.format("%.2f", metrics.qps()),
                    String.format("%.2f", metrics.p50Ms()), String.format("%.2f", metrics.p95Ms()),
                    String.format("%.2f", metrics.p99Ms()),
                    String.format("%.2f", metrics.failureCount() * 100.0 / total), codes);
        }
        writeReport(reports);
    }

    private static int[] concurrencySet() {
        String prop = System.getProperty("gateway.capacity.concurrencies");
        if (prop == null || prop.isBlank()) {
            return DEFAULT_CONCURRENCIES;
        }
        return Arrays.stream(prop.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    private static List<String> gatewayArgs() {
        return List.of(
                "--server.port=0",
                "--spring.application.name=gateway",
                "--spring.main.web-application-type=reactive",
                "--spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "--spring.data.redis.host=" + redisHost(),
                "--spring.data.redis.port=" + redisPort(),
                "--seckill.gateway.jwt.secret=" + JWT_SECRET,
                "--spring.cloud.gateway.routes[0].id=auth-service",
                "--spring.cloud.gateway.routes[0].uri=http://localhost:" + AUTH.port(),
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**",
                "--spring.cloud.gateway.routes[1].id=seckill-service",
                "--spring.cloud.gateway.routes[1].uri=http://localhost:" + SECKILL.port(),
                "--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**",
                "--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter",
                "--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@rateLimitKeyResolver}",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=100000",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=200000",
                "--spring.cloud.gateway.routes[2].id=order-service",
                "--spring.cloud.gateway.routes[2].uri=http://localhost:" + ORDER.port(),
                "--spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/orders/**",
                "--spring.cloud.gateway.routes[3].id=payment-service",
                "--spring.cloud.gateway.routes[3].uri=http://localhost:1",
                "--spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**");
    }

    private static void seedLoadUsers() throws Exception {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + MAX_USERS));
        int batch = 1000;
        for (int start = 0; start < MAX_USERS; start += batch) {
            int end = Math.min(start + batch, MAX_USERS);
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sql.append(',');
                }
                sql.append('(').append(BASE_USER + i)
                        .append(",'loaduser").append(BASE_USER + i)
                        .append("','").append(hash)
                        .append("',1,'USER')");
            }
            execute(sql.toString());
        }
    }

    private static void writeReport(List<Map<String, Object>> reports) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", "GATEWAY_CAPACITY");
        root.put("scenarios", reports);
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("GATEWAY_CAPACITY.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        StringBuilder csv = new StringBuilder(
                "concurrency,total,qps,p50Ms,p95Ms,p99Ms,success,failed,errorRate,"
                        + "cpuPercent,heapUsedBytes,threadCount,gcCount,gcTimeMs" + System.lineSeparator());
        for (Map<String, Object> report : reports) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = (Map<String, Object>) report.getOrDefault("resource", Map.of());
            csv.append(String.join(",",
                    String.valueOf(report.get("concurrency")),
                    String.valueOf(report.get("total")),
                    String.valueOf(report.get("qps")),
                    String.valueOf(report.get("p50Ms")),
                    String.valueOf(report.get("p95Ms")),
                    String.valueOf(report.get("p99Ms")),
                    String.valueOf(report.get("success")),
                    String.valueOf(report.get("failed")),
                    String.valueOf(report.get("errorRate")),
                    String.valueOf(resource.getOrDefault("cpuPercent", "")),
                    String.valueOf(resource.getOrDefault("heapUsedBytes", "")),
                    String.valueOf(resource.getOrDefault("threadCount", "")),
                    String.valueOf(resource.getOrDefault("gcCount", "")),
                    String.valueOf(resource.getOrDefault("gcTimeMs", "")))).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(directory.resolve("GATEWAY_CAPACITY.csv"), csv.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + MAX_USERS));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + MAX_USERS));
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
