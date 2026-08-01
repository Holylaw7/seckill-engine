package com.seckill.payment.dto;

import java.math.BigDecimal;

public record CallbackContext(
        String channel,
        String paymentNo,
        String channelTransactionNo,
        BigDecimal amount,
        String status,
        long timestamp,
        String signature,
        String rawBody,
        String traceId) {
}
