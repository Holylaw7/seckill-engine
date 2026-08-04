package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.inventory.dto.BucketReconcileReport;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import com.seckill.inventory.mapper.InventoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class BucketReconciliationTest {

    @Mock
    private InventoryBucketMapper bucketMapper;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private BucketReconciliationService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, InventoryBucket.class);
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
    }

    @Test
    void checkShouldPassWhenAllInvariantsHold() {
        service = new BucketReconciliationService(bucketMapper, inventoryMapper, redisTemplate);
        when(bucketMapper.selectBySku(20001L)).thenReturn(List.of(
                bucket(0, 125, 0), bucket(1, 125, 0)));
        when(inventoryMapper.selectOne(any())).thenReturn(summary(250, 0, 250, 0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("250");

        BucketReconcileReport report = service.check(20001L);
        assertTrue(report.isPass(), report.issues().toString());
    }

    @Test
    void checkShouldReportBucketAndRedisDrift() {
        service = new BucketReconciliationService(bucketMapper, inventoryMapper, redisTemplate);
        InventoryBucket broken = bucket(0, 125, 0);
        broken.setAvailableStock(120);
        when(bucketMapper.selectBySku(20001L)).thenReturn(List.of(broken));
        when(inventoryMapper.selectOne(any())).thenReturn(summary(125, 0, 125, 0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("120");

        BucketReconcileReport report = service.check(20001L);
        assertFalse(report.isPass());
        assertTrue(report.issues().stream().anyMatch(item -> item.contains("不变量破坏")));
        assertTrue(report.issues().stream().anyMatch(item -> item.contains("SUM(bucket.available)")));
    }

    @Test
    void syncSummaryShouldRefreshSummaryRow() {
        service = new BucketReconciliationService(bucketMapper, inventoryMapper, redisTemplate);
        when(bucketMapper.selectBySku(20001L)).thenReturn(List.of(
                bucket(0, 125, 25), bucket(1, 125, 25)));
        when(inventoryMapper.selectOne(any())).thenReturn(summary(250, 0, 250, 0));
        when(inventoryMapper.update(any(), any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:20001")).thenReturn("200");

        service.syncSummary(20001L);
        verify(inventoryMapper).update(eq(null), any());
    }

    private static InventoryBucket bucket(int bucketNo, int total, int locked) {
        InventoryBucket bucket = new InventoryBucket();
        bucket.setSkuId(20001L);
        bucket.setBucketNo(bucketNo);
        bucket.setTotalStock(total);
        bucket.setLockedStock(locked);
        bucket.setAvailableStock(total - locked);
        return bucket;
    }

    private static Inventory summary(int total, int locked, int available, int version) {
        Inventory inventory = new Inventory();
        inventory.setSkuId(20001L);
        inventory.setTotalStock(total);
        inventory.setLockedStock(locked);
        inventory.setAvailableStock(available);
        inventory.setVersion(version);
        return inventory;
    }
}
