package com.seckill.integration.integration;

import com.seckill.common.result.Result;
import com.seckill.integration.load.LoadConfig;
import com.seckill.integration.load.LoadTestExecutor;
import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.BucketReconciliationService;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.order.OrderApplication;
import com.seckill.order.task.TimeoutCloseTask;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.2.1 Inventory 分桶一致性回归（H-02 前身）：
 * 真实 MySQL/Redis/RocketMQ + 真实服务，分桶扣减/幂等/恢复/对账全链路。
 * 默认随集成测试执行（真实中间件）。
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryShardingConsistencyIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(InventoryShardingConsistencyIT.class);

    private static final int STOCK = 10000;
    private static final int BUCKET_COUNT = 8;
    private static final int CONCURRENCY = 500;
    private static final int BUCKET_TOTAL = STOCK / BUCKET_COUNT;

    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SESSION_ID = 7_300_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long SKU_ID = SESSION_ID + 100_000L;
    private static final long BASE_USER = 9_500_000_000L + (RUN_ID % 100_000L) * 10_000L;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    private static final AtomicInteger SUCCESS = new AtomicInteger();
    private static final List<String> SUCCESS_ORDER_IDS = new ArrayList<>();

    @BeforeAll
    static void startServices() throws Exception {
        TestDataHelper.seedSeckill(SESSION_ID, SKU_ID, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill",
                "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + BUCKET_COUNT);
        ORDER = ServiceSupport.start(OrderApplication.class, "order", "seckill_order", "order-service",
                "--seckill.order.pre-deduct-confirm.base-url=http://localhost:" + SECKILL.port(),
                "--seckill.order.timeout-close.period-seconds=3600000",
                "--seckill.order.timeout-close.batch-size=10000",
                "--seckill.order.cancel-notify-compensate-period-seconds=3600000");
        INVENTORY = ServiceSupport.start(InventoryApplication.class, "inventory", "seckill_inventory",
                "inventory-service",
                "--seckill.inventory.recover.base-url=http://localhost:" + SECKILL.port(),
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + BUCKET_COUNT);

        // 分桶迁移：先 dry-run 校验计划，再真实执行
        InventoryBucketMigrationService migration =
                INVENTORY.context().getBean(InventoryBucketMigrationService.class);
        InventoryBucketMigrationService.BucketMigrationResult plan =
                migration.migrate(SKU_ID, STOCK, BUCKET_COUNT, true);
        assertThat(plan.diff()).isNotEmpty();
        InventoryBucketMigrationService.BucketMigrationResult result =
                migration.migrate(SKU_ID, STOCK, BUCKET_COUNT, false);
        assertThat(result.after()).hasSize(BUCKET_COUNT);
        assertThat(result.after().stream().mapToInt(
                InventoryBucketMigrationService.BucketState::total).sum()).isEqualTo(STOCK);

        // Redis 预热：全局 + 分桶
        StockService stockService = SECKILL.context().getBean(StockService.class);
        stockService.prepare(String.valueOf(SKU_ID), STOCK);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            stockService.prepareBucket(String.valueOf(SKU_ID), i, BUCKET_TOTAL);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        ServiceLauncher.RunningService[] services = {INVENTORY, ORDER, SECKILL};
        for (ServiceLauncher.RunningService service : services) {
            if (service != null) {
                service.stop();
            }
        }
        cleanupNamespace();
    }

    @Test
    @Order(1)
    void shardingDeductShouldKeepZeroOversellAndInvariants() throws Exception {
        String baseUrl = "http://localhost:" + SECKILL.port();
        LoadTestExecutor.run(new LoadConfig(CONCURRENCY, CONCURRENCY, Duration.ofMinutes(3)),
                index -> {
                    long userId = BASE_USER + index;
                    String traceId = "l06c-" + RUN_ID + "-" + index;
                    Result<ExecuteResponse> result = TestHttp.executeWithAuth(
                            baseUrl, userId, SESSION_ID, SKU_ID, 1, traceId, null);
                    boolean ok = result != null && result.getCode() == 0;
                    if (ok) {
                        SUCCESS.incrementAndGet();
                        SUCCESS_ORDER_IDS.add(result.getData().getOrderId());
                    }
                    return ok;
                });

        int success = SUCCESS.get();
        assertThat(success).isLessThanOrEqualTo(STOCK);
        assertThat(success).isGreaterThan(0);

        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(success);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(success);
        });

        int sumAvailable = 0;
        int sumLocked = 0;
        int sumTotal = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            int available = queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
            int locked = queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
            int total = queryInt("SELECT total_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
            assertThat(available).isGreaterThanOrEqualTo(0);
            assertThat(locked).isGreaterThanOrEqualTo(0);
            assertThat(available + locked).isEqualTo(total);
            sumAvailable += available;
            sumLocked += locked;
            sumTotal += total;
        }
        assertThat(sumTotal).isEqualTo(STOCK);
        assertThat(sumAvailable + sumLocked).isEqualTo(STOCK);
        assertThat(sumAvailable).isEqualTo(STOCK - success);

        // Redis 全局 = SUM(bucket.available)
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(sumAvailable));
        for (int i = 0; i < BUCKET_COUNT; i++) {
            assertThat(redisGet("seckill:stock:bucket:" + SKU_ID + ":" + i)).isNotNull();
        }
        log.info("sharding deduct ok, success={}, sumAvailable={}, sumLocked={}",
                success, sumAvailable, sumLocked);
    }

    @Test
    @Order(2)
    void duplicateMessageShouldOnlyDeductOnce() throws Exception {
        String orderId = SUCCESS_ORDER_IDS.get(0);
        String messageId = queryString("SELECT message_id FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE order_id='" + orderId + "'");
        String bucketNoValue = queryString("SELECT bucket_no FROM seckill_inventory.stock_flow "
                + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'");
        assertThat(messageId).isNotBlank();
        assertThat(bucketNoValue).isNotBlank();
        Integer bucketNo = Integer.valueOf(bucketNoValue);

        String body = TestHttp.createOrderMessageJson(messageId, BASE_USER,
                SESSION_ID, SKU_ID, orderId, 1, 9900L, "l06c-dup-" + RUN_ID, bucketNo);
        sendCreateOrder(body);
        sendCreateOrder(body);

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                    + "WHERE biz_type='ORDER' AND biz_id='" + orderId + "'")).isEqualTo(1);
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(SUCCESS.get());
        });
        assertThat(queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                + "WHERE change_type='DEDUCT' AND sku_id=" + SKU_ID)).isEqualTo(SUCCESS.get());
    }

    @Test
    @Order(3)
    void recoverShouldRestoreBucketsAndRedis() throws Exception {
        int success = SUCCESS.get();
        execute("UPDATE seckill_order.seckill_order "
                + "SET pay_deadline = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE session_id=" + SESSION_ID + " AND order_status='WAIT_PAY'");
        ORDER.context().getBean(TimeoutCloseTask.class).closeExpiredOrders();

        await().atMost(Duration.ofSeconds(240)).untilAsserted(() -> {
            assertThat(queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                    + "WHERE session_id=" + SESSION_ID + " AND order_status='TIMEOUT'")).isEqualTo(success);
            assertThat(TestDataHelper.countRecoverFlow(SKU_ID)).isEqualTo(success);
        });

        int sumAvailable = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            int available = queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
            int locked = queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
            assertThat(available).isEqualTo(BUCKET_TOTAL);
            assertThat(locked).isZero();
            sumAvailable += available;
        }
        assertThat(sumAvailable).isEqualTo(STOCK);
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK));
        for (int i = 0; i < BUCKET_COUNT; i++) {
            assertThat(redisGet("seckill:stock:bucket:" + SKU_ID + ":" + i))
                    .isEqualTo(String.valueOf(BUCKET_TOTAL));
        }

        // 显式刷新汇总后对账必须 PASS
        BucketReconciliationService reconciliation =
                INVENTORY.context().getBean(BucketReconciliationService.class);
        reconciliation.syncSummary(SKU_ID);
        assertThat(reconciliation.check(SKU_ID).isPass()).isTrue();
    }

    private static void sendCreateOrder(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }

    private static void cleanupNamespace() throws Exception {
        execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id WHERE so.session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_order.idempotent WHERE user_id >= " + BASE_USER
                + " AND user_id < " + (BASE_USER + 10_000));
        execute("DELETE FROM seckill_order.seckill_order WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_pre_deduct WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + SESSION_ID);
        execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + SESSION_ID);
        cleanRedis("seckill:*", "auth:session:*", "risk:*");
    }
}
