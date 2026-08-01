package com.seckill.auth.dto;

import lombok.Data;

@Data
public class RiskCheckRequest {

    private String userId;

    private String ip;

    /** LOGIN / SECKILL / PAY */
    private String action;

    private String deviceFingerprint;
}
