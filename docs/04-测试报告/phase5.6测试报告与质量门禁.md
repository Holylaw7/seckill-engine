# Phase 5.6 测试报告与质量门禁

版本：v1.0
分支：feature/phase5-test
基线：Phase 5.2 ~ Phase 5.5 全部测试结果（真实 Testcontainers 环境）
环境：MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1 / JDK 21 / Spring Boot 3.2.5

---

## 1. 测试范围总览

本报告汇总 Phase 5.2 ~ Phase 5.5 全部测试结果，作为 Phase 5 测试验收与 Release Gate 基线。

| 阶段 | 内容 | 数量 | 结果 |
| --- | --- | ---: | --- |
| Phase 5.2 单元测试 | 7 个业务模块单元测试 | 237 | PASS |
| Phase 5.3 集成测试 | 正向/异常/并发/幂等/回调/竞争（真实中间件） | 17 | PASS |
| Phase 5.4 故障演练 | Redis/MySQL/RocketMQ/服务异常 | 13 | PASS |
| Phase 5.5 压测验证 | L-01~L-05 容量与稳定性 | 5 场景 | PASS |

模块覆盖：common、gateway、auth、seckill、inventory、order、payment、integration-test。

最终测试基线：

| 项 | 数量 |
| --- | ---: |
| 单元测试 | 237 |
| 集成测试（含故障演练） | 30 |
| 总测试基线 | 267 |
| failures / errors | 0 / 0 |

说明：L-01~L-05 为 `@LoadTest` 门控压测（默认跳过，`-Dload.enabled=true` 执行），不计入 267 常规基线，作为容量与稳定性专项记录。

---

## 2. 单元测试质量报告

| 模块 | 数量 | 结果 |
| --- | ---: | --- |
| common | 34 | PASS |
| gateway | 32 | PASS |
| auth | 38 | PASS |
| seckill | 41 | PASS |
| inventory | 31 | PASS |
| order | 29 | PASS |
| payment | 32 | PASS |
| **合计** | **237** | **PASS** |

覆盖重点：

- 状态机：订单/支付状态机合法流转、非法跳转拒绝、CAS 冲突返回 false；
- CAS：乐观锁冲突重试与超限失败路径；
- 幂等：重复消息/重复回调只产生一次业务效果；
- 库存一致性：扣减/回补流水、available+locked=total；
- MQ 异常处理：消费异常、业务错误不重试、重复消息 ACK；
- 雪花 ID、TraceId、JSON 序列化、限流/JWT/黑名单等公共能力。

---

## 3. 集成测试报告

真实环境：MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1（Testcontainers，禁止 Mock）。

### 3.1 正向链路

```text
登录（JWT）
    ↓
秒杀 execute（真实 HTTP）
    ↓
Redis Lua 原子预扣
    ↓
RocketMQ 事务消息
    ↓
order 建单（WAIT_PAY）
    ↓
inventory DEDUCT（行锁 + CAS）
    ↓
支付创建（WAIT_PAY）
```

### 3.2 异常与一致性链路

- MQ 重复消息幂等（CREATE_ORDER / CANCEL_ORDER / PAY_SUCCESS）；
- 超时关单 → CANCEL_ORDER → 库存回补（Redis/MySQL 一致）；
- 支付重复回调防重放、金额异常、验签失败；
- 超时关单与支付成功并发竞争（version CAS 唯一终态）；
- Lua 100 库存 / 200 并发零超卖。

### 3.3 结果

集成测试 17 项（Smoke 3 + 全链路 1 + 并发 1 + 失败链路 3 + MQ 幂等 2 + 取消恢复 2 + 支付回调 4 + 超时竞争 1）全部 PASS。

---

## 4. 故障演练报告

| 域 | 场景 | 结果 |
| --- | --- | --- |
| Redis | R-01 秒杀期间不可用；R-02 恢复与库存一致性；R-03 宕机期间取消回补 | PASS |
| MySQL | M-01 连接异常；M-02 写入失败；M-03 事务回滚 | PASS |
| RocketMQ | Q-01 producer 失败；Q-02 consumer 异常；Q-03 消费重试；Q-04 幂等复验 | PASS |
| 服务异常 | S-01 order 下线；S-02 inventory 下线；S-03 payment 下线 | PASS |

