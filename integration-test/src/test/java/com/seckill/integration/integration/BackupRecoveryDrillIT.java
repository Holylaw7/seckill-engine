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
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.5.8 Backup & Recovery Drill：
 * Redis key 丢失 → 预热 → 对账一致；MQ consumer 重启 → 积压恢复且只生效一次。
 */
@Tag("integration")
class BackupRecoveryDrillIT extends IntegrationTestBase {

    private static final long SKU_ID = 96001L;

    @Test
    void redisKeyLossPreheatAndReconcile() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        ServiceLauncher.RunningService seckill = ServiceSupport.start(
                SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(SKU_ID, 1000, 8, false);
            StockService stockService = seckill.context().getBean(StockService.class);
            // 模拟 key 丢失：删除全部分桶 key 与全局 key
            for (int i = 0; i < 8; i++) {
                redisSet("seckill:stock:bucket:" + SKU_ID + ":" + i, "0");
            }
            redisSet("seckill:stock:" + SKU_ID, "0");

            // 预热：按 MySQL 重建
            stockService.prepare(String.valueOf(SKU_ID), 1000);
            for (int i = 0; i < 8; i++) {
                int bucketTotal = queryInt("SELECT total_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
                stockService.prepareBucket(String.valueOf(SKU_ID), i, bucketTotal);
            }

            // 对账：Redis == SUM(bucket.available)，不变量成立
            assertThat(inventory.context().getBean(BucketReconciliationService.class)
                    .check(SKU_ID).isPass()).isTrue();
            assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo("1000");
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
