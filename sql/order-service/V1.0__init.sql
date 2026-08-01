-- ============================================================
-- order-service 初始化脚本 V1.0（冻结：详细设计基线 + v1.1 cancel_notify_status）
-- database: seckill_order
-- ============================================================
CREATE DATABASE IF NOT EXISTS `seckill_order`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `seckill_order`;

CREATE TABLE IF NOT EXISTS `seckill_order` (
    `id`           BIGINT        NOT NULL COMMENT '主键=orderId（Snowflake）',
    `order_no`     VARCHAR(64)   NOT NULL COMMENT '业务订单号（SO+Snowflake）',
    `user_id`      BIGINT        NOT NULL COMMENT '用户ID（预留分片键）',
    `session_id`   BIGINT        NOT NULL COMMENT '秒杀场次ID',
    `sku_id`       BIGINT        NOT NULL COMMENT '商品SKU',
    `quantity`     INT           NOT NULL DEFAULT 1 COMMENT '数量',
    `order_amount` DECIMAL(18,2) NOT NULL COMMENT '订单金额',
    `order_status` VARCHAR(20)   NOT NULL DEFAULT 'CREATE' COMMENT 'CREATE/WAIT_PAY/PAY_SUCCESS/CANCEL/TIMEOUT/REFUND',
    `active_key`   VARCHAR(64)   DEFAULT NULL COMMENT '有效订单唯一键=user_id:session_id:sku_id，终态置NULL',
    `cancel_notify_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'CANCEL_ORDER 发送状态：PENDING/SENT（v1.1 变更）',
    `pay_deadline` DATETIME(3)   DEFAULT NULL COMMENT '支付截止时间',
    `paid_at`      DATETIME(3)   DEFAULT NULL COMMENT '支付时间',
    `cancel_reason` VARCHAR(256) DEFAULT NULL COMMENT '取消/超时原因',
    `version`      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at`   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_active` (`active_key`),
    KEY `idx_user_status` (`user_id`, `order_status`),
    KEY `idx_user_session_sku` (`user_id`, `session_id`, `sku_id`),
    KEY `idx_status_deadline` (`order_status`, `pay_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀订单表';

CREATE TABLE IF NOT EXISTS `order_item` (
    `id`         BIGINT        NOT NULL COMMENT '主键（Snowflake）',
    `order_id`   BIGINT        NOT NULL COMMENT '订单ID',
    `user_id`    BIGINT        NOT NULL COMMENT '冗余分片键',
    `sku_id`     BIGINT        NOT NULL COMMENT '商品SKU',
    `sku_name`   VARCHAR(128)  NOT NULL COMMENT '商品名称快照',
    `quantity`   INT           NOT NULL COMMENT '数量',
    `price`      DECIMAL(18,2) NOT NULL COMMENT '成交单价快照',
    `amount`     DECIMAL(18,2) NOT NULL COMMENT '小计金额',
    `created_at` DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_order` (`order_id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细表';

CREATE TABLE IF NOT EXISTS `idempotent` (
    `id`          BIGINT      NOT NULL COMMENT '主键（Snowflake）',
    `biz_type`    VARCHAR(32) NOT NULL COMMENT '业务类型：ORDER_CREATE/PAY_CALLBACK/ORDER_CANCEL',
    `biz_id`      VARCHAR(64) NOT NULL COMMENT '业务幂等ID（messageId/回调流水号）',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `result_code` VARCHAR(32) DEFAULT NULL COMMENT '首次处理结果',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
    `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单域幂等表';
