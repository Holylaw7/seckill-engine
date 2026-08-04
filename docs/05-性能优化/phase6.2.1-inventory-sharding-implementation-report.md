# Phase 6.2.1 Inventory Sharding 实施报告

> 完成时间：2026-08-04
> 分支：feature/phase6.2-inventory-sharding
> 目标：在不改变业务语义、不降低一致性、不破坏幂等的前提下，将 Inventory 单 SKU 单行锁模型演进为可水平扩展的库存分桶模型，为 Phase 6.2.2 灰度（N=4/8）提供代码基础。
> 状态：代码 + 迁移能力 + 测试就绪；**默认关闭（enabled=false / bucket-count=1），未进行生产灰度，不切换默认桶数 N=8，不删除旧 inventory 表**。

---

## 1. Commit 列表

| Commit | 内容 |
| --- | --- |
| `63338ba` | feat(inventory): add inventory bucket migration（DDL + Entity + Mapper + 迁移服务） |
| `4bbec71` | feat(inventory): support bucket deduct and recover（分桶扣减/恢复/对账 + flow.bucket_no） |
| `395fdcd` | feat(seckill): introduce redis stock bucket lua（Lua v2 + Redis 适配器 + 消息字段） |
| `e7e9368` | feat(order): add optional bucketNo message field（DTO 兼容） |
| `c6a7ef0` | test(performance): add phase6.2 sharding benchmark（H-01 基准，@LoadTest 默认跳过） |
| `8c3bb14` | test(inventory): add sharding consistency tests（单元 + 端到端一致性 IT） |
| `0e4046a` | fix(inventory): recheck idempotency under bucket row lock（并发重复消息竞态修复） |
| `11ea963` | test(inventory): stabilize sharding consistency test（满载环境下限流与等待放宽） |
| （本报告） | docs(performance): phase6.2.1 implementation report |

## 2. 修改文件列表

### 2.1 数据库迁移

- `sql/inventory-service/V2__inventory_bucket.sql`：新增 `inventory_bucket`（uk_sku_bucket）
- `sql/inventory-service/V3__stock_flow_bucket.sql`：`stock_flow` 增加 `bucket_no` + `idx_sku_bucket`

### 2.2 inventory-service（生产）

- 新增：`entity/InventoryBucket`、`mapper/InventoryBucketMapper`、`config/InventoryShardingProperties`、`service/InventoryBucketMigrationService`、`service/InventoryBucketService`、`service/BucketReconciliationService`、`dto/BucketReconcileReport`
- 修改：`entity/StockFlow`（bucket_no）、`service/StockFlowService`（createFlow 重载）、`service/InventoryService`（findDeductBucketNo）、`service/impl/InventoryServiceImpl`（双路径开关 + 锁内幂等重查）、`consumer/StockRecoverConsumer`（桶定位回补）、`dto/RecoverRequest`、`dto/CreateOrderMessage`、`pom.xml`（Redis 对账依赖）、`application.yml`（inventory.sharding 默认关闭）

### 2.3 seckill-service（生产）

- 新增：`resources/lua/seckill_deduct_v2.lua`、`resources/lua/seckill_recover_v2.lua`、`config/SeckillShardingProperties`、`redis/BucketDeductResult`
- 修改：`redis/StockService`、`redis/impl/RedisStockService`（v2 适配 + 解析）、`config/RedisConfig`（v2 脚本 Bean）、`constant/SeckillConstants`（bucket/rr key）、`dto/SeckillOrderMessage`（bucketNo）、`service/impl/SeckillServiceImpl`（准入选择 v2）、`mq/TransactionListenerImpl`（分桶回补）、`dto/StockRecoverInternalRequest`、`controller/StockRecoverInternalController`、`application.yml`

### 2.4 order-service（生产）

- `dto/CreateOrderMessage` 增加可选 `bucketNo`（order 建单不感知分桶，仅兼容透传）

