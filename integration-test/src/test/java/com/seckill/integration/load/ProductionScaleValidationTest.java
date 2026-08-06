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
 * Phase 6.10 Task 3 L-08 Production Scale Validation：
 * 隔离生产拓扑全链路 50000 成功目标（默认，-Dl08.success-target 可调；100000 建议档位）。
 * 验证零超卖 / 不变量 / deadlock=0 / MQ 收敛 backlog=0 / 重复消息幂等 / CANCEL RECOVER；
 * 单机环境限制如实写入报告（environment limited），禁止虚报生产容量。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class ProductionScaleValidationTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ProductionScaleValidationTest.class);

    private static final int DEFAULT_TARGET = 50000;
    private static final String PASSWORD = "Test@123";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_300_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 8_600_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static int TARGET;
    private static int STOCK;
    private static Map<String, IsolatedTopology.RunningProcess> TOPOLOGY;
    private static String gatewayBaseUrl = "http://localhost:" + IsolatedTopology.GATEWAY_PORT;
    private static String[] TOKENS;
    private static final List<String> SUCCESS_ORDER_IDS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();

    @BeforeAll
    static void startTopology() throws Exception {
        TARGET = Integer.getInteger("l08.success-target", DEFAULT_TARGET);
        STOCK = TARGET * 2;
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        TOPOLOGY = IsolatedTopology.startAll();
        seedLoadUsers();
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        TOKENS = loginAll();
        log.info("L-08 topology ready, target={}, users={}", TARGET, TOKENS.length);
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
    void l08_productionScaleValidation() throws Exception {
        int concurrency = Math.min(TARGET, 200);
        AtomicInteger success = new AtomicInteger();
        Map<String, Integer> codes = new ConcurrentHashMap<>();
        long deadlockBefore = mysqlDelta("Innodb_deadlocks");
        long loadStart = System.nanoTime();

        SustainedLoadExecutor.run(concurrency, Duration.ofMinutes(90), index -> {
            if (success.get() >= TARGET) {
                return false;
            }
            int userIndex = REQUEST_COUNTER.getAndIncrement() % TARGET;
            long userId = BASE_USER + userIndex;
            try {
                Result<ExecuteResponse> response = TestHttp.executeWithAuth(
                        gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                        "l08-" + RUN_ID + "-" + System.nanoTime(), TOKENS[userIndex]);
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
        await().atMost(Duration.ofMinutes(30)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(TARGET);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(TARGET);
        });
        long convergeMs = (System.nanoTime() - convergeStart) / 1_000_000;

        // 零超卖 + 不变量 + 分桶汇总
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - TARGET));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK - TARGET);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(TARGET);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);

        long deadlocks = mysqlDelta("Innodb_deadlocks") - deadlockBefore;
        assertThat(deadlocks).isZero();

        // 重复消息安全：抽样 1 单重发
        String orderId = SUCCESS_ORDER_IDS.get(0);
        String messageId = queryString("SELECT message_id FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE order_id='" + orderId + "'");
        sendCreate(TestHttp.createOrderMessageJson(messageId, BASE_USER, SESSION_ID,
                SKU_ID, orderId, 1, 9900L, "l08-dup-" + RUN_ID));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'")).isEqualTo(1));

        // 取消恢复：抽样 50 单
        int sample = Math.min(50, TARGET);
        for (int i = 0; i < sample; i++) {
            String targetOrder = SUCCESS_ORDER_IDS.get(i);
            sendCancel(TestHttp.cancelOrderMessageJson("l08-cancel-" + targetOrder,
                    targetOrder, BASE_USER, SESSION_ID, SKU_ID, 1, "CANCEL"));
        }
        await().atMost(Duration.ofSeconds(180)).untilAsserted(() ->
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                        + "WHERE change_type='RECOVER' AND sku_id=" + SKU_ID)).isEqualTo(sample));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "L-08");
        report.put("target", TARGET);
        report.put("stock", STOCK);
        report.put("success", TARGET);
        report.put("zeroOversell", true);
        report.put("deadlocks", deadlocks);
        report.put("loadDurationMs", loadNanos / 1_000_000);
        report.put("produceQps", round(TARGET / (loadNanos / 1_000_000_000.0)));
        report.put("consumeConvergeMs", convergeMs);
        report.put("mqBacklogZero", true);
        report.put("duplicateSafe", true);
        report.put("recoverSample", sample);
        report.put("dlqCheck", "monitoring-side（backlog=0 且消费无重试耗尽；生产由 rocketmq_dlq_total 确认）");
        report.put("environmentLimitation",
                "single-machine isolated topology; produceQps 受压测客户端限制，不作为生产容量结论");
        report.put("codeDistribution", new TreeMap<>(codes));
        writeReport(report);
        log.info("L-08 done: success={}, loadMs={}, convergeMs={}, deadlocks={}",
                TARGET, loadNanos / 1_000_000, convergeMs, deadlocks);
    }

    private static long mysqlDelta(String name) throws Exception {
        String value = queryString("SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME='" + name + "'");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static String[] loginAll() throws Exception {
        String[] tokens = new String[TARGET];
        for (int i = 0; i < TARGET; i++) {
            Result<LoginResponse> login = TestHttp.login(gatewayBaseUrl,
                    "l08user" + (BASE_USER + i), PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("L-08 login failed at " + i);
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
                        .append(",'l08user").append(BASE_USER + i)
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
        java.nio.file.Files.writeString(directory.resolve("L-08.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        java.nio.file.Files.writeString(directory.resolve("L-08.csv"),
                "scenario,target,success,zeroOversell,deadlocks,loadDurationMs,produceQps,"
                        + "consumeConvergeMs,mqBacklogZero,duplicateSafe,recoverSample"
                        + System.lineSeparator()
                        + String.join(",",
                        String.valueOf(report.get("scenario")),
                        String.valueOf(report.get("target")),
                        String.valueOf(report.get("success")),
                        String.valueOf(report.get("zeroOversell")),
                        String.valueOf(report.get("deadlocks")),
                        String.valueOf(report.get("loadDurationMs")),
                        String.valueOf(report.get("produceQps")),
                        String.valueOf(report.get("consumeConvergeMs")),
                        String.valueOf(report.get("mqBacklogZero")),
                        String.valueOf(report.get("duplicateSafe")),
                        String.valueOf(report.get("recoverSample"))));
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
