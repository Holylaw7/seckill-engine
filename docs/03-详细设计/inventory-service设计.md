# Seckill-Engine inventory-service 设计确认（Phase 4.5）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（评审稿） |
| 状态 | 待评审 |
| 日期 | 2026-08-01 |
| 关联基线 | 需求基线 v1.0 / 架构基线 v1.0（3.1、7、11.4）/ 详细设计基线 v1.0（数据库/MQ/Redis） |
| 前置依赖 | seckill-common、seckill-service（预扣与流水）、order-service（状态事件，后续阶段） |

---

## 1. inventory-service 职责边界

| 允许 | 禁止 |
| --- | --- |
| 拥有并维护 MySQL 库存事实源（`inventory`）与库存流水（`stock_flow`） | **禁止直接操作 Redis 热点库存**（Redis 单写方为 seckill-service，见架构 3.1） |
| 消费 `CREATE_ORDER`（独立消费组）执行库存确认（DEDUCT） | **禁止修改 seckill-service**（本阶段） |
| 消费 `STOCK_RECOVER` / `CANCEL_ORDER` 执行回补（RECOVER） | **禁止访问 order_db**（seckill_order 库） |
| 对账与修复（REPAIR/ADJUST） | 禁止访问 auth 库与支付库 |
| 调用 seckill-service 内部回补接口（契约冻结，见附录 A） | 禁止跨服务直连数据库 |

数据归属：只读写 `seckill_inventory` 库（`inventory`、`stock_flow`），账号 `inventory_rw` 最小权限。

## 2. MySQL 库存事实源设计

`inventory` 表是**最终库存事实源**，Redis 仅为热点层：

| 字段 | 语义 |
| --- | --- |
| `total_stock` | 场次投放总库存（初始化写入，不变） |
| `locked_stock` | 已预扣/待支付占用库存（订单确认后 +1，回补后 -1） |
| `available_stock` | 剩余可售库存（确认 -1，回补 +1） |
| `version` | 乐观锁版本（并发更新防覆盖） |

约束：`total_stock = locked_stock + available_stock`，由 inventory-service 唯一维护。

库存生命周期（本阶段实现 INIT / DEDUCT / RECOVER / REPAIR；支付确认 CONFIRM 预留）：

```text
INIT（total=T, available=T, locked=0）
  → CREATE_ORDER 确认：available-1, locked+1（DEDUCT）
  → 回补：locked-1, available+1（RECOVER）
  → 支付确认（预留）：locked-1（CONFIRM，payment/order 阶段接入）
  → 对账修复：REPAIR（人工审批）
```

## 3. inventory 表与 stock_flow 表设计

复用冻结 DDL（数据库设计.md §6），编码阶段落地 `sql/inventory-service/V1.0__init.sql`：

| 表 | 关键约束 | 说明 |
| --- | --- | --- |
| `inventory` | `uk_sku(sku_id)`、`version` | 库存事实源，CAS 更新 |
| `stock_flow` | `uk_flow_no(flow_no)`、`uk_biz(biz_type,biz_id)` | 不可变流水，幂等载体 |

`stock_flow.change_type`：`INIT / DEDUCT / RECOVER / REPAIR / ADJUST / CONFIRM（预留）`；
`biz_type`：`ORDER / CANCEL / TIMEOUT / REFUND / MANUAL`；
`biz_id`：`orderId` 或修复单号；`before_qty / after_qty` 记录变动前后 available。

与 seckill 库 `seckill_pre_deduct` 的关系：前者是“秒杀预扣与事务消息”的本地依据，`stock_flow` 是“库存事实”流水，两者以 `orderId/messageId` 对账收敛。

## 4. CREATE_ORDER 消息消费流程

消费组：`inventory-consumer`（与 order-service 的 `order-consumer` 隔离，同一消息两消费组各自幂等消费）。

