# Phase 5 测试数据（sql/test-data）

用途：集成测试（Phase 5.3+）初始化各服务数据库种子数据。

约定：

- 脚本按服务分文件，仅包含**测试环境**种子数据，禁止包含生产数据；
- 表结构由各服务 `sql/{service}/V1.0__init.sql` 创建，本目录只负责数据；
- 测试代码按需执行对应脚本（集成测试基类提供加载入口）；
- 用户密码哈希由测试代码使用 BCrypt 生成，不在此固化。

文件：

| 文件 | 服务库 | 内容 |
| --- | --- | --- |
| `auth-test.sql` | seckill_auth | 测试用户/黑名单样例（占位说明） |
| `seckill-test.sql` | seckill_seckill | READY 场次与秒杀商品种子 |
| `inventory-test.sql` | seckill_inventory | 库存事实种子 |
