package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.5 RC-04/RC-07 发布演练：
 * 迁移演练（dry-run / 真实迁移 / 重复迁移幂等 / 回滚迁移）+ Redis 预热恢复一致性。
 */
@Tag("integration")
class ReleaseDrillIT extends IntegrationTestBase {

    private static final long SKU_ID = 95001L;

    @Test
    void migrationDrillShouldSplit1000Into8Buckets() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            InventoryBucketMigrationService migration =
                    inventory.context().getBean(InventoryBucketMigrationService.class);

            // dry-run：不写库
            InventoryBucketMigrationService.BucketMigrationResult plan =
                    migration.migrate(SKU_ID, 1000, 8, true);
            assertThat(plan.after()).hasSize(8);
            assertThat(plan.after().stream().mapToInt(
                    InventoryBucketMigrationService.BucketState::total).sum()).isEqualTo(1000);

            // 真实迁移：8 桶 × 125
            InventoryBucketMigrationService.BucketMigrationResult result =
                    migration.migrate(SKU_ID, 1000, 8, false);
            assertThat(result.after()).hasSize(8);
            assertThat(result.after().stream().allMatch(
                    bucket -> bucket.total() == 125 && bucket.available() == 125)).isTrue();
            assertThat(queryInt("SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1000);
            assertThat(queryInt("SELECT SUM(total_stock) FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID)).isEqualTo(1000);

            // 重复迁移幂等（计划一致不抛错）
            InventoryBucketMigrationService.BucketMigrationResult repeat =
                    migration.migrate(SKU_ID, 1000, 8, false);
            assertThat(repeat.after()).hasSize(8);

            // 回滚迁移：删除分桶，inventory 汇总行保留
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            assertThat(queryInt("SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(1000);
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        }
    }

    @Test
    void redisWarmupRecoveryShouldMatchMysql() throws Exception {
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
            stockService.prepare(String.valueOf(SKU_ID), 1000);

            // 模拟 Redis 分桶 key 丢失：删除后按 MySQL 预热
            for (int i = 0; i < 8; i++) {
                redisSet("seckill:stock:bucket:" + SKU_ID + ":" + i, "0");
            }
            for (int i = 0; i < 8; i++) {
                int bucketTotal = queryInt("SELECT total_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + SKU_ID + " AND bucket_no=" + i);
                stockService.prepareBucket(String.valueOf(SKU_ID), i, bucketTotal);
            }
            // Redis 全局 == SUM(bucket.available)
            int sum = 0;
            for (int i = 0; i < 8; i++) {
                sum += Integer.parseInt(redisGet("seckill:stock:bucket:" + SKU_ID + ":" + i));
            }
            assertThat(sum).isEqualTo(1000);
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
    void mysqlBackupRestoreDrillShouldKeepLegacyIntact() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        // 1. 备份 inventory（迁移前）
        execute("DROP TABLE IF EXISTS seckill_inventory.inventory_bak");
        execute("CREATE TABLE seckill_inventory.inventory_bak AS "
                + "SELECT * FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
        assertThat(queryInt("SELECT total_stock FROM seckill_inventory.inventory_bak "
                + "WHERE sku_id=" + SKU_ID)).isEqualTo(1000);

        // 2. 迁移（dry-run + 真实）
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            InventoryBucketMigrationService migration =
                    inventory.context().getBean(InventoryBucketMigrationService.class);
            migration.migrate(SKU_ID, 1000, 8, true);
            migration.migrate(SKU_ID, 1000, 8, false);
            assertThat(queryInt("SELECT SUM(total_stock) FROM seckill_inventory.inventory_bucket "
                    + "WHERE sku_id=" + SKU_ID)).isEqualTo(1000);
        } finally {
            inventory.stop();
        }

        // 3. 回滚 + 恢复：删除分桶，旧 inventory 与备份一致
        execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
        assertThat(queryInt("SELECT total_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(queryInt("SELECT total_stock FROM seckill_inventory.inventory_bak "
                        + "WHERE sku_id=" + SKU_ID));
        assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                .isEqualTo(queryInt("SELECT available_stock FROM seckill_inventory.inventory_bak "
                        + "WHERE sku_id=" + SKU_ID));
        execute("DROP TABLE IF EXISTS seckill_inventory.inventory_bak");
        execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
    }
}
