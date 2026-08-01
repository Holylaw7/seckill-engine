package com.seckill.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Mock 渠道回调体。
 */
@Data
public class CallbackPayload {

    private String paymentNo;

    private String channelTransactionNo;

    private BigDecimal amount;

    /** SUCCESS */
    private String status;

    private Long timestamp;
}
