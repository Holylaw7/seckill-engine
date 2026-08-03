package com.seckill.integration;

import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-04 订单取消库存恢复链路：超时取消 / 用户取消 → CANCEL_ORDER → inventory recover → Redis/MySQL 一致。
 */
@Tag("integration")
class CancelRecoverFlowIT extends IntegrationTestBase {

    private static final long SESSION_ID = 31005L;
    private static final long SKU_ID = 21005L;
    private static final long USER_TIMEOUT = 14001L;
    private static final long USER_CANCEL = 14002L;
    private static final int STOCK = 1000;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
    }

    @AfterAll
    static void stopServices() {
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @BeforeEach
    void reset() throws Exception {
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupOrderNamespace(USER_TIMEOUT, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_CANCEL, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
    }

    @Test
    void timeoutClose_should_publish_cancel_and_recover_stock() throws Exception {
        String orderId = completeSeckillFlow(USER_TIMEOUT, "test-cancel-timeout");
        IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE order_no='" + orderId + "'");

        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("TIMEOUT");
            assertThat(queryString("SELECT active_key FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isNull();
            assertThat(queryString("SELECT cancel_notify_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("SENT");
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, orderId)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
        });

        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
    }

    @Test
    void userCancel_should_publish_cancel_and_recover_stock() throws Exception {
        String orderId = completeSeckillFlow(USER_CANCEL, "test-cancel-user");

        Result<Void> cancel = TestHttp.cancelOrder(
                "http://localhost:" + ORDER.port(), USER_CANCEL, orderId, "test-cancel-user-1");
        assertThat(cancel.getCode()).isZero();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("CANCEL");
            assertThat(queryString("SELECT active_key FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isNull();
            assertThat(queryString("SELECT cancel_notify_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("SENT");
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, orderId)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
        });

        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
    }

    private static String completeSeckillFlow(long userId, String traceId) throws Exception {
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), userId, SESSION_ID, SKU_ID, 1, traceId);
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isZero();
        String orderId = result.getData().getOrderId();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct WHERE order_id='" + orderId + "'"))
                    .isEqualTo("CONFIRMED");
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
        });
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));
        return orderId;
    }
}