总结：

- 故障可恢复（容器重启/权限恢复/服务重启/MQ 续跑）；
- 无数据污染（命名空间隔离 + 清理）；
- MQ 最终收敛（backlog=0）；
- 库存最终一致（available+locked=total，Redis=MySQL）。

---

## 5. 性能基线

环境说明：Testcontainers 单机环境，数据为环境基线（非生产门禁）；生产级容量验证留 Phase 5.8。

### L-01 秒杀接口压力

| 项 | 值 |
| --- | --- |
| 规模 | 库存 100 / 并发 1000 / 请求 1000 |
| 结果 | 成功 100、零超卖、订单=成功数 |
| QPS | 741.31 |
| avgRT / p50 / p95 / p99 | 790.45ms / 849.01ms / 1237.11ms / 1323.36ms |

### L-02 Redis Lua 原子性

| 项 | 值 |
| --- | --- |
| 规模 | 库存 1000 / 并发 10000 / 请求 10000 |
| 结果 | 成功 1000、Redis stock=0、用户标记=1000、Lua 异常 0 |
| QPS | 1847.57 |
| avgRT / p50 / p95 / p99 | 2663.93ms / 2683.49ms / 4953.50ms / 5154.46ms |

### L-03 MQ 消费能力

| 项 | 值 |
| --- | --- |
| CREATE_ORDER | 生产 5000 / 消费 5000，生产 QPS 3618.61，收敛 51.8s |
| CANCEL_ORDER | 5000 / 5000，RECOVER 5000，backlog=0 |
| PAY_SUCCESS | 200 / 200（仅验证发布能力） |
| 幂等 | 重复消息只生效一次 |

### L-04 数据库压力

| 项 | 值 |
| --- | --- |
| 写入量 | Com_insert +99,024 / Com_update +57,001 |
| TPS | ≈2605 |
| 死锁 / 锁等待 / 慢 SQL | 0 / 18,839 / 4,596（环境基线记录） |
| 一致性 | 订单 24,000、DEDUCT 24,000、RECOVER 3,000，available+locked=total |

### L-05 30 分钟稳定性

| 项 | 值 |
| --- | --- |
| 时长 / 目标 QPS | 30 分钟 / 200 |
| 实测 | 342,720 请求，avgQps 190.4（±10% 内），成功 10,000，非预期失败 0.35% |
| MQ | backlog=0 |
| JVM | Heap 前段均值≈284MB → 后段≈365MB（无泄漏趋势）；GC ≈33 次/分钟 |
| 一致性 | Redis stock=MySQL available=10,000，locked=0，用户标记≤成功订单，PASS |

---

## 6. 质量门禁规则

以下为 Release Gate（G-01~G-07），全部通过才允许进入下一阶段：

| 门禁 | 规则 | 验证来源 |
| --- | --- | --- |
| G-01 单元测试 | 失败=0 | Phase 5.2：237/237 |
| G-02 集成测试 | 失败=0 | Phase 5.3/5.4：30/30 |
| G-03 库存一致性 | available + locked = total，Redis stock = MySQL available | 集成/故障/压测全场景 |
| G-04 超卖 | 成功订单 ≤ 库存，库存非负 | L-01/L-02/L-05 |
| G-05 MQ | backlog=0，消息不丢失、最终收敛 | L-03/L-05 |
| G-06 幂等 | 重复消息只产生一次业务效果 | MQ 幂等专项 + Q-04 |
| G-07 稳定性 | 30 分钟无持续错误增长、Heap 无泄漏趋势 | L-05 |

---

## 7. 已发现生产缺陷汇总

以下缺陷在 Phase 5 测试中暴露并已修复（按 git 提交记录整理，均为独立 fix 提交，与测试提交分离）：

