package com.seckill.gateway.constant;

/**
 * 网关常量（冻结）。
 */
public final class GatewayConstants {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String GATEWAY_USER_ID = "GATEWAY_USER_ID";

    public static final int TRACE_FILTER_ORDER = Integer.MIN_VALUE;
    public static final int LOG_FILTER_ORDER = -300;
    public static final int VALIDATION_FILTER_ORDER = -250;
    public static final int JWT_FILTER_ORDER = -200;
    public static final int BLACKLIST_FILTER_ORDER = -150;
    public static final int ROUTE_PROFILE_FILTER_ORDER = -100;

    private GatewayConstants() {
    }
}
