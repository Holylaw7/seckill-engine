package com.seckill.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ExecuteResponse {

    /** SUCCESS / STOCK_EMPTY / REPEAT_BUY / NOT_READY / BUSY */
    private String result;

    private String orderId;

    private Long payDeadline;
}
