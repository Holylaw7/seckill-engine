# Phase 6.7 Canary Launch Report

> 版本：v1.0（Canary Launch Validation 评审稿）
> 前置：Phase 6.6 Production Launch Preparation 完成（CONDITIONAL GO）
> 目标：RC1 5%→25%→50%→100% Canary 验证，为 Full Production Launch 提供可信依据
> 原则：只做发布工程与验证，RC1 业务模型冻结（状态机/Lua/幂等/分桶/schema 零改动）

---

## 1. Canary Timeline

生产时间线（运营执行计划；本仓库已完成同逻辑的自动化演练）：

```
T0       : prepare（Redis 预热 + 对账 + dependency-scan GREEN）
T+30     : 5%  → health gate PASS → 进入 25%
T+60     : 25% → health gate PASS → 进入 50%
T+120    : 50% → health gate PASS → 进入 100%
T+180    : 100% 全量验证（30min 观察窗口）
任意阶段  : WARNING 人工确认；CRITICAL 自动阻止升级并回退上一稳定档
```

自动化演练结果：

| 阶段 | 验证 | 结果 |
| --- | --- | --- |
| 5→25→50→100 | CanaryReleaseWorkflowTest（健康快照全 PASS） | PASS（4 阶段推进至 weight=100） |
| CRITICAL 拦截 | 25% 出现 oversell>0 → 停在上一个稳定档 5% | PASS（ROLLED_BACK_TO_5） |
| 100→50→25→0 | CanaryRollbackDrillIT（粘性 + RTO） | PASS |

## 2. Traffic Metrics

### Gateway（Phase 6.4 隔离拓扑官方容量 vs 6.6/6.7 单机复核）

| 指标 | 生产预测（Phase 6.4 隔离环境） | 6.7 单机复核（仅拓扑验证） |
| --- | --- | --- |
| 安全容量 | 单 Gateway ~700-900 QPS（200 并发档 p99≤500ms，error=0%） | c100=357.86 QPS（p99 3114ms，error 0%） |
| 容量拐点 | ≥5000 并发 error>1%（单机后端饱和） | 单机 c500 起客户端饱和 |
| 429 | 限流回归由 GatewayRateLimitRegressionTest 覆盖（Phase 6.3，@load 默认跳过） | Canary 演练未触发限流（429=0） |

### E2E 流量模拟（隔离 JVM 拓扑）

报告：`capacity/traffic-simulation-report.json`（Level 1）与 `capacity/production-e2e-capacity-report.json`（L-07 10000）。

| 指标 | Traffic-Sim L1（5000） | L-07（10000） |
| --- | --- | --- |
| 成功目标 | 5000 | 10000 |
| produceQPS | 10.42（单机客户端受限） | 20.83（单机客户端受限） |
| MQ 收敛（backlog=0） | 303ms | 298ms |
| 行锁等待增量 | 5001 | 10039 |
| 死锁 | 0 | 0 |
| Redis 命令增量 | 334053 | 142083 |
| 重复消息 | 安全（1 条流水） | 安全（1 条流水） |
| 恢复抽样 | 50 单 RECOVER PASS | 100 单 RECOVER PASS |

### Redis / DB

- Redis：非瓶颈（Phase 6.2 L-02 2195 QPS；G-09 c100 redis 3817 cmd/s）；生产由 `redis_command_latency` 监控；
- DB 慢 SQL：基线 `slow-sql-baseline.md`（row_lock_waits 18602 / slow SQL 2480 / deadlock 0，Phase 6.4）；本阶段演练 deadlock=0。

## 3. Consistency Report

| 项 | 结果 |
| --- | --- |
| Oversell | 0（Traffic-Sim L1 5000、L-07 10000 全过） |
| Deadlock | 0 |
| Inventory 不变量 | PASS（available+locked=total；Redis global==SUM(bucket.available)） |
| Redis/MySQL 一致性 | RedisStockValidationIT PASS（8 桶；污染分桶后 FAIL 并阻止升级） |
| Recovery | PASS（取消恢复 50/100 单 + Redis 回补） |
| Duplicate | PASS（重复消息只产生一次业务效果） |
| MQ backlog | 0（订单数==DEDUCT 流水数） |

## 4. Rollback Report

CanaryRollbackDrillIT（真实 Gateway + stub 后端）：

| 项 | 结果 |
| --- | --- |
| 回滚路径 | 100% → 50% → 25% → 0% |
| 粘性路由 | 同权重下同用户 5 次请求目标一致（PASS） |
| 回滚起点 | weight=100（全部 RC1） |
| 回滚终点 | weight=0（全部 stable） |
| RTO | 毫秒级（断言 < 5min） |
| 流量恢复 | 全部请求 200，`X-Canary-Version: stable` |
| 数据一致性 | 演练仅切换路由，不触碰库存/订单；库存一致性由 RedisStockValidationIT / ReleaseDrillIT 独立验证 PASS |

## 5. Chaos Result

本阶段未修改状态机/幂等/库存模型，沿用 Phase 6.4 H-05 Production Chaos 结论（4/4 PASS）：

