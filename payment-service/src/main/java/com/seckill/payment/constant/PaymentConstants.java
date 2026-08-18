package com.seckill.payment.constant;

/**
 * payment-service 常量（冻结）。
 */
public final class PaymentConstants {

    public static final String MQ_TOPIC = "seckill-order-tx";
    public static final String TAG_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String TAG_REFUND_SUCCESS = "REFUND_SUCCESS";

    public static final String STATUS_CREATE = "CREATE";
    public static final String STATUS_WAIT_PAY = "WAIT_PAY";
    public static final String STATUS_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String STATUS_REFUNDING = "REFUNDING";
    public static final String STATUS_REFUND_SUCCESS = "REFUND_SUCCESS";
    public static final String STATUS_PAY_FAILED = "PAY_FAILED";

    public static final String REFUND_STATUS_REFUNDING = "REFUNDING";
    public static final String REFUND_STATUS_SUCCESS = "REFUND_SUCCESS";
    public static final String REFUND_STATUS_FAILED = "REFUND_FAILED";
    public static final String REFUND_NOTIFY_PENDING = "PENDING";
    public static final String REFUND_NOTIFY_SENT = "SENT";

    public static final String CHANNEL_MOCK = "MOCK";
    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_ALIPAY = "ALIPAY";

    public static final String VERIFY_OK = "VERIFY_OK";
    public static final String VERIFY_FAIL = "VERIFY_FAIL";
    public static final String VERIFY_TIMEOUT = "TIMEOUT";
    public static final String VERIFY_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    public static final String PROCESS_NEW = "NEW";
    public static final String PROCESS_PROCESSING = "PROCESSING";
    public static final String PROCESS_SUCCESS = "SUCCESS";
    public static final String PROCESS_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";
    public static final String PROCESS_FAILED = "FAILED";

    public static final String CALLBACK_STATUS_SUCCESS = "SUCCESS";

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_SIGN = "X-Pay-Sign";
    public static final String HEADER_TIMESTAMP = "X-Pay-Timestamp";

    private PaymentConstants() {
    }
}
