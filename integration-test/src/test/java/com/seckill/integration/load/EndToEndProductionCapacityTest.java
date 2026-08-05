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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.4 L-07 Production Capacity（端到端）：
 * 隔离拓扑全链路 execute → Gateway → seckill(Redis Lua) → MQ → order → inventory，
 * 达到成功目标量（默认 10000；50k/100k 通过 -Dl07.success-target 配置）。
 * 验证零超卖、MQ 收敛（backlog=0）、重复安全、取消恢复。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class EndToEndProductionCapacityTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(EndToEndProductionCapacityTest.class);

    private static final int DEFAULT_TARGET = 10000;
    private static final String PASSWORD = "Test@123";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_500_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 8_900_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static int TARGET;
    private static int STOCK;
    private static Map<String, IsolatedTopology.RunningProcess> TOPOLOGY;
    private static String gatewayBaseUrl = "http://localhost:" + IsolatedTopology.GATEWAY_PORT;
    private static String[] TOKENS;
    private static final List<String> SUCCESS_ORDER_IDS = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicInteger REQUEST_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeAll
    static void startTopology() throws Exception {
        TARGET = Integer.getInteger("l07.success-target", DEFAULT_TARGET);
        STOCK = TARGET * 2;
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        TOPOLOGY = IsolatedTopology.startAll();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        TOKENS = loginAll();
        log.info("L-07 topology ready, target={}, users={}", TARGET, TOKENS.length);
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
    void l07_productionCapacity() throws Exception {
        int concurrency = Math.min(TARGET, 500);
        AtomicInteger success = new AtomicInteger();
        Map<String, Integer> codes = new ConcurrentHashMap<>();
        long mysqlBefore = mysqlDelta("Innodb_row_lock_waits");
        long deadlockBefore = mysqlDelta("Innodb_deadlocks");
        long commandsBefore = redisCommandsProcessed();
        long loadStart = System.nanoTime();

        SustainedLoadExecutor.run(concurrency, Duration.ofMinutes(30), index -> {
            if (success.get() >= TARGET) {
                return false;
            }
            int userIndex = REQUEST_COUNTER.getAndIncrement() % TARGET;
            long userId = BASE_USER + userIndex;
            Result<ExecuteResponse> response;
            try {
                response = TestHttp.executeWithAuth(gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                        "l07-" + RUN_ID + "-" + System.nanoTime(), TOKENS[userIndex]);
            } catch (Exception e) {
                codes.merge("EXCEPTION", 1, Integer::sum);
                return false;
            }
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
        });
        long loadNanos = System.nanoTime() - loadStart;

        int finalSuccess = success.get();
        assertThat(finalSuccess).isEqualTo(TARGET);
        assertThat(finalSuccess).isLessThanOrEqualTo(STOCK);

        // MQ 收敛（backlog=0）
        long convergeStart = System.nanoTime();
        await().atMost(Duration.ofMinutes(15)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(finalSuccess);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(finalSuccess);
        });
        long convergeMs = (System.nanoTime() - convergeStart) / 1_000_000;
        long commandsAfter = redisCommandsProcessed();

        // 零超卖 + 库存不变量
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - finalSuccess));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK - finalSuccess);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(finalSuccess);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);

        // 重复消息安全：抽样一个成功订单重发 CREATE_ORDER
        String orderId = SUCCESS_ORDER_IDS.get(0);
        String messageId = queryString("SELECT message_id FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE order_id='" + orderId + "'");
        String duplicateBody = TestHttp.createOrderMessageJson(messageId, BASE_USER, SESSION_ID,
                SKU_ID, orderId, 1, 9900L, "l07-dup-" + RUN_ID);
        sendCreate(duplicateBody);
        sendCreate(duplicateBody);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'")).isEqualTo(1));

        // 取消恢复：抽样 100 单发送 CANCEL_ORDER
        int sample = Math.min(100, finalSuccess);
        for (int i = 0; i < sample; i++) {
            String targetOrder = SUCCESS_ORDER_IDS.get(i);
            String cancelBody = TestHttp.cancelOrderMessageJson("l07-cancel-" + targetOrder,
                    targetOrder, BASE_USER, SESSION_ID, SKU_ID, 1, "CANCEL");
            sendCancel(cancelBody);
        }
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE change_type='RECOVER' AND sku_id=" + SKU_ID)).isEqualTo(sample));
        assertThat(redisGet("seckill:stock:" + SKU_ID))
                .isEqualTo(String.valueOf(STOCK - finalSuccess + sample));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "L-07");
        report.put("target", finalSuccess);
        report.put("stock", STOCK);
        report.put("totalRequests", finalSuccess + codes.values().stream().mapToInt(Integer::intValue).sum() - finalSuccess);
        report.put("success", finalSuccess);
        report.put("zeroOversell", true);
        report.put("loadDurationMs", loadNanos / 1_000_000);
        report.put("produceQps", round(finalSuccess / (loadNanos / 1_000_000_000.0)));
        report.put("consumeConvergeMs", convergeMs);
        report.put("consumeQps", round(finalSuccess / (convergeMs / 1000.0)));
        report.put("rowLockWaitsDelta", mysqlDelta("Innodb_row_lock_waits") - mysqlBefore);
        report.put("deadlocksDelta", mysqlDelta("Innodb_deadlocks") - deadlockBefore);
        report.put("redisCommandsDelta", commandsAfter - commandsBefore);
        report.put("duplicateSafe", true);
        report.put("recoverSample", sample);
        report.put("codeDistribution", new java.util.TreeMap<>(codes));
        writeReport(report);
        log.info("L-07 done: success={}, loadMs={}, produceQps={}, convergeMs={}, consumeQps={}, "
                        + "rowLockWaitsDelta={}, deadlocksDelta={}, redisCommandsDelta={}",
                finalSuccess, loadNanos / 1_000_000,
                String.format("%.2f", finalSuccess / (loadNanos / 1_000_000_000.0)), convergeMs,
                String.format("%.2f", finalSuccess / (convergeMs / 1000.0)),
                mysqlDelta("Innodb_row_lock_waits") - mysqlBefore,
                mysqlDelta("Innodb_deadlocks") - deadlockBefore, commandsAfter - commandsBefore);
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
                    "loaduser" + (BASE_USER + i), PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("l07 login failed at " + i);
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
                        .append(",'loaduser").append(BASE_USER + i)
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
        java.nio.file.Files.writeString(directory.resolve("L-07.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        StringBuilder csv = new StringBuilder(
                "scenario,target,stock,success,loadDurationMs,produceQps,consumeConvergeMs,consumeQps,"
                        + "rowLockWaitsDelta,deadlocksDelta,redisCommandsDelta,duplicateSafe,recoverSample"
                        + System.lineSeparator());
        csv.append(String.join(",",
                "L-07",
                String.valueOf(report.get("target")),
                String.valueOf(report.get("stock")),
                String.valueOf(report.get("success")),
                String.valueOf(report.get("loadDurationMs")),
                String.valueOf(report.get("produceQps")),
                String.valueOf(report.get("consumeConvergeMs")),
                String.valueOf(report.get("consumeQps")),
                String.valueOf(report.get("rowLockWaitsDelta")),
                String.valueOf(report.get("deadlocksDelta")),
                String.valueOf(report.get("redisCommandsDelta")),
                String.valueOf(report.get("duplicateSafe")),
                String.valueOf(report.get("recoverSample")))).append(System.lineSeparator());
        java.nio.file.Files.writeString(directory.resolve("L-07.csv"), csv.toString());
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
