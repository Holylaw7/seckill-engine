package com.seckill.integration;

import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.RocketMqTestConsumer;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.payment.PaymentApplication;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * S-01/S-02/S-03：服务异常故障演练（order/inventory 下线后 MQ 续跑，payment 下线后回调恢复）。
 */
@Tag("chaos")
@Tag("integration")
class ServiceFaultDrillIT extends IntegrationTestBase {

    private static final long SESSION_ID = 35001L;
    private static final long SKU_ID = 25001L;
    private static final int STOCK = 1000;
    private static final long USER_S01 = 25001L;
    private static final long USER_S02 = 25002L;
    private static final long USER_S03 = 25003L;
    private static final String PAY_AMOUNT = "99.00";
    private static final String MOCK_SECRET = "mock-channel-secret";

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;
    private static ServiceLauncher.RunningService PAYMENT;

    @AfterAll
    static void tearDown() {
        stopAllServices();
    }

    @BeforeEach
    void reset() throws Exception {
        stopAllServices();
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupOrderNamespace(USER_S01, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_S02, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupPaymentNamespace("test-pay-s03-");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
    }

    @Test
    void s01_orderServiceDown_shouldBacklogMessagesAndConsumeAfterRecovery() throws Exception {
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // order-service 未启动：消息必然积压
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_S01, SESSION_ID, SKU_ID, 1, "chaos-s01-1");
        assertThat(result.getCode()).isZero();
        String orderId = result.getData().getOrderId();

        assertThat(TestDataHelper.countOrders(USER_S01, SESSION_ID, SKU_ID)).isZero();
        assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE order_id='" + orderId + "'")).isEqualTo("DEDUCTED");

        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_S01, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE order_id='" + orderId + "'")).isEqualTo("CONFIRMED");
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));
        });
        assertThat(TestDataHelper.countOrders(USER_S01, SESSION_ID, SKU_ID)).isEqualTo(1);
    }

    @Test
    void s02_inventoryServiceDown_shouldRecoverDeductAndRecoverMessages() throws Exception {
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // inventory-service 未启动：DEDUCT 消息积压，订单正常创建
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_S02, SESSION_ID, SKU_ID, 1, "chaos-s02-1");
        assertThat(result.getCode()).isZero();
        String orderId = result.getData().getOrderId();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_S02, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE order_id='" + orderId + "'")).isEqualTo("CONFIRMED");
        });
        assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isZero();
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID)).isZero();

        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));
        });

        // DEDUCT 恢复后触发超时关单：RECOVER 链路恢复
        IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE order_no='" + orderId + "'");
        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("TIMEOUT");
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, orderId)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
    }

    @Test
    void s03_paymentServiceDown_shouldRecoverCallbackWithReplayProtection() throws Exception {
        PAYMENT = ServiceSupport.start(PaymentApplication.class, "payment", "seckill_payment", "payment-service",
                "--seckill.payment.refund-compensate.period-seconds=3600000");
        String orderNo = "test-pay-s03-" + UUID.randomUUID();
        Result<CreatePayResponse> pay = TestHttp.createPayment(
                "http://localhost:" + PAYMENT.port(), orderNo, USER_S03, PAY_AMOUNT);
        assertThat(pay.getCode()).isZero();
        String paymentNo = pay.getData().getPaymentNo();
        assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                .isEqualTo("WAIT_PAY");

        PAYMENT.stop();
        String transactionNo = "TXN-S03-1";
        long timestamp = System.currentTimeMillis() / 1000;
        String sign = TestHttp.signCallback(paymentNo, transactionNo, PAY_AMOUNT, timestamp, MOCK_SECRET);
        try {
            TestHttp.callback("http://localhost:" + PAYMENT.port(), paymentNo, transactionNo,
                    PAY_AMOUNT, timestamp, sign, "chaos-s03-1");
            fail("支付服务下线期间回调应连接失败");
        } catch (ResourceAccessException expected) {
            // 预期：服务不可达
        }

        try (RocketMqTestConsumer consumer = RocketMqTestConsumer.start(
                rocketMqNameServer(), "seckill-order-tx", "PAY_SUCCESS")) {
            PAYMENT = ServiceSupport.start(PaymentApplication.class, "payment", "seckill_payment", "payment-service",
                    "--seckill.payment.refund-compensate.period-seconds=3600000");
            ResponseEntity<String> retry = TestHttp.callback(
                    "http://localhost:" + PAYMENT.port(), paymentNo, transactionNo,
                    PAY_AMOUNT, timestamp, sign, "chaos-s03-2");
            assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(retry.getBody()).isEqualTo("success");

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(queryString("SELECT status FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo("PAY_SUCCESS");
                assertThat(queryInt("SELECT version FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo(2);
                assertThat(queryString("SELECT transaction_no FROM seckill_payment.payment_order WHERE payment_no='" + paymentNo + "'"))
                        .isEqualTo(transactionNo);
                assertThat(queryInt("SELECT COUNT(*) FROM seckill_payment.payment_callback_log "
                        + "WHERE channel_transaction_no='" + transactionNo + "'")).isEqualTo(1);
            });
            consumer.awaitCount(paymentNo, 1, Duration.ofSeconds(30));
        }
    }

    private static void stopAllServices() {
        ServiceLauncher.RunningService[] services = {PAYMENT, INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        PAYMENT = null;
        INVENTORY = null;
        ORDER = null;
        SECKILL = null;
    }
}