| 模块 | 问题 | 发现阶段 | 原因 | 修复 commit | 验证结果 |
| --- | --- | --- | --- | --- | --- |
| seckill | 事务消息本地流程与金额快照问题 | Phase 5.3.1 全链路联调 | 事务消息本地事务与金额快照口径缺陷 | 71cd4b6 | 全链路集成测试 PASS |
| auth / payment / common | LoginResponse / CreatePayResponse / PageResult 缺少无参构造 | Phase 5.3.1 | JSON 反序列化需要无参构造 | 0456743 / 1baa858 / 748cc44 | 集成测试 PASS |
| inventory | RECOVER 消费组与 CREATE_ORDER 同组导致订阅被拆分 | Phase 5.3.1 | 消费组 tag 订阅冲突 | 069aa62 | 全链路集成测试 PASS |
| seckill | 缺少内部库存回补接口（R-01） | Phase 5.3.2 设计评审 | 冻结契约未实现 | e8dc096 | 取消恢复链路 PASS |
| order / payment | @PathVariable 未显式命名 | Phase 5.3.2 集成测试 | 编译未开启 -parameters | 3e1346e / 210b3da | 用户取消/支付回调 PASS |
| inventory | confirmDeduct CAS 100 路竞争饥饿（仅 13/100 成功） | Phase 5.3.2 并发压测 | 3 次 CAS 重试不足，按 sku 串行化缺失 | e0d51b6 | 200 并发/100 库存零超卖 PASS |
| payment | 金额异常回调二次写日志触发 uk_callback_transaction 冲突（R-03） | Phase 5.3.2 支付回调 | 失败原因应回填既有日志行 | cc66bb2 | 金额异常回调 PASS |
| pom | 全工程控制器省略参数名依赖 -parameters 未开启（reconcile 等接口 10000） | Phase 5.4 故障演练 | 编译配置缺失 | 7fa4c5d | reconcile/全量回归 PASS |
| inventory | recoverStock CAS 5000 路竞争饥饿（RECOVER=4996） | Phase 5.5.4 L-03 | 回补路径缺少行锁串行化 | 4b3a25d | L-03 5000/5000 PASS |

说明：任务清单中提及的“Redis bean 注入”修复未在分支提交历史中找到对应记录，本报告不纳入未经验证条目。

---

## 8. 已知边界

以下边界在本阶段不修复，登记为 Known Issue / Follow-up：

1. **PAY_SUCCESS 订单联动已在整体收敛阶段补齐**：payment 回调发布事件，order-service 按 `paymentNo` 幂等消费并更新 `WAIT_PAY→PAY_SUCCESS`；由 `PaymentCallbackFlowIT` 回归验证。
2. **recover 契约无 userId**：Redis 回补不处理 `seckill:user:{skuId}:{userId}` 防重标记清理；用户标记语义留设计评审。
3. **Redis 无持久化**：宕机恢复依赖“按 MySQL available_stock 重新预热 + 对账/repair”，已由 `BackupRecoveryDrillIT` 演练。
4. **性能基线为 Testcontainers 单机环境值**（R-01/R-02/R-05）：10,000 QPS 等生产门禁需独立环境压测；L-04 慢 SQL 4,596 条为环境基线观察，不作为本阶段失败项。
5. **退款事件尚未闭环**：`REFUND_SUCCESS → order REFUND` 保留为后续工作。

---

## 9. 最终验收结论

Phase 5 测试体系全部通过：

- 单元 237/237、集成（含故障演练）30/30，全量 267 tests，failures=0、errors=0；
- 压测 L-01~L-05 全部 PASS；
- 质量门禁 G-01~G-07 全部满足。

当前版本满足：

- 功能正确性；
- 数据一致性（Redis/MySQL/MQ/订单/支付五域口径）；
- 故障恢复能力；
- 幂等能力；
- 200 QPS 级 30 分钟稳定运行能力。

达到进入下一阶段（Phase 5.8 报告/Phase 6 性能优化）条件。

---

## 10. 提交信息

```text
docs(test): complete phase5.6 quality report
```
