# Phase 6.5 RC-01 Release Freeze

> 冻结时间：2026-08-05
> 状态：Release Candidate 基线（Freeze）

## 1. Git 冻结信息

| 项目 | 值 |
| --- | --- |
| Branch | `feature/phase6.2-inventory-sharding` |
| Commit | `59e8120aaa24d60342c947c4e99493d0787a5c74` |
| Dirty workspace | 无已跟踪文件修改；仅遗留未跟踪 `docs/04-测试体系/.coverage-baseline`（release-check 容错项） |
| 未提交文件 | 无（除上述未跟踪基线文件） |

## 2. Included Phases

| Phase | 内容 |
| --- | --- |
| 6.0 | 性能分析冻结（瓶颈定位） |
| 6.1 | MQ 消费并发调优（order=16 / inventory=8） |
| 6.2 | Inventory Sharding（inventory_bucket 分桶 + Redis Lua v2，H-01~H-04） |
| 6.3 | Gateway RT 优化（JWT / 黑名单 MGET / 异步日志 / Netty） |
| 6.4 | Production Readiness（隔离拓扑 G-09 / L-07 / SLO / H-05） |
| 6.5 | RC Hardening & Production Launch Preparation（本阶段） |

## 3. Database Migration（冻结）

| Migration | 内容 | 幂等 |
| --- | --- | --- |
| V1.0__init.sql | 5 服务基础表（冻结基线） | CREATE IF NOT EXISTS |
| V2__inventory_bucket.sql | 库存分桶表 inventory_bucket（uk_sku_bucket） | CREATE IF NOT EXISTS |
| V3__stock_flow_bucket.sql | stock_flow.bucket_no + idx_sku_bucket | 条件 ALTER/建索引（可重放） |

## 4. Feature Flags（冻结）

| 配置 | 默认（发布初始） | 生产目标（灰度后） |
| --- | --- | --- |
| `inventory.sharding.enabled` | `false` | `true` |
| `inventory.sharding.bucket-count` | `1` | `8` |

灰度路径：N=1 → N=4 → N=8；全程保持零超卖/幂等/恢复门禁。

## 5. Release Commit

- 冻结提交：`59e8120 docs(performance): phase6.4 production readiness report`
- 本阶段（6.5）所有改动在冻结基线之上追加，最终 Release Gate 以 `phase6.5-release-gate.md` 为准。

## 6. 冻结约束

RC 阶段不允许：

1. 修改库存状态机；
2. 修改 Redis Lua 扣减语义（v1/v2 均冻结）；
3. 修改订单状态机；
4. 修改 MQ 幂等模型；
5. 修改数据库核心结构；
6. 为性能降低一致性保证；
7. 删除已有测试门禁。
