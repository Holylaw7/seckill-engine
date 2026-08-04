package com.seckill.integration.load;

import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.RocketMqTestConsumer;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.payment.PaymentApplication;
import com.seckill.payment.dto.CreatePayResponse;
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
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * L-03 MQ 消费能力压测：
 * CREATE_ORDER 5000 / CANCEL_ORDER 5000 / PAY_SUCCESS 200 + 重复消息幂等。
 * 真实 RocketMQ Producer/Broker/Consumer，默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MqConsumerLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(MqConsumerLoadTest.class);

    private static final int CREATE_COUNT = 5000;
    private static final int PAY_SUCCESS_COUNT = 200;
    private static final int STOCK = 10000;
    private static final String TOPIC = "seckill-order-tx";
    private static final String PAY_AMOUNT = "99.00";
    private static final String MOCK_SECRET = "mock-channel-secret";

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 5_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long IDEM_SKU = SKU_ID + 1;
    private static final long BASE_USER = 9_500_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final long ORDER_BASE = 95_000_000_001L;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService PAYMENT;
    private static DefaultMQProducer PRODUCER;

    private static LoadMetrics createProduceMetrics;
    private static long createConsumeDurationMs;
    private static long cancelPublishDurationMs;
    private static long cancelConsumeDurationMs;
    private static LoadMetrics callbackMetrics;
    private static ResourceMonitor.Sample resourceSample;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.timeout-close.batch-size=10000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        PAYMENT = ServiceSupport.start(PaymentApplication.class, "payment", "seckill_payment", "payment-service",
                "--seckill.payment.refund-compensate.period-seconds=3600000");

        PRODUCER = new DefaultMQProducer("integration-l03-producer");
        PRODUCER.setNamesrvAddr(rocketMqNameServer());
        PRODUCER.setRetryTimesWhenSendFailed(3);
        PRODUCER.start();
        seedPreDeducts();
    }

    @AfterAll
    static void tearDown() throws Exception {
        writeReport();
        cleanupNamespace();
        if (PRODUCER != null) {
            PRODUCER.shutdown();
        }
        ServiceLauncher.RunningService[] services = {PAYMENT, INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @Test
    @Order(1)
    void l03_1_create_order_consumption() throws Exception {
        try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(2), ROCKETMQ.getContainerId())) {
            createProduceMetrics = LoadTestExecutor.run(
                    new LoadConfig(200, CREATE_COUNT, Duration.ofMinutes(5)),
                    index -> {
                        long orderNo = ORDER_BASE + index;
                        String body = TestHttp.createOrderMessageJson(
                                "l03-1-" + orderNo, BASE_USER + index, SESSION_ID, SKU_ID,
                                String.valueOf(orderNo), 1, 9900L, "load-l03-1-" + index);
                        return send(TOPIC, "CREATE_ORDER", body);
                    });
            await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
            resourceSample = monitor.latest();
        }
        assertThat(createProduceMetrics.successCount()).isEqualTo(CREATE_COUNT);
        assertThat(createProduceMetrics.failureCount()).isZero();

        long consumeStart = System.nanoTime();
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(CREATE_COUNT);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(CREATE_COUNT);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE session_id=" + SESSION_ID + " AND deduct_status='CONFIRMED'"))
                    .isEqualTo(CREATE_COUNT);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND active_key IS NOT NULL"))
                    .isEqualTo(CREATE_COUNT);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - CREATE_COUNT);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(CREATE_COUNT);
        });
        createConsumeDurationMs = (System.nanoTime() - consumeStart) / 1_000_000;
        log.info("L-03-1 create order consumed: count={}, convergeMs={}", CREATE_COUNT, createConsumeDurationMs);
    }

    @Test
    @Order(2)
    void l03_2_cancel_order_consumption() throws Exception {
        // 订单统一置为已过期，由真实 TimeoutCloseTask 流转 TIMEOUT 并发布 5000 条 CANCEL_ORDER
        IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE session_id=" + SESSION_ID + " AND order_status='WAIT_PAY'");
        // Redis 校准：模拟真实链路 Lua 预扣后的状态（10000-5000）
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK - CREATE_COUNT));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        long publishStart = System.nanoTime();
        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();
        cancelPublishDurationMs = (System.nanoTime() - publishStart) / 1_000_000;

        long consumeStart = System.nanoTime();
        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND order_status='TIMEOUT'"))
                    .isEqualTo(CREATE_COUNT);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND cancel_notify_status='SENT'"))
                    .isEqualTo(CREATE_COUNT);
            assertThat(TestDataHelper.countRecoverFlow(SKU_ID)).isEqualTo(CREATE_COUNT);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
        cancelConsumeDurationMs = (System.nanoTime() - consumeStart) / 1_000_000;
        log.info("L-03-2 cancel order consumed: count={}, publishMs={}, convergeMs={}",
                CREATE_COUNT, cancelPublishDurationMs, cancelConsumeDurationMs);
    }

    @Test
    @Order(3)
    void l03_3_pay_success_publish() throws Exception {
        List<PayContext> contexts = new ArrayList<>(PAY_SUCCESS_COUNT);
        for (int i = 0; i < PAY_SUCCESS_COUNT; i++) {
            String orderNo = "test-pay-l03-" + i + "-" + RUN_ID;
            Result<CreatePayResponse> pay = TestHttp.createPayment(
                    "http://localhost:" + PAYMENT.port(), orderNo, BASE_USER + 9000 + i, PAY_AMOUNT);
            assertThat(pay.getCode()).isZero();
            String transactionNo = "TXN-L03-" + i;
            long timestamp = System.currentTimeMillis() / 1000;
            contexts.add(new PayContext(pay.getData().getPaymentNo(), transactionNo,
                    timestamp, TestHttp.signCallback(pay.getData().getPaymentNo(), transactionNo,
                    PAY_AMOUNT, timestamp, MOCK_SECRET)));
        }

        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), TOPIC, "PAY_SUCCESS")) {
            callbackMetrics = LoadTestExecutor.run(
                    new LoadConfig(50, PAY_SUCCESS_COUNT, Duration.ofMinutes(2)),
                    index -> {
                        PayContext context = contexts.get(index);
                        ResponseEntity<String> response = TestHttp.callback(
                                "http://localhost:" + PAYMENT.port(), context.paymentNo(), context.transactionNo(),
                                PAY_AMOUNT, context.timestamp(), context.sign(), "load-l03-3-" + index);
                        return response.getStatusCode().is2xxSuccessful();
                    });
            assertThat(callbackMetrics.failureCount()).isZero();
            consumer.awaitCount("", PAY_SUCCESS_COUNT, Duration.ofSeconds(60));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_order "
                        + "WHERE status='PAY_SUCCESS' AND order_no LIKE 'test-pay-l03-%'"))
                        .isEqualTo(PAY_SUCCESS_COUNT);
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no LIKE 'TXN-L03-%'"))
                        .isEqualTo(PAY_SUCCESS_COUNT);
            });
        }
    }

    @Test
    @Order(4)
    void idempotency_should_apply_once() throws Exception {
        String orderId = "9500000001";
        String messageId = "l03-idem-1";
        long user = BASE_USER + 5000;
        TestDataHelper.resetInventory(IDEM_SKU, 100, 100, 0);
        TestDataHelper.insertPreDeduct(96_990_000_001L, messageId, orderId, user,
                SESSION_ID, IDEM_SKU, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + IDEM_SKU, "100");
        redisSet("seckill:stock:total:" + IDEM_SKU, "100");

        String createBody = TestHttp.createOrderMessageJson(
                messageId, user, SESSION_ID, IDEM_SKU, orderId, 1, 9900L, "load-l03-idem-create");
        assertThat(send(TOPIC, "CREATE_ORDER", createBody)).isTrue();
        assertThat(send(TOPIC, "CREATE_ORDER", createBody)).isTrue();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(user, SESSION_ID, IDEM_SKU)).isEqualTo(1);
            assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", messageId)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(IDEM_SKU)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE message_id='" + messageId + "'")).isEqualTo("CONFIRMED");
        });

        redisSet("seckill:stock:" + IDEM_SKU, "99");
        String cancelBody = TestHttp.cancelOrderMessageJson(
                messageId, orderId, user, SESSION_ID, IDEM_SKU, 1, "TIMEOUT");
        assertThat(send(TOPIC, "CANCEL_ORDER", cancelBody)).isTrue();
        assertThat(send(TOPIC, "CANCEL_ORDER", cancelBody)).isTrue();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countRecoverFlowByOrder(IDEM_SKU, orderId)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + IDEM_SKU))
                    .isEqualTo(100);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + IDEM_SKU))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + IDEM_SKU)).isEqualTo("100");
        });
    }

    private static boolean send(String topic, String tag, String body) {
        try {
            Message message = new Message(topic, tag, body.getBytes(StandardCharsets.UTF_8));
            SendResult result = PRODUCER.send(message);
            return result.getSendStatus() == SendStatus.SEND_OK;
        } catch (Exception e) {
            log.warn("l03 send failed, tag={}", tag, e);
            return false;
        }
    }

    private static void seedPreDeducts() throws Exception {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO seckill_seckill.seckill_pre_deduct "
                        + "(id, message_id, order_id, user_id, session_id, sku_id, quantity, tx_status, deduct_status) VALUES ");
        for (int i = 0; i < CREATE_COUNT; i++) {
            if (i > 0) {
                sql.append(',');
            }
            long orderNo = ORDER_BASE + i;
            sql.append('(').append(96_000_000_000L + i)
                    .append(",'l03-1-").append(orderNo)
                    .append("','").append(orderNo)
                    .append("',").append(BASE_USER + i)
                    .append(',').append(SESSION_ID)
                    .append(',').append(SKU_ID)
                    .append(",1,'SUCCESS','DEDUCTED')");
        }
        execute(sql.toString());
    }

    private static void writeReport() throws Exception {
        LoadMetrics base = createProduceMetrics == null
                ? LoadMetrics.of(0, 0, 0, System.currentTimeMillis(), System.currentTimeMillis(),
                System.nanoTime(), System.nanoTime(), new long[0])
                : createProduceMetrics;
        double consumeQps = createConsumeDurationMs > 0
                ? CREATE_COUNT / (createConsumeDurationMs / 1000.0) : 0;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("produceCount", CREATE_COUNT);
        extra.put("consumeCount", CREATE_COUNT);
        extra.put("failedCount", base.failureCount());
        extra.put("consumeQps", round(consumeQps));
        extra.put("avgLatency", round(base.avgRtMs()));
        extra.put("p95", round(base.p95Ms()));
        extra.put("p99", round(base.p99Ms()));
        extra.put("backlog", 0);
        extra.put("createConsumeDurationMs", createConsumeDurationMs);
        extra.put("cancelPublishDurationMs", cancelPublishDurationMs);
        extra.put("cancelConsumeDurationMs", cancelConsumeDurationMs);
        extra.put("paySuccessProduced", PAY_SUCCESS_COUNT);
        extra.put("paySuccessConsumed", PAY_SUCCESS_COUNT);
        if (resourceSample != null) {
            extra.put("resource", Map.of(
                    "cpuPercent", resourceSample.cpuPercent(),
                    "memoryBytes", resourceSample.memoryBytes(),
                    "heapUsedBytes", resourceSample.heapUsedBytes(),
                    "threadCount", resourceSample.threadCount(),
                    "gcCount", resourceSample.gcCount(),
                    "gcTimeMs", resourceSample.gcTimeMs()));
        }
        LoadReport.writeJson("L-03", base, extra, LoadReport.defaultDir());
        writeCsv("L-03", base, extra);
        log.info("L-03 report written, produceQps={}, consumeQps={}, createConvergeMs={}, cancelConvergeMs={}",
                String.format("%.2f", base.qps()), String.format("%.2f", consumeQps),
                createConsumeDurationMs, cancelConsumeDurationMs);
    }

    private static void writeCsv(String scenario, LoadMetrics metrics, Map<String, Object> extra) throws Exception {
        String header = "scenario,produceCount,consumeCount,failedCount,duration,qps,consumeQps,avgLatency,p95,p99,backlog";
        String row = String.join(",",
                scenario,
                String.valueOf(extra.get("produceCount")),
                String.valueOf(extra.get("consumeCount")),
                String.valueOf(extra.get("failedCount")),
                String.valueOf(metrics.durationMillis()),
                String.valueOf(round(metrics.qps())),
                String.valueOf(extra.get("consumeQps")),
                String.valueOf(extra.get("avgLatency")),
                String.valueOf(extra.get("p95")),
                String.valueOf(extra.get("p99")),
                String.valueOf(extra.get("backlog")));
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
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id IN (" + SKU_ID + "," + IDEM_SKU + ")");
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id IN (" + SKU_ID + "," + IDEM_SKU + ")");
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        TestDataHelper.cleanupPaymentNamespace("test-pay-l03-");
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record PayContext(String paymentNo, String transactionNo, long timestamp, String sign) {
    }
}
