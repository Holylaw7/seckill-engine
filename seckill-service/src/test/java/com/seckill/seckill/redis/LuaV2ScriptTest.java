package com.seckill.seckill.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua v2 分桶脚本测试（Phase 6.2.1）：真实 Redis 7.2.4 容器，验证原子性与桶选择。
 */
@Tag("unit")
class LuaV2ScriptTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2.4")).withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;
    private static DefaultRedisScript<String> deductScript;
    private static DefaultRedisScript<String> recoverScript;

    private static final String STOCK = "seckill:stock:s1";
    private static final String TOTAL = "seckill:stock:total:s1";
    private static final String RR = "seckill:stock:rr:s1";
    private static final String BUCKET0 = "seckill:stock:bucket:s1:0";
    private static final String BUCKET1 = "seckill:stock:bucket:s1:1";

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        deductScript = load("lua/seckill_deduct_v2.lua");
        recoverScript = load("lua/seckill_recover_v2.lua");
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void reset() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void shouldRoundRobinAcrossBucketsAtomically() {
        seed(10, 5, 5);
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String result = deduct("u" + i, 1);
            assertTrue(result.startsWith("SUCCESS:"), result);
            buckets.add(Integer.valueOf(result.substring("SUCCESS:".length())));
        }
        assertEquals(6, buckets.size());
        assertEquals("4", redisTemplate.opsForValue().get(STOCK));
        int bucketSum = Integer.parseInt(redisTemplate.opsForValue().get(BUCKET0))
                + Integer.parseInt(redisTemplate.opsForValue().get(BUCKET1));
        assertEquals(4, bucketSum);
        assertTrue(buckets.stream().anyMatch(no -> no == 0));
        assertTrue(buckets.stream().anyMatch(no -> no == 1));
    }

    @Test
    void shouldRejectRepeatBuyWithoutDeducting() {
        seed(10, 5, 5);
        String first = deduct("u1", 1);
        assertTrue(first.startsWith("SUCCESS:"));
        String before = redisTemplate.opsForValue().get(STOCK);
        assertEquals("REPEAT_BUY", deduct("u1", 1));
        assertEquals(before, redisTemplate.opsForValue().get(STOCK));
    }

    @Test
    void shouldRejectWhenGlobalStockEmpty() {
        seed(1, 1, 0);
        assertTrue(deduct("u1", 1).startsWith("SUCCESS:"));
        assertEquals("STOCK_EMPTY", deduct("u2", 1));
        assertEquals("0", redisTemplate.opsForValue().get(STOCK));
    }

    @Test
    void shouldFallbackToOtherBucketWhenOneEmpty() {
        seed(5, 0, 5);
        String result = deduct("u1", 1);
        assertTrue(result.startsWith("SUCCESS:1"), result);
        assertEquals("4", redisTemplate.opsForValue().get(STOCK));
        assertEquals("4", redisTemplate.opsForValue().get(BUCKET1));
    }

    @Test
    void shouldRollbackGloballyWhenAllBucketsEmpty() {
        seed(5, 0, 0);
        assertEquals("STOCK_EMPTY", deduct("u1", 1));
        assertEquals("5", redisTemplate.opsForValue().get(STOCK));
        assertNull(redisTemplate.opsForValue().get("seckill:user:s1:u1"));
    }

    @Test
    void recoverShouldRestoreGlobalAndBucket() {
        seed(5, 0, 5);
        String result = deduct("u1", 1);
        assertTrue(result.startsWith("SUCCESS:1"));
        assertEquals("SUCCESS", recover("u1", 1, true));
        assertEquals("5", redisTemplate.opsForValue().get(STOCK));
        assertEquals("5", redisTemplate.opsForValue().get(BUCKET1));
        assertFalse(redisTemplate.hasKey("seckill:user:s1:u1"));
    }

    @Test
    void recoverShouldRejectOverTotal() {
        seed(5, 0, 5);
        assertEquals("OVER_TOTAL", recover("u1", 6, false));
        assertEquals("5", redisTemplate.opsForValue().get(STOCK));
    }

    private static String deduct(String userId, int quantity) {
        return redisTemplate.execute(deductScript,
                List.of(STOCK, "seckill:user:s1:" + userId, RR, BUCKET0, BUCKET1),
                String.valueOf(quantity), "3600", "2", "604800");
    }

    private static String recover(String userId, int quantity, boolean removeMark) {
        return redisTemplate.execute(recoverScript,
                List.of(STOCK, TOTAL, BUCKET1, "seckill:user:s1:" + userId),
                String.valueOf(quantity), removeMark ? "1" : "0");
    }

    private static void seed(int global, int bucket0, int bucket1) {
        redisTemplate.opsForValue().set(STOCK, String.valueOf(global));
        redisTemplate.opsForValue().set(TOTAL, String.valueOf(global));
        redisTemplate.opsForValue().set(BUCKET0, String.valueOf(bucket0));
        redisTemplate.opsForValue().set(BUCKET1, String.valueOf(bucket1));
    }

    private static DefaultRedisScript<String> load(String location) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(String.class);
        return script;
    }
}
