-- seckill_auth 测试种子（占位）
-- 说明：用户密码哈希由测试代码用 BCryptPasswordEncoder 动态生成并插入，
--       不在 SQL 中固化，避免弱口令哈希进入仓库。
USE `seckill_auth`;

-- 示例（仅供联调时由测试代码填充，不要直接执行）：
-- INSERT INTO `user` (`id`, `username`, `password_hash`, `status`, `roles`)
-- VALUES (10001, 'tester', '<BCrypt hash>', 1, 'USER');
