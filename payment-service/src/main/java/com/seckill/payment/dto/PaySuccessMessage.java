package com.seckill.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * PAY_SUCCESS 事件（冻结字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaySuccessMessage {

    private String messageId;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String transactionNo;

    private Long timestamp;
}
