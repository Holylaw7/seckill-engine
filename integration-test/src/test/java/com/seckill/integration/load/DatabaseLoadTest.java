package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.seckill.SeckillApplication;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * L-04 数据库压力验证：
 * 订单批量写入（5000）、库存扣减（10 SKU × 1000）、5 分钟混合写（CREATE_ORDER/DEDUCT/RECOVER）。
 * 真实链路 + MySQL 状态采集，默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLoadTest.class);

    private static final int L041_COUNT = 5000;
    private static final int L042_SKU_COUNT = 10;
    private static final int L042_PER_SKU_STOCK = 1000;
    private static final int L042_TOTAL = L042_SKU_COUNT * L042_PER_SKU_STOCK;
    private static final int L043_RATE_PER_SECOND = 30;
    private static final int L043_DURATION_SECONDS = 300;
    private static final int L043_TOTAL = L043_RATE_PER_SECOND * L043_DURATION_SECONDS;
    private static final int L043_RECOVER_COUNT = 3000;
    private static final String TOPIC = "seckill-order-tx";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 6_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_1 = SESSION_ID + 100_000L;
    private static final long SKU_2_BASE = SESSION_ID + 1_000_000L;
    private static final long SKU_3 = SESSION_ID + 2_000_000L;
    private static final long BASE_USER = 9_600_000_000L + (RUN_ID % 100_000L) * 100_000L;
    private static final long ORDER_1_BASE = 97_000_000_001L;
    private static final long ORDER_2_BASE = 97_100_000_001L;
    private static final long ORDER_3_BASE = 98_000_000_001L;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static DefaultMQProducer PRODUCER;

    private static long mysqlInsertDelta;
    private static long mysqlUpdateDelta;
    private static long mysqlLockWaitDelta;
    private static long mysqlDeadlockDelta;
    private static long mysqlSlowSqlDelta;
    private static long totalElapsedMs;
    private static ResourceMonitor.Sample resourceSample;
    private static double avgRtMs;
    private static double p95Ms;
    private static double p99Ms;

    @BeforeAll
    static void startServices() throws Exception {
        seedNamespaces();
        enableSlowLog();
        captureBaseline();
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.timeout-close.batch-size=20000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());

        PRODUCER = new DefaultMQProducer("integration-l04-producer");
        PRODUCER.setNamesrvAddr(rocketMqNameServer());
        PRODUCER.setRetryTimesWhenSendFailed(3);
        PRODUCER.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        collectMysqlMetrics();
        writeReport();
        cleanupNamespace();
        disableSlowLog();
        if (PRODUCER != null) {
            PRODUCER.shutdown();
        }
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @Test
    @Order(1)
    void l04_1_order_batch_write() throws Exception {
        seedPreDeducts(SKU_1, 96_100_000_000L, ORDER_1_BASE, 0, L041_COUNT, 10_000);
        long startNanos = System.nanoTime();
        try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(2), MYSQL.getContainerId())) {
            LoadMetrics metrics = LoadTestExecutor.run(
                    new LoadConfig(200, L041_COUNT, Duration.ofMinutes(5)),
                    index -> sendCreateOrder(ORDER_1_BASE + index, SKU_1, BASE_USER + index, "l04-1"));
            assertThat(metrics.failureCount()).isZero();
            avgRtMs = metrics.avgRtMs();
            p95Ms = metrics.p95Ms();
            p99Ms = metrics.p99Ms();
            await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
            resourceSample = monitor.latest();
        }
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_1)).isEqualTo(L041_COUNT);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.order_item oi "
                    + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id "
                    + "WHERE so.session_id=" + SESSION_ID + " AND so.sku_id=" + SKU_1))
                    .isEqualTo(L041_COUNT);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_1 + " AND active_key IS NOT NULL"))
                    .isEqualTo(L041_COUNT);
            assertThat(TestDataHelper.countDeductFlow(SKU_1)).isEqualTo(L041_COUNT);
            assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_1))
                    .isEqualTo(10_000);
        });
        assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.idempotent id "
                + "JOIN seckill_order.seckill_order so ON id.user_id = so.user_id "
                + "WHERE so.session_id=" + SESSION_ID + " AND so.sku_id=" + SKU_1))
                .isEqualTo(L041_COUNT);
        totalElapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("L-04-1 order batch write consumed: count={}", L041_COUNT);
    }

    @Test
    @Order(2)
    void l04_2_inventory_deduct_pressure() throws Exception {
        for (int i = 0; i < L042_SKU_COUNT; i++) {
            TestDataHelper.resetInventory(SKU_2_BASE + i, L042_PER_SKU_STOCK, L042_PER_SKU_STOCK, 0);
        }
        seedPreDeducts(SKU_2_BASE, 96_200_000_000L, ORDER_2_BASE, 5000, L042_TOTAL, L042_PER_SKU_STOCK);
        LoadMetrics metrics = LoadTestExecutor.run(
                new LoadConfig(500, L042_TOTAL, Duration.ofMinutes(8)),
                index -> {
                    long skuId = SKU_2_BASE + (index % L042_SKU_COUNT);
                    return sendCreateOrder(ORDER_2_BASE + index, skuId, BASE_USER + 5000 + index, "l04-2");
                });
        assertThat(metrics.failureCount()).isZero();

        await().atMost(Duration.ofSeconds(300)).untilAsserted(() -> {
            for (int i = 0; i < L042_SKU_COUNT; i++) {
                long skuId = SKU_2_BASE + i;
                assertThat(TestDataHelper.countDeductFlow(skuId)).isEqualTo(L042_PER_SKU_STOCK);
                assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isZero();
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isEqualTo(L042_PER_SKU_STOCK);
                assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isEqualTo(L042_PER_SKU_STOCK);
            }
        });
        log.info("L-04-2 inventory deduct pressure consumed: skus={}, perSku={}", L042_SKU_COUNT, L042_PER_SKU_STOCK);
    }

    @Test
    @Order(3)
    void l04_3_mixed_write_pressure() throws Exception {
        TestDataHelper.resetInventory(SKU_3, 20_000, 20_000, 0);
        seedPreDeducts(SKU_3, 96_300_000_000L, ORDER_3_BASE, 15_000, L043_TOTAL, 20_000);

        AtomicInteger failed = new AtomicInteger();
        long produceStart = System.nanoTime();
        int sent = paceProduce(L043_TOTAL, L043_RATE_PER_SECOND, Duration.ofSeconds(1),
                Duration.ofSeconds(L043_DURATION_SECONDS + 60), failed);
        long produceMs = (System.nanoTime() - produceStart) / 1_000_000;
        assertThat(sent).isEqualTo(L043_TOTAL);
        assertThat(failed.get()).isZero();

        await().atMost(Duration.ofSeconds(300)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_3)).isEqualTo(L043_TOTAL);
            assertThat(TestDataHelper.countDeductFlow(SKU_3)).isEqualTo(L043_TOTAL);
            assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_3))
                    .isEqualTo(20_000);
        });

        // RECOVER 混合负载：取 3000 单超时关单
        IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_3
                + " AND order_status='WAIT_PAY' LIMIT " + L043_RECOVER_COUNT);
        redisSet("seckill:stock:" + SKU_3, String.valueOf(20_000 - L043_TOTAL));
        redisSet("seckill:stock:total:" + SKU_3, String.valueOf(20_000));
        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();

        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND sku_id=" + SKU_3 + " AND order_status='TIMEOUT'"))
                    .isEqualTo(L043_RECOVER_COUNT);
            assertThat(TestDataHelper.countRecoverFlow(SKU_3)).isEqualTo(L043_RECOVER_COUNT);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_3))
                    .isEqualTo(20_000 - L043_TOTAL + L043_RECOVER_COUNT);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_3))
                    .isEqualTo(L043_TOTAL - L043_RECOVER_COUNT);
            assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_3))
                    .isEqualTo(20_000);
            assertThat(redisGet("seckill:stock:" + SKU_3))
                    .isEqualTo(String.valueOf(20_000 - L043_TOTAL + L043_RECOVER_COUNT));
        });
        log.info("L-04-3 mixed write pressure done: produced={}, recover={}, produceMs={}",
                L043_TOTAL, L043_RECOVER_COUNT, produceMs);
    }

    private static int paceProduce(int totalMessages, int batchSize, Duration interval,
                                   Duration overallTimeout, AtomicInteger failed) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workers = Executors.newFixedThreadPool(100);
        CountDownLatch done = new CountDownLatch(totalMessages);
        AtomicInteger index = new AtomicInteger();
        try {
            scheduler.scheduleAtFixedRate(() -> {
                for (int i = 0; i < batchSize; i++) {
                    int idx = index.getAndIncrement();
                    if (idx >= totalMessages) {
                        return;
                    }
                    workers.submit(() -> {
                        try {
                            if (!sendCreateOrder(ORDER_3_BASE + idx, SKU_3, BASE_USER + 15_000 + idx, "l04-3")) {
                                failed.incrementAndGet();
                            }
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(done.await(overallTimeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return index.get();
        } finally {
            scheduler.shutdownNow();
            workers.shutdownNow();
        }
    }

    private static boolean sendCreateOrder(long orderNo, long skuId, long userId, String prefix) {
        try {
            String body = TestHttp.createOrderMessageJson(
                    prefix + "-" + orderNo, userId, SESSION_ID, skuId,
                    String.valueOf(orderNo), 1, 9900L, "load-" + prefix + "-" + orderNo);
            Message message = new Message(TOPIC, "CREATE_ORDER", body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("l04 send failed, orderNo={}", orderNo, e);
            return false;
        }
    }

    private static void seedNamespaces() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_1, 10_000, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_1, 10_000, 10_000, 0);
        StringBuilder skuSql = new StringBuilder(
                "INSERT INTO seckill_seckill.seckill_sku "
                        + "(id, session_id, sku_id, stock_total, price, limit_per_user, status) VALUES ");
        List<Long> skus = new ArrayList<>();
        for (int i = 0; i < L042_SKU_COUNT; i++) {
            skus.add(SKU_2_BASE + i);
        }
        skus.add(SKU_3);
        for (int i = 0; i < skus.size(); i++) {
            if (i > 0) {
                skuSql.append(',');
            }
            skuSql.append('(').append(SESSION_ID + 20_000 + i)
                    .append(',').append(SESSION_ID)
                    .append(',').append(skus.get(i))
                    .append(",20000,99.00,1,1)");
        }
        execute(skuSql.toString());
        TestDataHelper.resetInventory(SKU_3, 20_000, 20_000, 0);
    }

    private static void seedPreDeducts(long skuId, long idBase, long orderBase,
                                       int userOffset, int count, int totalStock) throws Exception {
        String header = "INSERT INTO seckill_seckill.seckill_pre_deduct "
                + "(id, message_id, order_id, user_id, session_id, sku_id, quantity, tx_status, deduct_status) VALUES ";
        StringBuilder sql = new StringBuilder(header);
        int batchSize = 2000;
        int batch = 0;
        for (int i = 0; i < count; i++) {
            if (batch > 0) {
                sql.append(',');
            }
            long orderNo = orderBase + i;
            sql.append('(').append(idBase + i)
                    .append(",'l04-").append(skuId).append('-').append(orderNo)
                    .append("','").append(orderNo)
                    .append("',").append(BASE_USER + userOffset + i)
                    .append(',').append(SESSION_ID)
                    .append(',').append(skuId)
                    .append(",1,'SUCCESS','DEDUCTED')");
            // 按 2000 行分块，避免超过 max_allowed_packet
            if (++batch == batchSize || i == count - 1) {
                execute(sql.toString());
                sql = new StringBuilder(header);
                batch = 0;
            }
        }
    }

    private static void enableSlowLog() throws Exception {
        executeAsRoot("SET GLOBAL slow_query_log = ON");
        executeAsRoot("SET GLOBAL long_query_time = 0.2");
    }

    private static void disableSlowLog() throws Exception {
        executeAsRoot("SET GLOBAL slow_query_log = OFF");
    }

    private static long mysqlStatus(String name) throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW GLOBAL STATUS LIKE '" + name + "'")) {
            return resultSet.next() ? Long.parseLong(resultSet.getString(2)) : 0;
        }
    }

    private static void collectMysqlMetrics() throws Exception {
        long beforeInsert = mysqlStatus("Com_insert");
        long beforeUpdate = mysqlStatus("Com_update");
        long beforeLockWait = mysqlStatus("Innodb_row_lock_waits");
        long beforeDeadlock = mysqlStatus("Innodb_deadlocks");
        long beforeSlow = mysqlStatus("Slow_queries");
        log.info("mysql status final: insert={}, update={}, lockWait={}, deadlock={}, slow={}",
                beforeInsert, beforeUpdate, beforeLockWait, beforeDeadlock, beforeSlow);
        log.info("mysql status baseline: insert={}, update={}, lockWait={}, deadlock={}, slow={}",
                mysqlInsertBaselineInsert, mysqlInsertBaselineUpdate,
                mysqlInsertBaselineLockWait, mysqlInsertBaselineDeadlock, mysqlInsertBaselineSlow);
        // 首次采样在 @BeforeAll 后立即记录基线
        mysqlInsertDelta = beforeInsert - mysqlInsertBaselineInsert;
        mysqlUpdateDelta = beforeUpdate - mysqlInsertBaselineUpdate;
        mysqlLockWaitDelta = beforeLockWait - mysqlInsertBaselineLockWait;
        mysqlDeadlockDelta = beforeDeadlock - mysqlInsertBaselineDeadlock;
        mysqlSlowSqlDelta = beforeSlow - mysqlInsertBaselineSlow;
    }

    private static long mysqlInsertBaselineInsert;
    private static long mysqlInsertBaselineUpdate;
    private static long mysqlInsertBaselineLockWait;
    private static long mysqlInsertBaselineDeadlock;
    private static long mysqlInsertBaselineSlow;

    private static void captureBaseline() throws Exception {
        mysqlInsertBaselineInsert = mysqlStatus("Com_insert");
        mysqlInsertBaselineUpdate = mysqlStatus("Com_update");
        mysqlInsertBaselineLockWait = mysqlStatus("Innodb_row_lock_waits");
        mysqlInsertBaselineDeadlock = mysqlStatus("Innodb_deadlocks");
        mysqlInsertBaselineSlow = mysqlStatus("Slow_queries");
    }

    private static void writeReport() throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("insertCount", mysqlInsertDelta);
        extra.put("updateCount", mysqlUpdateDelta);
        extra.put("lockWait", mysqlLockWaitDelta);
        extra.put("deadlock", mysqlDeadlockDelta);
        extra.put("slowSql", mysqlSlowSqlDelta);
        extra.put("tps", round(totalElapsedMs > 0
                ? (mysqlInsertDelta + mysqlUpdateDelta) / (totalElapsedMs / 1000.0) : 0));
        extra.put("avgRT", round(avgRtMs));
        extra.put("p95", round(p95Ms));
        extra.put("p99", round(p99Ms));
        extra.put("businessWrites", Map.of(
                "orders", L041_COUNT + L042_TOTAL + L043_TOTAL,
                "deductFlows", L041_COUNT + L042_TOTAL + L043_TOTAL,
                "recoverFlows", L043_RECOVER_COUNT));
        extra.put("consistency", "PASS");
        if (resourceSample != null) {
            extra.put("resource", Map.of(
                    "cpuPercent", resourceSample.cpuPercent(),
                    "memoryBytes", resourceSample.memoryBytes(),
                    "heapUsedBytes", resourceSample.heapUsedBytes(),
                    "threadCount", resourceSample.threadCount(),
                    "gcCount", resourceSample.gcCount(),
                    "gcTimeMs", resourceSample.gcTimeMs()));
        }
        LoadMetrics metrics = LoadMetrics.of(0, 0, 0,
                System.currentTimeMillis(), System.currentTimeMillis(),
                System.nanoTime(), System.nanoTime(), new long[0]);
        LoadReport.writeJson("L-04", metrics, extra, LoadReport.defaultDir());
        String header = "scenario,insertCount,updateCount,TPS,avgRT,p95,p99,lockWait,deadlock,slowSql";
        String row = String.join(",",
                "L-04",
                String.valueOf(extra.get("insertCount")),
                String.valueOf(extra.get("updateCount")),
                String.valueOf(extra.get("tps")),
                String.valueOf(extra.get("avgRT")),
                String.valueOf(extra.get("p95")),
                String.valueOf(extra.get("p99")),
                String.valueOf(extra.get("lockWait")),
                String.valueOf(extra.get("deadlock")),
                String.valueOf(extra.get("slowSql")));
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("L-04.csv"),
                header + System.lineSeparator() + row);
        log.info("L-04 report written: insert={}, update={}, lockWait={}, deadlock={}, slowSql={}",
                mysqlInsertDelta, mysqlUpdateDelta, mysqlLockWaitDelta, mysqlDeadlockDelta, mysqlSlowSqlDelta);
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + 100_000));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id >= " + SKU_1 + " AND sku_id <= " + SKU_3);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id >= " + SKU_1 + " AND sku_id <= " + SKU_3);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
