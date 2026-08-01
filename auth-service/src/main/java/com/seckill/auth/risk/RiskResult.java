package com.seckill.auth.risk;

import com.seckill.common.error.ErrorCode;

public record RiskResult(Decision decision, ErrorCode errorCode, String reason) {

    public enum Decision {
        PASS, REJECT, CAPTCHA
    }

    public static RiskResult pass() {
        return new RiskResult(Decision.PASS, ErrorCode.SUCCESS, null);
    }

    public static RiskResult reject(ErrorCode errorCode, String reason) {
        return new RiskResult(Decision.REJECT, errorCode, reason);
    }

    public static RiskResult captcha(String reason) {
        return new RiskResult(Decision.CAPTCHA, ErrorCode.CAPTCHA_REQUIRED, reason);
    }
}
