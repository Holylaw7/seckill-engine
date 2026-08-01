package com.seckill.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RiskCheckResponse {

    /** PASS / REJECT / CAPTCHA */
    private String decision;

    private Integer code;

    private String reason;
}
