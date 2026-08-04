package com.seckill.seckill.constant;

/**
 * seckill-service 常量（冻结）。
 */
public final class SeckillConstants {

    public static final String SESSION_PREFIX = "seckill:session:";
    public static final String STOCK_PREFIX = "seckill:stock:";
    public static final String STOCK_TOTAL_PREFIX = "seckill:stock:total:";
    public static final String STOCK_BUCKET_PREFIX = "seckill:stock:bucket:";
    public static final String STOCK_RR_PREFIX = "seckill:stock:rr:";
    public static final String USER_PREFIX = "seckill:user:";
    public static final String FLOW_PREFIX = "seckill:flow:";
    public static final String LOCK_PREHEAT_PREFIX = "seckill:lock:preheat:";

    public static final String HEADER_USER_ID = "X-User-Id";

    public static final String MQ_TOPIC = "seckill-order-tx";
    public static final String MQ_TAG_CREATE_ORDER = "CREATE_ORDER";

    public static final String SESSION_STATUS_INIT = "INIT";
    public static final String SESSION_STATUS_PREHEATING = "PREHEATING";
    public static final String SESSION_STATUS_READY = "READY";
    public static final String SESSION_STATUS_ONGOING = "ONGOING";
    public static final String SESSION_STATUS_ENDED = "ENDED";

    public static final String TX_STATUS_INIT = "INIT";
    public static final String TX_STATUS_SUCCESS = "SUCCESS";
    public static final String TX_STATUS_FAIL = "FAIL";

    public static final String DEDUCT_STATUS_DEDUCTED = "DEDUCTED";
    public static final String DEDUCT_STATUS_CONFIRMED = "CONFIRMED";
    public static final String DEDUCT_STATUS_RECOVERED = "RECOVERED";

    public static final long PAY_DEADLINE_MILLIS = 15 * 60_000L;
    public static final long USER_MARK_EXTRA_MILLIS = 24 * 60 * 60_000L;

    private SeckillConstants() {
    }
}
