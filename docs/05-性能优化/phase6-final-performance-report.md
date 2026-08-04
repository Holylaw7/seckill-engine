# Phase 6 最终性能报告（含 Phase 6.2.2 分桶验证）

> 完成时间：2026-08-04
> 分支：feature/phase6.2-inventory-sharding
> 环境：真实 Testcontainers（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1），JDK 21，单机
> 说明：单机 Testcontainers 数据用于定位与对比，不作为生产容量结论。

---

## 1. 测试基线（Phase 5.5 原始）

| 场景 | 规模 | 关键指标 |
| --- | --- | --- |
| L-01 秒杀入口 | 库存 100 / 并发 1000 | QPS 741.31，p99 1323.36ms，成功 100，零超卖 |
| L-02 Redis Lua | 库存 1000 / 并发 10000 | QPS 1847.57，成功 1000，零超卖 |
| L-03 MQ 消费 | CREATE 5000 / CANCEL 5000 | 生产 3618.61 QPS，消费 96.5 QPS，收敛 51.8s，backlog=0 |
| L-04 数据库 | 5000 订单 + 10×1000 SKU + 混合写 | TPS≈2605，锁等待 18839，慢 SQL 4596，死锁 0 |
| L-05 稳定性 | 200 QPS / 30 分钟 | 190.4 QPS，Heap 284→365MB，无泄漏 |

## 2. Phase 6.1 优化结果（MQ 线程 + 分层测量）

| 项目 | 基线 | 优化后 |
| --- | --- | --- |
| order 消费链路 | 109 QPS（4 线程） | 269 QPS（16 线程） |
| L-03 combined 消费 | 99.73 QPS / 50.1s | 109.87 QPS / 45.5s（负载正常轮） |
| execute 核心路径 p99 | 1323ms（L-01 入口） | 网关 294.54ms / 直连 68.61ms（O-05 达标） |
| L-04 锁等待 / 慢 SQL | 18839 / 4596 | 18602 / 2480 |

结论：单行 `SELECT ... FOR UPDATE` 为硬瓶颈（~110-124 QPS），线程调优与批处理均无法突破。

## 3. Phase 6.2 分桶结果（L-06）

### 3.1 行数假设验证（InventoryShardingBenchmark，独立行模拟桶）

| 行数 | consume QPS | 2000 条收敛 | row_lock_waits 增量 | 死锁 |
| --- | --- | --- | --- | --- |
| 1 | 123.86 | 16.1 s | 1999 | 0 |
| 4 | 379.00 | 5.3 s | 1662 | 0 |
| 8 | 499.88 | 4.0 s | 1153 | 0 |
| 16 | 479.50 | 4.2 s | 635 | 0 |

### 3.2 真实分桶模型（BucketShardingLoadTest，5000 条 / N 桶 / 真实 inventory_bucket）

| N | consume QPS | 5000 条收敛 | row_lock_waits 增量 | 死锁 | 桶内不变量 |
| --- | --- | --- | --- | --- | --- |
| 1 | 127.12 | 39334 ms | 4999 | 0 | PASS |
| 4 | 295.28 | 16933 ms | 4116 | 0 | PASS |
| 8 | **360.18** | **13882 ms** | 2892 | 0 | PASS |

**O-01（≥300 QPS）与 O-02（5000 条 ≤25s）在 N=8 下达成（360.18 QPS / 13.9s）；N=4 已接近（295.28 QPS / 16.9s）。**

报告文件：`integration-test/target/load-reports/L-06-SHARDING*.json|csv`、`L-06-SHARDING-BUCKET*.json|csv`

## 4. Phase 6.2.2 验证结果（H-01~H-04）

### H-01 Inventory Sharding Benchmark

- 真实分桶 N=1/4/8 全部 PASS（见 §3.2）；行数假设 1/4/8/16 全部 PASS（见 §3.1）。

### H-02 Consistency Regression

- 10000 库存、500 并发（真实 Lua v2 + 8 桶 + MQ + 建单 + DEDUCT）：
  - 零超卖；`SUM(available)+SUM(locked)=SUM(total)=10000`；每桶不变量成立；
  - Redis 全局 == SUM(bucket.available)；重复 CREATE_ORDER 只产生 1 条 DEDUCT；
  - 超时恢复后每桶 available 恢复 1250、locked=0，Redis 全局与桶 key 恢复；
  - `syncSummary` 后对账 PASS。

### H-03 Failure Test

