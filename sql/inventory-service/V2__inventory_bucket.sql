-- ============================================================
-- inventory-service 分桶迁移 V2（Phase 6.2.1，评审后实施）
-- database: seckill_inventory
-- 说明：库存事实分桶表。inventory 保留为 SKU 汇总行（total 权威），
--       分桶 SUM 由对账校验；DEDUCT/RECOVER 在分桶行上加锁。
-- ============================================================
USE `seckill_inventory`;

CREATE TABLE IF NOT EXISTS `inventory_bucket` (
    `id`              BIGINT      NOT NULL COMMENT '主键（Snowflake）',
    `sku_id`          BIGINT      NOT NULL COMMENT '商品SKU',
    `bucket_no`       INT         NOT NULL COMMENT '桶号 0..N-1',
    `total_stock`     INT         NOT NULL COMMENT '本桶总库存',
    `locked_stock`    INT         NOT NULL DEFAULT 0 COMMENT '本桶已锁定库存',
    `available_stock` INT         NOT NULL COMMENT '本桶可用库存',
    `version`         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_bucket` (`sku_id`, `bucket_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='库存事实分桶表（Phase 6.2）';
