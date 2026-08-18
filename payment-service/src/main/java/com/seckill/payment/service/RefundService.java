package com.seckill.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.RefundChannelRequest;
import com.seckill.payment.dto.RefundChannelResult;
import com.seckill.payment.dto.RefundRequest;
import com.seckill.payment.dto.RefundResult;
import com.seckill.payment.dto.RefundSuccessMessage;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.entity.PaymentRefund;
import com.seckill.payment.mapper.PaymentRefundMapper;
import com.seckill.payment.mq.RefundSuccessProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentRefundMapper refundMapper;
    private final PaymentService paymentService;
    private final PaymentChannelRouter channelRouter;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final RefundSuccessProducer refundSuccessProducer;

    @Transactional
    public RefundResult refund(RefundRequest request) {
        PaymentRefund existing = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getRefundNo, request.getRefundNo()));
        if (existing != null) {
            retryRefundSuccessNotification(existing);
            return new RefundResult(existing.getRefundNo(), existing.getStatus(), existing.getChannelRefundNo());
        }

        PaymentOrder payment = paymentService.getByPaymentNo(request.getPaymentNo());
        if (payment == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        if (!PaymentConstants.STATUS_PAY_SUCCESS.equals(payment.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "仅支付成功可退款");
        }
        if (!paymentService.startRefund(payment)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "退款状态流转失败");
        }

        PaymentRefund refund = new PaymentRefund();
        refund.setId(snowflakeIdGenerator.nextId());
        refund.setRefundNo(request.getRefundNo());
        refund.setPaymentNo(request.getPaymentNo());
        refund.setOrderNo(request.getOrderNo());
        refund.setUserId(request.getUserId());
        refund.setAmount(request.getAmount());
        refund.setStatus(PaymentConstants.REFUND_STATUS_REFUNDING);
        refund.setOrderNotifyStatus(PaymentConstants.REFUND_NOTIFY_PENDING);
        refund.setVersion(0);
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException e) {
            PaymentRefund duplicate = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefund>()
                    .eq(PaymentRefund::getRefundNo, request.getRefundNo()));
            if (duplicate != null) {
                retryRefundSuccessNotification(duplicate);
                return new RefundResult(duplicate.getRefundNo(), duplicate.getStatus(), duplicate.getChannelRefundNo());
            }
            throw e;
        }
        return executeChannelRefund(refund);
    }

    /**
     * 补偿重试（幂等：按 refundNo 查状态，仅 REFUND_FAILED 重试）。
     */
    @Transactional
    public RefundResult retryRefund(String refundNo) {
        PaymentRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getRefundNo, refundNo));
        if (refund == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "退款单不存在");
        }
        if (PaymentConstants.REFUND_STATUS_SUCCESS.equals(refund.getStatus())) {
            retryRefundSuccessNotification(refund);
            return new RefundResult(refund.getRefundNo(), refund.getStatus(), refund.getChannelRefundNo());
        }
        return executeChannelRefund(refund);
    }

    public List<PaymentRefund> findRefundFailures(int batchSize) {
        return refundMapper.selectList(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getStatus, PaymentConstants.REFUND_STATUS_FAILED)
                .last("LIMIT " + batchSize));
    }

    public List<PaymentRefund> findPendingRefundNotifications(int batchSize) {
        return refundMapper.selectList(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getStatus, PaymentConstants.REFUND_STATUS_SUCCESS)
                .eq(PaymentRefund::getOrderNotifyStatus, PaymentConstants.REFUND_NOTIFY_PENDING)
                .last("LIMIT " + batchSize));
    }

    public void retryRefundSuccessNotification(String refundNo) {
        PaymentRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getRefundNo, refundNo));
        if (refund != null) {
            retryRefundSuccessNotification(refund);
        }
    }

    private RefundResult executeChannelRefund(PaymentRefund refund) {
        PaymentOrder payment = paymentService.getByPaymentNo(refund.getPaymentNo());
        PaymentChannel channel = channelRouter.getChannel(payment.getChannel());
        RefundChannelResult channelResult = channel.refund(new RefundChannelRequest(
                refund.getRefundNo(), refund.getPaymentNo(), refund.getAmount()));
        if (channelResult.success()) {
            refund.setStatus(PaymentConstants.REFUND_STATUS_SUCCESS);
            refund.setChannelRefundNo(channelResult.channelRefundNo());
            refund.setOrderNotifyStatus(PaymentConstants.REFUND_NOTIFY_PENDING);
            refundMapper.updateById(refund);
            paymentService.completeRefundSuccess(payment);
            retryRefundSuccessNotification(refund);
            return new RefundResult(refund.getRefundNo(), refund.getStatus(), refund.getChannelRefundNo());
        }
        refund.setStatus(PaymentConstants.REFUND_STATUS_FAILED);
        refundMapper.updateById(refund);
        log.warn("channel refund failed, refundNo={}, paymentNo={} -> 补偿任务",
                refund.getRefundNo(), refund.getPaymentNo());
        return new RefundResult(refund.getRefundNo(), refund.getStatus(), null);
    }

    private void retryRefundSuccessNotification(PaymentRefund refund) {
        if (!PaymentConstants.REFUND_STATUS_SUCCESS.equals(refund.getStatus())
                || PaymentConstants.REFUND_NOTIFY_SENT.equals(refund.getOrderNotifyStatus())) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            String refundNo = refund.getRefundNo();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendRefundSuccessNotificationAfterCommit(refundNo);
                }
            });
            return;
        }
        sendRefundSuccessNotification(refund);
    }

    /**
     * 事务提交后发送退款成功事件，避免订单域先于 payment 本地事务看到 REFUND。
     * 发送失败保持 PENDING，由补偿任务重试；重复消息由订单域按 refundNo 幂等。
     */
    private void sendRefundSuccessNotificationAfterCommit(String refundNo) {
        PaymentRefund refund = refundMapper.selectOne(new LambdaQueryWrapper<PaymentRefund>()
                .eq(PaymentRefund::getRefundNo, refundNo));
        if (refund == null
                || !PaymentConstants.REFUND_STATUS_SUCCESS.equals(refund.getStatus())
                || PaymentConstants.REFUND_NOTIFY_SENT.equals(refund.getOrderNotifyStatus())) {
            return;
        }
        sendRefundSuccessNotification(refund);
    }

    private void sendRefundSuccessNotification(PaymentRefund refund) {
        RefundSuccessMessage message = new RefundSuccessMessage(
                "refund-success:" + refund.getRefundNo(),
                refund.getRefundNo(),
                refund.getPaymentNo(),
                refund.getOrderNo(),
                refund.getUserId(),
                refund.getAmount(),
                refund.getChannelRefundNo(),
                System.currentTimeMillis());
        if (!refundSuccessProducer.send(message)) {
            log.error("refund success event pending compensation, refundNo={}, paymentNo={}",
                    refund.getRefundNo(), refund.getPaymentNo());
            return;
        }
        refundMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PaymentRefund>()
                .eq(PaymentRefund::getId, refund.getId())
                .eq(PaymentRefund::getStatus, PaymentConstants.REFUND_STATUS_SUCCESS)
                .eq(PaymentRefund::getOrderNotifyStatus, PaymentConstants.REFUND_NOTIFY_PENDING)
                .set(PaymentRefund::getOrderNotifyStatus, PaymentConstants.REFUND_NOTIFY_SENT));
    }
}
