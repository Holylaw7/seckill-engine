package com.seckill.integration.load;

import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.IsolatedTopology;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.seckill.dto.ExecuteResponse;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.4 G-09 Gateway Production Capacity：
 * 隔离拓扑（Gateway/auth/seckill/order/inventory 各自独立 JVM + 独立中间件容器），
 * 并发 100/200/500/1000/2000/5000/10000 每档持续压测（时长可配，默认 60s，生产协议 600s），
 * 输出 QPS / p50/p95/p99 / error rate / 429 rate / 资源 / Redis 延迟，并标定容量拐点。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class GatewayProductionCapacityBenchmark extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(GatewayProductionCapacityBenchmark.class);

    private static final int[] DEFAULT_LEVELS = {100, 200, 500, 1000, 2000, 5000, 10000};
    private static final int STOCK = 200000;
    private static final String PASSWORD = "Test@123";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_400_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_000_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static Map<String, IsolatedTopology.RunningProcess> TOPOLOGY;
    private static String gatewayBaseUrl = "http://localhost:" + IsolatedTopology.GATEWAY_PORT;
    private static String[] TOKENS;
    private static int MAX_USERS;

    @BeforeAll
    static void startTopology() throws Exception {
        MAX_USERS = Integer.getInteger("gateway.perf.users", 10000);
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        TOPOLOGY = IsolatedTopology.startAll();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        TOKENS = loginAll();
        log.info("G-09 topology ready, tokens={}", TOKENS.length);
    }

    @AfterAll
    static void tearDown() {
        IsolatedTopology.stopAll(TOPOLOGY);
        try {
            cleanupNamespace();
        } catch (Exception ignored) {
            // 清理失败不阻塞
        }
    }

    @Test
    void g09_gatewayProductionCapacity() throws Exception {
        int[] levels = levels();
        Duration duration = Duration.ofSeconds(
                Integer.getInteger("gateway.perf.duration-seconds", 60));
        List<Map<String, Object>> reports = new ArrayList<>();
        String inflection = null;
        for (int concurrency : levels) {
            Map<String, Integer> codes = new ConcurrentHashMap<>();
            long redisCommandsBefore = redisCommandsProcessed();
            long redisLatencyBeforeNanos = System.nanoTime();
            ResourceMonitor.Sample resourceSample;
            SustainedLoadExecutor.Result result;
            try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(2),
                    MYSQL.getContainerId(), REDIS.getContainerId(), ROCKETMQ.getContainerId())) {
                result = SustainedLoadExecutor.run(concurrency, duration, index -> {
                    int userIndex = (int) (Math.random() * MAX_USERS);
                    long userId = BASE_USER + userIndex;
                    Result<ExecuteResponse> response;
                    try {
                        response = TestHttp.executeWithAuth(gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                                "g09-" + RUN_ID + "-" + System.nanoTime(), TOKENS[userIndex]);
                    } catch (Exception e) {
                        codes.merge("EXCEPTION", 1, Integer::sum);
                        return false;
                    }
                    int code = response == null ? -1 : response.getCode();
                    codes.merge(String.valueOf(code), 1, Integer::sum);
                    // 网关容量口径：HTTP 200（任意业务码，含 REPEAT_BUY/STOCK_EMPTY）即网关成功处理
                    return response != null;
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
                resourceSample = monitor.latest();
            }
            long redisLatencyNanos = System.nanoTime() - redisLatencyBeforeNanos;
            long redisCommandsAfter = redisCommandsProcessed();

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("concurrency", concurrency);
            report.put("durationSeconds", duration.toSeconds());
            report.put("total", result.totalRequests());
            report.put("qps", round(result.qps()));
            report.put("p50Ms", round(percentileMs(result.latenciesNanos(), 50)));
            report.put("p95Ms", round(percentileMs(result.latenciesNanos(), 95)));
            report.put("p99Ms", round(percentileMs(result.latenciesNanos(), 99)));
            report.put("errorRate", round(result.failureCount() * 100.0 / Math.max(1, result.totalRequests())));
            report.put("codeDistribution", new TreeMap<>(codes));
            report.put("redisCommandsDelta", redisCommandsAfter - redisCommandsBefore);
            report.put("redisCommandsPerSec", round((redisCommandsAfter - redisCommandsBefore)
                    / Math.max(1, duration.toSeconds())));
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
            double errorRate = (double) report.get("errorRate");
            double p99 = (double) report.get("p99Ms");
            log.info("G-09 c{}: qps={}, p50={}ms, p95={}ms, p99={}ms, errorRate={}%, codes={}",
                    concurrency, String.format("%.2f", result.qps()),
                    String.format("%.2f", percentileMs(result.latenciesNanos(), 50)),
                    String.format("%.2f", percentileMs(result.latenciesNanos(), 95)),
                    String.format("%.2f", p99), String.format("%.2f", errorRate), codes);
            if (inflection == null && (errorRate > 0.1 || p99 > 150.0)) {
                inflection = "c" + concurrency + " (errorRate=" + String.format("%.2f", errorRate)
                        + "%, p99=" + String.format("%.2f", p99) + "ms)";
            }
        }
        writeReport(reports, inflection);
        assertThat(reports).isNotEmpty();
    }

    private static int[] levels() {
        String prop = System.getProperty("gateway.perf.levels");
        if (prop == null || prop.isBlank()) {
            return DEFAULT_LEVELS;
        }
        String[] parts = prop.split(",");
        int[] levels = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            levels[i] = Integer.parseInt(parts[i].trim());
        }
        return levels;
    }

    private static String[] loginAll() throws Exception {
        String[] tokens = new String[MAX_USERS];
        for (int i = 0; i < MAX_USERS; i++) {
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl,
                    "loaduser" + (BASE_USER + i), PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("isolated login failed at " + i);
            }
            tokens[i] = login.getData().getToken();
        }
        return tokens;
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

    private static long redisCommandsProcessed() {
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> connection = redisConnection()) {
            RedisCommands<String, String> commands = connection.sync();
            String info = commands.info("stats");
            for (String line : info.split("\\r?\\n")) {
                if (line.startsWith("total_commands_processed:")) {
                    return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static double percentileMs(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    private static void writeReport(List<Map<String, Object>> reports, String inflection) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", "G-09");
        root.put("topology", "isolated-jvm");
        root.put("capacityInflection", inflection == null ? "none-within-tested-range" : inflection);
        root.put("scenarios", reports);
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("gateway-capacity-report.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        StringBuilder csv = new StringBuilder(
                "scenario,concurrency,durationSeconds,total,qps,p50Ms,p95Ms,p99Ms,errorRate,"
                        + "redisCommandsPerSec,cpuPercent,heapUsedBytes,gcCount,gcTimeMs"
                        + System.lineSeparator());
        for (Map<String, Object> report : reports) {
            Map<String, Object> resource = (Map<String, Object>) report.getOrDefault("resource", Map.of());
            csv.append(String.join(",",
                    "G-09",
                    String.valueOf(report.get("concurrency")),
                    String.valueOf(report.get("durationSeconds")),
                    String.valueOf(report.get("total")),
                    String.valueOf(report.get("qps")),
                    String.valueOf(report.get("p50Ms")),
                    String.valueOf(report.get("p95Ms")),
                    String.valueOf(report.get("p99Ms")),
                    String.valueOf(report.get("errorRate")),
                    String.valueOf(report.get("redisCommandsPerSec")),
                    String.valueOf(resource.getOrDefault("cpuPercent", "")),
                    String.valueOf(resource.getOrDefault("heapUsedBytes", "")),
                    String.valueOf(resource.getOrDefault("gcCount", "")),
                    String.valueOf(resource.getOrDefault("gcTimeMs", "")))).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(directory.resolve("gateway-capacity-report.csv"), csv.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void cleanupNamespace() throws Exception {
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
