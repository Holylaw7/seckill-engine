package com.seckill.payment.dto;

public record CallbackResult(boolean accepted, String reason) {

    public static CallbackResult success() {
        return new CallbackResult(true, "success");
    }

    public static CallbackResult rejected(String reason) {
        return new CallbackResult(false, reason);
    }
}
