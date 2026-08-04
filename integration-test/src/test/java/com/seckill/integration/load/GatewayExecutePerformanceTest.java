package com.seckill.integration.load;

import com.seckill.auth.AuthApplication;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.gateway.GatewayApplication;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.1 Gateway/Execute 链路分层测量（L-06-GATEWAY）：
 * 模型 A：login + execute 全走真实 Gateway（真实 JWT，不绕过鉴权）；
 * 模型 B：预登录 token + execute（Gateway）；
 * 另取直连 seckill-service 的 execute 样本，用差值估算 Gateway 开销（JWT 解析 + Filter 链 + 路由）。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayExecutePerformanceTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(GatewayExecutePerformanceTest.class);

    private static final int STOCK = 100;
    private static final int MODEL_A_TASKS = 200;
    private static final int MODEL_B_TASKS = 500;
    private static final int DIRECT_SAMPLE = 100;
    private static final int CONCURRENCY = 100;
    private static final String PASSWORD = "Test@123";
    private static final String JWT_SECRET = "seckill-engine-dev-secret-change-me";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_700_000_000L + (RUN_ID % 100_000L) * 1000L;
    private static final int TOTAL_USERS = MODEL_A_TASKS + MODEL_B_TASKS + DIRECT_SAMPLE;

    private static ServiceLauncher.RunningService AUTH;
    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService GATEWAY;
    private static String gatewayBaseUrl;
    private static String seckillBaseUrl;
    private static final AtomicInteger TOTAL_SUCCESS = new AtomicInteger();

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
        GATEWAY = ServiceLauncher.start(GatewayApplication.class, "gateway",
                gatewayArgs(AUTH.port(), SECKILL.port(), ORDER.port()));
        gatewayBaseUrl = "http://localhost:" + GATEWAY.port();
        seckillBaseUrl = "http://localhost:" + SECKILL.port();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        log.info("L-06-GATEWAY started, gatewayPort={}, authPort={}, seckillPort={}",
                GATEWAY.port(), AUTH.port(), SECKILL.port());
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
    @Order(1)
    void model_a_login_plus_execute() throws Exception {
        List<Long> loginRts = Collections.synchronizedList(new ArrayList<>());
        List<Long> executeRts = Collections.synchronizedList(new ArrayList<>());
        List<Long> taskRts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success = new AtomicInteger();

        long startEpoch = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        LoadTestExecutor.run(new LoadConfig(CONCURRENCY, MODEL_A_TASKS, Duration.ofMinutes(5)),
                index -> {
                    long taskStart = System.nanoTime();
                    long userId = BASE_USER + index;
                    long loginStart = System.nanoTime();
                    Result<LoginResponse> login;
                    try {
                        login = TestHttp.login(gatewayBaseUrl, "loaduser" + userId, PASSWORD);
                    } catch (Exception e) {
                        loginRts.add(System.nanoTime() - loginStart);
                        taskRts.add(System.nanoTime() - taskStart);
                        return false;
                    }
                    long loginRt = System.nanoTime() - loginStart;
                    loginRts.add(loginRt);
                    if (login == null || login.getCode() != 0 || login.getData() == null
                            || login.getData().getToken() == null) {
                        taskRts.add(System.nanoTime() - taskStart);
                        return false;
                    }
                    long executeStart = System.nanoTime();
                    Result<ExecuteResponse> execute;
                    try {
                        execute = TestHttp.executeWithAuth(gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                                "load-gw-a-" + RUN_ID + "-" + index, login.getData().getToken());
                    } catch (Exception e) {
                        executeRts.add(System.nanoTime() - executeStart);
                        taskRts.add(System.nanoTime() - taskStart);
                        return false;
                    }
                    long executeRt = System.nanoTime() - executeStart;
                    executeRts.add(executeRt);
                    taskRts.add(System.nanoTime() - taskStart);
                    boolean ok = execute != null && execute.getCode() == 0;
                    if (ok) {
                        success.incrementAndGet();
                    }
                    return ok;
                });
        long endNanos = System.nanoTime();
        long endEpoch = System.currentTimeMillis();
        TOTAL_SUCCESS.addAndGet(success.get());

        Map<String, Object> report = segmentReport("L-06-GATEWAY-A", MODEL_A_TASKS, success.get(),
                startEpoch, endEpoch, startNanos, endNanos, taskRts, loginRts, executeRts, null, null);
        writeReport(report);
        log.info("L-06-GATEWAY-A: total={}, success={}, login p50/p95/p99={}/{}/{} ms, "
                        + "execute p50/p95/p99={}/{}/{} ms",
                MODEL_A_TASKS, success.get(),
                String.format("%.2f", percentileMs(loginRts, 50)),
                String.format("%.2f", percentileMs(loginRts, 95)),
                String.format("%.2f", percentileMs(loginRts, 99)),
                String.format("%.2f", percentileMs(executeRts, 50)),
                String.format("%.2f", percentileMs(executeRts, 95)),
                String.format("%.2f", percentileMs(executeRts, 99)));
        assertThat(success.get()).isLessThanOrEqualTo(STOCK);
    }

    @Test
    @Order(2)
    void model_b_pre_login_execute_and_direct_calibration() throws Exception {
        // 预登录：模型 B 使用 Gateway 签发 token（登录耗时不计入 execute RT）
        String[] tokens = new String[MODEL_B_TASKS];
        for (int i = 0; i < MODEL_B_TASKS; i++) {
            long userId = BASE_USER + MODEL_A_TASKS + i;
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl, "loaduser" + userId, PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("model B pre-login failed at " + i);
            }
            tokens[i] = login.getData().getToken();
        }
        String[] directTokens = new String[DIRECT_SAMPLE];
        for (int i = 0; i < DIRECT_SAMPLE; i++) {
            long userId = BASE_USER + MODEL_A_TASKS + MODEL_B_TASKS + i;
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl, "loaduser" + userId, PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("direct calibration pre-login failed at " + i);
            }
            directTokens[i] = login.getData().getToken();
        }

        List<Long> gatewayExecuteRts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger gatewaySuccess = new AtomicInteger();
        long startEpoch = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        LoadTestExecutor.run(new LoadConfig(CONCURRENCY, MODEL_B_TASKS, Duration.ofMinutes(5)),
                index -> {
                    long executeStart = System.nanoTime();
                    long userId = BASE_USER + MODEL_A_TASKS + index;
                    Result<ExecuteResponse> execute;
                    try {
                        execute = TestHttp.executeWithAuth(gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                                "load-gw-b-" + RUN_ID + "-" + index, tokens[index]);
                    } catch (Exception e) {
                        gatewayExecuteRts.add(System.nanoTime() - executeStart);
                        return false;
                    }
                    gatewayExecuteRts.add(System.nanoTime() - executeStart);
                    boolean ok = execute != null && execute.getCode() == 0;
                    if (ok) {
                        gatewaySuccess.incrementAndGet();
                    }
                    return ok;
                });
        long gatewayEndNanos = System.nanoTime();
        long gatewayEndEpoch = System.currentTimeMillis();
        TOTAL_SUCCESS.addAndGet(gatewaySuccess.get());

        // 直连校准：同 JWT 直连 seckill-service，分离 Gateway 开销
        List<Long> directRts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger directSuccess = new AtomicInteger();
        LoadTestExecutor.run(new LoadConfig(50, DIRECT_SAMPLE, Duration.ofMinutes(3)),
                index -> {
                    long directStart = System.nanoTime();
                    long userId = BASE_USER + MODEL_A_TASKS + MODEL_B_TASKS + index;
                    Result<ExecuteResponse> execute;
                    try {
                        execute = TestHttp.executeWithAuth(seckillBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                                "load-gw-d-" + RUN_ID + "-" + index, directTokens[index]);
                    } catch (Exception e) {
                        directRts.add(System.nanoTime() - directStart);
                        return false;
                    }
                    directRts.add(System.nanoTime() - directStart);
                    boolean ok = execute != null && execute.getCode() == 0;
                    if (ok) {
                        directSuccess.incrementAndGet();
                    }
                    return ok;
                });
        TOTAL_SUCCESS.addAndGet(directSuccess.get());

        Map<String, Object> report = segmentReport("L-06-GATEWAY-B", MODEL_B_TASKS, gatewaySuccess.get(),
                startEpoch, gatewayEndEpoch, startNanos, gatewayEndNanos, gatewayExecuteRts,
                null, gatewayExecuteRts, directRts, directSuccess.get());
        writeReport(report);
        log.info("L-06-GATEWAY-B: gateway execute p50/p95/p99={}/{}/{} ms, direct execute p50/p95/p99={}/{}/{} ms",
                String.format("%.2f", percentileMs(gatewayExecuteRts, 50)),
                String.format("%.2f", percentileMs(gatewayExecuteRts, 95)),
                String.format("%.2f", percentileMs(gatewayExecuteRts, 99)),
                String.format("%.2f", percentileMs(directRts, 50)),
                String.format("%.2f", percentileMs(directRts, 95)),
                String.format("%.2f", percentileMs(directRts, 99)));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(TOTAL_SUCCESS.get());
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(TOTAL_SUCCESS.get());
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - TOTAL_SUCCESS.get());
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(TOTAL_SUCCESS.get());
        });
        assertThat(TOTAL_SUCCESS.get()).isLessThanOrEqualTo(STOCK);
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - TOTAL_SUCCESS.get()));
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
    }

    private static Map<String, Object> segmentReport(String scenario, int total, int success,
                                                     long startEpoch, long endEpoch,
                                                     long startNanos, long endNanos,
                                                     List<Long> taskRts, List<Long> loginRts,
                                                     List<Long> executeRts, List<Long> directRts,
                                                     Integer directSuccess) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", scenario);
        report.put("total", total);
        report.put("success", success);
        report.put("failed", total - success);
        report.put("successRate", round(total == 0 ? 0 : success * 100.0 / total));
        double seconds = (endNanos - startNanos) / 1_000_000_000.0;
        report.put("qps", round(seconds <= 0 ? 0 : total / seconds));
        report.put("durationMs", (endNanos - startNanos) / 1_000_000);
        report.put("timestamp", java.time.Instant.ofEpochMilli(endEpoch).toString());
        if (taskRts != null) {
            putPercentiles(report, "task", taskRts);
        }
        if (loginRts != null) {
            putPercentiles(report, "login", loginRts);
        }
        if (executeRts != null) {
            putPercentiles(report, "execute", executeRts);
        }
        if (directRts != null) {
            putPercentiles(report, "directExecute", directRts);
            report.put("directSuccess", directSuccess);
            report.put("gatewayOverheadP50", round(Math.max(0,
                    percentileMs(executeRts, 50) - percentileMs(directRts, 50))));
            report.put("gatewayOverheadP95", round(Math.max(0,
                    percentileMs(executeRts, 95) - percentileMs(directRts, 95))));
            report.put("gatewayOverheadP99", round(Math.max(0,
                    percentileMs(executeRts, 99) - percentileMs(directRts, 99))));
        }
        return report;
    }

    private static void putPercentiles(Map<String, Object> report, String prefix, List<Long> rts) {
        report.put(prefix + "AvgMs", round(rts.isEmpty() ? 0 : rts.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0));
        report.put(prefix + "P50Ms", round(percentileMs(rts, 50)));
        report.put(prefix + "P95Ms", round(percentileMs(rts, 95)));
        report.put(prefix + "P99Ms", round(percentileMs(rts, 99)));
    }

    private static double percentileMs(List<Long> rts, double percentile) {
        if (rts.isEmpty()) {
            return 0;
        }
        long[] sorted = rts.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private static List<String> gatewayArgs(int authPort, int seckillPort, int orderPort) {
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
                "--spring.cloud.gateway.routes[0].uri=http://localhost:" + authPort,
                "--spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**",
                "--spring.cloud.gateway.routes[1].id=seckill-service",
                "--spring.cloud.gateway.routes[1].uri=http://localhost:" + seckillPort,
                "--spring.cloud.gateway.routes[1].predicates[0]=Path=/api/v1/seckill/**",
                "--spring.cloud.gateway.routes[1].filters[0].name=RequestRateLimiter",
                "--spring.cloud.gateway.routes[1].filters[0].args.key-resolver=#{@rateLimitKeyResolver}",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.replenishRate=10000",
                "--spring.cloud.gateway.routes[1].filters[0].args.redis-rate-limiter.burstCapacity=20000",
                "--spring.cloud.gateway.routes[2].id=order-service",
                "--spring.cloud.gateway.routes[2].uri=http://localhost:" + orderPort,
                "--spring.cloud.gateway.routes[2].predicates[0]=Path=/api/v1/orders/**",
                "--spring.cloud.gateway.routes[3].id=payment-service",
                "--spring.cloud.gateway.routes[3].uri=http://localhost:1",
                "--spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**");
    }

    private static void seedLoadUsers() throws Exception {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TOTAL_USERS));
        StringBuilder sql = new StringBuilder(
                "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ");
        for (int i = 0; i < TOTAL_USERS; i++) {
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

    private static void writeReport(Map<String, Object> report) throws Exception {
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        String scenario = String.valueOf(report.get("scenario"));
        java.nio.file.Files.writeString(directory.resolve(scenario + ".json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        StringBuilder csv = new StringBuilder(
                "scenario,total,success,failed,successRate,qps,durationMs,"
                        + "taskAvgMs,taskP50Ms,taskP95Ms,taskP99Ms,"
                        + "loginAvgMs,loginP50Ms,loginP95Ms,loginP99Ms,"
                        + "executeAvgMs,executeP50Ms,executeP95Ms,executeP99Ms,"
                        + "directAvgMs,directP50Ms,directP95Ms,directP99Ms,"
                        + "gatewayOverheadP50,gatewayOverheadP95,gatewayOverheadP99,timestamp");
        csv.append(System.lineSeparator()).append(String.join(",",
                scenario,
                String.valueOf(report.get("total")),
                String.valueOf(report.get("success")),
                String.valueOf(report.get("failed")),
                String.valueOf(report.get("successRate")),
                String.valueOf(report.get("qps")),
                String.valueOf(report.get("durationMs")),
                String.valueOf(report.getOrDefault("taskAvgMs", "")),
                String.valueOf(report.getOrDefault("taskP50Ms", "")),
                String.valueOf(report.getOrDefault("taskP95Ms", "")),
                String.valueOf(report.getOrDefault("taskP99Ms", "")),
                String.valueOf(report.getOrDefault("loginAvgMs", "")),
                String.valueOf(report.getOrDefault("loginP50Ms", "")),
                String.valueOf(report.getOrDefault("loginP95Ms", "")),
                String.valueOf(report.getOrDefault("loginP99Ms", "")),
                String.valueOf(report.getOrDefault("executeAvgMs", "")),
                String.valueOf(report.getOrDefault("executeP50Ms", "")),
                String.valueOf(report.getOrDefault("executeP95Ms", "")),
                String.valueOf(report.getOrDefault("executeP99Ms", "")),
                String.valueOf(report.getOrDefault("directAvgMs", "")),
                String.valueOf(report.getOrDefault("directP50Ms", "")),
                String.valueOf(report.getOrDefault("directP95Ms", "")),
                String.valueOf(report.getOrDefault("directP99Ms", "")),
                String.valueOf(report.getOrDefault("gatewayOverheadP50", "")),
                String.valueOf(report.getOrDefault("gatewayOverheadP95", "")),
                String.valueOf(report.getOrDefault("gatewayOverheadP99", "")),
                String.valueOf(report.get("timestamp"))));
        java.nio.file.Files.writeString(directory.resolve(scenario + ".csv"), csv.toString());
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + TOTAL_USERS));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TOTAL_USERS));
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
