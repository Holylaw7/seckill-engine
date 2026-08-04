package com.seckill.seckill.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.seckill.dto.SeckillOrderMessage;
import com.seckill.seckill.entity.SeckillPreDeduct;
import com.seckill.seckill.mapper.SeckillPreDeductMapper;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class PreDeductServiceTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SeckillPreDeduct.class);
    }

    @Mock
    private SeckillPreDeductMapper preDeductMapper;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private PreDeductService newService() {
        return new PreDeductService(preDeductMapper, snowflakeIdGenerator);
    }

    private SeckillOrderMessage message() {
        return new SeckillOrderMessage(
            "msg-001", 10001L, 20001L, 30001L, "123", 1000L, 1, null, 9900L, null);
    }

    @Test
    void createInitialShouldInsertInitRow() {
        when(snowflakeIdGenerator.nextId()).thenReturn(1L);
        newService().createInitial(message());
        ArgumentCaptor<SeckillPreDeduct> captor = ArgumentCaptor.forClass(SeckillPreDeduct.class);
        verify(preDeductMapper).insert(captor.capture());
        assertEquals("msg-001", captor.getValue().getMessageId());
        assertEquals("123", captor.getValue().getOrderId());
        assertEquals("INIT", captor.getValue().getTxStatus());
        assertEquals("DEDUCTED", captor.getValue().getDeductStatus());
    }

    @Test
    void confirmShouldUpdateWhenDeducted() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setMessageId("msg-001");
        row.setDeductStatus("DEDUCTED");
        when(preDeductMapper.selectOne(any())).thenReturn(row);
        assertTrue(newService().confirm("msg-001", "SO123"));
        verify(preDeductMapper).update(isNull(), any());
    }

    @Test
    void confirmShouldBeIdempotentWhenConfirmed() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setMessageId("msg-001");
        row.setDeductStatus("CONFIRMED");
        when(preDeductMapper.selectOne(any())).thenReturn(row);
        assertTrue(newService().confirm("msg-001", "SO123"));
        verify(preDeductMapper, never()).update(isNull(), any());
    }

    @Test
    void confirmShouldRejectWhenRecovered() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setMessageId("msg-001");
        row.setDeductStatus("RECOVERED");
        when(preDeductMapper.selectOne(any())).thenReturn(row);
        assertFalse(newService().confirm("msg-001", "SO123"));
        verify(preDeductMapper, never()).update(isNull(), any());
    }

    @Test
    void confirmMissingShouldThrow() {
        when(preDeductMapper.selectOne(any())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> newService().confirm("msg-missing", "SO123"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void findTxStatusShouldReturnStatusOrNull() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setTxStatus("SUCCESS");
        when(preDeductMapper.selectOne(any())).thenReturn(row);
        assertEquals("SUCCESS", newService().findTxStatus("msg-001"));

        when(preDeductMapper.selectOne(any())).thenReturn(null);
        assertEquals(null, newService().findTxStatus("msg-missing"));
    }

    @Test
    void markRecoveredShouldUpdateStatus() {
        newService().markRecovered("msg-001");
        verify(preDeductMapper).update(isNull(), any());
    }

    @Test
    void markTxSuccessShouldUpdateStatus() {
        newService().markTxSuccess("msg-001");
        verify(preDeductMapper).update(isNull(), any());
    }

    @Test
    void findLatestShouldReturnRowForNamespace() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setOrderId("SO123");
        row.setDeductStatus("CONFIRMED");
        when(preDeductMapper.selectOne(any())).thenReturn(row);

        SeckillPreDeduct result = newService().findLatestByUserSessionSku(10001L, 30001L, 20001L);
        assertEquals("SO123", result.getOrderId());
        assertEquals("CONFIRMED", result.getDeductStatus());
    }
}
