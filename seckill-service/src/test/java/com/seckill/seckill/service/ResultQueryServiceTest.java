package com.seckill.seckill.service;

import com.seckill.seckill.dto.ResultQueryResponse;
import com.seckill.seckill.entity.SeckillPreDeduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class ResultQueryServiceTest {

    @Mock
    private PreDeductService preDeductService;

    private ResultQueryService newService() {
        return new ResultQueryService(preDeductService);
    }

    @Test
    void noRecordShouldReturnNone() {
        when(preDeductService.findLatestByUserSessionSku(anyLong(), anyLong(), anyLong())).thenReturn(null);
        ResultQueryResponse response = newService().query(10001L, 30001L, 20001L);
        assertEquals("NONE", response.getStatus());
        assertNull(response.getOrderId());
    }

    @Test
    void confirmedShouldReturnSuccess() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setDeductStatus("CONFIRMED");
        row.setOrderId("SO123");
        when(preDeductService.findLatestByUserSessionSku(anyLong(), anyLong(), anyLong())).thenReturn(row);
        ResultQueryResponse response = newService().query(10001L, 30001L, 20001L);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("SO123", response.getOrderId());
    }

    @Test
    void recoveredShouldReturnFailed() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setDeductStatus("RECOVERED");
        when(preDeductService.findLatestByUserSessionSku(anyLong(), anyLong(), anyLong())).thenReturn(row);
        assertEquals("FAILED", newService().query(10001L, 30001L, 20001L).getStatus());
    }

    @Test
    void deductedShouldReturnProcessing() {
        SeckillPreDeduct row = new SeckillPreDeduct();
        row.setDeductStatus("DEDUCTED");
        row.setOrderId("SO123");
        when(preDeductService.findLatestByUserSessionSku(anyLong(), anyLong(), anyLong())).thenReturn(row);
        ResultQueryResponse response = newService().query(10001L, 30001L, 20001L);
        assertEquals("DEDUCTED", response.getStatus());
        assertEquals("SO123", response.getOrderId());
    }
}
