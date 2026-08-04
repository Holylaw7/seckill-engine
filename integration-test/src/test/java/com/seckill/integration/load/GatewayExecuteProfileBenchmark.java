package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.gateway.GatewayApplication;
import com.seckill.gateway.filter.GatewayProfileRecorder;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.seckill.SeckillApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.3 G-08 Gateway Pipeline Breakdown：
 * 经真实 Gateway（profile.enabled=true）执行秒杀，读取同 JVM GatewayProfileRecorder 采样，
 * 输出 trace/jwt/blacklist/validation/log/route/total 各阶段 p50/p95/p99。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class GatewayExecuteProfileBenchmark extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(GatewayExecuteProfileBenchmark.class);

    private static final int STOCK = 1000;
    private static final int REQUESTS = 500;
    private static final int CONCURRENCY = 100;
    private static final String PASSWORD = "Test@123";
    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";
    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_100_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_300_000_000L + (RUN_ID % 100_000L) * 1000L;

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
                "--server.tomcat.accept-count=1000");
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
    void g08_pipelineBreakdown() throws Exception {
        String[] tokens = new String[REQUESTS];
        for (int i = 0; i < REQUESTS; i++) {
            long userId = BASE_USER + i;
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl, "loaduser" + userId, PASSWORD);
            assertThat(login).isNotNull();
            assertThat(login.getCode()).isZero();
            tokens[i] = login.getData().getToken();
        }

        List<Long> totalRts = Collections.synchronizedList(new ArrayList<>());
        LoadTestExecutor.run(new LoadConfig(CONCURRENCY, REQUESTS, Duration.ofMinutes(5)),
                index -> {
                    long start = System.nanoTime();
                    long userId = BASE_USER + index;
                    Result<com.seckill.seckill.dto.ExecuteResponse> result = TestHttp.executeWithAuth(
                            gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                            "profile-" + RUN_ID + "-" + index, tokens[index]);
                    totalRts.add(System.nanoTime() - start);
                    return result != null && result.getCode() == 0;
                });

        Map<String, List<Long>> stageCosts = new ConcurrentHashMap<>();
        for (Map<String, Long> sample : GATEWAY.context()
                .getBean(GatewayProfileRecorder.class).samples()) {
            sample.forEach((stage, cost) ->
                    stageCosts.computeIfAbsent(stage, key -> Collections.synchronizedList(new ArrayList<>()))
                            .add(cost));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "G-08");
        report.put("requests", REQUESTS);
        report.put("concurrency", CONCURRENCY);
        for (String stage : List.of("trace", "validation", "jwt", "blacklist", "log", "route", "total")) {
            List<Long> costs = stageCosts.getOrDefault(stage, List.of());
            report.put(stage + "P50Ms", round(percentileMs(costs, 50)));
            report.put(stage + "P95Ms", round(percentileMs(costs, 95)));
            report.put(stage + "P99Ms", round(percentileMs(costs, 99)));
        }
        report.put("executeP50Ms", round(percentileMs(totalRts, 50)));
        report.put("executeP95Ms", round(percentileMs(totalRts, 95)));
        report.put("executeP99Ms", round(percentileMs(totalRts, 99)));
        writeReport(report);
        log.info("G-08 profile: traceP99={} jwtP99={} blacklistP99={} logP99={} routeP99={} totalP99={} executeP99={}",
                report.get("traceP99Ms"), report.get("jwtP99Ms"), report.get("blacklistP99Ms"),
                report.get("logP99Ms"), report.get("routeP99Ms"), report.get("totalP99Ms"),
                report.get("executeP99Ms"));

        assertThat(stageCosts).containsKeys("trace", "jwt", "blacklist", "route", "total");
        // 隔离环境严格门禁见 gateway JwtPerformanceTest（<5ms）；此处仅做健康检查（含 GC/排队噪声）
        assertThat((double) report.get("jwtP99Ms")).isLessThan(50.0);
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
                "--seckill.gateway.profile.enabled=true",
                "--spring.cloud.gateway.routes[0].id=auth-service",
                "--spring.cloud.gateway.routes[0].uri=http://localhost:" + AUTH.port(),
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**",
                "--spring.cloud.gateway.routes[1].id=seckill-service",
                "--spring.cloud.gateway.routes[1].uri=http://localhost:" + SECKILL.port(),
                "--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**",
                "--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter",
                "--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@rateLimitKeyResolver}",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=10000",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=20000",
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
                + " AND id < " + (BASE_USER + REQUESTS));
        StringBuilder sql = new StringBuilder(
                "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ");
        for (int i = 0; i < REQUESTS; i++) {
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

    private static double percentileMs(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1000.0;
    }

    private static void writeReport(Map<String, Object> report) throws Exception {
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("gateway-profile.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        String header = String.join(",", "scenario,requests,concurrency",
                "traceP50Ms,traceP95Ms,traceP99Ms",
                "jwtP50Ms,jwtP95Ms,jwtP99Ms",
                "blacklistP50Ms,blacklistP95Ms,blacklistP99Ms",
                "logP50Ms,logP95Ms,logP99Ms",
                "routeP50Ms,routeP95Ms,routeP99Ms",
                "totalP50Ms,totalP95Ms,totalP99Ms",
                "executeP50Ms,executeP95Ms,executeP99Ms");
        List<String> rowParts = new ArrayList<>();
        rowParts.add("G-08");
        rowParts.add(String.valueOf(report.get("requests")));
        rowParts.add(String.valueOf(report.get("concurrency")));
        for (String stage : List.of("trace", "jwt", "blacklist", "log", "route", "total", "execute")) {
            rowParts.add(String.valueOf(report.get(stage + "P50Ms")));
            rowParts.add(String.valueOf(report.get(stage + "P95Ms")));
            rowParts.add(String.valueOf(report.get(stage + "P99Ms")));
        }
        String row = String.join(",", rowParts);
        java.nio.file.Files.writeString(directory.resolve("gateway-profile.csv"),
                header + System.lineSeparator() + row);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + REQUESTS));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + REQUESTS));
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
