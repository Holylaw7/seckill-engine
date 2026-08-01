package com.seckill.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class PaymentQueryResponse {

    private String paymentNo;

    private String orderNo;

    private BigDecimal amount;

    private String status;

    private LocalDateTime payTime;
}
