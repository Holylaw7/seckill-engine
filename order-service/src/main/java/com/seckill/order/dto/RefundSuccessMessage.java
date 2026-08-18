package com.seckill.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * REFUND_SUCCESS 事件的订单域副本。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundSuccessMessage {

    private String messageId;

    private String refundNo;

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String channelRefundNo;

    private Long timestamp;
}
