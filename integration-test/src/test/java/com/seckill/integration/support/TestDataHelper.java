package com.seckill.integration.support;

/**
 * 测试数据辅助：按测试命名空间插入/清理 session、sku、inventory、pre_deduct 等数据。
 */
public final class TestDataHelper {

    private TestDataHelper() {
    }

    public static void seedSeckill(long sessionId, long skuId, int stockTotal,
                                   String sessionStatus, String price) throws Exception {
        IntegrationTestBase.execute("DELETE FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE session_id=" + sessionId + " AND sku_id=" + skuId);
        IntegrationTestBase.execute("DELETE FROM seckill_seckill.seckill_sku WHERE session_id=" + sessionId);
        IntegrationTestBase.execute("DELETE FROM seckill_seckill.seckill_session WHERE id=" + sessionId);
        IntegrationTestBase.execute("INSERT INTO seckill_seckill.seckill_session "
                + "(id, activity_id, session_name, start_time, end_time, status, limit_per_user) VALUES ("
                + sessionId + ", 1, '测试场次-" + sessionId + "', "
                + "'2000-01-01 00:00:00', '2999-12-31 00:00:00', '" + sessionStatus + "', 1)");
        IntegrationTestBase.execute("INSERT INTO seckill_seckill.seckill_sku "
                + "(id, session_id, sku_id, stock_total, price, limit_per_user, status) VALUES ("
                + (sessionId + 10000) + ", " + sessionId + ", " + skuId + ", "
                + stockTotal + ", " + price + ", 1, 1)");
    }

    public static void resetInventory(long skuId, int total, int available, int locked) throws Exception {
        IntegrationTestBase.execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + skuId);
        IntegrationTestBase.execute("DELETE FROM seckill_inventory.inventory WHERE sku_id=" + skuId);
        IntegrationTestBase.execute("INSERT INTO seckill_inventory.inventory "
                + "(id, sku_id, total_stock, locked_stock, available_stock, version) VALUES ("
                + (50000 + skuId) + ", " + skuId + ", " + total + ", " + locked + ", " + available + ", 0)");
    }

    public static void insertPreDeduct(long id, String messageId, String orderId, long userId,
                                       long sessionId, long skuId, int quantity,
                                       String txStatus, String deductStatus) throws Exception {
        IntegrationTestBase.execute("INSERT INTO seckill_seckill.seckill_pre_deduct "
                + "(id, message_id, order_id, user_id, session_id, sku_id, quantity, tx_status, deduct_status) VALUES ("
                + id + ", '" + messageId + "', '" + orderId + "', " + userId + ", "
                + sessionId + ", " + skuId + ", " + quantity + ", '" + txStatus + "', '" + deductStatus + "')");
    }

    public static void cleanupOrderNamespace(long userId, long sessionId, long skuId) throws Exception {
        IntegrationTestBase.execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id "
                + "WHERE so.user_id=" + userId + " AND so.session_id=" + sessionId + " AND so.sku_id=" + skuId);
        IntegrationTestBase.execute("DELETE FROM seckill_order.idempotent WHERE user_id=" + userId);
        IntegrationTestBase.execute("DELETE FROM seckill_order.seckill_order "
                + "WHERE user_id=" + userId + " AND session_id=" + sessionId + " AND sku_id=" + skuId);
        IntegrationTestBase.execute("DELETE FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE user_id=" + userId + " AND session_id=" + sessionId + " AND sku_id=" + skuId);
        IntegrationTestBase.execute("DELETE FROM seckill_inventory.stock_flow WHERE sku_id=" + skuId);
    }

    public static void cleanupPaymentNamespace(String orderNoPrefix) throws Exception {
        IntegrationTestBase.execute("DELETE FROM seckill_payment.payment_callback_log "
                + "WHERE payment_no IN (SELECT payment_no FROM seckill_payment.payment_order "
                + "WHERE order_no LIKE '" + orderNoPrefix + "%')");
        IntegrationTestBase.execute("DELETE FROM seckill_payment.payment_refund "
                + "WHERE payment_no IN (SELECT payment_no FROM seckill_payment.payment_order "
                + "WHERE order_no LIKE '" + orderNoPrefix + "%')");
        IntegrationTestBase.execute("DELETE FROM seckill_payment.payment_order "
                + "WHERE order_no LIKE '" + orderNoPrefix + "%'");
    }

    public static void seedWaitPayOrder(String orderNo, long userId, String amount) throws Exception {
        long suffix = Integer.toUnsignedLong(orderNo.hashCode());
        long orderId = 7_000_000_000L + suffix;
        long sessionId = 8_000_000_000L + suffix;
        long skuId = 9_000_000_000L + suffix;
        IntegrationTestBase.execute("INSERT INTO seckill_order.seckill_order "
                + "(id, order_no, user_id, session_id, sku_id, quantity, order_amount, order_status, "
                + "active_key, cancel_notify_status, pay_deadline, version) VALUES ("
                + orderId + ", '" + orderNo + "', " + userId + ", " + sessionId + ", " + skuId
                + ", 1, " + amount + ", 'WAIT_PAY', '" + userId + ":" + sessionId + ":" + skuId
                + "', 'PENDING', DATE_ADD(NOW(3), INTERVAL 15 MINUTE), 0)");
    }

    public static void cleanupOrderNoPrefix(String orderNoPrefix) throws Exception {
        IntegrationTestBase.execute("DELETE oi FROM seckill_order.order_item oi "
                + "JOIN seckill_order.seckill_order so ON oi.order_id = so.id "
                + "WHERE so.order_no LIKE '" + orderNoPrefix + "%'");
        IntegrationTestBase.execute("DELETE i FROM seckill_order.idempotent i "
                + "JOIN seckill_order.seckill_order so ON i.user_id = so.user_id "
                + "WHERE so.order_no LIKE '" + orderNoPrefix + "%'");
        IntegrationTestBase.execute("DELETE FROM seckill_order.seckill_order "
                + "WHERE order_no LIKE '" + orderNoPrefix + "%'");
    }

    public static int countOrders(long sessionId, long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                + "WHERE session_id=" + sessionId + " AND sku_id=" + skuId);
    }

    public static int countOrders(long userId, long sessionId, long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_order.seckill_order "
                + "WHERE user_id=" + userId + " AND session_id=" + sessionId + " AND sku_id=" + skuId);
    }

    public static int countPreDeduct(long sessionId, long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE session_id=" + sessionId + " AND sku_id=" + skuId);
    }

    public static int countPreDeduct(long userId, long sessionId, long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_seckill.seckill_pre_deduct "
                + "WHERE user_id=" + userId + " AND session_id=" + sessionId + " AND sku_id=" + skuId);
    }

    public static int countDeductFlow(long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                + "WHERE sku_id=" + skuId + " AND change_type='DEDUCT'");
    }

    public static int countRecoverFlow(long skuId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                + "WHERE sku_id=" + skuId + " AND change_type='RECOVER'");
    }

    public static int countRecoverFlowByOrder(long skuId, String orderId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_inventory.stock_flow "
                + "WHERE sku_id=" + skuId + " AND change_type='RECOVER' AND biz_id='" + orderId + "'");
    }

    public static int countIdempotent(String bizType, String bizId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_order.idempotent "
                + "WHERE biz_type='" + bizType + "' AND biz_id='" + bizId + "'");
    }

    public static int countIdempotentByUserRange(long fromUserId, long toUserId) throws Exception {
        return IntegrationTestBase.queryInt("SELECT COUNT(*) FROM seckill_order.idempotent "
                + "WHERE user_id >= " + fromUserId + " AND user_id <= " + toUserId);
    }
}
