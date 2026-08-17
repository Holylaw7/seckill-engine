package com.seckill.order.constant;

/**
 * order-service 常量（冻结）。
 */
public final class OrderConstants {

    public static final String MQ_TOPIC = "seckill-order-tx";
    public static final String TAG_CREATE_ORDER = "CREATE_ORDER";
    public static final String TAG_CANCEL_ORDER = "CANCEL_ORDER";
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String CONSUMER_GROUP = "order-consumer";
    public static final String PAY_SUCCESS_CONSUMER_GROUP = "order-pay-success-consumer";

    public static final String STATUS_CREATE = "CREATE";
    public static final String STATUS_WAIT_PAY = "WAIT_PAY";
    public static final String STATUS_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String STATUS_CANCEL = "CANCEL";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_REFUND = "REFUND";

    public static final String NOTIFY_PENDING = "PENDING";
    public static final String NOTIFY_SENT = "SENT";

    public static final String BIZ_TYPE_ORDER_CREATE = "ORDER_CREATE";
    public static final String BIZ_TYPE_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String ACTIVE_KEY_SEPARATOR = ":";
    public static final String HEADER_USER_ID = "X-User-Id";

    public static final String REASON_CANCEL = "CANCEL";
    public static final String REASON_TIMEOUT = "TIMEOUT";

    private OrderConstants() {
    }
}
