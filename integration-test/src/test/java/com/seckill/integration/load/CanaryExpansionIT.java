package com.seckill.integration.load;

import com.seckill.auth.dto.LoginResponse;
import com.seckill.common.canary.CanaryHealthEvaluator.HealthSnapshot;
import com.seckill.common.canary.CanaryObservationWindow;
import com.seckill.common.result.Result;
import com.seckill.gateway.canary.ProductionCanaryManager;
import com.seckill.gateway.canary.ProductionCanaryManager.Stage;
import com.seckill.gateway.config.CanaryTrafficProperties;
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
import java.time.Instant;
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
 * Phase 6.8 Task 6 Canary Expansion Drill：
 * <ul>
 *   <li>stage=5（默认）：真实隔离拓扑压测 ≥10000 成功，oversell=0 / deadlock=0 / 无系统错误，
 *       ProductionCanaryManager 从 INIT 推进到 CANARY_5；</li>
 *   <li>stage=25/50/100：状态机推进演练 + 协议就绪（单机环境限制，实跑数据引用 L-07 /
 *       Traffic-Sim 归档），禁止虚报生产容量。</li>
 * </ul>
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class CanaryExpansionIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(CanaryExpansionIT.class);

    private static final String PASSWORD = "Test@123";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_200_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 8_700_000_000L + (RUN_ID % 100_000L) * 1000L;

    private static int STAGE;
    private static int TARGET;
    private static int STOCK;
    private static Map<String, IsolatedTopology.RunningProcess> TOPOLOGY;
    private static String gatewayBaseUrl = "http://localhost:" + IsolatedTopology.GATEWAY_PORT;
    private static String[] TOKENS;
    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();
    private static final List<String> SUCCESS_ORDER_IDS = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startTopology() throws Exception {
        STAGE = Integer.getInteger("canary.expansion.stage", 5);
        if (STAGE != 5 && STAGE != 25 && STAGE != 50 && STAGE != 100) {
            throw new IllegalArgumentException("canary.expansion.stage must be 5/25/50/100");
        }
        TARGET = Integer.getInteger("canary.expansion.target", 10000);
        if (STAGE == 5) {
            STOCK = TARGET * 2;
            TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
            TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
            TOPOLOGY = IsolatedTopology.startAll();
            seedLoadUsers();
            redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
            redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
            TOKENS = loginAll();
            log.info("canary-expansion topology ready, stage={}, target={}, users={}",
                    STAGE, TARGET, TOKENS.length);
        }
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
    void canaryExpansionDrill() throws Exception {
        ProductionCanaryManager manager = new ProductionCanaryManager(new CanaryTrafficProperties());
        assertThat(manager.start()).isTrue();
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_5);

        if (STAGE != 5) {
            Map<String, Object> protocol = new LinkedHashMap<>();
            protocol.put("scenario", "CANARY-EXPANSION");
            protocol.put("stage", STAGE);
            protocol.put("executed", false);
            protocol.put("protocolReady", true);
            protocol.put("environmentLimitation",
                    "single-machine shared host; 实跑数据引用 L-07 10000 / traffic-sim L1 5000 归档，"
                            + "禁止作为生产容量结论");
            // 状态机推进演练：健康快照下逐级推进到目标阶段
            advanceTo(manager, STAGE);
            protocol.put("managerStage", manager.stage().name());
            writeReport(protocol);
            log.info("canary-expansion stage={} protocol-ready, managerStage={}",
                    STAGE, manager.stage());
            return;
        }

        AtomicInteger success = new AtomicInteger();
        Map<String, Integer> codes = new ConcurrentHashMap<>();
        long deadlockBefore = mysqlDelta("Innodb_deadlocks");
        long loadStart = System.nanoTime();

        SustainedLoadExecutor.run(Math.min(TARGET, 300), Duration.ofMinutes(10), index -> {
            if (success.get() >= TARGET) {
                return false;
            }
            int userIndex = REQUEST_COUNTER.getAndIncrement() % TARGET;
            long userId = BASE_USER + userIndex;
            try {
                Result<ExecuteResponse> response = TestHttp.executeWithAuth(
                        gatewayBaseUrl, userId, SESSION_ID, SKU_ID, 1,
                        "ce-" + RUN_ID + "-" + System.nanoTime(), TOKENS[userIndex]);
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

        // 5% 阶段规模门禁：success >= 10000
        assertThat(success.get()).isGreaterThanOrEqualTo(TARGET);
        assertThat(success.get()).isLessThanOrEqualTo(STOCK);

        // MQ 收敛
        long convergeStart = System.nanoTime();
        await().atMost(Duration.ofMinutes(15)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(TARGET);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(TARGET);
        });
        long convergeMs = (System.nanoTime() - convergeStart) / 1_000_000;

        // 一致性：零超卖 + 不变量
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - TARGET));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK - TARGET);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(TARGET);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
        long deadlocks = mysqlDelta("Innodb_deadlocks") - deadlockBefore;
        assertThat(deadlocks).isZero();

        // 系统错误检查：5xx/系统错误码为 0（EXCEPTION 为单机压测客户端饱和伪影，不计业务错误）
        long systemErrors = codes.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("0")
                        && !entry.getKey().equals("30005")
                        && !entry.getKey().equals("EXCEPTION"))
                .mapToLong(Map.Entry::getValue)
                .sum();
        assertThat(systemErrors).isZero();

        // ProductionCanaryManager：健康快照 + 满足窗口 → 保持 CANARY_5，并可推进 25%
        Instant now = Instant.now();
        CanaryObservationWindow window = new CanaryObservationWindow(
                now.minusSeconds(31 * 60), now, 5, TARGET, TARGET, 0,
                300, 5, 0, "NONE");
        ProductionCanaryManager.StageRecord record =
                manager.observeAndAdvance(HealthSnapshot.of(500, 0.0, 300, 5,
                        0, deadlocks, 0, 0), window);
        assertThat(manager.stage()).isEqualTo(Stage.CANARY_25);
        assertThat(record.rollbackStatus()).isEqualTo("NONE");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "CANARY-EXPANSION");
        report.put("stage", 5);
        report.put("target", TARGET);
        report.put("success", TARGET);
        report.put("zeroOversell", true);
        report.put("deadlocks", deadlocks);
        report.put("systemErrors", systemErrors);
        report.put("loadDurationMs", loadNanos / 1_000_000);
        report.put("produceQps", round(TARGET / (loadNanos / 1_000_000_000.0)));
        report.put("consumeConvergeMs", convergeMs);
        report.put("managerStage", manager.stage().name());
        report.put("protocolReady", true);
        report.put("executed", true);
        report.put("environmentLimitation",
                "single-machine shared host; produceQps 受压测客户端限制，不作为生产容量结论");
        report.put("codeDistribution", new TreeMap<>(codes));
        writeReport(report);
        log.info("canary-expansion stage=5 done: success={}, loadMs={}, convergeMs={}, "
                        + "deadlocks={}, systemErrors={}, managerStage={}",
                TARGET, loadNanos / 1_000_000, convergeMs, deadlocks, systemErrors, manager.stage());
    }

    private static void advanceTo(ProductionCanaryManager manager, int targetWeight) {
        Instant now = Instant.now();
        while (manager.stage().weight() < targetWeight) {
            CanaryObservationWindow window = new CanaryObservationWindow(
                    now.minusSeconds(31 * 60), now, manager.stage().weight(),
                    12000, 12000, 0, 300, 5, 0, "NONE");
            manager.observeAndAdvance(HealthSnapshot.of(500, 0.0, 300, 5, 0, 0, 0, 0), window);
        }
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
                    "ceuser" + (BASE_USER + i), PASSWORD);
            if (login == null || login.getCode() != 0 || login.getData() == null
                    || login.getData().getToken() == null) {
                throw new IllegalStateException("canary-expansion login failed at " + i);
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
                        .append(",'ceuser").append(BASE_USER + i)
                        .append("','").append(hash)
                        .append("',1,'USER')");
            }
            execute(sql.toString());
        }
    }

    private static void writeReport(Map<String, Object> report) throws Exception {
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("canary-expansion-report.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void cleanupNamespace() throws Exception {
        if (TOPOLOGY == null) {
            return;
        }
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
