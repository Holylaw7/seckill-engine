package com.seckill.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ResultQueryResponse {

    /** NONE / DEDUCTED / SUCCESS / FAILED（冻结状态机） */
    private String status;

    private String orderId;

    private Long payDeadline;
}
