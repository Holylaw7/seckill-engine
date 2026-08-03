package com.seckill.integration;

import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.dto.ReconcileReport;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * R-01/R-02/R-03：Redis 故障演练。
 * 独立运行（停/启共享 Redis 容器），禁止与其他集成测试同 JVM 混跑。
 */
@Tag("chaos")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisFaultDrillIT extends IntegrationTestBase {

    private static final long SESSION_ID = 32001L;
    private static final long SKU_ID = 22001L;
    private static final int STOCK = 1000;
    private static final long USER_R01 = 22001L;
    private static final long USER_R02 = 22002L;
    private static final long USER_R03 = 22003L;

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
    static void tearDown() throws Exception {
        restoreContainers();
        stopAllServices();
    }

    @BeforeEach
    void reset() throws Exception {
        restoreContainers();
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupOrderNamespace(USER_R01, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_R02, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_R03, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
    }

    @Test
    @Order(1)
    void r01_redisDown_duringSeckill_shouldFailFastAndKeepNoDirtyData() throws Exception {
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        REDIS.stop();
        try {
            Result<ExecuteResponse> result = TestHttp.execute(
                    "http://localhost:" + SECKILL.port(), USER_R01, SESSION_ID, SKU_ID, 1, "chaos-r01");
            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo(10000);
            assertThat(TestDataHelper.countOrders(USER_R01, SESSION_ID, SKU_ID)).isZero();
            assertThat(TestDataHelper.countPreDeduct(USER_R01, SESSION_ID, SKU_ID)).isZero();
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
        } finally {
            REDIS.start();
            awaitRedisReady(Duration.ofSeconds(120));
        }
        // Redis 重启后映射端口可能变化：重启服务上下文以重新连接
        restartAllServices();
    }

    @Test
    @Order(2)
    void r02_redisRecovery_shouldRequirePreheatAndConverge() throws Exception {
        // 承接 R-01：Redis 已恢复但热点库存键丢失（容器无持久化）
        Result<ExecuteResponse> notReady = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_R02, SESSION_ID, SKU_ID, 1, "chaos-r02-1");
        assertThat(notReady.getCode()).isEqualTo(30003);

        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        Result<ExecuteResponse> ok = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_R02, SESSION_ID, SKU_ID, 1, "chaos-r02-2");
        assertThat(ok.getCode()).isZero();
        String orderId = ok.getData().getOrderId();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct WHERE order_id='" + orderId + "'"))
                    .isEqualTo("CONFIRMED");
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
        });
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK - 1);

        Result<ReconcileReport> report = TestHttp.reconcile("http://localhost:" + INVENTORY.port(), SKU_ID);
        assertThat(report.getCode()).isZero();
        assertThat(report.getData().issues()).isEmpty();
    }

    @Test
    @Order(3)
    void r03_redisDown_duringCancelRecover_shouldKeepMysqlFactAndRepair() throws Exception {
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_R03, SESSION_ID, SKU_ID, 1, "chaos-r03-1");
        assertThat(result.getCode()).isZero();
        String orderId = result.getData().getOrderId();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
        });
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));

        REDIS.stop();
        try {
            IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                    + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE order_no='" + orderId + "'");
            ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                        .isEqualTo("TIMEOUT");
                assertThat(queryString("SELECT cancel_notify_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                        .isEqualTo("SENT");
                assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, orderId)).isEqualTo(1);
                assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                        .isEqualTo(STOCK);
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                        .isZero();
            });
        } finally {
            REDIS.start();
            awaitRedisReady(Duration.ofSeconds(120));
        }
        restartAllServices();

        // Redis 键已丢失：按 MySQL 事实校准（等价 repair/预热动作）
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isZero();
        assertThat(queryInt("SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
        Result<ReconcileReport> report = TestHttp.reconcile("http://localhost:" + INVENTORY.port(), SKU_ID);
        assertThat(report.getCode()).isZero();
        assertThat(report.getData().issues()).isEmpty();
    }

    private static void restartAllServices() {
        stopAllServices();
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
    }

    private static void stopAllServices() {
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    private static void restoreContainers() throws Exception {
        if (!REDIS.isRunning()) {
            REDIS.start();
            awaitRedisReady(Duration.ofSeconds(120));
        }
    }
}
