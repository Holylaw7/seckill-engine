package com.seckill.payment.controller;

import com.seckill.common.error.ErrorCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.result.Result;
import com.seckill.common.util.JsonUtils;
import com.seckill.payment.constant.PaymentConstants;
import com.seckill.payment.dto.CallbackContext;
import com.seckill.payment.dto.CallbackPayload;
import com.seckill.payment.dto.CallbackResult;
import com.seckill.payment.dto.CreatePayRequest;
import com.seckill.payment.dto.CreatePayResponse;
import com.seckill.payment.dto.PaymentQueryResponse;
import com.seckill.payment.dto.RefundRequest;
import com.seckill.payment.dto.RefundResult;
import com.seckill.payment.service.CallbackHandler;
import com.seckill.payment.service.PaymentService;
import com.seckill.payment.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final CallbackHandler callbackHandler;
    private final RefundService refundService;

    /**
     * 创建支付（order-service 服务端调用；金额由服务端传递）。
     */
    @PostMapping("/create")
    public Result<CreatePayResponse> create(@Valid @RequestBody CreatePayRequest request) {
        return Result.success(paymentService.createPayment(request));
    }

    /**
     * 渠道回调（网关白名单放行，验签在服务内完成）。
     */
    @PostMapping("/callback/{channel}")
    public ResponseEntity<String> callback(@PathVariable("channel") String channel,
                                           @RequestBody String rawBody,
                                           @RequestHeader(value = PaymentConstants.HEADER_SIGN, required = false) String sign,
                                           @RequestHeader(value = PaymentConstants.HEADER_TIMESTAMP, required = false) String timestampHeader,
                                           @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        CallbackPayload payload = JsonUtils.fromJson(rawBody, CallbackPayload.class);
        long timestamp = timestampHeader == null ? 0L : Long.parseLong(timestampHeader);
        CallbackContext context = new CallbackContext(
                channel, payload.getPaymentNo(), payload.getChannelTransactionNo(),
                payload.getAmount(), payload.getStatus(), timestamp, sign, rawBody, traceId);
        CallbackResult result = callbackHandler.handle(context);
        return result.accepted()
                ? ResponseEntity.ok("success")
                : ResponseEntity.badRequest().body(result.reason());
    }

    @GetMapping("/{paymentNo}")
    public Result<PaymentQueryResponse> query(@PathVariable String paymentNo,
                                              @RequestHeader(value = PaymentConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        return Result.success(paymentService.queryPayment(Long.parseLong(userId), paymentNo));
    }

    @PostMapping("/{paymentNo}/refund")
    public Result<RefundResult> refund(@PathVariable String paymentNo,
                                       @Valid @RequestBody RefundRequest request,
                                       @RequestHeader(value = PaymentConstants.HEADER_USER_ID, required = false) String userId) {
        requireUserId(userId);
        if (!paymentNo.equals(request.getPaymentNo())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "paymentNo 不一致");
        }
        return Result.success(refundService.refund(request));
    }

    private static void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
