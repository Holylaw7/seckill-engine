package com.seckill.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PreDeductConfirmRequest {

    @NotBlank(message = "messageId不能为空")
    private String messageId;

    @NotBlank(message = "orderId不能为空")
    private String orderId;
}
