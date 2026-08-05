package com.seckill.integration.load;

import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.IsolatedTopology;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.7 Task 5 Production Traffic Simulation：
 * <ul>
 *   <li>Level 1：默认 5000 成功目标（零超卖 / deadlock=0 / duplicate=0 / recover PASS）；</li>
 *   <li>Level 2（50000）/ Level 3（100000）：协议就绪，单机环境默认标记 protocol-ready；
 *       独立环境执行时加 -Dtraffic.sim.run-large=true 实跑。</li>
 * </ul>
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class ProductionTrafficSimulationIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ProductionTrafficSimulationIT.class);

    private static final int[] LEVEL_TARGETS = {5000, 50000, 100000};
    private static final String PASSWORD = "Test@123";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_100_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 8_800_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static int LEVEL;
    private static int TARGET;
    private static int STOCK;
    private static Map<String, IsolatedTopology.RunningProcess> TOPOLOGY;
    private static String gatewayBaseUrl = "http://localhost:" + IsolatedTopology.GATEWAY_PORT;
    private static String[] TOKENS;
    private static final List<String> SUCCESS_ORDER_IDS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startTopology() throws Exception {
        LEVEL = Integer.getInteger("traffic.sim.level", 1);
        if (LEVEL < 1 || LEVEL > 3) {
            throw new IllegalArgumentException("traffic.sim.level must be 1/2/3");
        }
        TARGET = LEVEL_TARGETS[LEVEL - 1];
        STOCK = TARGET * 2;
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        TOPOLOGY = IsolatedTopology.startAll();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        TOKENS = loginAll();
        log.info("traffic-sim topology ready, level={}, target={}, users={}",
                LEVEL, TARGET, TOKENS.length);
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
    void productionTrafficSimulation() throws Exception {
        boolean runLarge = Boolean.getBoolean("traffic.sim.run-large");
        if (LEVEL >= 2 && !runLarge) {
            // 单机环境限制：Level 2/3 标记协议就绪，禁止虚报生产容量
            Map<String, Object> protocol = new LinkedHashMap<>();
            protocol.put("scenario", "TRAFFIC-SIM-L" + LEVEL);
            protocol.put("level", LEVEL);
            protocol.put("target", TARGET);
            protocol.put("protocolReady", true);
            protocol.put("executed", false);
            protocol.put("environmentLimitation",
                    "single-machine shared host; run with -Dtraffic.sim.run-large=true in isolated environment");
            writeReport(protocol);
            log.info("traffic-sim L{} protocol-ready (not executed on single machine)", LEVEL);
            return;
        }

        int concurrency = Math.min(TARGET, 300);
        AtomicInteger success = new AtomicInteger();
        Map<String, Integer> codes = new ConcurrentHashMap<>();
        long mysqlBefore = mysqlDelta("Innodb_row_lock_waits");
        long deadlockBefore = mysqlDelta("Innodb_deadlocks");
        long commandsBefore = redisCommandsProcessed();
        long loadStart = System.nanoTime();

        SustainedLoadExecutor.run(concurrency, Duration.ofMinutes(8), index -> {
            if (success.get() >= TARGET) {
                return false;
            }
            int userIndex = REQUEST_COUNTER.getAndIncrement() % TARGET;
            long userId = BASE_USER + userIndex;
            try {
                Result<ExecuteResponse> response = TestHttp.executeWithAuth(
                        gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                        "sim-" + RUN_ID + "-" + System.nanoTime(), TOKENS[userIndex]);
                int code = response == null ? -1 : response.getCode();
                codes.merge(String.valueOf(code), 1, Integer::sum);
                if (code == 0) {
                    int now = success.incrementAndGet();
                    if (now <= TARGET) {
                        SUCCESS_ORDER_IDS.add(response.getData().getOrderId());
                    }
                    return true;
                }
                return false;
            } catch (Exception e) {
                codes.merge("EXCEPTION", 1, Integer::sum);
                return false;
            }
        });
        long loadNanos = System.nanoTime() - loadStart;

        assertThat(success.get()).isEqualTo(TARGET);
        assertThat(success.get()).isLessThanOrEqualTo(STOCK);

        // MQ 收敛：订单数 == DEDUCT 流水数（backlog=0）
        long convergeStart = System.nanoTime();
        await().atMost(Duration.ofMinutes(15)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(TARGET);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(TARGET);
        });
        long convergeMs = (System.nanoTime() - convergeStart) / 1_000_000;
        long commandsAfter = redisCommandsProcessed();

        // 一致性：零超卖 + 不变量
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - TARGET));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK - TARGET);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(TARGET);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);

        // 重复消息安全：抽样 1 单重发
        String orderId = SUCCESS_ORDER_IDS.get(0);
        String messageId = queryString("SELECT message_id FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE order_id='" + orderId + "'");
        sendCreate(TestHttp.createOrderMessageJson(messageId, BASE_USER, SESSION_ID,
                SKU_ID, orderId, 1, 9900L, "sim-dup-" + RUN_ID));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'")).isEqualTo(1));

        // 取消恢复：抽样 50 单
        int sample = Math.min(50, TARGET);
        for (int i = 0; i < sample; i++) {
            String targetOrder = SUCCESS_ORDER_IDS.get(i);
            sendCancel(TestHttp.cancelOrderMessageJson("sim-cancel-" + targetOrder,
                    targetOrder, BASE_USER, SESSION_ID, SKU_ID, 1, "CANCEL"));
        }
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE change_type='RECOVER' AND sku_id=" + SKU_ID)).isEqualTo(sample));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "TRAFFIC-SIM-L" + LEVEL);
        report.put("level", LEVEL);
        report.put("target", TARGET);
        report.put("stock", STOCK);
        report.put("success", TARGET);
        report.put("zeroOversell", true);
        report.put("deadlocksDelta", mysqlDelta("Innodb_deadlocks") - deadlockBefore);
        report.put("rowLockWaitsDelta", mysqlDelta("Innodb_row_lock_waits") - mysqlBefore);
        report.put("loadDurationMs", loadNanos / 1_000_000);
        report.put("produceQps", round(TARGET / (loadNanos / 1_000_000_000.0)));
        report.put("consumeConvergeMs", convergeMs);
        report.put("redisCommandsDelta", commandsAfter - commandsBefore);
        report.put("duplicateSafe", true);
        report.put("recoverSample", sample);
        report.put("protocolReady", true);
        report.put("executed", true);
        report.put("codeDistribution", new TreeMap<>(codes));
        writeReport(report);
        log.info("traffic-sim L{} done: success={}, loadMs={}, convergeMs={}, deadlocks={}",
                LEVEL, TARGET, loadNanos / 1_000_000, convergeMs,
                mysqlDelta("Innodb_deadlocks") - deadlockBefore);
    }

    private static long mysqlDelta(String name) throws Exception {
        String value = queryString("SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME='" + name + "'");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static long redisCommandsProcessed() {
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> connection = redisConnection()) {
            String info = connection.sync().info("stats");
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

    private static String[] loginAll() throws Exception {
        String[] tokens = new String[TARGET];
        for (int i = 0; i < TARGET; i++) {
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl,
                    "simuser" + (BASE_USER + i), PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("traffic-sim login failed at " + i);
            }
            tokens[i] = login.getData().getToken();
        }
        return tokens;
    }

    private static void seedLoadUsers() throws Exception {
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TARGET));
        int batch = 1000;
        for (int start = 0; start < TARGET; start += batch) {
            int end = Math.min(start + batch, TARGET);
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO seckill_auth.`user` (id, username, password_hash, status, roles) VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sql.append(',');
                }
                sql.append('(').append(BASE_USER + i)
                        .append(",'simuser").append(BASE_USER + i)
                        .append("','").append(hash)
                        .append("',1,'USER')");
            }
            execute(sql.toString());
        }
    }

    private static void sendCreate(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }

    private static void sendCancel(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", body);
    }

    private static void writeReport(Map<String, Object> report) throws Exception {
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("traffic-simulation-report.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        java.nio.file.Files.writeString(directory.resolve("traffic-simulation-report.csv"),
                "scenario,level,target,success,zeroOversell,deadlocksDelta,rowLockWaitsDelta,"
                        + "loadDurationMs,produceQps,consumeConvergeMs,duplicateSafe,recoverSample,executed"
                        + System.lineSeparator()
                        + String.join(",",
                        String.valueOf(report.get("scenario")),
                        String.valueOf(report.get("level")),
                        String.valueOf(report.get("target")),
                        String.valueOf(report.get("success")),
                        String.valueOf(report.get("zeroOversell")),
                        String.valueOf(report.get("deadlocksDelta")),
                        String.valueOf(report.get("rowLockWaitsDelta")),
                        String.valueOf(report.get("loadDurationMs")),
                        String.valueOf(report.get("produceQps")),
                        String.valueOf(report.get("consumeConvergeMs")),
                        String.valueOf(report.get("duplicateSafe")),
                        String.valueOf(report.get("recoverSample")),
                        String.valueOf(report.get("executed"))));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + TARGET));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        execute("DELETE FROM seckill_auth.`user` WHERE id >= " + BASE_USER
                + " AND id < " + (BASE_USER + TARGET));
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
