package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.inventory.dto.ReconcileReport;
import com.seckill.inventory.entity.Inventory;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.InventoryMapper;
import com.seckill.inventory.mapper.StockFlowMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private StockFlowMapper stockFlowMapper;
    @Mock
    private StockFlowService stockFlowService;

    private ReconciliationService newService() {
        return new ReconciliationService(inventoryMapper, stockFlowMapper, stockFlowService);
    }

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Inventory.class);
        TableInfoHelper.initTableInfo(assistant, StockFlow.class);
    }

    private Inventory inventory(int total, int locked, int available, int version) {
        Inventory inventory = new Inventory();
        inventory.setSkuId(20001L);
        inventory.setTotalStock(total);
        inventory.setLockedStock(locked);
        inventory.setAvailableStock(available);
        inventory.setVersion(version);
        return inventory;
    }

    @Test
    void checkShouldReportConsistentState() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 30, 70, 1));
        when(stockFlowMapper.selectCount(any())).thenReturn(30L, 0L);

        ReconcileReport report = newService().check(20001L);
        assertEquals(100, report.totalStock());
        assertEquals(30, report.lockedStock());
        assertEquals(70, report.availableStock());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void checkShouldDetectBrokenInvariant() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 30, 60, 1));
        when(stockFlowMapper.selectCount(any())).thenReturn(30L, 0L);

        ReconcileReport report = newService().check(20001L);
        assertTrue(report.issues().stream().anyMatch(issue -> issue.contains("不变量")));
    }

    @Test
    void checkShouldReportMissingInventory() {
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        ReconcileReport report = newService().check(20001L);
        assertTrue(report.issues().contains("库存事实不存在"));
    }

    @Test
    void repairShouldCreateRepairFlow() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 30, 5, 3));
        when(inventoryMapper.update(isNull(), any())).thenReturn(1);
        when(stockFlowService.createFlow(anyString(), anyString(), anyString(), any(),
                any(Integer.class), any(Integer.class), any(Integer.class), any(), anyString()))
                .thenReturn("SF-REPAIR-1");

        String flowNo = newService().repair(20001L, 10, "对账修复", 9L);
        assertEquals("SF-REPAIR-1", flowNo);
        verify(stockFlowService).createFlow(
                eq("REPAIR"), eq("MANUAL"), anyString(), eq(20001L),
                eq(5), eq(5), eq(10), eq(9L), eq("对账修复"));
    }

    @Test
    void repairInvalidTargetShouldThrow() {
        when(inventoryMapper.selectOne(any())).thenReturn(inventory(100, 30, 5, 3));
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().repair(20001L, 101, "修复", 9L));
        assertEquals(ErrorCode.PARAM_ERROR, e.getErrorCode());
    }
}
