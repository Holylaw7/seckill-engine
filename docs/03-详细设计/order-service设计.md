# Seckill-Engine order-service 设计确认（Phase 4.6）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.1（设计已评审 + 冻结补充） |
| 状态 | 已评审，冻结补充已确认 |
| 日期 | 2026-08-01 |
| 关联基线 | 需求基线 v1.0（订单状态机、BR-04）/ 架构基线 v1.0 / 详细设计基线 v1.0（数据库/MQ/接口/事务边界） |
| 前置依赖 | seckill-common、seckill-service（`/internal/pre-deducts/confirm` 已实现） |
| 变更记录 | v1.0 评审稿；v1.1 冻结补充：金额快照、状态机唯一入口、取消约束、超时关闭参数、CANCEL_ORDER 补偿 |

---

## 1. order-service 职责边界

| 允许 | 禁止 |
| --- | --- |
| 消费 `CREATE_ORDER` 创建秒杀订单（消费组 `order-consumer`） | **禁止访问 inventory 数据库** |
| 订单状态机与 `active_key` 生命周期管理 | **禁止直接修改库存**（Redis 热点与 MySQL 事实均不触碰） |
| 幂等表（订单域）与唯一约束 | **禁止直接操作 Redis** |
| 15 分钟支付超时关单（定时任务） | 禁止访问 seckill/auth/payment 库 |
| 取消订单，关闭后发布 `CANCEL_ORDER` | 禁止跨服务直连数据库 |
| 调用 seckill-service 内部接口回填预扣确认 | 数据交换只走 MQ + 内部接口 |

数据归属：只读写 `seckill_order` 库（`seckill_order` / `order_item` / `idempotent`），账号 `order_rw` 最小权限。

## 2. CREATE_ORDER 消费与建单流程

消费组：`order-consumer`（冻结；inventory-service 使用独立 `inventory-consumer`）。

```text
1 解析消息（messageId/userId/skuId/sessionId/orderId/timestamp/quantity + amount(变更申请见附录 A)）
2 同事务（seckill_order 库）：
   a. 幂等表插入（biz_type=ORDER_CREATE, biz_id=messageId）
   b. 创建订单（order_no=orderId，状态 CREATE → WAIT_PAY）
   c. 创建订单明细（order_item，金额快照）
   d. active_key = user_id:session_id:sku_id（唯一占用）
3 事务提交成功 → ACK（冻结：事务成功后才 ACK）
4 提交后调用 seckill-service /internal/pre-deducts/confirm（messageId, orderId）回填
   → 失败不阻塞 ACK，告警 + 对账兜底
5 重复消息：幂等表唯一冲突 → 直接 ACK
```

唯一约束兜底：`uk_order_no` / `uk_active` 冲突视为已处理（幂等返回），禁止创建第二笔订单。

## 3. 订单状态机（冻结）

```text
CREATE → WAIT_PAY → PAY_SUCCESS → REFUND（payment 阶段接入）
                 ├→ CANCEL（用户取消，本阶段实现）
                 └→ TIMEOUT（15 分钟未支付，本阶段实现）
```

| 当前状态 | 事件 | 目标状态 | active_key | 副作用 |
| --- | --- | --- | --- | --- |
| CREATE | 建单完成 | WAIT_PAY | 保留 | 设置 pay_deadline=now+15min |
| WAIT_PAY | 支付成功回调 | PAY_SUCCESS | 保留 | payment 阶段接入 |
| WAIT_PAY | 用户取消 | CANCEL | **释放（置 NULL）** | 发布 CANCEL_ORDER(reason=CANCEL) |
| WAIT_PAY | 支付超时 | TIMEOUT | **释放（置 NULL）** | 发布 CANCEL_ORDER(reason=TIMEOUT) |
| PAY_SUCCESS | 退款成功 | REFUND | 按业务策略（默认保留） | payment 阶段接入 |

规则（冻结）：

- 禁止非法跳转（如 TIMEOUT→PAY_SUCCESS）；状态更新一律 `version` CAS + 状态前置校验；
- `active_key` 释放与状态变更同一事务提交；
- 重复事件（重复取消/重复关单）幂等，不改变终态。

## 4. 订单唯一约束（冻结）

