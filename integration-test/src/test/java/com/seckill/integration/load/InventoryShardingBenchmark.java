package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
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
 * Phase 6.2 H-01 Inventory Sharding Benchmark 准备（L-06-SHARDING）：
 * 以现有 inventory 独立行模拟分桶（1/4/8/16 行），验证“库存事实行数 × 吞吐”假设，
 * 为分桶设计提供实测依据。仅启动 inventory-service，纯测试代码，不修改生产。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class InventoryShardingBenchmark extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryShardingBenchmark.class);

    private static final int[] ROW_COUNTS = {1, 4, 8, 16};
    private static final int ROW_BLOCK_STRIDE = 32;
    private static final int PER_SCENARIO = 2000;
    private static final int STOCK_PER_ROW = 20000;
    private static final int CONCURRENCY = 500;
    private static final int CONSUMER_THREADS = 16;
    private static final String TOPIC = "seckill-order-tx";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_200_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_BASE = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_600_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final AtomicLong NEXT_ORDER = new AtomicLong(99_200_000_001L);

    private static ServiceLauncher.RunningService INVENTORY;
    private static DefaultMQProducer PRODUCER;

    @BeforeAll
    static void startServices() throws Exception {
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.mq.consumer.threads=" + CONSUMER_THREADS);
        PRODUCER = new DefaultMQProducer("integration-l06-sharding-producer");
        PRODUCER.setNamesrvAddr(rocketMqNameServer());
        PRODUCER.setRetryTimesWhenSendFailed(3);
        PRODUCER.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (PRODUCER != null) {
            PRODUCER.shutdown();
        }
        if (INVENTORY != null) {
            INVENTORY.stop();
        }
        for (int scenarioIndex = 0; scenarioIndex < ROW_COUNTS.length; scenarioIndex++) {
            for (int i = 0; i < ROW_BLOCK_STRIDE; i++) {
                long skuId = SKU_BASE + scenarioIndex * ROW_BLOCK_STRIDE + i;
                execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + skuId);
                execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + skuId);
            }
        }
        cleanRedis("seckill:*");
    }

    @Test
    void row_count_scaling_benchmark() throws Exception {
        List<Map<String, Object>> scenarioReports = new ArrayList<>();
        for (int scenarioIndex = 0; scenarioIndex < ROW_COUNTS.length; scenarioIndex++) {
            int rowCount = ROW_COUNTS[scenarioIndex];
            long rowBase = SKU_BASE + scenarioIndex * ROW_BLOCK_STRIDE;
            for (int i = 0; i < rowCount; i++) {
                TestDataHelper.resetInventory(rowBase + i, STOCK_PER_ROW, STOCK_PER_ROW, 0);
            }
            DbStats before = dbStats();
            LoadMetrics produce = LoadTestExecutor.run(
                    new LoadConfig(CONCURRENCY, PER_SCENARIO, Duration.ofMinutes(3)),
                    index -> {
                        long skuId = rowBase + (index % rowCount);
                        long orderNo = NEXT_ORDER.getAndIncrement();
                        String body = TestHttp.createOrderMessageJson(
                                "l06s-" + orderNo, BASE_USER + (index % 10000), SESSION_ID, skuId,
                                String.valueOf(orderNo), 1, 9900L, "load-l06s-" + orderNo);
                        return send(TOPIC, "CREATE_ORDER", body);
                    });
            assertThat(produce.failureCount()).isZero();

            long produceEnd = System.nanoTime();
            await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
                int consumed = 0;
                for (int i = 0; i < rowCount; i++) {
                    consumed += TestDataHelper.countDeductFlow(rowBase + i);
                }
                assertThat(consumed).isEqualTo(PER_SCENARIO);
                for (int i = 0; i < rowCount; i++) {
                    assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id="
                            + (rowBase + i))).isEqualTo(STOCK_PER_ROW - PER_SCENARIO / rowCount);
                }
            });
            long convergeMs = (System.nanoTime() - produceEnd) / 1_000_000;
            DbStats after = dbStats();
            double consumeQps = convergeMs > 0 ? PER_SCENARIO / (convergeMs / 1000.0) : 0;

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scenario", "L-06-SHARDING-rows" + rowCount);
            report.put("rowCount", rowCount);
            report.put("total", PER_SCENARIO);
            report.put("consumeQps", round(consumeQps));
            report.put("convergeMs", convergeMs);
            report.put("rowLockWaitsDelta", after.rowLockWaits - before.rowLockWaits);
            report.put("deadlocksDelta", after.deadlocks - before.deadlocks);
            scenarioReports.add(report);
            log.info("L-06-SHARDING rows={}: consumeQps={}, convergeMs={}, rowLockWaitsDelta={}, deadlocksDelta={}",
                    rowCount, String.format("%.2f", consumeQps), convergeMs,
                    after.rowLockWaits - before.rowLockWaits, after.deadlocks - before.deadlocks);
        }

        writeReport(scenarioReports);
        // 多行场景必须显著优于单行（分桶假设验证）
        Map<String, Object> rows1 = scenarioReports.get(0);
        Map<String, Object> rows16 = scenarioReports.get(3);
        assertThat((double) rows16.get("consumeQps"))
                .isGreaterThan((double) rows1.get("consumeQps") * 2);
    }

    private static boolean send(String topic, String tag, String body) {
        try {
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("sharding send failed, tag={}", tag, e);
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

    private static void writeReport(List<Map<String, Object>> scenarios) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scenario", "L-06-SHARDING");
        root.put("consumerThreads", CONSUMER_THREADS);
        root.put("concurrency", CONCURRENCY);
        root.put("scenarios", scenarios);
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("L-06-SHARDING.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        StringBuilder csv = new StringBuilder(
                "scenario,rowCount,total,consumeQps,convergeMs,rowLockWaitsDelta,deadlocksDelta"
                        + System.lineSeparator());
        for (Map<String, Object> scenario : scenarios) {
            csv.append(String.join(",",
                    String.valueOf(scenario.get("scenario")),
                    String.valueOf(scenario.get("rowCount")),
                    String.valueOf(scenario.get("total")),
                    String.valueOf(scenario.get("consumeQps")),
                    String.valueOf(scenario.get("convergeMs")),
                    String.valueOf(scenario.get("rowLockWaitsDelta")),
                    String.valueOf(scenario.get("deadlocksDelta")))).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(directory.resolve("L-06-SHARDING.csv"), csv.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DbStats(long rowLockWaits, long deadlocks) {
    }
}