### 2.5 测试

- inventory 单测：`InventoryBucketServiceTest`、`InventoryBucketDeductTest`、`InventoryBucketRecoverTest`、`BucketReconciliationTest`
- seckill 单测：`LuaV2ScriptTest`（真实 Redis 7.2.4 容器执行 Lua v2）
- order 单测：`CreateOrderBucketMessageTest`
- 集成：`integration-test/.../InventoryShardingConsistencyIT`（扣减/幂等/恢复/对账全链路）
- 测试基建：`IntegrationTestBase` 注册 V2/V3 脚本、`TestHttp` 支持 bucketNo
- 基准：`InventoryShardingBenchmark`（既有，默认跳过）

## 3. Migration 说明

- `inventory_bucket` 按 `(sku_id, bucket_no)` 唯一，桶内 `total = locked + available`；
- `InventoryBucketMigrationService.migrate(skuId, totalStock, bucketCount, dryRun)`：
  - dry-run 只输出 `before / after / diff`，不写库；
  - 校验 `inventory.total == 输入 totalStock`，`sum(bucket.total) == inventory.total`；
  - 已存在分桶且与计划不一致时拒绝覆盖（人工对账）；
  - 示例 1000/8 → 每桶 125；
- `stock_flow.bucket_no` 仅定位字段，不参与 `uk_biz(biz_type, biz_id)` 幂等；
- Redis 预热：`StockService.prepare`（全局）+ `prepareBucket`（每桶）迁移后调用。

## 4. Redis Lua v2 说明

### 4.1 扣减 `seckill_deduct_v2.lua`

- KEYS：`seckill:stock:{skuId}`、`seckill:user:{skuId}:{userId}`、`seckill:stock:rr:{skuId}`（轮询计数）、`seckill:stock:bucket:{skuId}:{bucketNo}`（N 个）
- 单脚本原子：防重 → 全局库存检查 → INCR 计数轮询选桶（最多 N 次）→ 全局 + 目标桶 DECRBY → 写防重标记
- 返回：`SUCCESS:{bucketNo}` / `STOCK_EMPTY` / `REPEAT_BUY` / `NOT_READY`

### 4.2 回补 `seckill_recover_v2.lua`

- KEYS：全局库存、全局 total、目标桶 key（可能缺失）、防重标记
- 全局 + 桶原子 INCRBY；超 total 返回 `OVER_TOTAL`；`removeUserMark` 语义与 v1 一致

### 4.3 兼容与回滚

- `inventory.sharding.enabled=false`（默认）→ seckill 走 Lua v1、inventory 走旧 `inventory` 单行路径，行为与基线完全一致；
- 新准入消息携带 `bucketNo`；旧消息无该字段时：单桶回退桶 0，多桶按 `orderId.hashCode() % N` 路由（仅兼容过渡，新路径桶号一律来自 Lua）。

## 5. 数据一致性说明

1. **Redis stock = MySQL available**：Lua v2 原子维护全局与桶 key；恢复按 `stock_flow.bucket_no` 同时回补全局与桶；
2. **available + locked = total**：桶行内维护；SKU 维度由 `SUM(bucket)` 校验；
3. **订单数量 ≤ 库存（零超卖）**：Redis 全局 Lua → Redis 桶 Lua → MySQL 桶行 `FOR UPDATE + available >= qty + CAS` 三重防线；
4. **重复消息只产生一次效果**：`uk_biz` 幂等保持不变；新增**持有行锁后的幂等重查**（`0e4046a`），并发重复消息在桶行锁上串行化，杜绝二次扣减/二次恢复；
5. **取消恢复正确**：DEDUCT 流水记录 bucket_no，RECOVER 精确回补同一桶；Redis 全局与桶同步；
6. **故障可 repair**：`BucketReconciliationService.check` 输出桶内/汇总/Redis 差异报告；`syncSummary` 为显式汇总刷新（非自动修复，符合 Phase 6.2.1 约束）。