| 约束 | 字段 | 作用 |
| --- | --- | --- |
| `uk_order_no` | order_no | 订单号全局唯一（Snowflake） |
| `uk_active` | active_key | 同一用户同场次同商品**最多一条有效订单**（可空唯一列） |
| `idx_user_status` | user_id, order_status | 我的订单列表（分片键收敛） |
| `idx_user_session_sku` | user_id, session_id, sku_id | 防重查询 |
| `idx_status_deadline` | order_status, pay_deadline | 超时关单扫描 |

`user_id` 为预留分片字段；所有查询按 user_id 收敛。

## 5. active_key 生命周期（冻结，同数据库设计 7.4）

- 写入：建单事务内 `user_id:session_id:sku_id`；
- 保留：CREATE / WAIT_PAY / PAY_SUCCESS；
- 释放（置 NULL）：CANCEL / TIMEOUT（同一事务）；REFUND 按业务策略默认保留；
- 语义：释放后同一用户可再次参与同场次（Redis 购买标记是否删除由 seckill-service 回补接口按场次配置处理，order-service 不操作 Redis）。

## 6. 支付超时关闭（15 分钟）

```text
定时任务（周期 30s，基础值）：
1 扫描 order_status=WAIT_PAY AND pay_deadline < now（idx_status_deadline，分批）
2 逐单：version CAS 更新 WAIT_PAY→TIMEOUT + active_key 置 NULL（同一事务）
3 提交后发布 CANCEL_ORDER（reason=TIMEOUT）
4 发布失败：不阻塞，补偿任务按 TIMEOUT 状态补发（幂等）
```

- 与支付回调并发：`version` CAS 保证只有一方成功（支付先到则关单跳过）；
- 关单只影响订单域，库存回补由 inventory-service 消费 `CANCEL_ORDER` 执行。

## 7. CANCEL_ORDER 发布

- 触发：用户取消（WAIT_PAY→CANCEL）、超时关单（WAIT_PAY→TIMEOUT）；
- Topic/Tag：`seckill-order-tx` / `CANCEL_ORDER`（冻结）；
- 消息字段：`messageId / orderId / userId / skuId / sessionId / quantity / reason(CANCEL|TIMEOUT) / timestamp`；
- 消费方：inventory-service（`STOCK_RECOVER || CANCEL_ORDER` 已实现）；
- 幂等：同一 orderId 同一 reason 只发布一次（消息 Key=orderId + 状态机前置校验）。

## 8. 幂等设计

| 层 | 载体 | 说明 |
| --- | --- | --- |
| 消费幂等 | 幂等表 `uk_biz(biz_type,biz_id)` | ORDER_CREATE/messageId 同事务先行插入 |
| 订单唯一 | `uk_order_no`、`uk_active` | 数据层兜底 |
| 状态幂等 | 状态机 + `version` CAS | 重复取消/关单/回调无副作用 |
| 消息幂等 | 状态前置 + 消息 Key=orderId | CANCEL_ORDER 不重复发布 |

## 9. 事务边界与异常补偿

| 场景 | 处理 |
| --- | --- |
| 建单事务失败 | 回滚，不 ACK → MQ 重试（幂等） |
| 幂等表冲突（重复消息） | 直接 ACK |
| confirm 回填失败 | 不阻塞 ACK，告警 + 对账兜底（seckill 侧 pre_deduct 保持 DEDUCTED） |
| 关单发布失败 | 补偿任务按 TIMEOUT 状态补发 CANCEL_ORDER（幂等） |
| 消费最终失败 | DLQ + 人工重放 |
| 与支付并发 | version CAS 胜者生效 |

## 10. 数据库表使用范围

```text
可读写（seckill_order 库）：
  seckill_order / order_item / idempotent

禁止访问：
  seckill_inventory（库存事实）  ← 禁止访问 inventory 数据库
  seckill_seckill / seckill_auth / seckill_payment
```

跨服务交互：消费 `CREATE_ORDER`、发布 `CANCEL_ORDER`（MQ）；调用 seckill-service `/internal/pre-deducts/confirm`（内部接口，已实现）。

## 11. 接口设计（order 域）

