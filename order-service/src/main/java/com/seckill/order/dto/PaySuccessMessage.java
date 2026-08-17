package com.seckill.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * PAY_SUCCESS 事件的订单域副本。
 *
 * <p>订单服务只依赖稳定的 MQ 契约，不直接依赖 payment-service 的代码。</p>
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
