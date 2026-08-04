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
 * Phase 6.1 Inventory 热点锁测量（L-06-HOTSPOT）：
 * 单 SKU 全部消息命中同一库存行，验证 confirmDeduct 的 FOR UPDATE 行锁串行化
 * 在并发 100/300/500/1000 下的消费 TPS、行锁等待、死锁与事务耗时。
 * 仅启动 inventory-service，隔离 inventory 链路。默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class InventoryHotspotLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryHotspotLoadTest.class);

    private static final int[] CONCURRENCIES = {100, 300, 500, 1000};
    private static final int PER_SCENARIO = 2000;
    private static final int STOCK = 200000;
    private static final int CONSUMER_THREADS = 8;
    private static final String TOPIC = "seckill-order-tx";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_100_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_800_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final AtomicLong NEXT_ORDER = new AtomicLong(99_100_000_001L);

    private static ServiceLauncher.RunningService INVENTORY;
    private static DefaultMQProducer PRODUCER;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.mq.consumer.threads=" + CONSUMER_THREADS);
        PRODUCER = new DefaultMQProducer("integration-l06-hotspot-producer");
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
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        cleanRedis("seckill:*");
    }

    @Test
    void single_sku_hotspot_lock_measurement() throws Exception {
        int consumedTotal = 0;
        List<Map<String, Object>> scenarioReports = new ArrayList<>();
        for (int concurrency : CONCURRENCIES) {
            DbStats before = dbStats();
            long scenarioStartOrder = NEXT_ORDER.get();
            LoadMetrics produce = LoadTestExecutor.run(
                    new LoadConfig(concurrency, PER_SCENARIO, Duration.ofMinutes(3)),
                    index -> {
                        long orderNo = NEXT_ORDER.getAndIncrement();
                        String body = TestHttp.createOrderMessageJson(
                                "l06h-" + orderNo, BASE_USER + (index % 10000), SESSION_ID, SKU_ID,
                                String.valueOf(orderNo), 1, 9900L, "load-l06h-" + orderNo);
                        return send(TOPIC, "CREATE_ORDER", body);
                    });
            assertThat(produce.failureCount()).isZero();

            int expectedTotal = consumedTotal + PER_SCENARIO;
            long produceEnd = System.nanoTime();
            await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
                assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(expectedTotal);
                assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                        .isEqualTo(STOCK - expectedTotal);
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                        .isEqualTo(expectedTotal);
            });
            long convergeMs = (System.nanoTime() - produceEnd) / 1_000_000;
            DbStats after = dbStats();
            double consumeQps = convergeMs > 0 ? PER_SCENARIO / (convergeMs / 1000.0) : 0;
            double avgPerMessageMs = convergeMs > 0 ? convergeMs / (double) PER_SCENARIO : 0;
            consumedTotal = expectedTotal;

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scenario", "L-06-HOTSPOT-c" + concurrency);
            report.put("concurrency", concurrency);
            report.put("total", PER_SCENARIO);
            report.put("consumeCount", PER_SCENARIO);
            report.put("consumeQps", round(consumeQps));
            report.put("convergeMs", convergeMs);
            report.put("avgPerMessageMs", round(avgPerMessageMs));
            report.put("rowLockWaitsDelta", after.rowLockWaits - before.rowLockWaits);
            report.put("deadlocksDelta", after.deadlocks - before.deadlocks);
            report.put("produceQps", round(produce.qps()));
            scenarioReports.add(report);
            log.info("L-06-HOTSPOT c{}: consumeQps={}, convergeMs={}, avgPerMessageMs={}, "
                            + "rowLockWaitsDelta={}, deadlocksDelta={}",
                    concurrency, String.format("%.2f", consumeQps), convergeMs,
                    String.format("%.2f", avgPerMessageMs),
                    after.rowLockWaits - before.rowLockWaits, after.deadlocks - before.deadlocks);
        }

        writeReport(scenarioReports);
        assertThat(consumedTotal).isEqualTo(CONCURRENCIES.length * PER_SCENARIO);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
    }

    private static boolean send(String topic, String tag, String body) {
        try {
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("hotspot send failed, tag={}", tag, e);
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
        root.put("scenario", "L-06-HOTSPOT");
        root.put("consumerThreads", CONSUMER_THREADS);
        root.put("skuId", SKU_ID);
        root.put("stock", STOCK);
        root.put("scenarios", scenarios);
        java.nio.file.Path directory = LoadReport.defaultDir();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("L-06-HOTSPOT.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(root));
        StringBuilder csv = new StringBuilder(
                "scenario,concurrency,total,consumeCount,consumeQps,convergeMs,avgPerMessageMs,"
                        + "rowLockWaitsDelta,deadlocksDelta,produceQps" + System.lineSeparator());
        for (Map<String, Object> scenario : scenarios) {
            csv.append(String.join(",",
                    String.valueOf(scenario.get("scenario")),
                    String.valueOf(scenario.get("concurrency")),
                    String.valueOf(scenario.get("total")),
                    String.valueOf(scenario.get("consumeCount")),
                    String.valueOf(scenario.get("consumeQps")),
                    String.valueOf(scenario.get("convergeMs")),
                    String.valueOf(scenario.get("avgPerMessageMs")),
                    String.valueOf(scenario.get("rowLockWaitsDelta")),
                    String.valueOf(scenario.get("deadlocksDelta")),
                    String.valueOf(scenario.get("produceQps")))).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(directory.resolve("L-06-HOTSPOT.csv"), csv.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DbStats(long rowLockWaits, long deadlocks) {
    }
}
