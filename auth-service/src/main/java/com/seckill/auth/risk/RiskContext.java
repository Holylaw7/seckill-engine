package com.seckill.auth.risk;

public record RiskContext(String userId, String ip, String action, String deviceFingerprint) {

    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_SECKILL = "SECKILL";
    public static final String ACTION_PAY = "PAY";
}