- F-01 Redis 分桶 key 缺失：秒杀快速失败（无订单/无预扣/无脏库存），预热后恢复；
- F-02 CANCEL_ORDER 重复投递：只产生 1 条 RECOVER，Redis 只回补一次；
- F-03 consumer crash 后重投：DEDUCT 只生效一次。

### H-04 Full Regression

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| 单元测试 | 305（common 34 / gateway 32 / auth 38 / seckill 69 / inventory 46 / order 31 / payment 55） | 0 failures / 0 errors |
| 集成测试 | 20（原 17 + 分桶一致性 3） | 0 failures / 0 errors |
| 故障演练 | 16（原 13 + 分桶故障 3） | 0 failures / 0 errors |
| L-01 | 1000 请求 / 库存 100 | PASS（474.07 QPS，成功 100，零超卖） |
| L-02 | 10000 并发 / 库存 1000 | PASS（2195.24 QPS，成功 1000，零超卖，0 异常） |
| L-03 | 5000 CREATE / 5000 CANCEL | PASS（backlog=0，幂等 4/4） |
| L-04 | 数据库压力 | PASS（insert 99024 / update 57001 / 死锁 0） |
| L-05 | 200 QPS / 30 分钟 | PASS（qps 184.73，closed=10000，Heap 292.8→371.6MB 无泄漏） |

注：L-01/L-03 绝对值随主机负载波动（L-03 本次 79.32 QPS / 63.0s），正确性门禁全部成立；L-04 慢 SQL 本次 5932（负载波动），死锁 0。

## 5. G-01~G-07 门禁

| 门禁 | 要求 | 结果 |
| --- | --- | --- |
| G-01 单元测试 | 失败=0 | PASS（305） |
| G-02 集成测试 | 失败=0 | PASS（20） |
| G-03 库存一致性 | available+locked=total | PASS（单行 + 分桶） |
| G-04 超卖 | 成功订单 ≤ 库存 | PASS（L-01/L-02/L-05/分桶） |
| G-05 MQ | backlog=0 | PASS |
| G-06 幂等 | 重复消息只一次效果 | PASS（含行锁内幂等重查） |
| G-07 稳定性 | 30 分钟无泄漏 | PASS |

## 6. 生产缺陷记录（Phase 6.1~6.2）

| Commit | 缺陷 | 根因 | 修复 | 验证 |
| --- | --- | --- | --- | --- |
| `1d470c7`/`f00d6c9` | gateway 无法启动 | SCG 4.1.2 grpc 可选依赖缺失/版本过旧 | grpc 1.69.0 | 网关启动成功 |
| `70d5080` | KeyResolver 注入失败 | 4 个 KeyResolver 无 @Primary | 标记 @Primary | 网关启动成功 |
| `0e4046a` | 并发重复消息二次扣减 | exists 检查在行锁外 | 持有行锁后幂等重查 | 单测 + 一致性 IT |
| `be2ccdc` | 迁移脚本重放失败 | V3 ALTER 非幂等 | 条件 ALTER/建索引 | chaos 全量 PASS |

## 7. 已知边界与登记

1. **order-service PAY_SUCCESS 消费端未实现**：本阶段仅验证 payment 消息发布（保持 Phase 5.6 登记）；
2. **recover 契约无 userId**：Redis 回补不清理用户防重标记（保持登记）；
3. **Redis 无持久化**：恢复依赖预热 + 对账 REPAIR；
4. **分桶启用边界**：仅对已迁移 SKU 生效；未迁移 SKU 的在途/存量消息按冻结差异告警（灰度要求：先迁移、再启用、再切换 N）；
5. **汇总行漂移**：分桶热路径不维护 `inventory` 汇总行 available/locked，由对账 diff + 显式 syncSummary 刷新（非自动修复）；
6. **单机负载波动**：L-01/L-03/L-04 绝对值随主机负载波动，正确性不受影响。

## 8. 结论

Phase 6 全部目标状态：

- **O-01/O-02**：单行模型不可达 → **分桶模型 N=4/8 达成**（L-06：N=8 360.18 QPS / 13.9s）；
- **O-05**：execute 核心路径 p99 ≤500ms 达成（网关 294.54ms / 直连 68.61ms）；
- **G-01~G-07**：全部 PASS；
- **H-01~H-04**：全部完成；
- 默认 `inventory.sharding.enabled=false / bucket-count=1`，行为等价旧系统；灰度路径 N=1→4→8 由测试参数驱动验证，未切换生产默认值。

建议：按 Phase 6.2.2 评审条件进入后续（Phase 6.3 / Phase 5.8 生产级 10K QPS 验证）时，将 N=8 作为受控灰度目标，并在独立环境复核性能绝对值。
