package com.seckill.payment.dto;

import java.math.BigDecimal;

public record PayChannelRequest(String paymentNo, String orderNo, Long userId, BigDecimal amount) {
}
