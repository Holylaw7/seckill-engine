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
import com.seckill.inventory.service.InventoryBucketMigrationService;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.dto.ExecuteResponse;
import com.seckill.seckill.redis.StockService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6.5.4 Canary Release Validation：
 * Stage 1（N=4）与 Stage 2（N=8）真实秒杀（Redis Lua v2 + 分桶 DEDUCT），
 * 验证零超卖、Redis==SUM(bucket.available)、available+locked=total。
 */
@Tag("integration")
class CanaryDrillIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(CanaryDrillIT.class);
    private static final int STOCK = 1000;
    private static final int CONCURRENCY = 150;

    @Test
    void canaryStagesShouldKeepZeroOversell() throws Exception {
        for (int bucketCount : new int[]{4, 8}) {
            runStage(bucketCount);
        }
    }

    private static void runStage(int bucketCount) throws Exception {
        long runBase = 8_000_000L + bucketCount * 10_000_000L;
        long sessionId = runBase + (System.currentTimeMillis() % 1_000_000L);
        long skuId = sessionId + 100_000L;
        TestDataHelper.seedSeckill(sessionId, skuId, STOCK, "READY", "99.00");
        TestDataHelper.resetInventory(skuId, STOCK, STOCK, 0);

        ServiceLauncher.RunningService seckill = ServiceSupport.start(
                SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + bucketCount);
        ServiceLauncher.RunningService inventory = ServiceSupport.start(
                InventoryApplication.class, "inventory", "seckill_inventory", "inventory-service",
                "--inventory.sharding.enabled=true",
                "--inventory.sharding.bucket-count=" + bucketCount);
        try {
            inventory.context().getBean(InventoryBucketMigrationService.class)
                    .migrate(skuId, STOCK, bucketCount, false);
            StockService stockService = seckill.context().getBean(StockService.class);
            stockService.prepare(String.valueOf(skuId), STOCK);
            for (int i = 0; i < bucketCount; i++) {
                stockService.prepareBucket(String.valueOf(skuId), i, STOCK / bucketCount);
            }

            AtomicInteger success = new AtomicInteger();
            LoadTestExecutor.run(new LoadConfig(CONCURRENCY, CONCURRENCY, Duration.ofMinutes(3)),
                    index -> {
                        long user = runBase + index + 1;
                        Result<ExecuteResponse> result = TestHttp.execute(
                                "http://localhost:" + seckill.port(), user, sessionId, skuId, 1,
                                "canary-" + bucketCount + "-" + index);
                        boolean ok = result != null && result.getCode() == 0;
                        if (ok) {
                            success.incrementAndGet();
                        }
                        return ok;
                    });

            assertThat(success.get()).isLessThanOrEqualTo(STOCK);
            await().atMost(Duration.ofSeconds(240)).untilAsserted(() ->
                    assertThat(TestDataHelper.countDeductFlow(skuId)).isEqualTo(success.get()));
            int sumAvailable = 0;
            for (int i = 0; i < bucketCount; i++) {
                int available = queryInt("SELECT available_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + skuId + " AND bucket_no=" + i);
                int locked = queryInt("SELECT locked_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + skuId + " AND bucket_no=" + i);
                int total = queryInt("SELECT total_stock FROM seckill_inventory.inventory_bucket "
                        + "WHERE sku_id=" + skuId + " AND bucket_no=" + i);
                assertThat(available + locked).isEqualTo(total);
                sumAvailable += available;
            }
            assertThat(sumAvailable).isEqualTo(STOCK - success.get());
            assertThat(redisGet("seckill:stock:" + skuId))
                    .isEqualTo(String.valueOf(STOCK - success.get()));
            log.info("CANARY stage N={}: success={}, sumAvailable={}, zeroOversell=OK", 
                    bucketCount, success.get(), sumAvailable);
        } finally {
            seckill.stop();
            inventory.stop();
            execute("DELETE FROM seckill_inventory.inventory_bucket WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + skuId);
            execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + sessionId);
            execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + sessionId);
            cleanRedis("seckill:*");
        }
    }
}
