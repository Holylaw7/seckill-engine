-- seckill_inventory 测试种子：与 seckill-test.sql 对应（库存 1000）
USE `seckill_inventory`;

INSERT INTO `inventory`
    (`id`, `sku_id`, `total_stock`, `locked_stock`, `available_stock`, `version`)
VALUES
    (50001, 20001, 1000, 0, 1000, 0);
