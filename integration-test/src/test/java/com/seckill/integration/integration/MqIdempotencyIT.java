package com.seckill.integration.integration;

import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.order.constant.OrderConstants;
import com.seckill.seckill.SeckillApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-03 MQ 重复消息幂等：CREATE_ORDER / CANCEL_ORDER 重复消费均无副作用。
 */
@Tag("integration")
class MqIdempotencyIT extends IntegrationTestBase {

    private static final long SESSION_ID = 31004L;
    private static final long SKU_ID = 21004L;
    private static final long USER_ID = 13001L;
    private static final String ORDER_CREATE = "9000000001";
    private static final String ORDER_RECOVER = "9000000002";
    private static final String MESSAGE_CREATE = "test-mq-create-1";
    private static final String MESSAGE_RECOVER = "test-mq-recover-1";
    private static final int STOCK = 100;

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
        TestDataHelper.cleanupOrderNamespace(USER_ID, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
    }

    @Test
    void duplicateCreateOrder_should_be_idempotent() throws Exception {
        TestDataHelper.insertPreDeduct(9000001001L, MESSAGE_CREATE, ORDER_CREATE, USER_ID,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        String message = TestHttp.createOrderMessageJson(
                MESSAGE_CREATE, USER_ID, SESSION_ID, SKU_ID, ORDER_CREATE, 1, 9900L, "test-mq-create-1");
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_ID, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE message_id='" + MESSAGE_CREATE + "'")).isEqualTo("CONFIRMED");
        });

        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);
        await().atMost(Duration.ofSeconds(20)).pollDelay(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_ID, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countIdempotent(OrderConstants.BIZ_TYPE_ORDER_CREATE, MESSAGE_CREATE))
                    .isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
        });
    }

    @Test
    void duplicateCancelOrder_should_recover_only_once() throws Exception {
        TestDataHelper.insertPreDeduct(9000001002L, MESSAGE_RECOVER, ORDER_RECOVER, USER_ID,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "CONFIRMED");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK - 1, 1);
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK - 1));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        String message = TestHttp.cancelOrderMessageJson(
                MESSAGE_RECOVER, ORDER_RECOVER, USER_ID, SESSION_ID, SKU_ID, 1, "TIMEOUT");
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", message);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, ORDER_RECOVER)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });

        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", message);
        await().atMost(Duration.ofSeconds(20)).pollDelay(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, ORDER_RECOVER)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
    }
}
