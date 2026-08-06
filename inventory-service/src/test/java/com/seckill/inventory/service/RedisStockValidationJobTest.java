package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import com.seckill.inventory.mapper.InventoryMapper;
import com.seckill.inventory.service.RedisStockValidationJob.RedisStockValidationReport;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 6.7 Redis 库存一致性校验单测：一致通过、Redis 分桶不一致、MySQL 汇总不一致。
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class RedisStockValidationJobTest {

    private static final long SKU_ID = 20001L;

    @Mock
    private InventoryBucketMapper bucketMapper;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
        TableInfoHelper.initTableInfo(assistant, InventoryBucket.class);
    }

    @Test
    void consistentStateShouldPass() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("1000");
        when(valueOperations.get("seckill:stock:bucket:20001:0")).thenReturn("500");
        when(valueOperations.get("seckill:stock:bucket:20001:1")).thenReturn("500");
        when(bucketMapper.selectBySku(SKU_ID)).thenReturn(List.of(
                bucket(0, 500, 0), bucket(1, 500, 0)));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(1000, 0, 1000));

        RedisStockValidationReport report = service().validate(SKU_ID);

        assertThat(report.isPass()).isTrue();
        assertThat(report.redisGlobal()).isEqualTo(1000L);
        assertThat(report.redisBucketSum()).isEqualTo(1000L);
        assertThat(report.bucketTotalSum()).isEqualTo(1000L);
    }

    @Test
    void redisBucketMismatchShouldFail() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("1000");
        when(valueOperations.get("seckill:stock:bucket:20001:0")).thenReturn("400");
        when(valueOperations.get("seckill:stock:bucket:20001:1")).thenReturn("500");
        when(bucketMapper.selectBySku(SKU_ID)).thenReturn(List.of(
                bucket(0, 500, 0), bucket(1, 500, 0)));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(1000, 0, 1000));

        RedisStockValidationReport report = service().validate(SKU_ID);

        assertThat(report.isPass()).isFalse();
        assertThat(report.diffs()).anyMatch(diff -> diff.contains("Redis global"));
    }

    @Test
    void mysqlSummaryMismatchShouldFail() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("1000");
        when(valueOperations.get("seckill:stock:bucket:20001:0")).thenReturn("500");
        when(bucketMapper.selectBySku(SKU_ID)).thenReturn(List.of(bucket(0, 500, 0)));
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(1200, 0, 1200));

        RedisStockValidationReport report = service().validate(SKU_ID);

        assertThat(report.isPass()).isFalse();
        assertThat(report.diffs()).anyMatch(diff -> diff.contains("inventory.total"));
    }

    private RedisStockValidationJob service() {
        return new RedisStockValidationJob(bucketMapper, inventoryMapper, redisTemplate,
                new SimpleMeterRegistry());
    }

    private static InventoryBucket bucket(int bucketNo, int total, int locked) {
        InventoryBucket bucket = new InventoryBucket();
        bucket.setSkuId(SKU_ID);
        bucket.setBucketNo(bucketNo);
        bucket.setTotalStock(total);
        bucket.setLockedStock(locked);
        bucket.setAvailableStock(total - locked);
        return bucket;
    }

    private static Inventory inventory(int total, int locked, int available) {
        Inventory inventory = new Inventory();
        inventory.setSkuId(SKU_ID);
        inventory.setTotalStock(total);
        inventory.setLockedStock(locked);
        inventory.setAvailableStock(available);
        return inventory;
    }
}
