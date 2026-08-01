package com.seckill.order.task;

import com.seckill.order.config.OrderProperties;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelNotifyCompensationTaskTest {

    @Mock
    private OrderService orderService;

    private CancelNotifyCompensationTask task;

    @BeforeEach
    void setUp() {
        OrderProperties properties = new OrderProperties();
        properties.getTimeoutClose().setBatchSize(200);
        task = new CancelNotifyCompensationTask(orderService, properties);
    }

    @Test
    void shouldRepublishPendingCancelNotify() {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        when(orderService.findPendingCancelNotify(200)).thenReturn(List.of(order));

        task.compensatePendingCancelNotify();
        verify(orderService).publishCancelNotify(order);
    }
}
