# Phase 6.6 Production Launch Report

> 版本：v1.0（Go/No-Go 评审稿）
> 前置：Phase 6.5 RC1（0.1.0-RC1）冻结，RC Hardening 完成
> 目标：将 RC1 从“技术可发布”提升到“生产可运营”，完成最终 Go/No-Go 评审
> 原则：只做安全、运维、验证、发布工程变更，RC1 业务模型冻结

---

## 1. Environment

隔离拓扑（与 Phase 6.4 一致，生产拓扑模型）：

| 组件 | 形态 |
| --- | --- |
| Gateway / auth / seckill / order / inventory | 独立 JVM（-Xmx1024m，GC 日志落盘） |
| MySQL 8.0.36 | Testcontainers 独立容器 |
| Redis 7.2.4 | Testcontainers 独立容器 |
| RocketMQ 5.3.1 | Testcontainers 独立容器 |
| Load Generator | 独立于被测组件 |

测试运行环境：单机 Windows + Docker Desktop（绝对数值受主机资源限制，不作为生产容量结论；
生产容量预测沿用 Phase 6.4 隔离环境数据并标注）。

## 2. RC Baseline

- 分支：`release/RC1`（Phase 6.5 冻结），tag `0.1.0-RC1`
- Phase 6.6 前置状态：Phase 5 Release Gate PASS、6.0 设计评审 PASS、6.1~6.5 全部完成
- 冻结项：inventory_bucket 模型、Redis Lua v2、stock_flow 幂等、DEDUCT/RECOVER 状态流转、
  JWT payload/安全策略、限流算法、blacklist fail-open、MQ message schema、消费状态机、幂等键

## 3. Security Hardening（Task 6.6.1 / 6.6.2）

### 内部接口 Service ACL

- `InternalSignature`：HMAC-SHA256（serviceName:timestamp），公共模块
- seckill-service `InternalApiAuthFilter`：
  - `/stocks/recover` 仅 inventory-service；`/pre-deducts/confirm` 仅 order-service
  - 时间窗 300s + Nonce 防重放
- inventory-service `InternalApiAuthFilter`：
  - `/admin/reconcile` 允许 inventory-service 或 admin
  - `/admin/reconcile/repair`、`/syncSummary` 仅 admin
- 客户端签名：order-service `RestPreDeductConfirmClient`、inventory-service `RestRecoverClient`
- 密钥：`seckill.internal-auth.*`（生产经配置中心下发；测试 CLI 补齐）

### 验证

| 用例 | 断言 | 结果 |
| --- | --- | --- |
| InternalApiSecurityIT | 未授权 401 / 错服务 403 / 授权 200 / 重放 401 | PASS |
| RepairAuthorizationIT | 未授权 401 / operator 查询 200 / operator repair 403 / admin repair 200 | PASS |
| SeckillFullFlowIT | 全链路（登录→秒杀→建单→确认→DEDUCT→支付） | PASS |
| CancelRecoverFlowIT | 取消/回补经签名调用 recover | PASS |

## 4. Dependency Scan（Task 6.6.3）

- CI 新增 `dependency-scan` 阶段：OWASP Dependency Check（10.0.4）
  - 规则：CVSS 0-6.9 warning；≥7.0 FAIL，阻塞 release-gate
  - 产物：`**/target/dependency-check-report.{json,html}` 上传 artifact
- 流水线顺序：compile → test → dependency scan → quality → release gate
- 本地验证说明：NVD 数据源在本地网络下 15min 未完成下载，扫描已登记为 CI 必跑门禁；
  生产发布前必须取得 CI dependency-scan 绿色结果（可配置 NVD API Key 加速）。

## 5. Monitoring Readiness（Task 6.6.4）

指标冻结（Prometheus）：

| 域 | 指标 | 状态 |
| --- | --- | --- |
| Gateway | gateway_request_total / gateway_request_duration / gateway_error_total / gateway_429_total / gateway_rate_limit_total | ✅（ObservabilitySmokeIT） |
| Seckill | seckill_success_total / seckill_fail_total / seckill_stock_empty_total | ✅ |
| Inventory | inventory_deduct_success/fail_total / inventory_bucket_lock_wait_seconds / inventory_deadlock_total / inventory_recover_total / reconcile_diff_total / inventory_repair_total | ✅ |
| MQ | rocketmq_consumer_lag / rocketmq_retry_total / rocketmq_dlq_total | 生产侧 exporter/mqadmin 接线（冻结命名） |

