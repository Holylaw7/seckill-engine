package com.seckill.payment.task;

import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.entity.PaymentRefund;
import com.seckill.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款补偿（冻结：周期扫描 REFUND_FAILED 重试，幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundCompensationTask {

    private final RefundService refundService;
    private final PaymentProperties properties;

    @Scheduled(fixedDelayString = "${seckill.payment.refund-compensate.period-seconds:60}000")
    public void compensateRefundFailures() {
        List<PaymentRefund> refunds = refundService.findRefundFailures(
                properties.getRefundCompensate().getBatchSize());
        for (PaymentRefund refund : refunds) {
            try {
                refundService.retryRefund(refund.getRefundNo());
            } catch (Exception e) {
                log.warn("refund retry failed, refundNo={}, error={}", refund.getRefundNo(), e.getMessage());
            }
        }
    }
}
