package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class InventoryBucketServiceTest {

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
    void deductShouldSucceedAndWriteBucketFlow() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(false);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 3))
                .thenReturn(bucket(3, 100, 0, 0));
        when(bucketMapper.update(any(), any())).thenReturn(1);
        when(stockFlowService.createFlow(eq("DEDUCT"), eq("ORDER"), eq("SO123"), eq(20001L),
                eq(-1), eq(100), eq(99), isNull(), eq("CREATE_ORDER"), eq(3)))
                .thenReturn("SF1");

        assertTrue(service.deduct(message(3)));
    }

    @Test
    void deductShouldRejectWhenBucketInsufficient() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(false);
        InventoryBucket empty = bucket(3, 100, 0, 0);
        empty.setAvailableStock(0);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 3)).thenReturn(empty);

        BusinessException e = assertThrows(BusinessException.class, () -> service.deduct(message(3)));
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
    }

    @Test
    void deductShouldRejectWhenBucketMissing() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(false);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 3)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.deduct(message(3)));
    }

    @Test
    void deductShouldBeIdempotentWhenFlowExists() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(true);

        assertFalse(service.deduct(message(3)));
        verify(bucketMapper, never()).selectBySkuAndBucketForUpdate(anyLong(), anyInt());
    }

    @Test
    void recoverShouldLocateBucketFromDeductFlow() {
        when(stockFlowService.findByBiz("TIMEOUT", "SO123")).thenReturn(null);
        StockFlow deductFlow = new StockFlow();
        deductFlow.setBucketNo(5);
        when(stockFlowService.findByBiz("ORDER", "SO123")).thenReturn(deductFlow);
        when(bucketMapper.selectBySkuAndBucketForUpdate(20001L, 5))
                .thenReturn(bucket(5, 100, 1, 0));
        when(bucketMapper.update(any(), any())).thenReturn(1);
        when(stockFlowService.createFlow(eq("RECOVER"), eq("TIMEOUT"), eq("SO123"), eq(20001L),
                eq(1), eq(99), eq(100), isNull(), eq("STOCK_RECOVER"), eq(5)))
                .thenReturn("SF2");

        assertEquals("SF2", service.recover("SO123", 20001L, 1, "TIMEOUT"));
    }

    @Test
    void recoverShouldBeIdempotentWhenFlowExists() {
        StockFlow existing = new StockFlow();
        existing.setFlowNo("SF2");
        when(stockFlowService.findByBiz("CANCEL", "SO123")).thenReturn(existing);

        assertEquals("SF2", service.recover("SO123", 20001L, 1, "CANCEL"));
        verify(bucketMapper, never()).selectBySkuAndBucketForUpdate(anyLong(), anyInt());
    }

    @Test
    void recoverShouldRejectWhenNoBucketLocator() {
        when(stockFlowService.findByBiz("TIMEOUT", "SO123")).thenReturn(null);
        when(stockFlowService.findByBiz("ORDER", "SO123")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.recover("SO123", 20001L, 1, "TIMEOUT"));
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
