-- ============================================================
-- inventory-service stock_flow 分桶定位 V3（Phase 6.2.1）
-- 说明：bucket_no 仅作定位字段（DEDUCT 命中桶 / RECOVER 回补桶），
--       不参与 uk_biz 幂等键。
-- ============================================================
USE `seckill_inventory`;

-- 幂等：列已存在时跳过（restoreMysqlSchemas 会重放 V1~V3）
SET @bucket_col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'seckill_inventory'
      AND TABLE_NAME = 'stock_flow'
      AND COLUMN_NAME = 'bucket_no'
);
SET @bucket_ddl = IF(@bucket_col_exists = 0,
    'ALTER TABLE `stock_flow` ADD COLUMN `bucket_no` INT DEFAULT NULL COMMENT ''命中桶号（NULL=单桶/旧路径）''',
    'SELECT 1');
PREPARE bucket_stmt FROM @bucket_ddl;
EXECUTE bucket_stmt;
DEALLOCATE PREPARE bucket_stmt;

-- 幂等：索引已存在时跳过
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'seckill_inventory'
      AND TABLE_NAME = 'stock_flow'
      AND INDEX_NAME = 'idx_sku_bucket'
);
SET @idx_ddl = IF(@idx_exists = 0,
    'CREATE INDEX `idx_sku_bucket` ON `stock_flow` (`sku_id`, `bucket_no`)',
    'SELECT 1');
PREPARE idx_stmt FROM @idx_ddl;
EXECUTE idx_stmt;
DEALLOCATE PREPARE idx_stmt;
