package com.seckill.seckill.dto;

import lombok.Data;

@Data
public class RiskCheckResponse {

    private String decision;

    private Integer code;

    private String reason;
}
