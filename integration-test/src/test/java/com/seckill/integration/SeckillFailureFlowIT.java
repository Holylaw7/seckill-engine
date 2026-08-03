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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-02 秒杀失败链路：STOCK_EMPTY / NOT_READY / REPEAT_BUY。
 */
@Tag("integration")
class SeckillFailureFlowIT extends IntegrationTestBase {

    private static final long READY_SESSION = 31002L;
    private static final long READY_SKU = 21002L;
    private static final long INIT_SESSION = 31003L;
    private static final long INIT_SKU = 21003L;
    private static final long USER_STOCK_EMPTY = 12001L;
    private static final long USER_NOT_READY = 12002L;
    private static final long USER_REPEAT = 12003L;
    private static final int STOCK = 100;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(READY_SESSION, READY_SKU, STOCK, "READY", "99.00");
        TestDataHelper.seedSeckill(INIT_SESSION, INIT_SKU, STOCK, "INIT", "99.00");
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
        TestDataHelper.cleanupOrderNamespace(USER_STOCK_EMPTY, READY_SESSION, READY_SKU);
        TestDataHelper.cleanupOrderNamespace(USER_NOT_READY, INIT_SESSION, INIT_SKU);
        TestDataHelper.cleanupOrderNamespace(USER_REPEAT, READY_SESSION, READY_SKU);
        TestDataHelper.resetInventory(READY_SKU, STOCK, STOCK, 0);
        TestDataHelper.resetInventory(INIT_SKU, STOCK, STOCK, 0);
    }

    @Test
    void stockEmpty_shouldReturn30004AndNoSideEffects() throws Exception {
        redisSet("seckill:stock:" + READY_SKU, "0");
        redisSet("seckill:stock:total:" + READY_SKU, String.valueOf(STOCK));

        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_STOCK_EMPTY, READY_SESSION, READY_SKU, 1,
                "test-failure-stock-empty");

        assertThat(result.getCode()).isEqualTo(30004);
        assertThat(redisGet("seckill:stock:" + READY_SKU)).isEqualTo("0");
        assertThat(countRedisKeys("seckill:flow:*")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                + "WHERE user_id=" + USER_STOCK_EMPTY + " AND session_id=" + READY_SESSION + " AND sku_id=" + READY_SKU))
                .isZero();
        assertThat(TestDataHelper.countPreDeduct(READY_SESSION, READY_SKU)).isZero();
        assertThat(TestDataHelper.countDeductFlow(READY_SKU)).isZero();
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + READY_SKU))
                .isEqualTo(STOCK);
        assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + READY_SKU))
                .isZero();
    }

    @Test
    void notReady_shouldReturn30003AndNotTouchStock() throws Exception {
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_NOT_READY, INIT_SESSION, INIT_SKU, 1,
                "test-failure-not-ready");

        assertThat(result.getCode()).isEqualTo(30003);
        assertThat(redisGet("seckill:stock:" + INIT_SKU)).isNull();
        assertThat(TestDataHelper.countPreDeduct(INIT_SESSION, INIT_SKU)).isZero();
        assertThat(TestDataHelper.countOrders(INIT_SESSION, INIT_SKU)).isZero();
        assertThat(TestDataHelper.countDeductFlow(INIT_SKU)).isZero();
    }

    @Test
    void repeatBuy_shouldReturn30005AndStockUnchanged() throws Exception {
        redisSet("seckill:stock:" + READY_SKU, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + READY_SKU, String.valueOf(STOCK));

        Result<ExecuteResponse> first = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_REPEAT, READY_SESSION, READY_SKU, 1,
                "test-failure-repeat-1");
        assertThat(first.getCode()).isZero();
        assertThat(redisGet("seckill:stock:" + READY_SKU)).isEqualTo(String.valueOf(STOCK - 1));
        assertThat(redisExists("seckill:user:" + READY_SKU + ":" + USER_REPEAT)).isEqualTo(1L);

        Result<ExecuteResponse> second = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_REPEAT, READY_SESSION, READY_SKU, 1,
                "test-failure-repeat-2");
        assertThat(second.getCode()).isEqualTo(30005);
        assertThat(redisGet("seckill:stock:" + READY_SKU)).isEqualTo(String.valueOf(STOCK - 1));
        assertThat(redisExists("seckill:user:" + READY_SKU + ":" + USER_REPEAT)).isEqualTo(1L);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(USER_REPEAT, READY_SESSION, READY_SKU)).isEqualTo(1);
            assertThat(TestDataHelper.countPreDeduct(USER_REPEAT, READY_SESSION, READY_SKU)).isEqualTo(1);
            assertThat(TestDataHelper.countDeductFlow(READY_SKU)).isEqualTo(1);
        });
    }
}
