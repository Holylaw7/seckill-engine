package com.seckill.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 恢复接口契约（冻结：POST /api/v1/seckill/internal/stocks/recover）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoverRequest {

    /** 必须等于 stock_flow.flow_no，接口侧幂等 */
    @NotBlank(message = "requestId不能为空")
    private String requestId;

    @NotNull(message = "skuId不能为空")
    private Long skuId;

    @NotNull(message = "sessionId不能为空")
    private Long sessionId;

    @NotNull(message = "recoverCount不能为空")
    @Min(value = 1, message = "recoverCount必须大于0")
    private Integer recoverCount;
}
