package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.integration.support.TestHttp;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.BucketReconciliationService;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.redis.BucketDeductResult;
import com.seckill.seckill.redis.StockService;
import com.seckill.seckill.redis.StockDeductResult;
import com.seckill.seckill.redis.StockRecoverResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.5.8 Backup & Recovery Drill：
 * Redis 全量 key 丢失 → 按 MySQL 可用库存预热 → 对账一致；MQ consumer 重启 → 积压恢复且只生效一次。
 */
@Tag("integration")
class BackupRecoveryDrillIT extends IntegrationTestBase {

    private static final long SKU_ID = 96001L;

    @Test
    void redisKeyLossPreheatAndReconcile() throws Exception {
        // 保留 2 件已锁定库存，验证恢复不能错误地把 total 当成 available。
        TestDataHelper.resetInventory(SKU_ID, 1000, 998, 2);
        ServiceLauncher.RunningService seckill = ServiceSupport.start(
                SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(SKU_ID, 1000, 8, false);
            execute("UPDATE seckill_inventory.inventory_bucket "
                    + "SET locked_stock=1, available_stock=124, version=version+1 "
                    + "WHERE sku_id=" + SKU_ID + " AND bucket_no IN (0, 1)");
            StockService stockService = seckill.context().getBean(StockService.class);
            // 模拟 Redis 全量丢失：global / total / bucket key 全部删除。
            cleanRedis("seckill:stock:" + SKU_ID,
                    "seckill:stock:total:" + SKU_ID,
                    "seckill:stock:bucket:" + SKU_ID + ":*");

            // 恢复：global 和 bucket 均按 MySQL available_stock 重建，total 只保留为上限。
            stockService.prepareAvailable(String.valueOf(SKU_ID), 1000, 998);
            for (int i = 0; i < 8; i++) {
                int bucketAvailable = queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
                stockService.prepareAvailableBucket(String.valueOf(SKU_ID), i, bucketAvailable);
            }

            // 对账：Redis == SUM(bucket.available) == inventory.available，且锁定库存仍保留。
            assertThat(inventory.context().getBean(BucketReconciliationService.class)
                    .check(SKU_ID).isPass()).isTrue();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo("998");
            assertThat(redisGet("seckill:stock:total:" + SKU_ID)).isEqualTo("1000");
            assertThat(queryInt("SELECT SUM(available_stock) FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID)).isEqualTo(998);
            assertThat(queryInt("SELECT SUM(locked_stock) FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID)).isEqualTo(2);

            // 恢复后继续扣减，再回补验证 Lua 仍可工作且不突破 total 上限。
            BucketDeductResult deduct = stockService.preDeductBucket(
                    String.valueOf(SKU_ID), "recovery-user-1", 1,
                    3600, 8, 3600);
            assertThat(deduct.result()).isEqualTo(StockDeductResult.SUCCESS);
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo("997");
            assertThat(stockService.recoverBucket(String.valueOf(SKU_ID), "recovery-user-1", 1,
                    true, deduct.bucketNo())).isEqualTo(StockRecoverResult.SUCCESS);
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo("998");
        } finally {
            seckill.stop();
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
            cleanRedis("seckill:*");
        }
    }

    @Test
    void mqConsumerRestartShouldRecoverAndApplyOnce() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=4");
        inventory.context().getBean(InventoryBucketMigrationService.class)
                .migrate(SKU_ID, 1000, 4, false);
        String orderId = "REC-ORDER-1";
        String body = TestHttp.createOrderMessageJson("REC-MSG-1", 960001L, 96001L,
                SKU_ID, orderId, 1, 9900L, "recovery-drill", 0);
        sendCreate(body);
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1));

        // consumer 重启：停止 → 发重复消息 → 重启 → 只生效一次
        inventory.stop();
        inventory = null;
        sendCreate(body);
        inventory = ServiceSupport.start(InventoryApplication.class, "inventory",
                "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=4");
        try {
            await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                    assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1));
            sendCreate(body);
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(1));
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
            cleanRedis("seckill:*");
        }
    }

    private static void sendCreate(String body) throws Exception {
        sendRocketMqMessage("seckill-order-tx", "CREATE_ORDER", body);
    }
}
