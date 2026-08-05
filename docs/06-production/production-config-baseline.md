# Phase 6.5.1 Production Configuration Baseline

> 冻结时间：2026-08-05；分支：phase6.5
> 用途：生产启动配置可审计基线；同环境启动必须与以下配置一致。

## 1. 配置校验和（SHA-256）

| 文件 | SHA-256 |
| --- | --- |
| gateway/src/main/resources/application.yml | `C9C504486A4468941267640AB890A9981BDF8A4CB351A7BAFBF649D790329512` |
| auth-service/src/main/resources/application.yml | `A4CE2659FEA9447A909DD83EB9B16C1087F8CE9B96A102461689F6F6B12D8668` |
| seckill-service/src/main/resources/application.yml | `55ACEC442029E09DE15F227E3A08406A45C8BEC67AAAFFFBFD59053581D78329` |
| order-service/src/main/resources/application.yml | `4A6B67B2085548B37608516FE95DF627D5D305EAAE5A39271A62ACDCD2CE30A3` |
| inventory-service/src/main/resources/application.yml | `D18E82D8B164576FA0439503C9DEF639CBC8DF457BA8825AEF596359EE4E42DF` |

## 2. Gateway 基线

| 项 | 值 |
| --- | --- |
| 实例数（建议） | ≥2（生产） |
| JVM | -Xmx1024m，`-Xlog:gc` |
| server.netty.max-connections | 20000 |
| server.netty.connection-timeout | 5000ms |
| httpclient.pool | fixed / max-connections=2000 / max-pending-acquire=10000 / acquire-timeout=10000 |
| httpclient.connect-timeout / response-timeout | 5000ms / 30s |
| spring.data.redis.timeout | 3s |

## 3. Redis 基线

| 项 | 值 |
| --- | --- |
| maxmemory | 由运维按容量规划下发（登记） |
| eviction policy | allkeys-lru（建议，运维确认） |
| timeout（客户端） | 3s |
| blacklist strategy | 每请求 MGET（Option A），fail-open；Option B（本地缓存）待延迟监控触发后启用 |

## 4. MySQL 基线

| 项 | 值 |
| --- | --- |
| isolation level | REPEATABLE_READ（默认，未修改） |
| Hikari maximum-pool-size | 20 |
| connection-timeout / validation-timeout | 5000ms / 3000ms |
| leak-detection-threshold | 10000ms |
| slow query threshold | 200ms（生产慢查询阈值，运维确认） |

## 5. RocketMQ 基线

| 项 | 值 |
| --- | --- |
| seckill producer retry | 2（有限重试） |
| seckill producer send timeout | 3000ms |
| 消费并发 | order-consumer=16，inventory-consumer=8（Phase 6.1 冻结） |
| consumer maxReconsumeTimes | 默认 16（建议生产 3-5 + DLQ，登记） |

## 6. Inventory 分桶（生产目标）

| 项 | 值 |
| --- | --- |
| inventory.sharding.enabled | `true`（灰度后） |
| inventory.sharding.bucket-count | `8` |

## 7. 审计说明

- 配置变更必须更新校验和并走评审；
- 生产环境由配置中心下发，本地 yml 为基线模板；启动前以校验和比对。
