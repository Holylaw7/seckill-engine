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
 * Q-01~Q-04：RocketMQ 故障演练（producer 失败 / consumer 异常 / 重试 / 幂等复验）。
 * 独立运行（Q-01 停/启共享 RocketMQ 容器）。
 */
@Tag("chaos")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RocketMqFaultDrillIT extends IntegrationTestBase {

    private static final long SESSION_ID = 34001L;
    private static final long SKU_ID = 24001L;
    private static final int STOCK = 100;
    private static final long USER_Q01 = 24001L;
    private static final long USER_Q02 = 24002L;
    private static final long USER_Q03 = 24003L;
    private static final long USER_Q04 = 24004L;
    private static final String ORDER_Q01 = "9400000001";
    private static final String ORDER_Q02 = "9400000002";
    private static final String ORDER_Q03 = "9400000003";
    private static final String ORDER_Q04 = "9400000004";
    private static final String MESSAGE_Q01 = "q01";
    private static final String MESSAGE_Q02 = "q02";
    private static final String MESSAGE_Q03 = "q03";
    private static final String MESSAGE_Q04 = "q04";

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
        if (!ROCKETMQ.isRunning()) {
            ROCKETMQ.start();
            awaitRocketMqReady(Duration.ofSeconds(180));
            ensureRocketMqTopic();
        }
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
    }

    @BeforeEach
    void reset() throws Exception {
        if (!ROCKETMQ.isRunning()) {
            ROCKETMQ.start();
            awaitRocketMqReady(Duration.ofSeconds(180));
            ensureRocketMqTopic();
        }
        executeAsRoot("SET GLOBAL read_only = OFF");
        executeAsRoot("GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'");
        executeAsRoot("FLUSH PRIVILEGES");
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
        TestDataHelper.cleanupOrderNamespace(USER_Q01, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_Q02, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_Q03, SESSION_ID, SKU_ID);
        TestDataHelper.cleanupOrderNamespace(USER_Q04, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
    }

    @Test
    @Order(1)
    void q01_producerFailure_shouldReturnBusyAndCompensate() throws Exception {
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        ROCKETMQ.stop();
        try {
            Result<ExecuteResponse> result = TestHttp.execute(
                    "http://localhost:" + SECKILL.port(), USER_Q01, SESSION_ID, SKU_ID, 1, "chaos-q01");
            assertThat(result.getCode()).isEqualTo(30006);
            // 发送失败补偿：Redis 预扣已回补、用户标记清除、无本地事务载体
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
            assertThat(redisExists("seckill:user:" + SKU_ID + ":" + USER_Q01)).isZero();
            assertThat(TestDataHelper.countPreDeduct(USER_Q01, SESSION_ID, SKU_ID)).isZero();
            assertThat(TestDataHelper.countOrders(USER_Q01, SESSION_ID, SKU_ID)).isZero();
        } finally {
            ROCKETMQ.start();
            awaitRocketMqReady(Duration.ofSeconds(180));
            ensureRocketMqTopic();
            sendRocketMqMessage("seckill-order-tx", "TEST_WARMUP", "{\"warmup\":true}");
        }
        // Broker 重启后重建客户端连接：重启服务上下文
        restartAllServices();

        Result<ExecuteResponse> ok = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_Q01, SESSION_ID, SKU_ID, 1, "chaos-q01-2");
        assertThat(ok.getCode()).isZero();
        String orderId = ok.getData().getOrderId();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo("WAIT_PAY");
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            // 恢复后真实秒杀链路：Lua 预扣生效
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - 1));
        });
    }

    @Test
    @Order(2)
    void q02_consumerException_shouldHaveZeroBusinessSideEffects() throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", "{\"broken\":true}");
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", "not-json");

        await().atMost(Duration.ofSeconds(25)).pollDelay(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_Q02, SESSION_ID, SKU_ID)).isZero();
            assertThat(TestDataHelper.countPreDeduct(SESSION_ID, SKU_ID)).isZero();
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isZero();
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.idempotent WHERE user_id=" + USER_Q02)).isZero();
        });

        // 正常消息不受故障消息影响
        TestDataHelper.insertPreDeduct(944000002L, MESSAGE_Q02, ORDER_Q02, USER_Q02,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
        String message = TestHttp.createOrderMessageJson(
                MESSAGE_Q02, USER_Q02, SESSION_ID, SKU_ID, ORDER_Q02, 1, 9900L, "chaos-q02-ok");
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_Q02, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            // 手工消息不经 Lua 预扣：Redis 库存保持初始值
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
    }

    @Test
    @Order(3)
    void q03_consumeRetry_shouldConvergeAfterRecovery() throws Exception {
        TestDataHelper.insertPreDeduct(944000003L, MESSAGE_Q03, ORDER_Q03, USER_Q03,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // 故障注入：inventory-service 使用无 INSERT 权限的专用账号（流水写入失败）
        INVENTORY.stop();
        executeAsRoot("REVOKE INSERT ON seckill_inventory.* FROM 'inventory_fault'@'%'");
        try {
            INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                    "--spring.datasource.username=inventory_fault",
                    "--spring.datasource.password=test",
                    "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
            String message = TestHttp.createOrderMessageJson(
                    MESSAGE_Q03, USER_Q03, SESSION_ID, SKU_ID, ORDER_Q03, 1, 9900L, "chaos-q03");
            sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", message);
            await().atMost(Duration.ofSeconds(25)).pollDelay(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isZero();
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                        .isZero();
            });
        } finally {
            executeAsRoot("GRANT INSERT ON seckill_inventory.* TO 'inventory_fault'@'%'");
            INVENTORY.stop();
            INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                    "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
        }

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_Q03, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct "
                    + "WHERE message_id='" + MESSAGE_Q03 + "'")).isEqualTo("CONFIRMED");
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1);
            // 手工消息不经 Lua 预扣：Redis 库存保持初始值
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
    }

    @Test
    @Order(4)
    void q04_idempotencyAfterFaultRecovery_shouldApplyOnce() throws Exception {
        TestDataHelper.insertPreDeduct(944000004L, MESSAGE_Q04, ORDER_Q04, USER_Q04,
                SESSION_ID, SKU_ID, 1, "SUCCESS", "DEDUCTED");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));

        // 故障窗口内重复投递 CREATE_ORDER（order 写入失败 → MQ 重试）
        // 故障注入：order-service 使用无 INSERT 权限的专用账号（写入失败 + 重复投递）
        ORDER.stop();
        executeAsRoot("REVOKE INSERT ON seckill_order.* FROM 'order_fault'@'%'");
        String createMessage = TestHttp.createOrderMessageJson(
                MESSAGE_Q04, USER_Q04, SESSION_ID, SKU_ID, ORDER_Q04, 1, 9900L, "chaos-q04-create");
        try {
            ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                    "--spring.datasource.username=order_fault",
                    "--spring.datasource.password=test",
                    "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                    "--seckill.order.timeout-close.period-seconds=3600000",
                    "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
            sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", createMessage);
            sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", createMessage);
            await().atMost(Duration.ofSeconds(25)).pollDelay(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(TestDataHelper.countOrders(USER_Q04, SESSION_ID, SKU_ID)).isZero();
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
            assertThat(TestDataHelper.countOrders(USER_Q04, SESSION_ID, SKU_ID)).isEqualTo(1);
            assertThat(TestDataHelper.countIdempotent("ORDER_CREATE", MESSAGE_Q04)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1);
            // 手工消息不经 Lua 预扣：Redis 库存保持初始值
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });

        // 恢复后重复投递 CANCEL_ORDER：RECOVER 只生效一次
        // Redis 预置 99，模拟真实链路已发生的 Lua 预扣状态
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK - 1));
        String cancelMessage = TestHttp.cancelOrderMessageJson(
                MESSAGE_Q04, ORDER_Q04, USER_Q04, SESSION_ID, SKU_ID, 1, "TIMEOUT");
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", cancelMessage);
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", cancelMessage);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countRecoverFlowByOrder(SKU_ID, ORDER_Q04)).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isZero();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        });
    }

    private static void restartAllServices() {
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port());
    }
}
