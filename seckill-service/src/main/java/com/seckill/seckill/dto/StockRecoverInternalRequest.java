package com.seckill.seckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 内部库存回补请求（冻结契约：POST /api/v1/seckill/internal/stocks/recover）。
 *
 * <p>requestId 必须等于 inventory stock_flow.flow_no，接口侧按 requestId 幂等。</p>
 */
@Data
public class StockRecoverInternalRequest {

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
