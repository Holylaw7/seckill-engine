-- ============================================================
-- payment-service 初始化脚本 V1.0（冻结：payment-service 设计 v1.0）
-- database: seckill_payment
-- ============================================================
CREATE DATABASE IF NOT EXISTS `seckill_payment`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `seckill_payment`;

CREATE TABLE IF NOT EXISTS `payment_order` (
    `id`               BIGINT        NOT NULL COMMENT '主键（Snowflake）',
    `payment_no`       VARCHAR(64)   NOT NULL COMMENT '支付单号（唯一）',
    `order_no`         VARCHAR(64)   NOT NULL COMMENT '订单号',
    `user_id`          BIGINT        NOT NULL COMMENT '用户ID',
    `amount`           DECIMAL(18,2) NOT NULL COMMENT '支付金额（服务端传递）',
    `channel`          VARCHAR(20)   NOT NULL COMMENT '支付渠道：MOCK/WECHAT/ALIPAY',
    `status`           VARCHAR(20)   NOT NULL DEFAULT 'CREATE' COMMENT 'CREATE/WAIT_PAY/PAY_SUCCESS/REFUNDING/REFUND_SUCCESS/PAY_FAILED',
    `transaction_no`   VARCHAR(64)   DEFAULT NULL COMMENT '渠道交易号',
    `active_order_key` VARCHAR(64)   DEFAULT NULL COMMENT 'order_no+active payment 唯一（终态置NULL）',
    `pay_time`         DATETIME(3)   DEFAULT NULL COMMENT '支付成功时间',
    `refund_time`      DATETIME(3)   DEFAULT NULL COMMENT '退款成功时间',
    `version`          INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_time`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    UNIQUE KEY `uk_transaction_no` (`transaction_no`),
    UNIQUE KEY `uk_active_order` (`active_order_key`),
    KEY `idx_user` (`user_id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_channel_transaction` (`channel`, `transaction_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付订单表';

CREATE TABLE IF NOT EXISTS `payment_callback_log` (
    `id`                     BIGINT       NOT NULL COMMENT '主键（Snowflake）',
    `payment_no`             VARCHAR(64)  NOT NULL COMMENT '支付单号',
    `channel_transaction_no` VARCHAR(64)  NOT NULL COMMENT '渠道交易号（防重放唯一）',
    `callback_body`          TEXT         COMMENT '回调原文快照',
    `verify_result`          VARCHAR(20)  NOT NULL COMMENT 'VERIFY_OK/VERIFY_FAIL/TIMEOUT/AMOUNT_MISMATCH',
    `process_status`         VARCHAR(20)  NOT NULL COMMENT 'NEW/PROCESSING/SUCCESS/SKIPPED_DUPLICATE/FAILED',
    `trace_id`               VARCHAR(64)  DEFAULT NULL COMMENT '全链路追踪',
    `created_time`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_transaction` (`channel_transaction_no`),
    KEY `idx_payment_no` (`payment_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付回调日志表';

CREATE TABLE IF NOT EXISTS `payment_refund` (
    `id`                 BIGINT        NOT NULL COMMENT '主键（Snowflake）',
    `refund_no`          VARCHAR(64)   NOT NULL COMMENT '退款单号（唯一）',
    `payment_no`         VARCHAR(64)   NOT NULL COMMENT '支付单号',
    `order_no`           VARCHAR(64)   NOT NULL COMMENT '订单号',
    `user_id`            BIGINT        NOT NULL COMMENT '用户ID',
    `amount`             DECIMAL(18,2) NOT NULL COMMENT '退款金额',
    `status`             VARCHAR(20)   NOT NULL DEFAULT 'REFUNDING' COMMENT 'REFUNDING/REFUND_SUCCESS/REFUND_FAILED',
    `channel_refund_no`  VARCHAR(64)   DEFAULT NULL COMMENT '渠道退款号',
    `version`            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_time`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    KEY `idx_payment_no` (`payment_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款单表';
