package com.seckill.integration;

import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.seckill.SeckillApplication;
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
 * M-01/M-02/M-03：MySQL 故障演练（连接异常 / 写入失败 / 事务回滚）。
 * 独立运行（M-01 停/启共享 MySQL 容器）。
 */
@Tag("chaos")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlFaultDrillIT extends IntegrationTestBase {

    private static final long SESSION_ID = 33001L;
    private static final long SKU_ID = 23001L;
    private static final int STOCK = 100;
    private static final long USER_M01 = 23001L;
    private static final long USER_M02 = 23002L;
    private static final long USER_M03 = 23003L;
    private static final String ORDER_M01 = "9300000001";
    private static final String ORDER_M02 = "9300000002";
    private static final String ORDER_M03A = "9300000003";
    private static final String ORDER_M03B = "9300000004";
    private static final String MESSAGE_M01 = "m01";
    private static final String MESSAGE_M02 = "m02";
    private static final String MESSAGE_M03A = "m03a";
    private static final String MESSAGE_M03B = "m03b";

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        prepareFaultUsers();
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
        if (!MYSQL.isRunning()) {
            MYSQL.start();
            awaitMysqlReady(Duration.ofSeconds(180));
        }
        // 幂等重建 schema 并恢复 test 用户全局授权
        restoreMysqlSchemas();
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @BeforeEach
    void reset() throws Exception {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
            awaitMysqlReady(Duration.ofSeconds(180));
        }
        restoreMysqlSchemas();
        executeAsRoot("SET GLOBAL read_only = OFF");
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupOrderNamespace(USER_M01, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_M02, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_M03, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
    }

    @Test
    @Order(1)
    void m01_mysqlDown_shouldNotLoseMessages() throws Exception {
        TestDataHelper.insertPreDeduct(933000001L, MESSAGE_M01, ORDER_M01, USER_M01,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // 故障注入：锁定 test 账号并断开现有连接，模拟数据库连接异常（无需重启容器）
        executeAsRoot("ALTER USER 'test'@'%' ACCOUNT LOCK");
        killTestConnections();
        try {
            String message = TestHttp.createOrderMessageJson(
                    MESSAGE_M01, USER_M01, SESSION_ID, SKU_ID, ORDER_M01, 1, 9900L, "chaos-m01");
            sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);
            // 连接异常期间无法查询 DB：仅等待一个静默窗口（Awaitility，非 sleep）
            await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ofSeconds(5)).until(() -> true);
        } finally {
            executeAsRoot("ALTER USER 'test'@'%' ACCOUNT UNLOCK");
        }

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_M01, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order "
                    + "WHERE order_no='" + ORDER_M01 + "'")).isEqualTo("WAIT_PAY");
            assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", MESSAGE_M01)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE message_id='" + MESSAGE_M01 + "'")).isEqualTo("CONFIRMED");
            // 手工消息不经 Lua 预扣：Redis 库存保持初始值，不被手工消息影响
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
        });
    }

    @Test
    @Order(2)
    void m02_writeFailure_shouldRetryAndRecover() throws Exception {
        TestDataHelper.insertPreDeduct(933000002L, MESSAGE_M02, ORDER_M02, USER_M02,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // 故障注入：order-service 使用无 INSERT 权限的专用账号（写入失败、SELECT 正常）
        ORDER.stop();
        executeAsRoot("REVOKE INSERT ON seckill_order.* FROM 'order_fault'@'%'");
        try {
            ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                    "--spring.datasource.username=order_fault",
                    "--spring.datasource.password=test",
                    "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                    "--seckill.order.timeout-close.period-seconds=3600000",
                    "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
            String message = TestHttp.createOrderMessageJson(
                    MESSAGE_M02, USER_M02, SESSION_ID, SKU_ID, ORDER_M02, 1, 9900L, "chaos-m02");
            sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);
            await().atMost(Duration.ofSeconds(25)).pollDelay(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(TestDataHelper.countOrders(USER_M02, SESSION_ID, SKU_ID)).isZero();
                assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", MESSAGE_M02)).isZero();
            });
        } finally {
            executeAsRoot("GRANT INSERT ON seckill_order.* TO 'order_fault'@'%'");
            ORDER.stop();
            ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                    "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                    "--seckill.order.timeout-close.period-seconds=3600000",
                    "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        }

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_M02, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", MESSAGE_M02)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE message_id='" + MESSAGE_M02 + "'")).isEqualTo("CONFIRMED");
            // 手工消息不经 Lua 预扣：Redis 库存保持初始值
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
        });
    }

    @Test
    @Order(3)
    void m03_transactionRollback_onActiveKeyConflict_shouldLeaveNoPartialRows() throws Exception {
        TestDataHelper.insertPreDeduct(933000003L, MESSAGE_M03A, ORDER_M03A, USER_M03,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        String first = TestHttp.createOrderMessageJson(
                MESSAGE_M03A, USER_M03, SESSION_ID, SKU_ID, ORDER_M03A, 1, 9900L, "chaos-m03-1");
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", first);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_M03, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order "
                    + "WHERE order_no='" + ORDER_M03A + "'")).isEqualTo("WAIT_PAY");
        });

        // 第二条：不同 messageId/orderId、相同 user/session/sku → uk_active 冲突，事务整体回滚
        String second = TestHttp.createOrderMessageJson(
                MESSAGE_M03B, USER_M03, SESSION_ID, SKU_ID, ORDER_M03B, 1, 9900L, "chaos-m03-2");
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", second);
        await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_M03, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE order_no='" + ORDER_M03B + "'")).isZero();
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.order_item "
                    + "WHERE order_id=" + ORDER_M03B)).isZero();
            assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", MESSAGE_M03B)).isZero();
        });
        assertThat(queryString("SELECT active_key FROM seckill_order.seckill_order "
                + "WHERE order_no='" + ORDER_M03A + "'")).isEqualTo(USER_M03 + ":" + SESSION_ID + ":" + SKU_ID);
        assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(STOCK);
    }

}
