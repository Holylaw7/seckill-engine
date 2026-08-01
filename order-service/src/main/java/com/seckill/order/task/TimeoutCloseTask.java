package com.seckill.order.task;

import com.seckill.order.config.OrderProperties;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时关单（冻结：30s 扫描、batch=200、version CAS）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutCloseTask {

    private final OrderService orderService;
    private final OrderProperties properties;

    @Scheduled(fixedDelayString = "${seckill.order.timeout-close.period-seconds:30}000")
    public void closeExpiredOrders() {
        List<SeckillOrder> orders = orderService.findExpiredWaitPay(properties.getTimeoutClose().getBatchSize());
        for (SeckillOrder order : orders) {
            if (orderService.closeOrder(order)) {
                orderService.publishCancelNotify(order);
            }
        }
    }
}
