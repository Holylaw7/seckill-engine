package com.seckill.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RepairRequest {

    @NotNull(message = "skuId不能为空")
    private Long skuId;

    @NotNull(message = "targetAvailable不能为空")
    @Min(value = 0, message = "targetAvailable不能小于0")
    private Integer targetAvailable;

    private String reason;
}
