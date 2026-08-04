package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.2.2 H-01 真实分桶基准（L-06-SHARDING-BUCKET）：
 * 真实 inventory_bucket 模型，N=1/4/8，每档 5000 条 CREATE_ORDER（bucketNo 轮询 = Lua v2 选择语义），
 * 测量 consume QPS / 收敛 / 行锁等待 / 死锁 / 桶内不变量。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class BucketShardingLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(BucketShardingLoadTest.class);

    private static final int[] BUCKET_COUNTS = {1, 4, 8};
    private static final int MESSAGES = 5000;
    private static final int STOCK = 20000;
    private static final int CONCURRENCY = 200;
    private static final int CONSUMER_THREADS = 16;
    private static final String TOPIC = "seckill-order-tx";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_400_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_400_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final AtomicLong NEXT_ORDER = new AtomicLong(99_300_000_001L);

    private static DefaultMQProducer PRODUCER;

    @BeforeAll
    static void startProducer() throws Exception {
        PRODUCER = new DefaultMQProducer("integration-l06-bucket-producer");
        PRODUCER.setNamesrvAddr(rocketMqNameServer());
        PRODUCER.setRetryTimesWhenSendFailed(3);
        PRODUCER.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (PRODUCER != null) {
            PRODUCER.shutdown();
        }
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        cleanRedis("seckill:*");
    }

    @Test
    void realBucketModeScaling() throws Exception {
        List<Map<String, Object>> reports = new ArrayList<>();
        for (int bucketCount : BUCKET_COUNTS) {
            ServiceLauncher.RunningService inventory = null;
            try {
                TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
                inventory = ServiceSupport.start(InventoryApplication.class, "inventory",
                        "seckill_inventory", "inventory-service",
                        "--seckill.mq.consumer.threads=" + CONSUMER_THREADS,
                        "--inventory.sharding.enabled=true",
                        "--inventory.sharding.bucket-count=" + bucketCount);
                inventory.context().getBean(InventoryBucketMigrationService.class)
                        .migrate(SKU_ID, STOCK, bucketCount, false);

                DbStats before = dbStats();
                LoadMetrics produce = LoadTestExecutor.run(
                        new LoadConfig(CONCURRENCY, MESSAGES, Duration.ofMinutes(5)),
                        index -> {
                            int bucketNo = index % bucketCount;
                            long orderNo = NEXT_ORDER.getAndIncrement();
                            String body = TestHttp.createOrderMessageJson(
                                    "l6b-" + orderNo, BASE_USER + (index % 10000), SESSION_ID, SKU_ID,
                                    String.valueOf(orderNo), 1, 9900L, "load-l6b-" + orderNo, bucketNo);
                            return send(TOPIC, "CREATE_ORDER", body);
                        });
                assertThat(produce.failureCount()).isZero();

                long produceEnd = System.nanoTime();
                await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
                    assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(MESSAGES);
                    for (int i = 0; i < bucketCount; i++) {
                        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                                + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i))
                                .isEqualTo(STOCK / bucketCount - MESSAGES / bucketCount);
                        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                                + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i))
                                .isEqualTo(MESSAGES / bucketCount);
                    }
                });
                long convergeMs = (System.nanoTime() - produceEnd) / 1_000_000;
                DbStats after = dbStats();
                double consumeQps = convergeMs > 0 ? MESSAGES / (convergeMs / 1000.0) : 0;

                Map<String, Object> report = new LinkedHashMap<>();
                report.put("scenario", "L-06-SHARDING-BUCKET-N" + bucketCount);
                report.put("bucketCount", bucketCount);
                report.put("total", MESSAGES);
                report.put("consumeQps", round(consumeQps));
                report.put("convergeMs", convergeMs);
                report.put("rowLockWaitsDelta", after.rowLockWaits - before.rowLockWaits);
                report.put("deadlocksDelta", after.deadlocks - before.deadlocks);
                reports.add(report);
                writeReport(report);
                log.info("L-06-SHARDING-BUCKET N={}: consumeQps={}, convergeMs={}, rowLockWaitsDelta={}, deadlocksDelta={}",
                        bucketCount, String.format("%.2f", consumeQps), convergeMs,
                        after.rowLockWaits - before.rowLockWaits, after.deadlocks - before.deadlocks);
            } finally {
                if (inventory != null) {
                    inventory.stop();
                }
                execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
                execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            }
        }
        writeSummary(reports);
        Map<String, Object> n1 = reports.get(0);
        Map<String, Object> n8 = reports.get(2);
        assertThat((double) n8.get("consumeQps"))
                .isGreaterThan((double) n1.get("consumeQps") * 2);
    }

    private static boolean send(String topic, String tag, String body) {
        try {
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("bucket load send failed, tag={}", tag, e);
            return false;
        }
    }

    private static DbStats dbStats() throws Exception {
        return new DbStats(globalStatus("Innodb_row_lock_waits"), globalStatus("Innodb_deadlocks"));
    }

    private static long globalStatus(String name) throws Exception {
        String value = queryString("SELECT VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME='" + name + "'");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static void writeReport(Map<String, Object> report) throws Exception {
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        String scenario = String.valueOf(report.get("scenario"));
        java.nio.file.Files.writeString(directory.resolve(scenario + ".json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        java.nio.file.Files.writeString(directory.resolve(scenario + ".csv"),
                "scenario,bucketCount,total,consumeQps,convergeMs,rowLockWaitsDelta,deadlocksDelta"
                        + System.lineSeparator()
                        + String.join(",",
                        scenario,
                        String.valueOf(report.get("bucketCount")),
                        String.valueOf(report.get("total")),
                        String.valueOf(report.get("consumeQps")),
                        String.valueOf(report.get("convergeMs")),
                        String.valueOf(report.get("rowLockWaitsDelta")),
                        String.valueOf(report.get("deadlocksDelta"))));
    }

    private static void writeSummary(List<Map<String, Object>> reports) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", "L-06-SHARDING-BUCKET");
        root.put("consumerThreads", CONSUMER_THREADS);
        root.put("messages", MESSAGES);
        root.put("scenarios", reports);
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("L-06-SHARDING-BUCKET.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        StringBuilder csv = new StringBuilder(
                "scenario,bucketCount,total,consumeQps,convergeMs,rowLockWaitsDelta,deadlocksDelta"
                        + System.lineSeparator());
        for (Map<String, Object> report : reports) {
            csv.append(String.join(",",
                    String.valueOf(report.get("scenario")),
                    String.valueOf(report.get("bucketCount")),
                    String.valueOf(report.get("total")),
                    String.valueOf(report.get("consumeQps")),
                    String.valueOf(report.get("convergeMs")),
                    String.valueOf(report.get("rowLockWaitsDelta")),
                    String.valueOf(report.get("deadlocksDelta")))).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(directory.resolve("L-06-SHARDING-BUCKET.csv"), csv.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DbStats(long rowLockWaits, long deadlocks) {
    }
}
