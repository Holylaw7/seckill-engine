package com.seckill.integration.load;

import com.seckill.integration.support.IntegrationTestBase;
import com.seckill.integration.support.ServiceLauncher;
import com.seckill.integration.support.ServiceSupport;
import com.seckill.seckill.SeckillApplication;
import com.seckill.seckill.redis.StockDeductResult;
import com.seckill.seckill.redis.StockService;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * L-02 Redis Lua 原子性压力测试：
 * 库存 1000 / 并发 10000 / 请求 10000 / 独立用户 10000，
 * 直接调用真实 StockService.preDeduct（真实 Redis Lua，不经 HTTP）。
 * 默认跳过（-Dload.enabled=true 执行）。
 */
@LoadTest
@Tag("load")
@Tag("integration")
class RedisLuaAtomicityLoadTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(RedisLuaAtomicityLoadTest.class);

    private static final int STOCK = 1000;
    private static final int CONCURRENCY = 10000;
    private static final int TOTAL_REQUESTS = 10000;
    private static final long USER_KEY_TTL_SECONDS = 3600;

    /** 每次运行独立 namespace（基于运行时刻生成） */
    private static final long RUN_ID = System.currentTimeMillis();
    private static final long SKU_ID = 2_000_000_000L + (RUN_ID % 1_000_000_000L);
    private static final long REPEAT_SKU = SKU_ID + 1;
    private static final long BASE_USER = 9_000_000_000L + (RUN_ID % 100_000L) * 10_000L;
    private static final long REPEAT_USER = BASE_USER + 9_999_999L;

    private static ServiceLauncher.RunningService SECKILL;

    @BeforeAll
    static void startServices() {
        SECKILL = ServiceSupport.start(SeckillApplication.class, "seckill", "seckill_seckill", "seckill-service",
                "--seckill.core.risk-check.enabled=false");
    }

    @AfterAll
    static void tearDown() throws Exception {
        cleanRedis("seckill:stock:" + SKU_ID, "seckill:stock:total:" + SKU_ID,
                "seckill:user:" + SKU_ID + ":*",
                "seckill:stock:" + REPEAT_SKU, "seckill:stock:total:" + REPEAT_SKU,
                "seckill:user:" + REPEAT_SKU + ":*");
        if (SECKILL != null) {
            SECKILL.stop();
        }
    }

    @BeforeEach
    void initRedis() {
        cleanRedis("seckill:stock:" + SKU_ID, "seckill:stock:total:" + SKU_ID,
                "seckill:user:" + SKU_ID + ":*");
        redisSet("seckill:stock:" + SKU_ID, String.valueOf(STOCK));
        redisSet("seckill:stock:total:" + SKU_ID, String.valueOf(STOCK));
    }

    @Test
    void l02_should_be_atomic_with_10000_concurrency() throws Exception {
        StockService stockService = SECKILL.context().getBean(StockService.class);
        Map<String, Integer> resultCodes = new ConcurrentHashMap<>();
        AtomicInteger exceptions = new AtomicInteger();
        LoadMetrics metrics;
        ResourceMonitor.Sample resourceSample;
        try (ResourceMonitor monitor = ResourceMonitor.start(Duration.ofSeconds(2), REDIS.getContainerId())) {
            metrics = LoadTestExecutor.run(
                    new LoadConfig(CONCURRENCY, TOTAL_REQUESTS, Duration.ofMinutes(10)),
                    index -> {
                        long userId = BASE_USER + index;
                        try {
                            StockDeductResult result = stockService.preDeduct(
                                    String.valueOf(SKU_ID), String.valueOf(userId), 1, USER_KEY_TTL_SECONDS);
                            resultCodes.merge(result.name(), 1, Integer::sum);
                            return result == StockDeductResult.SUCCESS;
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                            resultCodes.merge("EXCEPTION", 1, Integer::sum);
                            return false;
                        }
                    });
            await().atMost(Duration.ofSeconds(10)).until(() -> monitor.latest() != null);
            resourceSample = monitor.latest();
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resultCodeDistribution", new TreeMap<>(resultCodes));
        extra.put("luaExceptions", exceptions.get());
        extra.put("redis", Map.of(
                "usedMemoryBytes", redisInfoField("memory", "used_memory"),
                "connectedClients", redisInfoField("clients", "connected_clients")));
        if (resourceSample != null) {
            extra.put("resource", Map.of(
                    "cpuPercent", resourceSample.cpuPercent(),
                    "memoryBytes", resourceSample.memoryBytes(),
                    "heapUsedBytes", resourceSample.heapUsedBytes(),
                    "threadCount", resourceSample.threadCount(),
                    "gcCount", resourceSample.gcCount(),
                    "gcTimeMs", resourceSample.gcTimeMs()));
        }
        LoadReport.writeJson("L-02", metrics, extra, LoadReport.defaultDir());
        LoadReport.writeCsv("L-02", metrics, LoadReport.defaultDir());
        log.info("L-02 result: total={}, success={}, fail={}, qps={}, avgRT={}ms, p50={}ms, p95={}ms, p99={}ms, codes={}, exceptions={}",
                metrics.totalRequests(), metrics.successCount(), metrics.failureCount(),
                String.format("%.2f", metrics.qps()), String.format("%.2f", metrics.avgRtMs()),
                String.format("%.2f", metrics.p50Ms()), String.format("%.2f", metrics.p95Ms()),
                String.format("%.2f", metrics.p99Ms()), resultCodes, exceptions.get());

        int success = metrics.successCount();
        assertThat(success).isLessThanOrEqualTo(STOCK);
        assertThat(exceptions.get()).isZero();
        assertThat(resultCodes.getOrDefault("NOT_READY", 0)).isZero();
        assertThat(resultCodes.getOrDefault("SUCCESS", 0)).isEqualTo(success);
        assertThat(redisGet("seckill:stock:" + SKU_ID)).isEqualTo(String.valueOf(STOCK - success));
        assertThat(Integer.parseInt(redisGet("seckill:stock:" + SKU_ID))).isGreaterThanOrEqualTo(0);
        assertThat(countRedisKeys("seckill:user:" + SKU_ID + ":*")).isEqualTo(success);
    }

    @Test
    void repeatBuy_should_only_allow_one_success_per_user() {
        cleanRedis("seckill:stock:" + REPEAT_SKU, "seckill:stock:total:" + REPEAT_SKU,
                "seckill:user:" + REPEAT_SKU + ":*");
        redisSet("seckill:stock:" + REPEAT_SKU, "10");
        redisSet("seckill:stock:total:" + REPEAT_SKU, "10");

        StockService stockService = SECKILL.context().getBean(StockService.class);
        StockDeductResult first = stockService.preDeduct(
                String.valueOf(REPEAT_SKU), String.valueOf(REPEAT_USER), 1, USER_KEY_TTL_SECONDS);
        assertThat(first).isEqualTo(StockDeductResult.SUCCESS);
        assertThat(redisGet("seckill:stock:" + REPEAT_SKU)).isEqualTo("9");

        StockDeductResult second = stockService.preDeduct(
                String.valueOf(REPEAT_SKU), String.valueOf(REPEAT_USER), 1, USER_KEY_TTL_SECONDS);
        assertThat(second).isEqualTo(StockDeductResult.REPEAT_BUY);
        assertThat(redisGet("seckill:stock:" + REPEAT_SKU)).isEqualTo("9");
        assertThat(countRedisKeys("seckill:user:" + REPEAT_SKU + ":*")).isEqualTo(1);
    }

    private static String redisInfoField(String section, String field) {
        try (StatefulRedisConnection<String, String> connection = redisConnection()) {
            String info = connection.sync().info(section);
            for (String line : info.split("\\R")) {
                if (line.startsWith(field + ":")) {
                    return line.substring(field.length() + 1).trim();
                }
            }
        } catch (Exception e) {
            log.warn("redis info failed, section={}, field={}", section, field, e);
        }
        return null;
    }
}
