package com.seckill.order.state;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.order.constant.OrderConstants;
import com.seckill.order.entity.SeckillOrder;
import com.seckill.order.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 订单状态机（冻结：所有状态变更必须经过本组件，Controller 禁止直接改 status）。
 */
@Component
@RequiredArgsConstructor
public class OrderStateMachine {

    private final SeckillOrderMapper orderMapper;

    public boolean canTransition(String current, String target) {
        return switch (current) {
            case OrderConstants.STATUS_CREATE -> OrderConstants.STATUS_WAIT_PAY.equals(target);
            case OrderConstants.STATUS_WAIT_PAY ->
                    OrderConstants.STATUS_PAY_SUCCESS.equals(target)
                            || OrderConstants.STATUS_CANCEL.equals(target)
                            || OrderConstants.STATUS_TIMEOUT.equals(target);
            case OrderConstants.STATUS_PAY_SUCCESS -> OrderConstants.STATUS_REFUND.equals(target);
            default -> false;
        };
    }

    /**
     * CAS 状态流转：非法流转抛 40002；冲突返回 false。
     */
    public boolean transition(SeckillOrder order, String targetStatus, String cancelReason) {
        if (!canTransition(order.getOrderStatus(), targetStatus)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "订单状态不允许该操作：" + order.getOrderStatus() + " -> " + targetStatus);
        }
        boolean releaseActiveKey = OrderConstants.STATUS_CANCEL.equals(targetStatus)
                || OrderConstants.STATUS_TIMEOUT.equals(targetStatus);
        String newActiveKey = releaseActiveKey ? null : order.getActiveKey();

        LambdaUpdateWrapper<SeckillOrder> wrapper = new LambdaUpdateWrapper<SeckillOrder>()
                .eq(SeckillOrder::getId, order.getId())
                .eq(SeckillOrder::getOrderStatus, order.getOrderStatus())
                .eq(SeckillOrder::getVersion, order.getVersion())
                .set(SeckillOrder::getOrderStatus, targetStatus)
                .set(SeckillOrder::getActiveKey, newActiveKey)
                .set(SeckillOrder::getCancelReason, cancelReason)
                .set(SeckillOrder::getVersion, order.getVersion() + 1);
        if (OrderConstants.STATUS_PAY_SUCCESS.equals(targetStatus)) {
            wrapper.set(SeckillOrder::getPaidAt, LocalDateTime.now());
        }
        if (releaseActiveKey) {
            wrapper.set(SeckillOrder::getCancelNotifyStatus, OrderConstants.NOTIFY_PENDING);
        }
        return orderMapper.update(null, wrapper) == 1;
    }
}
