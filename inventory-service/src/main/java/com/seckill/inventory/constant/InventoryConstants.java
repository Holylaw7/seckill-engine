package com.seckill.inventory.constant;

/**
 * inventory-service 常量（冻结）。
 */
public final class InventoryConstants {

    public static final String MQ_TOPIC = "seckill-order-tx";
    public static final String TAG_CREATE_ORDER = "CREATE_ORDER";
    public static final String TAG_CANCEL_ORDER = "CANCEL_ORDER";
    public static final String TAG_STOCK_RECOVER = "STOCK_RECOVER";
    public static final String CONSUMER_GROUP = "inventory-consumer";

    public static final String FLOW_TYPE_INIT = "INIT";
    public static final String FLOW_TYPE_DEDUCT = "DEDUCT";
    public static final String FLOW_TYPE_RECOVER = "RECOVER";
    public static final String FLOW_TYPE_REPAIR = "REPAIR";
    public static final String FLOW_TYPE_ADJUST = "ADJUST";
    public static final String FLOW_TYPE_CONFIRM = "CONFIRM";

    public static final String BIZ_TYPE_ORDER = "ORDER";
    public static final String BIZ_TYPE_CANCEL = "CANCEL";
    public static final String BIZ_TYPE_TIMEOUT = "TIMEOUT";
    public static final String BIZ_TYPE_REFUND = "REFUND";
    public static final String BIZ_TYPE_MANUAL = "MANUAL";

    public static final String REASON_CANCEL = "CANCEL";
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_REFUND = "REFUND";

    public static final int CAS_MAX_RETRY = 3;
    public static final String HEADER_USER_ID = "X-User-Id";

    private InventoryConstants() {
    }
}
