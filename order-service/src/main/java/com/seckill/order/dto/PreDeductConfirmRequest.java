package com.seckill.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreDeductConfirmRequest {

    @NotBlank(message = "messageId不能为空")
    private String messageId;

    @NotBlank(message = "orderId不能为空")
    private String orderId;
}
