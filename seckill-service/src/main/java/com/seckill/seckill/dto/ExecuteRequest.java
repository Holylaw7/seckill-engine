package com.seckill.seckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ExecuteRequest {

    @NotNull(message = "场次ID不能为空")
    private Long sessionId;

    @NotNull(message = "商品ID不能为空")
    private Long skuId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于0")
    private Integer quantity;
}
