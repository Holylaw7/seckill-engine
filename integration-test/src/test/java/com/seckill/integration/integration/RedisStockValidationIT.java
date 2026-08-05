package com.seckill.integration.integration;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.integration.support.TestDataHelper;
import com.seckill.inventory.InventoryApplication;
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.inventory.service.RedisStockValidationJob;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.7 Task 4：Redis 生产一致性校验（真实 MySQL/Redis）。
 * 迁移 8 桶 + Redis 预热 → PASS；污染分桶 key → FAIL（禁止 Canary 升级）。
 */
@Tag("integration")
class RedisStockValidationIT extends IntegrationTestBase {

    private static final long SKU_ID = 97001L;

    @Test
    void redisStockValidationShouldMatchMysqlAfterWarmup() throws Exception {
        TestDataHelper.resetInventory(SKU_ID, 1000, 1000, 0);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service");
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(SKU_ID, 1000, 8, false);
            redisSet("seckill:stock:" + SKU_ID, "1000");
            for (int i = 0; i < 8; i++) {
                redisSet("seckill:stock:bucket:" + SKU_ID + ":" + i, "125");
            }

            RedisStockValidationJob job =
                    inventory.context().getBean(RedisStockValidationJob.class);
            RedisStockValidationReport report = job.validate(SKU_ID);

            assertThat(report.isPass()).as("diffs=%s", report.diffs()).isTrue();
            assertThat(report.redisGlobal()).isEqualTo(1000L);
            assertThat(report.redisBucketSum()).isEqualTo(1000L);
            assertThat(report.mysqlTotal()).isEqualTo(1000L);
            assertThat(report.bucketTotalSum()).isEqualTo(1000L);

            // 污染分桶：Redis bucket=100 → 全局 1000 != SUM(900)，必须 FAIL
            redisSet("seckill:stock:bucket:" + SKU_ID + ":3", "100");
            RedisStockValidationReport polluted = job.validate(SKU_ID);
            assertThat(polluted.isPass()).isFalse();
            assertThat(polluted.diffs()).anyMatch(diff -> diff.contains("Redis global"));
        } finally {
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + SKU_ID);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID);
            cleanRedis("seckill:*");
        }
    }
}
