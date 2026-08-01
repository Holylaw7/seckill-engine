-- ============================================================
-- seckill-service 初始化脚本 V1.0（冻结：详细设计基线）
-- database: seckill_seckill
-- ============================================================
CREATE DATABASE IF NOT EXISTS `seckill_seckill`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `seckill_seckill`;

CREATE TABLE IF NOT EXISTS `seckill_activity` (
    `id`            BIGINT       NOT NULL COMMENT '活动ID（Snowflake）',
    `activity_name` VARCHAR(128) NOT NULL COMMENT '活动名称',
    `start_time`    DATETIME(3)  NOT NULL COMMENT '活动开始时间',
    `end_time`      DATETIME(3)  NOT NULL COMMENT '活动结束时间',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布 2已结束',
    `remark`        VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀活动表';

CREATE TABLE IF NOT EXISTS `seckill_session` (
    `id`             BIGINT      NOT NULL COMMENT '场次ID（Snowflake，即sessionId）',
    `activity_id`    BIGINT      NOT NULL COMMENT '所属活动ID',
    `session_name`   VARCHAR(128) NOT NULL COMMENT '场次名称',
    `start_time`     DATETIME(3) NOT NULL COMMENT '秒杀开始时间',
    `end_time`       DATETIME(3) NOT NULL COMMENT '秒杀结束时间',
    `status`         VARCHAR(20) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/PREHEATING/READY/ONGOING/ENDED',
    `limit_per_user` INT         NOT NULL DEFAULT 1 COMMENT '每人限购数量（默认1，可扩展N）',
    `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`        TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_activity` (`activity_id`),
    KEY `idx_status_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀场次表';

CREATE TABLE IF NOT EXISTS `seckill_sku` (
    `id`             BIGINT        NOT NULL COMMENT '主键（Snowflake）',
    `session_id`     BIGINT        NOT NULL COMMENT '场次ID',
    `sku_id`         BIGINT        NOT NULL COMMENT '商品SKU（引用商品主数据）',
    `stock_total`    INT           NOT NULL COMMENT '场次投放库存总量',
    `price`          DECIMAL(18,2) NOT NULL COMMENT '秒杀价',
    `limit_per_user` INT           NOT NULL DEFAULT 1 COMMENT '每人限购数量',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    `created_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_sku` (`session_id`, `sku_id`),
    KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀商品表';

CREATE TABLE IF NOT EXISTS `seckill_pre_deduct` (
    `id`            BIGINT      NOT NULL COMMENT '主键（Snowflake）',
    `message_id`    VARCHAR(64) NOT NULL COMMENT 'MQ消息ID（唯一，全链路冻结）',
    `order_id`      VARCHAR(64) DEFAULT NULL COMMENT '订单号（消费后回填）',
    `user_id`       BIGINT      NOT NULL COMMENT '用户ID',
    `session_id`    BIGINT      NOT NULL COMMENT '场次ID',
    `sku_id`        BIGINT      NOT NULL COMMENT '商品ID',
    `quantity`      INT         NOT NULL DEFAULT 1 COMMENT '数量',
    `tx_status`     VARCHAR(20) NOT NULL DEFAULT 'INIT' COMMENT '本地事务状态：INIT/SUCCESS/FAIL',
    `deduct_status` VARCHAR(20) NOT NULL DEFAULT 'DEDUCTED' COMMENT 'DEDUCTED已预扣/CONFIRMED已确认/RECOVERED已回补',
    `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秒杀预扣流水表（事务消息本地事务载体与回查依据）';
