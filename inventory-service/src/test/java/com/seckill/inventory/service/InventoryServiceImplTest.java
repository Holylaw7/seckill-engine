package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.constant.InventoryConstants;
import com.seckill.inventory.dto.CreateOrderMessage;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.config.InventoryShardingProperties;
import com.seckill.inventory.mapper.InventoryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.seckill.inventory.service.impl.InventoryServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class InventoryServiceImplTest {

    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private StockFlowService stockFlowService;
    @Mock
    private InventoryBucketService inventoryBucketService;

    private InventoryServiceImpl newService() {
        InventoryShardingProperties properties = new InventoryShardingProperties();
        properties.setEnabled(false);
        properties.setBucketCount(1);
        return new InventoryServiceImpl(inventoryMapper, stockFlowService, inventoryBucketService,
                properties, new SimpleMeterRegistry());
    }

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
        TableInfoHelper.initTableInfo(assistant, StockFlow.class);
    }

    private CreateOrderMessage message() {
        return new CreateOrderMessage(
            "msg-001", 10001L, 20001L, 30001L, "SO123", 1000L, 1, null, null);
    }

    private Inventory inventory(int available, int locked, int version) {
        Inventory inventory = new Inventory();
        inventory.setSkuId(20001L);
        inventory.setTotalStock(100);
        inventory.setAvailableStock(available);
        inventory.setLockedStock(locked);
        inventory.setVersion(version);
        return inventory;
    }

    @Test
    void confirmDeductShouldCreateDeductFlow() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(10, 0, 5));
        when(inventoryMapper.update(isNull(), any())).thenReturn(1);
        when(stockFlowService.createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString()))
                .thenReturn("SF1");

        assertTrue(newService().confirmDeduct(message()));

        verify(stockFlowService).createFlow(
                eq("DEDUCT"), eq("ORDER"), eq("SO123"), eq(20001L),
                eq(-1), eq(10), eq(9), eq(null), eq("CREATE_ORDER"));
    }

    @Test
    void confirmDeductDuplicateShouldBeIdempotent() {
        when(stockFlowService.existsByBiz("ORDER", "SO123")).thenReturn(true);
        assertFalse(newService().confirmDeduct(message()));
        verify(inventoryMapper, never()).selectOne(any());
    }

    @Test
    void confirmDeductInsufficientShouldThrow() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(0, 10, 5));
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().confirmDeduct(message()));
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
        verify(inventoryMapper, never()).update(isNull(), any());
        verify(stockFlowService, never()).createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString());
    }

    @Test
    void confirmDeductConflictShouldRetry() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(10, 0, 5));
        when(inventoryMapper.update(isNull(), any())).thenReturn(0, 1);
        when(stockFlowService.createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString()))
                .thenReturn("SF1");

        assertTrue(newService().confirmDeduct(message()));
        verify(inventoryMapper, times(2)).selectOne(any());
    }

    @Test
    void should_fail_when_cas_conflict_exceeds_retry_limit() {
        // Arrange
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(10, 0, 5));
        when(inventoryMapper.update(isNull(), any())).thenReturn(0, 0, 0);

        // Act
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().confirmDeduct(message()));

        // Assert
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
        verify(inventoryMapper, times(InventoryConstants.CAS_MAX_RETRY)).selectOne(any());
        verify(stockFlowService, never()).createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString());
    }

    @Test
    void confirmDeductMissingInventoryShouldThrow() {
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().confirmDeduct(message()));
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
    }

    @Test
    void recoverStockShouldCreateRecoverFlowAndReturnFlowNo() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(5, 3, 2));
        when(inventoryMapper.update(isNull(), any())).thenReturn(1);
        when(stockFlowService.createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString()))
                .thenReturn("SF9");

        String flowNo = newService().recoverStock("SO123", 20001L, 1, "CANCEL");
        assertEquals("SF9", flowNo);
        verify(stockFlowService).createFlow(
                eq("RECOVER"), eq("CANCEL"), eq("SO123"), eq(20001L),
                eq(1), eq(5), eq(6), eq(null), eq("STOCK_RECOVER"));
    }

    @Test
    void recoverStockDuplicateShouldReturnExistingFlowNo() {
        com.seckill.inventory.entity.StockFlow existing = new com.seckill.inventory.entity.StockFlow();
        existing.setFlowNo("SF8");
        when(stockFlowService.findByBiz("CANCEL", "SO123")).thenReturn(existing);

        assertEquals("SF8", newService().recoverStock("SO123", 20001L, 1, "CANCEL"));
        verify(inventoryMapper, never()).selectOne(any());
    }

    @Test
    void recoverStockNoLockedShouldThrow() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(10, 0, 2));
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().recoverStock("SO123", 20001L, 1, "CANCEL"));
        assertEquals(ErrorCode.INVENTORY_ERROR, e.getErrorCode());
    }

    @Test
    void should_succeed_when_recover_cas_succeeds_after_retry() {
        // Arrange
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(5, 3, 2));
        when(inventoryMapper.update(isNull(), any())).thenReturn(0, 1);
        when(stockFlowService.createFlow(anyString(), anyString(), anyString(), anyLong(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString()))
                .thenReturn("SF10");

        // Act
        String flowNo = newService().recoverStock("SO123", 20001L, 1, "CANCEL");

        // Assert
        assertEquals("SF10", flowNo);
        verify(inventoryMapper, times(2)).selectOne(any());
        verify(stockFlowService).createFlow(
                eq("RECOVER"), eq("CANCEL"), eq("SO123"), eq(20001L),
                eq(1), eq(5), eq(6), eq(null), eq("STOCK_RECOVER"));
    }

    @Test
    void recoverStockInvalidQuantityShouldThrow() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().recoverStock("SO123", 20001L, 0, "CANCEL"));
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
    }
}