| 接口 | 方法/路径 | 说明 |
| --- | --- | --- |
| 订单详情 | `GET /api/v1/orders/{orderId}` | 含明细，强制 user_id 归属校验 |
| 我的订单 | `GET /api/v1/orders?status=&page=` | 按 X-User-Id 收敛 |
| 取消订单 | `POST /api/v1/orders/{orderId}/cancel` | WAIT_PAY→CANCEL + 发布 CANCEL_ORDER |
| 订单状态 | `GET /api/v1/orders/{orderId}/status` | orderStatus/payDeadline |

统一 `Result<T>`；错误码：订单不存在 40001、状态不允许 40002、已超时 40003。

## 12. 单元测试计划

| 测试项 | 内容 |
| --- | --- |
| CreateOrderConsumerTest | 建单成功（幂等表+订单+明细同事务）、重复消息 ACK、confirm 失败不阻塞、运行时异常重试 |
| OrderStateMachineTest | 合法流转、非法跳转拒绝、version 冲突不覆盖 |
| OrderServiceTest | 创建、取消、active_key 写入/释放、pay_deadline=15min |
| TimeoutCloseTaskTest | 扫描、CAS 关单、active_key 释放、CANCEL_ORDER 发布与失败补发 |
| IdempotencyTest | 幂等表冲突、uk_active 冲突 |
| CancelOrderProducerTest | 消息字段/幂等发布 |

真实 MySQL/RocketMQ 联调（双消费组、并发关单与支付、唯一约束）列入 Phase 5 集成测试。

---

## 附录 A：跨服务契约变更申请（待评审批准）

建单需要订单金额，当前 `CREATE_ORDER` 冻结消息不含金额，二选一：

| 方案 | 内容 | 影响 |
| --- | --- | --- |
| **A（推荐）** | `SeckillOrderMessage` 扩展字段 `amount`（分，Long）：seckill-service 预扣成功后携带 sku.price×quantity | 修改 seckill-service 消息 DTO 与发送处（独立小变更）；order-service 直接使用 |
| B | 新增 seckill 内部接口 `GET /api/v1/seckill/internal/skus?sessionId=&skuId=` 返回价格 | seckill-service 新增查询接口（独立小变更）；order-service 建单时同步查询 |

实施方式：评审批准后作为**独立小变更**追加（本阶段 order-service 编码不修改 seckill-service）。`/internal/pre-deducts/confirm` 已在 Phase 4.4 实现，无需变更。

## 附录 B：待评审确认项

1. 金额来源采用附录 A 方案 A（消息扩展 amount 分）；
2. 超时关单扫描周期 30s，批量大小 200；
3. 用户取消接口本阶段实现（WAIT_PAY→CANCEL）；
4. 关单消息发布失败由补偿任务补发（周期 1 分钟）。

---

## 附录 C：设计冻结补充（评审确认，v1.1）

### C.1 金额快照（冻结）

- `CREATE_ORDER` 消息携带 `amount` 字段，单位：**分**（Long）；
- order-service 建单时转换为 `DECIMAL(18,2)` 金额快照写入订单与明细；
- 消息缺少 `amount` 视为数据异常：告警、不建单（依赖附录 A 方案 A 的 seckill-service 独立变更）。

### C.2 状态机唯一入口（冻结）

- **所有状态变更必须经过 `OrderStateMachine`**（`canTransition` 校验 + `version` CAS 更新）；
- Controller 禁止直接修改 `order_status` / `active_key`；
- 非法流转抛出 `ORDER_STATUS_INVALID`（40002）。

### C.3 用户取消约束（冻结）

- 仅 `WAIT_PAY` 状态允许用户取消（否则 40002）；
- 取消成功释放 `active_key` 并发布 `CANCEL_ORDER(reason=CANCEL)`。

### C.4 超时关闭（冻结）

- 扫描周期 **30 秒**，单批 **batch=200**，条件 `WAIT_PAY AND pay_deadline < now`；
- 状态更新一律 `version` CAS；关单释放 `active_key`；
- DDL 变更：`seckill_order` 新增 `cancel_notify_status VARCHAR(20) DEFAULT 'PENDING'`（PENDING/SENT），用于补偿任务判断 CANCEL_ORDER 是否已发送。

### C.5 CANCEL_ORDER 失败补偿（冻结）

- 发布成功置 `SENT`；发布失败保持 `PENDING`；
- **补偿任务每 1 分钟**扫描 `CANCEL/TIMEOUT AND cancel_notify_status=PENDING` 补发；
- 重复发布由下游（inventory uk_biz）幂等兜底。
