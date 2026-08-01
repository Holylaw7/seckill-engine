package com.seckill.auth.constant;

/**
 * auth-service 常量（冻结）。
 */
public final class AuthConstants {

    public static final String SESSION_PREFIX = "auth:session:";
    public static final String LOGIN_FAIL_PREFIX = "risk:login:fail:";
    public static final String BLACKLIST_PREFIX = "risk:blacklist:";

    public static final String BLACKLIST_TYPE_USER = "user";
    public static final String BLACKLIST_TYPE_IP = "ip";
    public static final String BLACKLIST_TYPE_DEVICE = "device";

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_DEVICE = "X-Device-Fingerprint";

    public static final int LOGIN_FAIL_CAPTCHA_THRESHOLD = 5;
    public static final int LOGIN_FAIL_FREEZE_THRESHOLD = 10;
    public static final long LOGIN_FAIL_WINDOW_SECONDS = 600L;

    private AuthConstants() {
    }
}
