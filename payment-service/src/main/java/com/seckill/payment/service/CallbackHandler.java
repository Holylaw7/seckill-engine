package com.seckill.payment.service;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.SnowflakeIdGenerator;
import com.seckill.payment.channel.PaymentChannel;
import com.seckill.payment.channel.PaymentChannelRouter;
import com.seckill.payment.config.PaymentProperties;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.CallbackResult;
import com.seckill.payment.dto.PaySuccessMessage;
import com.seckill.payment.entity.PaymentCallbackLog;
import com.seckill.payment.entity.PaymentOrder;
import com.seckill.payment.mapper.PaymentCallbackLogMapper;
import com.seckill.payment.mq.PaySuccessProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 回调处理：验签 → 时间窗口 → 防重放落库 → 金额校验 → CAS 更新 → PAY_SUCCESS 事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackHandler {

    private final PaymentChannelRouter channelRouter;
    private final PaymentService paymentService;
    private final PaymentCallbackLogMapper callbackLogMapper;
    private final PaySuccessProducer paySuccessProducer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final PaymentProperties properties;

    public CallbackResult handle(CallbackContext context) {
        PaymentChannel channel = channelRouter.getChannel(context.channel());

        // 1 验签
        if (!channel.verifyCallback(context)) {
            saveLog(context, PaymentConstants.VERIFY_FAIL, PaymentConstants.PROCESS_FAILED);
            log.warn("callback verify failed, paymentNo={}, traceId={}",
                    context.paymentNo(), context.traceId());
            return CallbackResult.rejected("验签失败");
        }
        // 2 时间窗口
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowSeconds - context.timestamp()) > properties.getCallback().getWindowSeconds()) {
            saveLog(context, PaymentConstants.VERIFY_TIMEOUT, PaymentConstants.PROCESS_FAILED);
            log.warn("callback timestamp out of window, paymentNo={}, timestamp={}",
                    context.paymentNo(), context.timestamp());
            return CallbackResult.rejected("时间窗口超限");
        }
        // 3 防重放落库（uk_callback_transaction）
        try {
            saveLog(context, PaymentConstants.VERIFY_OK, PaymentConstants.PROCESS_NEW);
        } catch (DuplicateKeyException e) {
            // 重复回调：直接返回成功
            return CallbackResult.success();
        }

        // 4 金额校验（禁止相信客户端金额）
        PaymentOrder payment = paymentService.getByPaymentNo(context.paymentNo());
        if (payment == null) {
            markLogResult(context, PaymentConstants.VERIFY_FAIL, PaymentConstants.PROCESS_FAILED);
            log.error("callback payment not found, paymentNo={}", context.paymentNo());
            return CallbackResult.rejected("支付单不存在");
        }
        if (context.amount().compareTo(payment.getAmount()) != 0) {
            markLogResult(context, PaymentConstants.VERIFY_AMOUNT_MISMATCH, PaymentConstants.PROCESS_FAILED);
            log.error("callback amount mismatch, paymentNo={}, expect={}, actual={}",
                    context.paymentNo(), payment.getAmount(), context.amount());
            return CallbackResult.rejected("金额不符");
        }
        if (!PaymentConstants.CALLBACK_STATUS_SUCCESS.equals(context.status())) {
            return CallbackResult.rejected("渠道未支付成功");
        }
        if (PaymentConstants.STATUS_PAY_SUCCESS.equals(payment.getStatus())) {
            markLogProcessed(context, PaymentConstants.PROCESS_SKIPPED_DUPLICATE);
            return CallbackResult.success();
        }

        // 5 CAS 更新 WAIT_PAY → PAY_SUCCESS
        boolean updated = paymentService.completePaySuccess(payment, context.channelTransactionNo());
        if (!updated) {
            markLogProcessed(context, PaymentConstants.PROCESS_SKIPPED_DUPLICATE);
            return CallbackResult.success();
        }
        markLogProcessed(context, PaymentConstants.PROCESS_SUCCESS);

        // 6 发布 PAY_SUCCESS 事件
        PaySuccessMessage message = new PaySuccessMessage(
                UUID.randomUUID().toString().replace("-", ""),
                payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(),
                payment.getAmount(), context.channelTransactionNo(), System.currentTimeMillis());
        if (!paySuccessProducer.send(message)) {
            log.error("pay success message send failed, paymentNo={} -> 对账兜底", payment.getPaymentNo());
        }
        return CallbackResult.success();
    }

    private void saveLog(CallbackContext context, String verifyResult, String processStatus) {
        PaymentCallbackLog logRow = new PaymentCallbackLog();
        logRow.setId(snowflakeIdGenerator.nextId());
        logRow.setPaymentNo(context.paymentNo());
        logRow.setChannelTransactionNo(context.channelTransactionNo());
        logRow.setCallbackBody(context.rawBody());
        logRow.setVerifyResult(verifyResult);
        logRow.setProcessStatus(processStatus);
        logRow.setTraceId(context.traceId());
        callbackLogMapper.insert(logRow);
    }

    private void markLogProcessed(CallbackContext context, String processStatus) {
        PaymentCallbackLog row = callbackLogMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentCallbackLog>()
                        .eq(PaymentCallbackLog::getChannelTransactionNo, context.channelTransactionNo()));
        if (row != null) {
            row.setProcessStatus(processStatus);
            callbackLogMapper.updateById(row);
        }
    }

    /**
     * 校验/处理失败原因回填到防重放日志行：
     * 防重放落库已占用 uk_callback_transaction，失败原因必须更新既有行，禁止二次插入。
     */
    private void markLogResult(CallbackContext context, String verifyResult, String processStatus) {
        PaymentCallbackLog row = callbackLogMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaymentCallbackLog>()
                        .eq(PaymentCallbackLog::getChannelTransactionNo, context.channelTransactionNo()));
        if (row != null) {
            row.setVerifyResult(verifyResult);
            row.setProcessStatus(processStatus);
            callbackLogMapper.updateById(row);
        } else {
            saveLog(context, verifyResult, processStatus);
        }
    }
}
