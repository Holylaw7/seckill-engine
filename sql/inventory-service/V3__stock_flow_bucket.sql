-- ============================================================
-- inventory-service stock_flow 分桶定位 V3（Phase 6.2.1）
-- 说明：bucket_no 仅作定位字段（DEDUCT 命中桶 / RECOVER 回补桶），
--       不参与 uk_biz 幂等键。
-- ============================================================
USE `seckill_inventory`;

ALTER TABLE `stock_flow`
    ADD COLUMN `bucket_no` INT DEFAULT NULL COMMENT '命中桶号（NULL=单桶/旧路径）';

CREATE INDEX `idx_sku_bucket`
    ON `stock_flow` (`sku_id`, `bucket_no`);