```text
1 解析消息（messageId/userId/skuId/sessionId/orderId/quantity）
2 幂等校验：stock_flow 查 uk_biz(ORDER, orderId)
   已存在 → 直接 ACK（重复消息）
3 事务（MySQL 本地事务）：
   a. 插入 DEDUCT 流水（flow_no=Snowflake，uk_biz 幂等）
   b. 更新 inventory：available-1、locked+1（CAS：available>=1）
4 事务成功 → ACK（CONSUME_SUCCESS）
   失败（可重试：锁冲突/DB 抖动）→ RECONSUME_LATER
```

库存不足保护：CAS 失败且 available=0 时视为数据异常（预扣已成功但事实不足），**冻结差异 + 告警 + 对账修复**，不盲目重试。

## 5. STOCK_RECOVER 消息消费流程

触发来源：order-service（CANCEL/TIMEOUT 发布）、退款事件（payment 阶段）、对账/补偿任务。

```text
1 解析消息（orderId/reason）
2 幂等校验：uk_biz(CANCEL|TIMEOUT|REFUND, orderId)
3 前置校验：存在对应 DEDUCT 流水且 locked>0（无确认的回补视为异常 → 告警人工）
4 事务（MySQL 本地事务）：
   a. 插入 RECOVER 流水
   b. 更新 inventory：locked-1、available+1（CAS：locked>=1）
5 事务成功后调用 seckill-service 内部回补接口（契约冻结，见附录 A）
   → 成功：ACK
   → 失败：不阻塞 ACK，告警 + 对账 REPAIR 兜底（Redis 单写方原则）
```

## 6. 库存确认流程

- 确认 = `CREATE_ORDER` 消费（inventory-consumer）：DEDUCT 流水 + `inventory.available-1/locked+1`；
- 与订单落库解耦：同一消息由 order-service（建单）与 inventory-service（确认）各自幂等消费，最终一致由消费幂等 + 对账保证；
- 确认幂等：`uk_biz(ORDER, orderId)`；
- 支付成功后的最终确认（locked→sold）预留 `CONFIRM` 变更类型，payment/order 阶段接入。

## 7. 回补流程

| 步骤 | 动作 | 幂等载体 |
| --- | --- | --- |
| 1 | 消费 CANCEL/TIMEOUT/REFUND/STOCK_RECOVER 消息 | messageId/orderId |
| 2 | 插入 RECOVER 流水（uk_biz 防重复回补） | uk_biz(biz_type,biz_id) |
| 3 | 更新 inventory（locked-1、available+1，CAS） | version CAS |
| 4 | 调用 seckill-service 回补接口恢复 Redis（契约冻结） | requestId=flowNo（接口侧幂等） |
| 5 | 用户购买标记删除由 seckill-service 按场次配置处理 | - |

顺序铁律：**MySQL 事实先落，Redis 热点后补**；Redis 回补失败不影响事实层，由对账修复收敛。

## 8. 对账修复流程

```text
数据源：inventory / stock_flow（本库）
        + seckill-service 预扣流水查询接口（跨库经接口，契约随附录 A 冻结）

差异分类：
  有 DEDUCT 无订单         → 冻结差异 + 告警（order 侧重试/回补）
  有 RECOVER 无对应占用     → 告警 + 人工核查
  数量不平（流水 vs inventory）→ REPAIR 修复任务
  Redis 与 MySQL 不一致     → 以 MySQL 为准，经 seckill-service 接口校准 Redis

修复动作：REPAIR 流水（幂等）→ inventory CAS 修正 → Redis 校准 → 审计
```

修复铁律：以 MySQL 事实源为准；高影响修复需人工审批；全部留痕（REPAIR/ADJUST 流水 + operator_id）。

## 9. 幂等设计

| 层 | 载体 | 说明 |
| --- | --- | --- |
| 流水 | `uk_flow_no` | 全局流水号唯一（Snowflake） |
| 业务事件 | `uk_biz(biz_type,biz_id)` | 同一订单同一操作只产生一条流水（防重复确认/回补） |
| 消费 | 先查流水后处理，重复消息直接 ACK | 与 MQ 重投配合 |
| 跨服务 Redis 回补 | requestId=flowNo | seckill-service 回补接口按 requestId 幂等（契约冻结） |

## 10. 乐观锁 / version 设计

`inventory.version` 使用 CAS 更新范式（冻结）：

