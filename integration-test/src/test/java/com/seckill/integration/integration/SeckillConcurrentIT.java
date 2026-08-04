package com.seckill.integration.integration;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SC-01 Lua 真实并发秒杀：100 库存 / 200 并发 / 零超卖。
 */
@Tag("integration")
class SeckillConcurrentIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(SeckillConcurrentIT.class);

    private static final long SESSION_ID = 31001L;
    private static final long SKU_ID = 21001L;
    private static final int STOCK = 100;
    private static final int USER_COUNT = 200;
    private static final long FIRST_USER_ID = 11001L;

    private static ServiceLauncher.RunningService SECKILL;
    private static ServiceLauncher.RunningService ORDER;
    private static ServiceLauncher.RunningService INVENTORY;

    @BeforeAll
    static void startServices() throws Exception {
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
        TestDataHelper.cleanupOrderNamespace(FIRST_USER_ID, SESSION_ID, SKU_ID);
        TestDataHelper.resetInventory(SKU_ID, STOCK, STOCK, 0);
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
    }

    @Test
    void should_not_oversell_with_200_concurrent_requests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(USER_COUNT);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long startNanos = System.nanoTime();

        for (int i = 0; i < USER_COUNT; i++) {
            long userId = FIRST_USER_ID + i;
            String traceId = "test-concurrent-" + i + "-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                try {
                    ready.await();
                    Result<ExecuteResponse> result = TestHttp.execute(
                            "http://localhost:" + SECKILL.port(), userId, SESSION_ID, SKU_ID, 1, traceId);
                    if (result != null && result.getCode() == 0) {
                        success.incrementAndGet();
                    } else {
                        failure.incrementAndGet();
                    }
                } catch (Exception e) {
                    failure.incrementAndGet();
                }
            }));
        }

        ready.countDown();
        for (Future<?> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        pool.shutdownNow();

        int successCount = success.get();
        int failCount = failure.get();
        double qps = USER_COUNT / (elapsedMs / 1000.0);
        double successRate = successCount * 100.0 / USER_COUNT;
        log.info("seckill concurrent result: success={}, fail={}, elapsedMs={}, qps={}, successRate={}%",
                successCount, failCount, elapsedMs, String.format("%.2f", qps), String.format("%.2f", successRate));

        assertThat(successCount).isLessThanOrEqualTo(STOCK);
        assertThat(failCount).isGreaterThanOrEqualTo(USER_COUNT - STOCK);
        assertThat(Integer.parseInt(redisGet("seckill:stock:" + SKU_ID))).isEqualTo(STOCK - successCount);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(TestDataHelper.countOrders(SESSION_ID, SKU_ID)).isEqualTo(successCount);
            assertThat(TestDataHelper.countPreDeduct(SESSION_ID, SKU_ID)).isEqualTo(successCount);
            assertThat(TestDataHelper.countDeductFlow(SKU_ID)).isEqualTo(successCount);
            assertThat(TestDataHelper.countIdempotentByUserRange(FIRST_USER_ID, FIRST_USER_ID + USER_COUNT - 1))
                    .isEqualTo(successCount);
            assertThat(queryInt("SELECT available_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK - successCount);
            assertThat(queryInt("SELECT locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(successCount);
            assertThat(queryInt("SELECT available_stock + locked_stock FROM seckill_inventory.inventory WHERE sku_id=" + SKU_ID))
                    .isEqualTo(STOCK);
        });
        assertThat(countRedisKeys("seckill:user:" + SKU_ID + ":*")).isEqualTo(successCount);
    }
}
