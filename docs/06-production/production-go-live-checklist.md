# Production Go-Live Checklist（Phase 6.6）

> 状态：Phase 6.6 各 Task 已验证；此清单为上线当日逐项确认表。

## Infrastructure

- [x] Gateway >= 2 实例（生产拓扑：独立 JVM，见 phase6.4-production-topology.md）
- [x] Service JVM 隔离（auth / seckill / order / inventory / payment 独立 JVM）
- [x] Redis 就绪（key 预热 + failover 方案）
- [x] MySQL 备份已验证（BackupRecoveryDrillIT PASS）
- [x] RocketMQ 健康（namesrv/broker、Topic seckill-order-tx 已建）

## Security

- [x] 内部接口 Service ACL（X-Service-Name/Timestamp/Signature + 时间窗 + 防重放）
  - [x] recover：仅 inventory-service
  - [x] pre-deduct confirm：仅 order-service
  - [x] reconciliation：inventory-service / admin
  - [x] repair / syncSummary：仅 admin
- [x] Repair 权限体系（InternalApiSecurityIT / RepairAuthorizationIT PASS）
- [x] 依赖漏洞扫描门禁（CI dependency-scan，CVSS>=7 FAIL）

## Data

- [x] inventory_bucket 就绪（V2__inventory_bucket.sql，迁移演练 PASS）
- [x] Redis warmup 完成（预热=MySQL，Redis==SUM(bucket.available)）
- [x] reconciliation PASS（对账不变量 + Redis 口径）
- [x] 慢 SQL 基线（slow_query_log / long_query_time=1s，slow-sql-baseline.md）

## Monitoring

- [x] Prometheus 接线（/actuator/prometheus：Gateway / Seckill / Inventory）
- [x] Grafana Dashboard 就绪（Seckill Production Overview）
- [x] 告警规则启用（production-alert-rule.md：Redis!=MySQL / oversell / deadlock / MQ lag / error rate）
- [x] MQ 指标（rocketmq_consumer_lag / retry / dlq，生产侧 exporter/mqadmin）

## Rollback

- [x] Inventory 回滚已验证（N=8 → 删除分桶 → 旧 inventory 完整，ReleaseDrillIT PASS）
- [x] Gateway 配置回滚已验证（新容量配置 → 默认配置 → 流量恢复，GatewayRollbackDrillIT PASS）
- [x] DB 回滚已验证（inventory_bak 备份 → 迁移 → 恢复一致性，ReleaseDrillIT PASS）
- [x] Redis 恢复已验证（key 丢失 → 预热 → 对账一致，BackupRecoveryDrillIT PASS）
- [x] MQ consumer 恢复已验证（重启 → 积压消费且只生效一次，BackupRecoveryDrillIT PASS）

## Capacity（最终复核）

- [x] Gateway 容量模型（G-09 隔离拓扑，Phase 6.4 安全包络 ~700-900 QPS/实例；6.6 单机复核见 capacity/gateway-production-capacity.json）
- [x] E2E 容量闭环（L-07 10000 成功：零超卖 / deadlock=0 / 幂等 / 恢复 PASS；见 capacity/production-e2e-capacity-report.json）

## 上线当日动作（运维）

- [ ] 配置中心下发：inventory.sharding.enabled=true / bucket-count=8 / internal-auth 密钥
- [ ] Redis 预热脚本执行并对账（Redis stock == SUM(bucket.available)）
- [ ] 金丝雀：先 1 实例接入 5% 流量，观察 30min（SLO 面板）
- [ ] 全量前复核：error=0、oversell=0、deadlock=0、backlog=0
- [ ] 回滚预案责任人确认（DB 备份文件位置 / 配置版本 / 脚本）
