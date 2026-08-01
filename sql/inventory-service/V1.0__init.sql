-- ============================================================
-- inventory-service 初始化脚本 V1.0（冻结：详细设计基线）
-- database: seckill_inventory
-- ============================================================
CREATE DATABASE IF NOT EXISTS `seckill_inventory`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `seckill_inventory`;

CREATE TABLE IF NOT EXISTS `inventory` (
    `id`              BIGINT NOT NULL COMMENT '主键（Snowflake）',
    `sku_id`          BIGINT NOT NULL COMMENT '商品SKU',
    `total_stock`     INT    NOT NULL COMMENT '总库存',
    `locked_stock`    INT    NOT NULL DEFAULT 0 COMMENT '已锁定库存',
    `available_stock` INT    NOT NULL COMMENT '可用库存',
    `version`         INT    NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`         TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存事实表（MySQL为最终库存事实源）';

CREATE TABLE IF NOT EXISTS `stock_flow` (
    `id`          BIGINT       NOT NULL COMMENT '主键（Snowflake）',
    `flow_no`     VARCHAR(64)  NOT NULL COMMENT '流水号（唯一）',
    `sku_id`      BIGINT       NOT NULL COMMENT '商品SKU',
    `change_type` VARCHAR(20)  NOT NULL COMMENT 'INIT/DEDUCT/RECOVER/REPAIR/ADJUST/CONFIRM',
    `change_qty`  INT          NOT NULL COMMENT '变动数量（扣减为负，回补为正）',
    `before_qty`  INT          NOT NULL COMMENT '变动前可用库存',
    `after_qty`   INT          NOT NULL COMMENT '变动后可用库存',
    `biz_type`    VARCHAR(32)  NOT NULL COMMENT '业务类型：ORDER/CANCEL/TIMEOUT/REFUND/MANUAL',
    `biz_id`      VARCHAR(64)  NOT NULL COMMENT '业务ID（订单号/修复单号）',
    `operator_id` BIGINT       DEFAULT NULL COMMENT '人工修复操作人ID',
    `remark`      VARCHAR(256) DEFAULT NULL COMMENT '备注',
    `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_flow_no` (`flow_no`),
    UNIQUE KEY `uk_biz` (`biz_type`, `biz_id`),
    KEY `idx_sku_created` (`sku_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水表（只增，支持扣减/回补/人工修复）';
