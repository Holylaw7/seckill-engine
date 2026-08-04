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
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.1 MQ 消费性能基准（L-06）：
 * 真实 RocketMQ Producer/Broker/Consumer，分别记录 order 链路与 inventory 链路收敛耗时、
 * 消费 QPS、MySQL 行锁等待/死锁增量。
 * 消费线程数通过 --seckill.mq.consumer.threads 传入（默认 20），报告按实际生效值命名。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MQConsumerPerformanceTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(MQConsumerPerformanceTest.class);

    private static final int DEFAULT_COUNT = 5000;
    private static final int DEFAULT_THREADS = 20;
    private static final int STOCK = 20000;
    private static final String TOPIC = "seckill-order-tx";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_900_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final long ORDER_BASE = 99_000_000_001L;

    private static int createCount;
    private static int orderThreads;
    private static int inventoryThreads;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static DefaultMQProducer PRODUCER;

    private static LoadMetrics produceMetrics;
    private static long orderConvergeMs;
    private static long inventoryConvergeMs;
    private static long combinedConvergeMs;
    private static long cancelPublishMs;
    private static long cancelConvergeMs;
    private static long rowLockWaitsBefore;
    private static long rowLockWaitsAfter;
    private static long deadlocksBefore;
    private static long deadlocksAfter;

    @BeforeAll
    static void startServices() throws Exception {
        createCount = Integer.getInteger("mq.perf.count", DEFAULT_COUNT);
        orderThreads = Integer.getInteger("mq.perf.order.threads", DEFAULT_THREADS);
        inventoryThreads = Integer.getInteger("mq.perf.inventory.threads", DEFAULT_THREADS);

        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.timeout-close.batch-size=10000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000",
                "--seckill.mq.consumer.threads=" + orderThreads);
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.mq.consumer.threads=" + inventoryThreads);

        PRODUCER = new DefaultMQProducer("integration-l06-producer");
        PRODUCER.setNamesrvAddr(rocketMqNameServer());
        PRODUCER.setRetryTimesWhenSendFailed(3);
        PRODUCER.start();
        seedPreDeducts();

        log.info("L-06 started, count={}, orderThreadsConfigured={}, inventoryThreadsConfigured={}, "
                        + "actualOrderThreads={}, actualInventoryThreads={}",
                createCount, orderThreads, inventoryThreads,
                containerThreads("order", ORDER), containerThreads("inventory", INVENTORY));
    }

    @AfterAll
    static void tearDown() throws Exception {
        writeReport();
        cleanupNamespace();
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
    void create_order_consumption_capacity() throws Exception {
        DbStats before = dbStats();
        produceMetrics = LoadTestExecutor.run(
                new LoadConfig(200, createCount, Duration.ofMinutes(5)),
                index -> {
                    long orderNo = ORDER_BASE + index;
                    String body = TestHttp.createOrderMessageJson(
                            "l06-" + orderNo, BASE_USER + index, SESSION_ID, SKU_ID,
                            String.valueOf(orderNo), 1, 9900L, "load-l06-" + index);
                    return send(TOPIC, "CREATE_ORDER", body);
                });
        assertThat(produceMetrics.successCount()).isEqualTo(createCount);
        assertThat(produceMetrics.failureCount()).isZero();

        long produceEnd = System.nanoTime();
        long orderDone = awaitOrderChain(produceEnd);
        long inventoryDone = awaitInventoryChain(produceEnd);
        orderConvergeMs = (orderDone - produceEnd) / 1_000_000;
        inventoryConvergeMs = (inventoryDone - produceEnd) / 1_000_000;
        combinedConvergeMs = (Math.max(orderDone, inventoryDone) - produceEnd) / 1_000_000;

        DbStats after = dbStats();
        rowLockWaitsAfter = after.rowLockWaits;
        deadlocksAfter = after.deadlocks;
        rowLockWaitsBefore = before.rowLockWaits;
        deadlocksBefore = before.deadlocks;

        log.info("L-06 create consumed: orderConvergeMs={}, inventoryConvergeMs={}, combinedConvergeMs={}, "
                        + "rowLockWaitsDelta={}, deadlocksDelta={}",
                orderConvergeMs, inventoryConvergeMs, combinedConvergeMs,
                after.rowLockWaits - before.rowLockWaits, after.deadlocks - before.deadlocks);

        assertThat(combinedConvergeMs).isLessThan(Duration.ofMinutes(4).toMillis());
        assertThat(after.deadlocks - before.deadlocks).isZero();
    }

    @Test
    @Order(2)
    void cancel_order_consumption_capacity() throws Exception {
        // 订单统一置为已过期，由真实 TimeoutCloseTask 流转 TIMEOUT 并发布 CANCEL_ORDER
        execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE session_id=" + SESSION_ID + " AND order_status='WAIT_PAY'");
        // Redis 校准：模拟真实链路 Lua 预扣后的状态
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK - createCount));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        long publishStart = System.nanoTime();
        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();
        cancelPublishMs = (System.nanoTime() - publishStart) / 1_000_000;

        long consumeStart = System.nanoTime();
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND order_status='TIMEOUT'"))
                    .isEqualTo(createCount);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND cancel_notify_status='SENT'"))
                    .isEqualTo(createCount);
            assertThat(TestDataHelper.countRecoverFlow(SKU_ID)).isEqualTo(createCount);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
        cancelConvergeMs = (System.nanoTime() - consumeStart) / 1_000_000;
        log.info("L-06 cancel consumed: publishMs={}, convergeMs={}", cancelPublishMs, cancelConvergeMs);
    }

    private static long awaitOrderChain(long produceEnd) {
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(createCount);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.idempotent "
                    + "WHERE user_id >= " + BASE_USER + " AND user_id < " + (BASE_USER + 10_000)))
                    .isEqualTo(createCount);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND active_key IS NOT NULL"))
                    .isEqualTo(createCount);
        });
        return System.nanoTime();
    }

    private static long awaitInventoryChain(long produceEnd) {
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(createCount);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - createCount);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(createCount);
        });
        return System.nanoTime();
    }

    private static boolean send(String topic, String tag, String body) {
        try {
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("l06 send failed, tag={}", tag, e);
            return false;
        }
    }

    private static int containerThreads(String label, ServiceLauncher.RunningService service) {
        try {
            Map<String, DefaultRocketMQListenerContainer> containers =
                    service.context().getBeansOfType(DefaultRocketMQListenerContainer.class);
            for (DefaultRocketMQListenerContainer container : containers.values()) {
                return container.getConsumeThreadNumber();
            }
        } catch (Exception e) {
            log.warn("cannot read {} consumer threads", label, e);
        }
        return -1;
    }

    private static DbStats dbStats() throws Exception {
        return new DbStats(
                globalStatus("Innodb_row_lock_waits"),
                globalStatus("Innodb_deadlocks"));
    }

    private static long globalStatus(String name) throws Exception {
        String value = queryString("SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME='" + name + "'");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static void seedPreDeducts() throws Exception {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO seckill_seckill.seckill_pre_deduct "
                        + "(id, message_id, order_id, user_id, session_id, sku_id, quantity, tx_status, deduct_status) VALUES ");
        for (int i = 0; i < createCount; i++) {
            if (i > 0) {
                sql.append(',');
            }
            long orderNo = ORDER_BASE + i;
            sql.append('(').append(98_000_000_000L + i)
                    .append(",'l06-").append(orderNo)
                    .append("','").append(orderNo)
                    .append("',").append(BASE_USER + i)
                    .append(',').append(SESSION_ID)
                    .append(',').append(SKU_ID)
                    .append(",1,'SUCCESS','DEDUCTED')");
        }
        execute(sql.toString());
    }

    private static void writeReport() throws Exception {
        LoadMetrics base = produceMetrics == null
                ? LoadMetrics.of(0, 0, 0, System.currentTimeMillis(), System.currentTimeMillis(),
                System.nanoTime(), System.nanoTime(), new long[0])
                : produceMetrics;
        int actualOrder = containerThreads("order", ORDER);
        int actualInventory = containerThreads("inventory", INVENTORY);
        String scenario = "L-06-CREATE-o" + actualOrder + "-i" + actualInventory;
        double combinedQps = combinedConvergeMs > 0
                ? createCount / (combinedConvergeMs / 1000.0) : 0;
        double orderQps = orderConvergeMs > 0 ? createCount / (orderConvergeMs / 1000.0) : 0;
        double inventoryQps = inventoryConvergeMs > 0 ? createCount / (inventoryConvergeMs / 1000.0) : 0;
        double cancelQps = cancelConvergeMs > 0 ? createCount / (cancelConvergeMs / 1000.0) : 0;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("scenario", scenario);
        extra.put("createCount", createCount);
        extra.put("consumeCount", createCount);
        extra.put("failedCount", base.failureCount());
        extra.put("produceQps", round(base.qps()));
        extra.put("orderConsumeQps", round(orderQps));
        extra.put("inventoryConsumeQps", round(inventoryQps));
        extra.put("combinedConsumeQps", round(combinedQps));
        extra.put("orderConvergeMs", orderConvergeMs);
        extra.put("inventoryConvergeMs", inventoryConvergeMs);
        extra.put("combinedConvergeMs", combinedConvergeMs);
        extra.put("orderThreads", actualOrder);
        extra.put("inventoryThreads", actualInventory);
        extra.put("rowLockWaitsDelta", Math.max(0, rowLockWaitsAfter - rowLockWaitsBefore));
        extra.put("deadlocksDelta", Math.max(0, deadlocksAfter - deadlocksBefore));
        extra.put("cancelPublishMs", cancelPublishMs);
        extra.put("cancelConvergeMs", cancelConvergeMs);
        extra.put("cancelConsumeQps", round(cancelQps));
        LoadReport.writeJson(scenario, base, extra, LoadReport.defaultDir());
        writeCsv(scenario, base, extra);
        log.info("L-06 report written: {} combinedQps={} orderQps={} inventoryQps={} "
                        + "combinedConvergeMs={} orderConvergeMs={} inventoryConvergeMs={}",
                scenario, String.format("%.2f", combinedQps), String.format("%.2f", orderQps),
                String.format("%.2f", inventoryQps), combinedConvergeMs, orderConvergeMs, inventoryConvergeMs);
    }

    private static void writeCsv(String scenario, LoadMetrics metrics, Map<String, Object> extra) throws Exception {
        String header = "scenario,total,success,failed,produceQps,orderConsumeQps,inventoryConsumeQps,"
                + "combinedConsumeQps,orderConvergeMs,inventoryConvergeMs,combinedConvergeMs,"
                + "orderThreads,inventoryThreads,rowLockWaitsDelta,deadlocksDelta,"
                + "cancelPublishMs,cancelConvergeMs,cancelConsumeQps,duration,timestamp";
        String row = String.join(",",
                String.valueOf(extra.get("scenario")),
                String.valueOf(metrics.totalRequests()),
                String.valueOf(metrics.successCount()),
                String.valueOf(metrics.failureCount()),
                String.valueOf(extra.get("produceQps")),
                String.valueOf(extra.get("orderConsumeQps")),
                String.valueOf(extra.get("inventoryConsumeQps")),
                String.valueOf(extra.get("combinedConsumeQps")),
                String.valueOf(extra.get("orderConvergeMs")),
                String.valueOf(extra.get("inventoryConvergeMs")),
                String.valueOf(extra.get("combinedConvergeMs")),
                String.valueOf(extra.get("orderThreads")),
                String.valueOf(extra.get("inventoryThreads")),
                String.valueOf(extra.get("rowLockWaitsDelta")),
                String.valueOf(extra.get("deadlocksDelta")),
                String.valueOf(extra.get("cancelPublishMs")),
                String.valueOf(extra.get("cancelConvergeMs")),
                String.valueOf(extra.get("cancelConsumeQps")),
                String.valueOf(metrics.durationMillis()),
                java.time.Instant.ofEpochMilli(metrics.endTimeMillis()).toString());
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(scenario + ".csv"),
                header + System.lineSeparator() + row);
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + 10_000));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DbStats(long rowLockWaits, long deadlocks) {
    }
}
