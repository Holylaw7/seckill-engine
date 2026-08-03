package com.seckill.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.result.Result;
import com.seckill.inventory.InventoryApplication;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.order.OrderApplication;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.mapper.SeckillOrderMapper;
import com.seckill.order.state.OrderStateMachine;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-06 超时关单竞争：TimeoutCloseTask vs 支付成功流转（模拟 PAY_SUCCESS 消费端），验证 version CAS 唯一终态。
 */
@Tag("integration")
class OrderTimeoutRaceIT extends IntegrationTestBase {

    private static final int ROUNDS = 5;
    private static final long BASE_SESSION = 31006L;
    private static final long BASE_SKU = 21006L;
    private static final long BASE_USER = 16001L;
    private static final int STOCK = 1000;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            TestDataHelper.seedSeckill(BASE_SESSION + i, BASE_SKU + i, STOCK, "READY", "99.00");
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
        for (int i = 0; i < ROUNDS; i++) {
            TestDataHelper.cleanupOrderNamespace(BASE_USER + i, BASE_SESSION + i, BASE_SKU + i);
            TestDataHelper.resetInventory(BASE_SKU + i, STOCK, STOCK, 0);
        }
    }

    @Test
    void race_should_have_single_terminal_state_and_version_cas() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long sessionId = BASE_SESSION + round;
            long skuId = BASE_SKU + round;
            long userId = BASE_USER + round;
            redisSet("seckill:stock:" + skuId, String.valueOf(STOCK));
            redisSet("seckill:stock:total:" + skuId, String.valueOf(STOCK));

            Result<ExecuteResponse> result = TestHttp.execute(
                    "http://localhost:" + SECKILL.port(), userId, sessionId, skuId, 1,
                    "test-race-" + round + "-1");
            assertThat(result.getCode()).isZero();
            String orderId = result.getData().getOrderId();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                        .isEqualTo("WAIT_PAY");
                assertThat(queryString("SELECT deduct_status FROM seckill_seckill.seckill_pre_deduct WHERE order_id='" + orderId + "'"))
                        .isEqualTo("CONFIRMED");
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isEqualTo(1);
                assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isEqualTo(STOCK - 1);
            });
            assertThat(redisGet("seckill:stock:" + skuId)).isEqualTo(String.valueOf(STOCK - 1));

            IntegrationTestBase.execute("UPDATE seckill_order.seckill_order "
                    + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE order_no='" + orderId + "'");

            int versionBeforeRace = queryInt("SELECT version FROM seckill_order.seckill_order "
                    + "WHERE order_no='" + orderId + "'");
            runRace(orderId, skuId);
            assertSingleTerminalState(orderId, userId, sessionId, skuId, versionBeforeRace);
        }
    }

    private static void runRace(String orderId, long skuId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread timeoutThread = new Thread(() -> {
            try {
                start.await();
                ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread payThread = new Thread(() -> {
            try {
                start.await();
                SeckillOrder order = ORDER.context().getBean(SeckillOrderMapper.class).selectOne(
                        new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderId));
                if (order != null && OrderConstants.STATUS_WAIT_PAY.equals(order.getOrderStatus())) {
                    ORDER.context().getBean(OrderStateMachine.class)
                            .transition(order, OrderConstants.STATUS_PAY_SUCCESS, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 竞争失败方或状态已流转：由最终终态断言裁决
            } finally {
                done.countDown();
            }
        });

        timeoutThread.start();
        payThread.start();
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    }

    private static void assertSingleTerminalState(String orderId, long userId, long sessionId, long skuId,
                                                  int versionBeforeRace)
            throws Exception {
        String status = queryString("SELECT order_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'");
        assertThat(status).isIn(OrderConstants.STATUS_TIMEOUT, OrderConstants.STATUS_PAY_SUCCESS);
        assertThat(queryInt("SELECT version FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                .isEqualTo(versionBeforeRace + 1);

        if (OrderConstants.STATUS_TIMEOUT.equals(status)) {
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(queryString("SELECT active_key FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                        .isNull();
                assertThat(queryString("SELECT cancel_notify_status FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                        .isEqualTo("SENT");
                assertThat(TestDataHelper.countRecoverFlowByOrder(skuId, orderId)).isEqualTo(1);
                assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isEqualTo(STOCK);
                assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                        .isZero();
            });
            assertThat(redisGet("seckill:stock:" + skuId)).isEqualTo(String.valueOf(STOCK));
        } else {
            assertThat(queryString("SELECT active_key FROM seckill_order.seckill_order WHERE order_no='" + orderId + "'"))
                    .isEqualTo(userId + ":" + sessionId + ":" + skuId);
            assertThat(TestDataHelper.countRecoverFlowByOrder(skuId, orderId)).isZero();
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                    .isEqualTo(STOCK - 1);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + skuId))
                    .isEqualTo(1);
            assertThat(redisGet("seckill:stock:" + skuId)).isEqualTo(String.valueOf(STOCK - 1));
        }
    }
}
