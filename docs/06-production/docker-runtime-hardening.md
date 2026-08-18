# Docker Runtime Hardening

> Phase 6 整体风险收敛：运行配置加固，不改变秒杀、Redis Lua、库存分桶或 MQ 业务语义。
> 本文只描述本地/演示 Compose 的可验证边界，不能替代生产 HA 拓扑验收。

## 1. 已落地的运行保证

| 风险 | 加固措施 | 边界 |
| --- | --- | --- |
| MySQL 健康检查误报 | 使用容器内 `MYSQL_ROOT_PASSWORD` 执行 `mysqladmin ping` | 只证明 MySQL 可接受连接 |
| MySQL 重启丢数据 | `mysql-data:/var/lib/mysql` | 仍需独立备份和恢复演练 |
| Redis 正常重启丢写入 | AOF `everysec`、RDB save、`redis-data:/data` | Redis 仍不是库存最终事实源 |
| RocketMQ broker 重启丢本地文件 | 演示/生产 Compose 均挂载 broker store/logs 命名卷 | 演示为单 broker；生产基线为双 NameServer + SYNC_MASTER/SLAVE，同一主机仍不等于跨故障域 HA |
| 依赖未就绪即启动业务 | MySQL/Redis/namesrv/broker healthcheck + `service_healthy` | 应用自身仍需通过 `/actuator/health` 验证 |
| 演示密钥误用于共享环境 | `.env` 覆盖入口，`.env` 被 `.gitignore` 忽略 | 生产必须接入密钥管理系统 |
| 内部接口 nonce 多实例冲突 | Redis `SET NX EX` 共享防重放键，TTL 60s | Redis 不可用时 fail-closed；生产 Redis 需高可用 |

## 2. 验证命令

```bash
docker compose -f docker/docker-compose.yml config --quiet
docker compose -f docker/docker-compose.yml up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose -f docker/docker-compose.yml ps
docker compose -f docker/docker-compose.yml restart redis
docker exec seckill-redis-compose redis-cli CONFIG GET appendonly
docker exec seckill-redis-compose redis-cli INFO persistence
```

生产拓扑校验：

```bash
# 由 Vault/KMS/云密钥管理系统注入变量，禁止提交 .env
powershell -ExecutionPolicy Bypass -File scripts/validate-production-env.ps1
docker compose -f docker/docker-compose.production.yml config --quiet
docker compose -f docker/docker-compose.production.yml up -d --build
docker compose -f docker/docker-compose.production.yml ps
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

生产 Compose 已提供两个 NameServer 和一组 `SYNC_MASTER/SLAVE` Broker，并为业务服务注入双 NameServer
地址。该文件是可部署拓扑基线，不代表同一 Docker 主机已经具备物理隔离；正式生产必须将 NameServer
和主从 Broker 分散到独立节点/可用区，验证主节点故障、消息积压、重试、DLQ 和恢复时延。

退款通知遵循“本地事务提交后发布”：退款记录成功提交后才发送 `REFUND_SUCCESS`；
`PENDING` 扫描任务负责进程退出、Broker 短暂不可用等场景的补偿，订单消费按 `refundNo` 幂等。