```text
确认：UPDATE inventory
      SET available_stock = available_stock - 1,
          locked_stock = locked_stock + 1,
          version = version + 1
      WHERE sku_id = ? AND available_stock >= 1 AND version = ?

回补：UPDATE inventory
      SET locked_stock = locked_stock - 1,
          available_stock = available_stock + 1,
          version = version + 1
      WHERE sku_id = ? AND locked_stock >= 1 AND version = ?
```

- 影响行数 = 1：成功；= 0：冲突/数量不足 → 有限次重试，仍失败进对账；
- **禁止无 version 校验的覆盖写**；流水表只增不改，无需 version。

## 11. 异常补偿方案

| 异常 | 检测 | 处理 | 补偿 |
| --- | --- | --- | --- |
| 消费失败（可重试） | 消费异常 | 延迟重试（16 次上限） | 重试幂等（uk_biz） |
| 消费最终失败 | DLQ | 告警 + 人工重放 | 重放幂等 |
| 库存不足（事实层） | CAS 失败 | 冻结差异 + 告警 | 对账修复/人工 |
| Redis 回补失败 | 接口异常/超时 | 不阻塞 ACK + 告警 | 对账 REPAIR 校准 Redis |
| MySQL 故障 | 事务失败 | 重试 + 不 ACK | 恢复后重放 |
| 消息乱序（先回补后确认） | 前置校验 | 拒绝 + 告警人工 | 人工核查流水 |
| 对账差异 | 对账任务 | 分类处置 | REPAIR 流水 + 审批 |

## 12. 单元测试计划

| 测试项 | 内容 | 说明 |
| --- | --- | --- |
| InventoryServiceTest | 确认扣减/回补的 CAS 更新、数量校验、冲突处理 | Mockito + Mapper mock |
| StockFlowServiceTest | uk_biz 幂等、flow_no 唯一、before/after 计算 | Mockito |
| CreateOrderConsumerTest | 消息解析、重复消息 ACK、可重试/不可重试分类 | Mockito + TableInfo 初始化 |
| StockRecoverConsumerTest | 回补流水 + CAS、Redis 接口调用成功/失败、失败告警 | Mockito |
| RecoverClientTest | seckill 回补接口契约调用、超时/异常处理 | Mock RestClient |
| ReconciliationServiceTest | 差异分类、REPAIR 流水幂等 | Mockito |
| 乐观锁测试 | CAS 冲突 → 重试/对账 | 集成留 Phase 5 |

真实 MySQL/RocketMQ 联调（双消费组、并发扣减、回补一致性）列入 Phase 5 集成测试。

---

## 附录 A：跨服务契约变更申请（待评审批准）

本设计遵守“inventory-service 禁止直接操作 Redis 热点库存”，Redis 回补统一由 seckill-service 承担，因此需要新增一个 seckill-service 内部接口：

| 项 | 内容 |
| --- | --- |
| 接口 | `POST /api/v1/seckill/internal/stocks/recover` |
| 入参 | `{ requestId, skuId, userId, quantity, removeUserMark }` |
| 出参 | `Result<Void>`（按 requestId 幂等；回补校验 ≤ total） |
| 变更范围 | seckill-service（复用既有 recover Lua 与流水逻辑） |
| 实施方式 | 评审批准后作为**独立小变更**追加（feat(seckill): internal stock recover endpoint），本阶段 inventory-service 编码**不修改 seckill-service**；未批准前回补仅完成 MySQL 侧并告警 |

同时冻结对账所需查询契约：`GET /api/v1/seckill/internal/pre-deducts?orderId=`（或按 messageId），供对账任务跨库核对。

## 附录 B：待评审确认项

1. 库存确认采用 inventory-service 独立消费组消费 `CREATE_ORDER`（与 order-service 建单并行、各自幂等），确认此方案；
2. Redis 回补经 seckill-service 内部接口（附录 A 变更申请），确认批准；
3. 支付最终确认（locked→sold，CONFIRM 变更类型）在 payment/order 阶段接入，本阶段预留；
4. 对账任务周期默认 5 分钟，修复审批人默认运营管理员。
