package com.seckill.payment.dto;

import java.math.BigDecimal;

public record RefundChannelRequest(String refundNo, String paymentNo, BigDecimal amount) {
}
