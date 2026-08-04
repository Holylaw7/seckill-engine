package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.config.InventoryShardingProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class InventoryBucketRecoverTest {

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
    void recoverShouldRejectWhenLockedInsufficient() {
        when(stockFlowService.findByBiz("TIMEOUT", "SO123")).thenReturn(null);
        StockFlow deductFlow = new StockFlow();
        deductFlow.setBucketNo(2);
        when(stockFlowService.findByBiz("ORDER", "SO123")).thenReturn(deductFlow);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 2))
                .thenReturn(bucket(2, 100, 0, 0));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.recover("SO123", 20001L, 1, "TIMEOUT"));
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
    }

    @Test
    void recoverShouldRetryOnCasConflict() {
        when(stockFlowService.findByBiz("TIMEOUT", "SO123")).thenReturn(null);
        StockFlow deductFlow = new StockFlow();
        deductFlow.setBucketNo(2);
        when(stockFlowService.findByBiz("ORDER", "SO123")).thenReturn(deductFlow);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 2))
                .thenReturn(bucket(2, 100, 1, 0), bucket(2, 100, 1, 1));
        when(bucketMapper.update(any(), any())).thenReturn(0, 1);
        when(stockFlowService.createFlow(eq("RECOVER"), eq("TIMEOUT"), eq("SO123"), eq(20001L),
                eq(1), eq(99), eq(100), isNull(), eq("STOCK_RECOVER"), eq(2)))
                .thenReturn("SF2");

        assertEquals("SF2", service.recover("SO123", 20001L, 1, "TIMEOUT"));
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
}
