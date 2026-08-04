package com.seckill.integration.chaos;

import com.seckill.common.result.Result;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.2.2 H-03 分桶故障演练：
 * F-01 Redis 分桶 key 缺失快速失败（无订单/无 MQ/无脏库存）
 * F-02 CANCEL_ORDER 重复投递只回补一次（bucket 模式）
 * F-03 consumer crash 后重投只生效一次（bucket 模式）
 * 仅 chaos 标签执行（默认 mvn test 排除）。
 */
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryShardingFaultIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryShardingFaultIT.class);

    private static final int STOCK = 100;
    private static final int BUCKET_COUNT = 4;
    private static final int BUCKET_TOTAL = STOCK / BUCKET_COUNT;

    private static final long SESSION_ID = 32002L;
    private static final long SKU_F01 = 22011L;
    private static final long SKU_F02 = 22012L;
    private static final long SKU_F03 = 22013L;
    private static final long USER_F01 = 22021L;
    private static final long USER_F02 = 22022L;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill",
                "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + BUCKET_COUNT);
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port(),
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + BUCKET_COUNT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        ServiceLauncher.RunningService[] services = {INVENTORY, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        cleanRedis("seckill:*");
    }

    @BeforeEach
    void reset() throws Exception {
        cleanRedis("seckill:*");
        for (long skuId : new long[]{SKU_F01, SKU_F02, SKU_F03}) {
            execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + skuId);
        }
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
    }

    @Test
    @Order(1)
    void f01_redisKeysMissingShouldFailFastWithoutDirtyData() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_F01, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_F01, STOCK, STOCK, 0);
        // 不预热 Redis：模拟分桶 key 缺失（含全局 key）
        Result<ExecuteResponse> result = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_F01, SESSION_ID, SKU_F01, 1, "chaos-f01");
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isNotZero();
        assertThat(TestDataHelper.countPreDeduct(USER_F01, SESSION_ID, SKU_F01)).isZero();
        assertThat(TestDataHelper.countOrders(USER_F01, SESSION_ID, SKU_F01)).isZero();

        // 预热后恢复可用
        migrate(SKU_F01);
        StockService stockService = SECKILL.context().getBean(StockService.class);
        stockService.prepare(String.valueOf(SKU_F01), STOCK);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            stockService.prepareBucket(String.valueOf(SKU_F01), i, BUCKET_TOTAL);
        }
        Result<ExecuteResponse> recovered = TestHttp.execute(
                "http://localhost:" + SECKILL.port(), USER_F01 + 1, SESSION_ID, SKU_F01, 1, "chaos-f01b");
        assertThat(recovered).isNotNull();
        assertThat(recovered.getCode()).isZero();
        log.info("F-01 pass: missing redis bucket keys fail fast, preheat recovers");
    }

    @Test
    @Order(2)
    void f02_duplicateCancelShouldRecoverOnce() throws Exception {
        TestDataHelper.resetInventory(SKU_F02, STOCK, STOCK, 0);
        migrate(SKU_F02);
        StockService stockService = SECKILL.context().getBean(StockService.class);
        stockService.prepare(String.valueOf(SKU_F02), STOCK);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            stockService.prepareBucket(String.valueOf(SKU_F02), i, BUCKET_TOTAL);
        }
        // 模拟一笔已 DEDUCT 到 bucket 1 的订单
        String orderId = "F02-ORDER-1";
        execute("INSERT INTO seckill_inventory.stock_flow "
                + "(id, flow_no, sku_id, change_type, change_qty, before_qty, after_qty, biz_type, biz_id, bucket_no) "
                + "VALUES (990200000001, 'SF-F02-D1', " + SKU_F02
                + ", 'DEDUCT', -1, " + BUCKET_TOTAL + ", " + (BUCKET_TOTAL - 1)
                + ", 'ORDER', '" + orderId + "', 1)");
        execute("UPDATE seckill_inventory.inventory_bucket "
                + "SET available_stock=available_stock-1, locked_stock=locked_stock+1, version=version+1 "
                + "WHERE sku_id=" + SKU_F02 + " AND bucket_no=1");
        redisSet("seckill:stock:" + SKU_F02, String.valueOf(STOCK - 1));
        redisSet("seckill:stock:bucket:" + SKU_F02 + ":1", String.valueOf(BUCKET_TOTAL - 1));

        String body = TestHttp.cancelOrderMessageJson("F02-MSG-1", orderId, USER_F02,
                SESSION_ID, SKU_F02, 1, "CANCEL");
        sendCancel(body);
        sendCancel(body);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                    + "WHERE biz_type='CANCEL' AND biz_id='" + orderId + "'")).isEqualTo(1);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_F02 + " AND bucket_no=1")).isEqualTo(BUCKET_TOTAL);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_F02 + " AND bucket_no=1")).isZero();
            assertThat(redisGet("seckill:stock:" + SKU_F02)).isEqualTo(String.valueOf(STOCK));
            assertThat(redisGet("seckill:stock:bucket:" + SKU_F02 + ":1"))
                    .isEqualTo(String.valueOf(BUCKET_TOTAL));
        });
        log.info("F-02 pass: duplicate CANCEL_ORDER recovers once");
    }

    @Test
    @Order(3)
    void f03_consumerCrashRedeliveryShouldApplyOnce() throws Exception {
        TestDataHelper.resetInventory(SKU_F03, STOCK, STOCK, 0);
        migrate(SKU_F03);

        String orderId = "F03-ORDER-1";
        String body = TestHttp.createOrderMessageJson("F03-MSG-1", USER_F02, SESSION_ID,
                SKU_F03, orderId, 1, 9900L, "chaos-f03", 0);
        sendCreate(body);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));

        // consumer crash：停止 inventory，消息投递滞留
        INVENTORY.stop();
        INVENTORY = null;
        sendCreate(body);

        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port(),
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + BUCKET_COUNT);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));
        sendCreate(body);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_F03)).isEqualTo(1));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                + "WHERE sku_id=" + SKU_F03 + " AND bucket_no=0")).isEqualTo(BUCKET_TOTAL - 1);
        log.info("F-03 pass: consumer crash redelivery applies once");
    }

    private static void migrate(long skuId) throws Exception {
        InventoryBucketMigrationService migration =
                INVENTORY.context().getBean(InventoryBucketMigrationService.class);
        migration.migrate(skuId, STOCK, BUCKET_COUNT, false);
    }

    private static void sendCreate(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }

    private static void sendCancel(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CANCEL_ORDER", body);
    }
}
