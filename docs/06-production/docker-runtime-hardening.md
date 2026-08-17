# Docker Runtime Hardening

> Phase 6 整体风险收敛：运行配置加固，不改变秒杀、Redis Lua、库存分桶或 MQ 业务语义。
> 本文只描述本地/演示 Compose 的可验证边界，不能替代生产 HA 拓扑验收。

## 1. 已落地的运行保证

| 风险 | 加固措施 | 边界 |
| --- | --- | --- |
| MySQL 健康检查误报 | 使用容器内 `MYSQL_ROOT_PASSWORD` 执行 `mysqladmin ping` | 只证明 MySQL 可接受连接 |
| MySQL 重启丢数据 | `mysql-data:/var/lib/mysql` | 仍需独立备份和恢复演练 |
| Redis 正常重启丢写入 | AOF `everysec`、RDB save、`redis-data:/data` | Redis 仍不是库存最终事实源 |
| RocketMQ broker 重启丢本地文件 | broker store/logs 命名卷 | 单 broker 仍存在单点，生产稳定性保持 PENDING |
| 依赖未就绪即启动业务 | MySQL/Redis/namesrv/broker healthcheck + `service_healthy` | 应用自身仍需通过 `/actuator/health` 验证 |
| 演示密钥误用于共享环境 | `.env` 覆盖入口，`.env` 被 `.gitignore` 忽略 | 生产必须接入密钥管理系统 |

## 2. 验证命令

```bash
docker compose -f docker/docker-compose.yml config --quiet
docker compose -f docker/docker-compose.yml up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose -f docker/docker-compose.yml ps
docker compose -f docker/docker-compose.yml restart redis
docker exec seckill-redis-compose redis-cli CONFIG GET appendonly
docker exec seckill-redis-compose redis-cli INFO persistence
```

验证重点：

- MySQL `Health` 为 `healthy`，且不再使用错误的固定 `-proot` 密码；
- Redis `appendonly` 为 `yes`，`aof_enabled:1`，重启后关键探针 key 仍存在；
- namesrv/broker `Health` 为 `healthy`；
- `docker compose down` 后命名卷保留，`down -v` 才执行清理。

## 3. 恢复边界

Redis AOF 是基础设施级重启恢复能力，不改变业务恢复契约。出现 Redis 全量 key 丢失、
AOF 损坏或对账不一致时，必须：

1. 暂停秒杀入口；
2. 从 MySQL `inventory.available_stock` 和 `inventory_bucket.available_stock` 重建；
3. 对账确认 `Redis.available == SUM(bucket.available) == inventory.available`；
4. 必要时通过受保护的 repair 接口写入 REPAIR 流水；
5. 通过后再恢复流量。

RocketMQ 单 namesrv/单 broker Compose 仅用于演示和回归。生产 MQ 稳定性仍需独立
Namesrv/Broker 集群验证吞吐、重试、积压、DLQ 和故障切换，现有状态保持 `PENDING`。
