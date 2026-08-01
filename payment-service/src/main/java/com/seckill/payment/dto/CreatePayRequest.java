package com.seckill.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建支付（order-service 服务端调用，金额服务端传递，客户端不可信）。
 */
@Data
public class CreatePayRequest {

    @NotBlank(message = "orderNo不能为空")
    private String orderNo;

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotNull(message = "amount不能为空")
    @Positive(message = "amount必须大于0")
    private BigDecimal amount;

    @NotBlank(message = "channel不能为空")
    private String channel;
}
