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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("unit")
class TimeoutCloseTaskTest {

    @Mock
    private OrderService orderService;

    private TimeoutCloseTask task;

    @BeforeEach
    void setUp() {
        OrderProperties properties = new OrderProperties();
        properties.getTimeoutClose().setBatchSize(200);
        task = new TimeoutCloseTask(orderService, properties);
    }

    @Test
    void shouldCloseAndPublishExpiredOrders() {
        SeckillOrder order1 = new SeckillOrder();
        order1.setId(1L);
        SeckillOrder order2 = new SeckillOrder();
        order2.setId(2L);
        when(orderService.findExpiredWaitPay(200)).thenReturn(List.of(order1, order2));
        when(orderService.closeOrder(order1)).thenReturn(true);
        when(orderService.closeOrder(order2)).thenReturn(true);

        task.closeExpiredOrders();
        verify(orderService, times(2)).publishCancelNotify(any(SeckillOrder.class));
    }

    @Test
    void conflictOrderShouldSkipPublish() {
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        when(orderService.findExpiredWaitPay(200)).thenReturn(List.of(order));
        when(orderService.closeOrder(order)).thenReturn(false);

        task.closeExpiredOrders();
        verify(orderService, never()).publishCancelNotify(any());
    }

    @Test
    void should_close_order_once_when_task_runs_multiple_times() {
        // Arrange
        SeckillOrder order = new SeckillOrder();
        order.setId(1L);
        // 第一次扫描命中 WAIT_PAY 过期订单；第二次扫描不再返回（已 TIMEOUT，不在查询范围）
        when(orderService.findExpiredWaitPay(200)).thenReturn(List.of(order), List.of());
        when(orderService.closeOrder(order)).thenReturn(true);

        // Act
        task.closeExpiredOrders();
        task.closeExpiredOrders();

        // Assert
        verify(orderService, times(1)).closeOrder(any());
        verify(orderService, times(1)).publishCancelNotify(any());
    }
}
