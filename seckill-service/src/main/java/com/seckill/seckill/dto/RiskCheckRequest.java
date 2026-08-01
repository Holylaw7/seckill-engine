package com.seckill.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskCheckRequest {

    private String userId;

    private String ip;

    /** LOGIN / SECKILL / PAY */
    private String action;

    private String deviceFingerprint;
}