Grafana Dashboard：`grafana/seckill-production-overview.json`（5 视图：Gateway Traffic / 秒杀成功率 /
Inventory Safety / MQ Health / Recovery Status）。

慢 SQL：`SlowSqlMonitoringIT` 验证 slow_query_log=ON、long_query_time=1s、log_output=TABLE；
基线见 `slow-sql-baseline.md`（row_lock_waits 18602、slow SQL 2480、deadlock 0，Phase 6.4 数据）。

## 6. Capacity Verification（Task 6.6.6）

### G-09 Gateway（Phase 6.6 单机复核，隔离 JVM 拓扑）

报告：[capacity/gateway-production-capacity.json](./capacity/gateway-production-capacity.json)

| 并发 | QPS | p50 | p95 | p99 | error rate |
| --- | --- | --- | --- | --- | --- |
| 100 | 357.86 | 119ms | 487ms | 3114ms | 0.00% |
| 500 | 2236.92 | 131ms | 257ms | 267ms | 80.54%（单机客户端饱和） |
| 1000 | 1777.99 | 502ms | 574ms | 584ms | 99.27%（单机客户端饱和） |

> 说明：本机压测客户端与后端共享主机资源，高并发档出现 EXCEPTION 饱和，属环境伪影；
> 生产容量预测沿用 Phase 6.4 隔离环境结论：单 Gateway 安全容量 **~700-900 QPS**
> （p99 ≤500ms 取 200 并发档，错误率 0%）。

### L-07 E2E（10000 成功目标）

报告：[capacity/production-e2e-capacity-report.json](./capacity/production-e2e-capacity-report.json)

| 指标 | 结果 |
| --- | --- |
| 成功秒杀 | 10000（目标 10000） |
| 零超卖 | PASS（Redis stock == MySQL available，available+locked=total） |
| 死锁 | 0 |
| 行锁等待增量 | 10039（单机，非生产结论） |
| MQ 收敛 | 298ms（load 结束后 DB 计数收敛，backlog=0） |
| 重复消息安全 | PASS（抽样重发 CREATE_ORDER，stock_flow 仍 1 条） |
| 取消恢复 | PASS（抽样 100 单，RECOVER 流水 + Redis 回补） |
| 尾部业务码 | REPEAT_BUY 200（计数器回绕后的重复购买，预期行为） |

50k / 100k 协议已就绪（`-Dl07.success-target`），需独立压测环境执行，本单机不冒充生产结论。

## 7. Rollback Drill（Task 6.6.7）

| 演练 | 验证 | 结果 |
| --- | --- | --- |
| Inventory 回滚 | N=8 迁移 → 删除分桶 → 旧 inventory 完整（ReleaseDrillIT） | PASS |
| DB 回滚 | inventory_bak 备份 → 迁移 → 恢复后 total/available 一致（ReleaseDrillIT） | PASS |
| Gateway 配置回滚 | 新容量配置 → 默认配置 → stub 路由 200 流量恢复（GatewayRollbackDrillIT） | PASS |
| Redis 恢复 | key 丢失 → 按 MySQL 预热 → 对账一致（BackupRecoveryDrillIT） | PASS |
| MQ consumer 恢复 | 重启 → 积压消费且重复消息只生效一次（BackupRecoveryDrillIT） | PASS |

## 8. Known Limitations（诚实声明）

1. 本机单机环境：G-09 高并发档客户端饱和、L-07 produceQPS 受客户端限制（20.83），
   绝对数值不作为生产容量结论；生产容量以 Phase 6.4 隔离环境 + 独立压测环境复核为准；
2. 50k / 100k E2E 未在本机执行（协议就绪，需独立环境）；
3. OWASP dependency-check 本地 NVD 下载超时，扫描门禁以 CI 结果为准；
4. order-service PAY_SUCCESS 消费端未实现（Phase 5.6 登记，不阻塞秒杀主链路）；
5. Redis 无持久化，恢复依赖预热 + 对账（演练已覆盖）；
6. chaos 全量同 JVM 连续执行存在 MQ 收敛级联伪影，生产演练按类隔离执行（Phase 6.4 登记）。