已知边界：分桶热路径不维护 `inventory` 汇总行的 available/locked（避免热点回归），汇总行差异由对账报告暴露、由显式 `syncSummary` 刷新；该行为已在 IT 中验证。

## 6. 测试结果

### 6.1 全量 `mvn clean test`（默认排除 chaos、load 跳过）

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| 单元测试 | 305（common 34 / gateway 32 / auth 38 / seckill 69 / inventory 46 / order 31 / payment 55） | 0 failures / 0 errors |
| 集成测试 | 20（原 17 + 分桶一致性 3） | 0 failures / 0 errors |
| Load 框架冒烟 | 4 | PASS（16 个 load 用例按设计跳过） |
| BUILD | — | SUCCESS |

### 6.2 分桶一致性 IT（真实 MySQL/Redis/RocketMQ）

- 150 并发真实秒杀（Lua v2 + 8 桶）→ 零超卖，`SUM(available)=850 / SUM(locked)=150 / SUM(total)=1000`；
- 每桶 `available + locked == total`、非负；Redis 全局 == SUM(bucket.available)；
- 重复 CREATE_ORDER（同 messageId/orderId）只产生 1 条 DEDUCT 流水；
- 超时恢复：RECOVER 流水数=成功数，每桶 available 恢复 125、locked=0，Redis 全局与桶 key 恢复；
- `syncSummary` 后 `BucketReconciliationService.check` PASS。

### 6.3 Chaos

本阶段未重跑 13 项故障演练（Phase 5.4 基线 PASS 且本阶段未改动故障路径语义）；Phase 6.2.2 H-04 全量门禁将一并复跑。

## 7. Benchmark 准备情况

- `InventoryShardingBenchmark`（@LoadTest 默认跳过，`-Dload.enabled=true` 执行）已就绪；
- 输出：`integration-test/target/load-reports/L-06-SHARDING.json|csv`（consumeQPS / convergeTime / rowLockWaits / deadlocks）；
- 预跑（独立行模拟桶）：1/4/8/16 行 = 123.86 / 379.00 / 499.88 / 479.50 QPS，死锁 0；
- Phase 6.2.2 对真实分桶模型（N=4/8）复测。

## 8. 风险与回滚方案

| 风险 | 缓解 | 回滚 |
| --- | --- | --- |
| 分桶路径缺陷 | 默认关闭；双路径并存；IT 覆盖 | `enabled=false` 即回旧路径 |
| 迁移数据不一致 | dry-run + 校验 + 拒绝覆盖 | 弃 `inventory_bucket`，旧表未动 |
| Lua v2 缺陷 | 单脚本原子 + 真实 Redis 单测 + 灰度 N=1 | 切回 Lua v1 |
| 旧消息兼容 | bucketNo 可选 + 单桶回退 | 不兼容字段不发 |
| 汇总行漂移 | 对账 diff + 显式 syncSummary | 保留单桶对账路径 |
| 并发重复扣减 | 行锁内幂等重查（已修复） | — |
| 测试环境满载抖动 | 降低并发 + 放宽收敛等待 | — |

## 9. 是否进入 Phase 6.2.2

**建议进入 Phase 6.2.2（灰度验证）**：

1. 分桶代码、迁移能力、Lua v2、对账能力均已就绪且默认关闭，无线上行为变化；
2. 一致性门禁保持（零超卖、零重复扣减、恢复正确、对账 PASS）；
3. Phase 6.2.2 工作项：N=1→4→8 灰度、H-01 真实分桶复测、H-02 一致性回归、H-03 故障测试、H-04 全量门禁（Unit 305+ / Integration 20+ / Chaos 13 / L-01~L-05 / Release Gate）。

**不自动进入灰度**：等待 Review 批准后，由 6.2.2 执行 `enabled=true` 的受控验证。
