-- payment-service 增量迁移：退款成功通知 order-service 的持久化状态。
-- 兼容已由 V1 创建 payment_refund 的环境，脚本可重复执行。
USE `seckill_payment`;

SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund'
      AND column_name = 'order_notify_status'
);
SET @add_column_sql = IF(
    @column_exists = 0,
    'ALTER TABLE payment_refund ADD COLUMN order_notify_status VARCHAR(20) NOT NULL DEFAULT ''PENDING'' COMMENT ''REFUND_SUCCESS 订单通知状态：PENDING/SENT'' AFTER channel_refund_no',
    'SELECT 1'
);
PREPARE add_column_stmt FROM @add_column_sql;
EXECUTE add_column_stmt;
DEALLOCATE PREPARE add_column_stmt;

SET @index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_refund'
      AND index_name = 'idx_order_notify'
);
SET @add_index_sql = IF(
    @index_exists = 0,
    'ALTER TABLE payment_refund ADD KEY idx_order_notify (status, order_notify_status)',
    'SELECT 1'
);
PREPARE add_index_stmt FROM @add_index_sql;
EXECUTE add_index_stmt;
DEALLOCATE PREPARE add_index_stmt;

UPDATE payment_refund
SET order_notify_status = 'PENDING'
WHERE order_notify_status IS NULL OR order_notify_status = '';
