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
 * CANCEL_ORDER 补偿（冻结：1 分钟扫描 PENDING 补发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelNotifyCompensationTask {

    private final OrderService orderService;
    private final OrderProperties properties;

    @Scheduled(fixedDelayString = "${seckill.order.cancel-notify-compensate-period-seconds:60}000")
    public void compensatePendingCancelNotify() {
        List<SeckillOrder> orders = orderService.findPendingCancelNotify(
                properties.getTimeoutClose().getBatchSize());
        for (SeckillOrder order : orders) {
            orderService.publishCancelNotify(order);
        }
    }
}