- F-01 Gateway 实例关闭 → 流量迁移 PASS
- F-02 Redis blacklist 不可用 → fail-open PASS
- F-03 MQ consumer crash → 重复消费只生效一次 PASS
- F-04 Inventory 节点异常 → 恢复后消费积压 PASS

## 6. Dependency Scan Gate（Task 7）

- CI：`dependency-scan`（CVSS≥7 FAIL）阻塞 release-gate；
- 归档：CI artifact `release-security-audit` → `docs/06-production/security/dependency-report.{json,html}`；
- 规则说明：[security/dependency-scan-gate.md](./security/dependency-scan-gate.md)；
- 状态：门禁实现 ✅；CI 绿色结果 ⏳（需目标 commit 在 GitHub Actions 执行确认，本地 NVD 超时无法替代）。

## 7. Commit 列表

| Commit | 内容 |
| --- | --- |
| `66d9f1f` | feat(gateway): add canary traffic controller |
| `dd39bd9` | feat(release): add canary health evaluator |
| `2b8fb36` | feat(inventory): add redis stock validation job |
| `8be6c3e` | test(load): add production traffic simulation |
| `ed900fc` | test(recovery): add canary rollback drill |
| `da42e94` | feat(ci): enforce dependency security gate |
| （本次） | docs(release): phase6.7 canary launch report |

## 8. Changed Files

### 生产代码（发布工程，业务模型零改动）

- `gateway/.../canary/`：CanaryTrafficProperties / CanaryRoutePredicate / CanaryTrafficFilter / CanaryTrafficController / CanaryReleaseWorkflow
- `gateway/.../GatewayApplication.java`、`application.yml`（canary 默认关闭）
- `seckill-common/.../canary/CanaryHealthEvaluator.java`
- `inventory-service/.../service/RedisStockValidationJob.java`

### 测试与 CI

- `gateway`：CanaryRoutePredicateTest / CanaryReleaseWorkflowTest
- `seckill-common`：CanaryHealthEvaluatorTest
- `inventory-service`：RedisStockValidationJobTest
- `integration-test`：RedisStockValidationIT / CanaryRollbackDrillIT / ProductionTrafficSimulationIT（load）
- `.github/workflows/ci.yml`：依赖扫描归档

### 文档

- `docs/06-production/security/dependency-scan-gate.md`
- `docs/06-production/capacity/traffic-simulation-report.json`（归档）
- `docs/06-production/phase6.7-canary-launch-report.md`（本报告）

## 9. Release Gate

| Gate | 要求 | 结果 |
| --- | --- | --- |
| Canary controller | 决策 + sticky + 动态权重 + header 路由 | ✅ |
| User sticky routing | 同用户同权重恒定 | ✅（单测 + IT） |
| 5% / 25% / 50% / 100% | 时间线推进与健康门禁 | ✅（Workflow 演练） |
| Dependency scan GREEN | CI 门禁 | ⏳ 需 CI 确认 |
| Production traffic simulation | L1 5000 实跑 PASS；L2/L3 协议就绪 | ✅（环境限制如实记录） |
| Oversell / Deadlock | 0 | ✅ |
| MQ backlog recover | PASS | ✅ |
| Redis/MySQL consistency | PASS（RedisStockValidationIT） | ✅ |
| Rollback | 100→0，RTO<5min | ✅ |
| Documentation | 本报告 + 子文档 | ✅ |

## 10. Remaining Limitations（诚实声明）

1. Canary Timeline 为自动化演练（Workflow + Rollback Drill），非真实生产流量；
2. Traffic-Sim Level 2（50k）/ Level 3（100k）在单机环境仅协议就绪，未实跑；
3. Dependency Scan 的 CI 绿色结果待目标 commit 在 GitHub Actions 确认；
4. produceQPS 受单机压测客户端限制（10-20 QPS），不代表生产吞吐；
5. H-05 Chaos 结论沿用 Phase 6.4（本阶段未重复执行）；
6. 429/限流回归为 @load 默认跳过，生产金丝雀窗口需在监控面板确认 gateway_rate_limit_total。

## 11. Final Go / No-Go Decision

**结论：CONDITIONAL GO — 允许进入生产 5% Canary 验证，禁止直接全量发布。**

放行条件：

1. 目标 commit 的 CI dependency-scan 绿色；
2. 生产配置中心：`seckill.gateway.canary.enabled=true, weight=5, version=RC1`；
3. Redis 预热 + RedisStockValidationJob 对账 PASS；
4. 5% 窗口 30min：error=0 / oversell=0 / deadlock=0 / MQ lag=0 / gateway_rate_limit_total 正常；
5. 每档升级前 Health Gate 由 CanaryHealthEvaluator 判定（WARNING 人工确认，CRITICAL 自动回退）；
6. 回滚预案就绪（CanaryRollbackDrillIT 已验证，RTO<5min）。

不满足任一项 → NO-GO 并回退 stable；完整清单见 production-go-live-checklist.md。
