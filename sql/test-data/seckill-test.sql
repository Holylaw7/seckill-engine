-- seckill_seckill 测试种子：场次 READY + 秒杀商品
USE `seckill_seckill`;

INSERT INTO `seckill_session`
    (`id`, `activity_id`, `session_name`, `start_time`, `end_time`, `status`, `limit_per_user`)
VALUES
    (30001, 1, '测试场次-1000库存', '2000-01-01 00:00:00', '2999-12-31 00:00:00', 'READY', 1);

INSERT INTO `seckill_sku`
    (`id`, `session_id`, `sku_id`, `stock_total`, `price`, `limit_per_user`, `status`)
VALUES
    (40001, 30001, 20001, 1000, 99.00, 1, 1);
