package com.seckill.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.payment.dto.PayChannelRequest;
import com.seckill.payment.dto.PayChannelResult;
import com.seckill.payment.dto.PaymentQueryResponse;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentOrderMapper;
import com.seckill.payment.state.PaymentStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentOrderMapper paymentOrderMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final PaymentStateMachine paymentStateMachine;
    private final PaymentChannelRouter channelRouter;

    /**
     * 创建支付（幂等：同一 orderNo 有效支付唯一）。
     */
    @Transactional
    public CreatePayResponse createPayment(CreatePayRequest request) {
        PaymentOrder existing = paymentOrderMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getActiveOrderKey, request.getOrderNo()));
        if (existing != null) {
            return rebuildResponse(existing);
        }

        PaymentOrder payment = new PaymentOrder();
        payment.setId(snowflakeIdGenerator.nextId());
        payment.setPaymentNo("P" + snowflakeIdGenerator.nextId());
        payment.setOrderNo(request.getOrderNo());
        payment.setUserId(request.getUserId());
        payment.setAmount(request.getAmount());
        payment.setChannel(request.getChannel());
        payment.setStatus(PaymentConstants.STATUS_CREATE);
        payment.setActiveOrderKey(request.getOrderNo());
        payment.setVersion(0);
        try {
            paymentOrderMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            PaymentOrder duplicate = paymentOrderMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                    .eq(PaymentOrder::getActiveOrderKey, request.getOrderNo()));
            if (duplicate != null) {
                return rebuildResponse(duplicate);
            }
            throw e;
        }

        boolean ok = paymentStateMachine.transition(payment, PaymentConstants.STATUS_WAIT_PAY, null, null);
        if (!ok) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "支付状态流转失败");
        }
        return rebuildResponse(payment);
    }

    public PaymentOrder getByPaymentNo(String paymentNo) {
        return paymentOrderMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getPaymentNo, paymentNo));
    }

    /**
     * 回调确认：WAIT_PAY → PAY_SUCCESS（CAS）。
     */
    public boolean completePaySuccess(PaymentOrder payment, String transactionNo) {
        return paymentStateMachine.transition(
                payment, PaymentConstants.STATUS_PAY_SUCCESS, transactionNo, LocalDateTime.now());
    }

    /**
     * 支付失败：WAIT_PAY → PAY_FAILED（释放 active 占用）。
     */
    public boolean failPayment(PaymentOrder payment) {
        return paymentStateMachine.transition(
                payment, PaymentConstants.STATUS_PAY_FAILED, null, LocalDateTime.now());
    }

    /**
     * 发起退款：PAY_SUCCESS → REFUNDING（CAS）。
     */
    public boolean startRefund(PaymentOrder payment) {
        return paymentStateMachine.transition(
                payment, PaymentConstants.STATUS_REFUNDING, null, null);
    }

    /**
     * 退款成功：REFUNDING → REFUND_SUCCESS（CAS，释放 active 占用）。
     */
    public boolean completeRefundSuccess(PaymentOrder payment) {
        return paymentStateMachine.transition(
                payment, PaymentConstants.STATUS_REFUND_SUCCESS, null, LocalDateTime.now());
    }

    public PaymentQueryResponse queryPayment(Long userId, String paymentNo) {
        PaymentOrder payment = getByPaymentNo(paymentNo);
        if (payment == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        if (!payment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该支付单");
        }
        return new PaymentQueryResponse(payment.getPaymentNo(), payment.getOrderNo(),
                payment.getAmount(), payment.getStatus(), payment.getPayTime());
    }

    private CreatePayResponse rebuildResponse(PaymentOrder payment) {
        PaymentChannel channel = channelRouter.getChannel(payment.getChannel());
        PayChannelResult channelResult = channel.createPay(new PayChannelRequest(
                payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(), payment.getAmount()));
        return new CreatePayResponse(payment.getPaymentNo(), channelResult.params());
    }
}
