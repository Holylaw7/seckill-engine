package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.inventory.config.InventoryShardingProperties;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.entity.InventoryBucket;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.InventoryBucketMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class InventoryBucketDeductTest {

    @Mock
    private InventoryBucketMapper bucketMapper;
    @Mock
    private StockFlowService stockFlowService;

    private InventoryBucketService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, InventoryBucket.class);
        TableInfoHelper.initTableInfo(assistant, StockFlow.class);
    }

    @BeforeEach
    void setUp() {
        InventoryShardingProperties properties = new InventoryShardingProperties();
        properties.setEnabled(true);
        properties.setBucketCount(8);
        service = new InventoryBucketService(bucketMapper, stockFlowService, properties);
    }

    @Test
    void casConflictShouldRetryUntilSuccess() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(false);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 1))
                .thenReturn(bucket(1, 100, 0, 0), bucket(1, 100, 0, 1));
        when(bucketMapper.update(any(), any())).thenReturn(0, 1);
        when(stockFlowService.createFlow(eq("DEDUCT"), eq("ORDER"), eq("SO123"), eq(20001L),
                eq(-1), eq(100), eq(99), isNull(), eq("CREATE_ORDER"), eq(1)))
                .thenReturn("SF1");

        assertTrue(service.deduct(message(1)));
        verify(bucketMapper, times(2)).selectBySkuAndBucketForUpdate(20001L, 1);
    }

    @Test
    void legacyMessageWithoutBucketNoShouldRouteByOrderHashWhenMultiBucket() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(false);
        int expected = Math.floorMod("SO123".hashCode(), 8);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, expected))
                .thenReturn(bucket(expected, 100, 0, 0));
        when(bucketMapper.update(any(), any())).thenReturn(1);
        when(stockFlowService.createFlow(eq("DEDUCT"), eq("ORDER"), eq("SO123"), eq(20001L),
                eq(-1), eq(100), eq(99), isNull(), eq("CREATE_ORDER"), eq(expected)))
                .thenReturn("SF1");

        assertTrue(service.deduct(message(null)));
    }

    private static InventoryBucket bucket(int bucketNo, int total, int locked, int version) {
        InventoryBucket bucket = new InventoryBucket();
        bucket.setSkuId(20001L);
        bucket.setBucketNo(bucketNo);
        bucket.setTotalStock(total);
        bucket.setLockedStock(locked);
        bucket.setAvailableStock(total - locked);
        bucket.setVersion(version);
        return bucket;
    }

    private static CreateOrderMessage message(Integer bucketNo) {
        return new CreateOrderMessage("msg-001", 10001L, 20001L, 30001L,
                "SO123", 1000L, 1, null, bucketNo);
    }
}
