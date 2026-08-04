package com.seckill.inventory.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.inventory.entity.StockFlow;
import com.seckill.inventory.mapper.StockFlowMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class StockFlowServiceTest {

    @Mock
    private StockFlowMapper stockFlowMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private StockFlowService newService() {
        return new StockFlowService(stockFlowMapper, snowflakeIdGenerator);
    }

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockFlow.class);
    }

    @Test
    void createFlowShouldInsertWithFlowNo() {
        when(stockFlowMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);

        String flowNo = newService().createFlow("DEDUCT", "ORDER", "SO123", 20001L,
                -1, 10, 9, null, "test");
        assertEquals("SF1", flowNo);

        ArgumentCaptor<StockFlow> captor = ArgumentCaptor.forClass(StockFlow.class);
        verify(stockFlowMapper).insert(captor.capture());
        assertEquals("SF1", captor.getValue().getFlowNo());
        assertEquals("DEDUCT", captor.getValue().getChangeType());
        assertEquals(10, captor.getValue().getBeforeQty());
        assertEquals(9, captor.getValue().getAfterQty());
    }

    @Test
    void createFlowDuplicateShouldReturnExisting() {
        StockFlow existing = new StockFlow();
        existing.setFlowNo("SF9");
        when(stockFlowMapper.selectOne(any())).thenReturn(existing);

        assertEquals("SF9", newService().createFlow("DEDUCT", "ORDER", "SO123", 20001L,
                -1, 10, 9, null, "test"));
        verify(stockFlowMapper, never()).insert(any(StockFlow.class));
    }

    @Test
    void createFlowInsertConflictShouldFallbackToExisting() {
        StockFlow existing = new StockFlow();
        existing.setFlowNo("SF7");
        when(stockFlowMapper.selectOne(any())).thenReturn(null, existing);
        doThrow(new DuplicateKeyException("dup")).when(stockFlowMapper).insert(any(StockFlow.class));

        assertEquals("SF7", newService().createFlow("DEDUCT", "ORDER", "SO123", 20001L,
                -1, 10, 9, null, "test"));
    }

    @Test
    void existsByBizShouldReflectLookup() {
        when(stockFlowMapper.selectOne(any())).thenReturn(new StockFlow());
        assertTrue(newService().existsByBiz("ORDER", "SO123"));

        when(stockFlowMapper.selectOne(any())).thenReturn(null);
        assertFalse(newService().existsByBiz("ORDER", "SO999"));
    }
}