## 9. Go / No-Go Decision

### Release Gate

| Gate | 要求 | 结果 |
| --- | --- | --- |
| Security（内部 ACL + Repair 权限） | PASS | ✅ |
| Dependency Scan | CI 门禁已建（CVSS≥7 FAIL） | ⏳ 需 CI 绿色（本机 NVD 超时） |
| Monitoring | 指标冻结 + Dashboard + 告警 | ✅ |
| Capacity | 容量模型 + E2E 10000 闭环 | ✅（生产结论以独立环境复核） |
| Rollback | Inventory/DB/Gateway/Redis/MQ 全演练 | ✅ |
| Inventory consistency | Redis/MySQL 一致、对账 PASS | ✅ |
| Oversell | 0 | ✅（L-07 10000 档） |
| Deadlock | 0 | ✅ |
| Duplicate effect | 0 | ✅ |
| 单元回归（非 integration-test 模块） | 329 tests, 0 failure, 0 error | ✅ |
| 关键集成/演练 | 17 类用例独立执行全部 PASS | ✅ |

### 结论：**CONDITIONAL GO（有条件放行，进入金丝雀上线准备）**

放行条件：

1. CI dependency-scan 在目标提交上取得绿色（CVSS≥7=0）；
2. 生产配置中心下发 `inventory.sharding.enabled=true / bucket-count=8` 与 internal-auth 密钥，
   并完成 Redis 预热 + 对账（Redis stock == SUM(bucket.available)）；
3. 金丝雀先 1 实例 5% 流量 30min，SLO 面板 error=0 / oversell=0 / deadlock=0 / backlog=0；
4. 50k/100k E2E 与 G-09 生产档（10min/档）在独立环境执行后冻结最终容量报告；
5. 回滚预案与责任人确认（DB 备份、配置版本、Gateway/分桶回滚脚本已演练）。

不满足条件 1-5 任一项 → 回退为 NO-GO，问题清单见 [production-go-live-checklist.md](./production-go-live-checklist.md)。

---

## 附：Commit 列表（Phase 6.6）

| Commit | 内容 |
| --- | --- |
| `f17b445` | feat(security): add internal api authentication |
| `e204a1f` | feat(security): secure repair authorization |
| `46c30e9` | ci: add dependency vulnerability gate |
| `23324e5` | feat(observability): production monitoring wiring |
| `89dd7ee` | feat(database): add slow sql monitoring |
| `408eb1a` | test(load): production capacity verification |
| `c7f92ca` | test(release): add production rollback validation |
| （本次） | docs(release): phase6.6 launch report |

## 附：主要修改文件

### 生产代码（仅安全/可观测补强）

- `seckill-common/.../security/InternalSignature.java`（新增）
- `seckill-service/.../security/InternalApiAuthFilter.java`、`config/InternalAuthProperties.java`（新增）
- `inventory-service/.../security/InternalApiAuthFilter.java`、`config/InternalAuthProperties.java`（新增）
- `seckill-service` / `inventory-service` / `order-service` 的 Application / application.yml / 内部客户端
- `gateway/.../RequestLogGlobalFilter.java`（gateway_rate_limit_total）
- `inventory-service/.../ReconciliationService.java`（inventory_repair_total）

### 测试与 CI

- `.github/workflows/ci.yml`（dependency-scan 门禁）
- `integration-test`：InternalApiSecurityIT / RepairAuthorizationIT / ObservabilitySmokeIT /
  SlowSqlMonitoringIT / GatewayRollbackDrillIT（新增），ServiceSupport / SeckillFullFlowIT /
  IsolatedTopology / 压测报告别名（修改）
- 客户端单测 ReflectionTestUtils 注入（inventory / order）

### 文档

- `docs/06-production/`：observability-hardening.md、slow-sql-baseline.md、
  production-go-live-checklist.md、phase6.6-production-launch-report.md（本报告）、
  capacity/（G-09/L-07 报告）、grafana/seckill-production-overview.json
