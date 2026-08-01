package com.seckill.payment.state;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 支付状态机（冻结：所有状态变更必须经过本组件，Controller 禁止直接改 status）。
 */
@Component
@RequiredArgsConstructor
public class PaymentStateMachine {

    private final PaymentOrderMapper paymentOrderMapper;

    public boolean canTransition(String current, String target) {
        return switch (current) {
            case PaymentConstants.STATUS_CREATE -> PaymentConstants.STATUS_WAIT_PAY.equals(target);
            case PaymentConstants.STATUS_WAIT_PAY ->
                    PaymentConstants.STATUS_PAY_SUCCESS.equals(target)
                            || PaymentConstants.STATUS_PAY_FAILED.equals(target);
            case PaymentConstants.STATUS_PAY_SUCCESS -> PaymentConstants.STATUS_REFUNDING.equals(target);
            case PaymentConstants.STATUS_REFUNDING -> PaymentConstants.STATUS_REFUND_SUCCESS.equals(target);
            default -> false;
        };
    }

    /**
     * CAS 状态流转：非法跳转抛 50001 语义错误（ORDER_STATUS_INVALID 复用于状态不允许场景）。
     */
    public boolean transition(PaymentOrder payment, String targetStatus,
                              String transactionNo, LocalDateTime time) {
        if (!canTransition(payment.getStatus(), targetStatus)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "支付状态不允许该操作：" + payment.getStatus() + " -> " + targetStatus);
        }
        boolean releaseActiveKey = PaymentConstants.STATUS_PAY_FAILED.equals(targetStatus)
                || PaymentConstants.STATUS_REFUND_SUCCESS.equals(targetStatus);
        LambdaUpdateWrapper<PaymentOrder> wrapper = new LambdaUpdateWrapper<PaymentOrder>()
                .eq(PaymentOrder::getId, payment.getId())
                .eq(PaymentOrder::getStatus, payment.getStatus())
                .eq(PaymentOrder::getVersion, payment.getVersion())
                .set(PaymentOrder::getStatus, targetStatus)
                .set(PaymentOrder::getVersion, payment.getVersion() + 1);
        if (releaseActiveKey) {
            wrapper.set(PaymentOrder::getActiveOrderKey, null);
        }
        if (transactionNo != null) {
            wrapper.set(PaymentOrder::getTransactionNo, transactionNo);
        }
        if (time != null) {
            if (PaymentConstants.STATUS_PAY_SUCCESS.equals(targetStatus)) {
                wrapper.set(PaymentOrder::getPayTime, time);
            } else if (PaymentConstants.STATUS_REFUND_SUCCESS.equals(targetStatus)) {
                wrapper.set(PaymentOrder::getRefundTime, time);
            }
        }
        return paymentOrderMapper.update(null, wrapper) == 1;
    }
}
