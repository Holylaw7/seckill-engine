package com.seckill.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RefundRequest {

    @NotBlank(message = "refundNo不能为空")
    private String refundNo;

    @NotBlank(message = "paymentNo不能为空")
    private String paymentNo;

    @NotBlank(message = "orderNo不能为空")
    private String orderNo;

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotNull(message = "amount不能为空")
    @Positive(message = "amount必须大于0")
    private BigDecimal amount;

    private String reason;
}
