# Seckill-Engine MQ 设计（Phase 3）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（评审稿） |
| 状态 | 待评审 |
| 日期 | 2026-08-01 |
| 关联基线 | 架构基线 v1.0（8.1 Topic 冻结）、数据库设计（pre_deduct/idempotent）、Redis 设计 |

---

## 1. 设计目标

1. **最终一致 ≤ 5 秒**：预扣 → 建单 → 库存确认收敛；
2. **消息不丢、业务不重**：事务消息保投递，消费幂等保不重；
3. **削峰填谷**：订单创建与支付等慢路径异步化；
4. **故障可补偿、可观测**：重试、DLQ、对账补偿闭环，指标齐全。

## 2. 方案选择

| 方案 | 可靠性 | 复杂度 | 实时性 | 结论 |
| --- | --- | --- | --- | --- |
| RocketMQ 事务消息 | 高（半消息 + 回查） | 中 | 高 | **采用** |
| 本地消息表 | 高 | 高（双写 + 补偿任务） | 中 | 不采用 |
| 定时扫表补偿 | 中 | 低 | 低 | 仅作对账兜底 |

理由：RocketMQ 事务消息由 Broker 保证“本地事务成功 ↔ 消息投递成功”的一致性，回查机制成熟，业务无需自行维护消息表；本地消息表方案与 MQ 能力重复，维护成本高。

## 3. Topic 与消息模型（冻结落地）

**Topic：`seckill-order-tx`**

| Tag | 用途 | 消息类型 | 发送方 | 消费方 |
| --- | --- | --- | --- | --- |
| `CREATE_ORDER` | 预扣成功后异步建单 | 事务消息 | seckill-service | order-service |
| `CANCEL_ORDER` | 取消/超时/退款触发回补 | 普通可靠消息 | order-service / payment-service | inventory-service |
| `STOCK_RECOVER` | 库存回补指令/结果 | 普通可靠消息 | 对账/补偿任务 | inventory-service |

**消息结构（冻结基础六字段 + 扩展字段）：**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `messageId` | String | 消息全局唯一 ID（幂等键） |
| `userId` | Long | 用户 ID |
| `skuId` | Long | 商品 ID |
| `sessionId` | Long | 秒杀场次 ID |
| `orderId` | String | 订单号（Snowflake） |
| `timestamp` | Long | 事件时间戳（毫秒） |
| `quantity` | Integer | 扩展：数量（默认 1） |
| `traceId` | String | 扩展：全链路追踪 |
| `status` | String | 扩展：事件相关状态 |
| `reason` | String | 扩展：取消/超时/退款原因 |

消息示例（JSON）：

```json
{
  "messageId": "msg-20260801-0001",
  "userId": 10001,
  "skuId": 20001,
  "sessionId": 30001,
  "orderId": "SO1754000000000000001",
  "timestamp": 1782892800000,
  "quantity": 1,
  "traceId": "trace-0001"
}
```

消息 Key 使用 `orderId`（可检索）；环境隔离通过独立 NameServer 集群实现，Topic 名称各环境保持一致，减少环境差异。

## 4. 事务消息完整流程

```text
1 seckill-service Redis Lua 预扣成功
2 发送半消息（CREATE_ORDER）
3 执行本地事务：插入 seckill_pre_deduct（message_id 唯一）
4 本地事务成功 → Commit 半消息 → Broker 投递
   本地事务失败 → Rollback 半消息 → 消息丢弃 + Lua 回补库存
5 半消息超时未确认 → Broker 回查 seckill-service
6 回查：按 message_id 查 seckill_pre_deduct.tx_status
   COMMITTED → Commit 继续投递
   ROLLBACK → Rollback 丢弃
   UNKNOWN/不存在 → 返回 UNKNOWN，Broker 继续回查（上限后告警人工）
```

状态机：`UNKNOWN → COMMITTED / ROLLBACK`

关键参数：`sendMsgTimeout=3000ms`；回查逻辑以数据库为准，**不依赖内存状态**。

## 5. Producer 事务监听设计

### 5.1 executeLocalTransaction（半消息发送后执行）

```text
1 幂等插入 seckill_pre_deduct（uk_message_id）
2 插入成功 → 返回 COMMITTED
3 唯一索引冲突 → 已提交过 → 返回 COMMITTED
4 任何异常 → 返回 ROLLBACK，并补偿回补 Redis 库存（INCRBY + 回补流水）
```

关键点：Redis 扣减先于本地事务发生；本地事务失败时**必须补偿回补**，否则出现“扣了库存没有流水”的悬挂。

### 5.2 checkLocalTransaction（回查）

```text
按 messageId 查询 seckill_pre_deduct.tx_status：
  COMMITTED  → 返回 COMMITTED
  ROLLBACK   → 返回 ROLLBACK
  不存在/UNKNOWN → 返回 UNKNOWN（继续回查，最多 15 次，之后告警人工介入）
```

### 5.3 发送失败处理

- 半消息发送异常/超时：本地事务未执行，直接 Lua 回补 + 快速失败；
- 发送重试 `retryTimesWhenSendFailed=2`，重试仍失败按上条处理；
- 禁止发送失败后重试预扣（避免重复扣减）。

## 6. Consumer 幂等设计

消费组：`order-consumer`（order-service）

消费流程（单条消息）：

```text
1 幂等表插入（biz_type=ORDER_CREATE, biz_id=messageId, user_id）
2 唯一索引冲突 → 已处理过 → 返回 CONSUME_SUCCESS（重复消息直接成功）
3 同事务创建订单（CREATE → WAIT_PAY），订单唯一约束兜底
4 回填 seckill_pre_deduct：order_id、deduct_status=CONFIRMED
5 返回 CONSUME_SUCCESS
```

三层幂等载体：

| 载体 | 作用 |
| --- | --- |
| 幂等表 `uk_biz(biz_type,biz_id)` | 消费去重主防线 |
| 订单 `uk_order_no` | 订单号唯一兜底 |
| 订单 `uk_active(active_key)` | 同一用户同场次同商品仅一条有效订单 |

重复/乱序消息：事件幂等，订单状态机拒绝非法流转，不依赖消息顺序。

## 7. 重试策略

| 维度 | 配置 | 说明 |
| --- | --- | --- |
| 失败分类 | 可重试 vs 不可重试 | 可重试：DB 抖动、锁冲突、依赖超时；不可重试：消息结构/业务数据非法 |
| 可重试处理 | 返回 `RECONSUME_LATER` | 按延迟等级重试 |
| 延迟等级 | RocketMQ 默认 18 级（1s → 2h） | 消费失败按 1s/5s/10s/30s/1m…递增 |
| 重试上限 | `maxReconsumeTimes=16` | 超限自动进入 DLQ |
| 不可重试处理 | 记录告警，转 DLQ 人工处理 | 避免死循环 |
| 消费线程 | min 20 / max 64 | 与分区数匹配，压测校准 |

重试期间消费位点不提交，消息不丢失。

## 8. DLQ 设计

- 死信 Topic：`%DLQ%order-consumer`（RocketMQ 自动生成）；
- 告警：DLQ 出现新消息即触发 P1 告警；
- 重放：管理工具查询 DLQ → 人工确认 → 重新投递原 Topic（消费幂等保证重放安全），重放操作审计留痕；
- 指标：DLQ 积压数、重试中消息数、消费 TPS/RT。

## 9. 消息补偿设计

### 9.1 对账补偿任务（5 分钟周期）

```text
1 扫描 seckill_pre_deduct：tx_status=COMMITTED 且超过 5 分钟无对应订单
2 重投 CREATE_ORDER（幂等，最多 3 次）
3 3 次后仍无订单 → 触发 STOCK_RECOVER 回补库存
4 全部动作写审计日志，人工可介入
```

### 9.2 回补链路

```text
取消/超时（order-service）→ 发布 CANCEL_ORDER
退款成功（payment-service）→ 发布 CANCEL_ORDER(reason=REFUND)
inventory-service 消费：
  写 RECOVER 流水（uk_biz 幂等）→ 更新事实库存
  → Lua 回补 Redis（校验 ≤ total）→ 按场次配置删除购买标记
```

### 9.3 人工补偿

- 运营发起补偿申请 → 审批 → 复用 inventory 修复流程（repair 锁 + REPAIR 流水）；
- 所有补偿幂等，禁止重复执行。

## 10. 消息丢失防护矩阵

| 风险 | 防护 |
| --- | --- |
| 半消息丢失 | Broker 持久化 + 回查机制 |
| Broker 宕机 | 主从同步 + 发送重试 |
| 本地事务与消息不一致 | 事务消息语义保证 |
| 消费处理中宕机（未提交位点） | 位点不提交，重启后重投 |
| 重复投递/重复消费 | 消费幂等（幂等表 + 唯一约束） |
| 极端丢失 | 对账补偿（重投 CREATE_ORDER / 触发 STOCK_RECOVER） |

## 11. 异常场景与监控

| 场景 | 处理 |
| --- | --- |
| Broker 不可用 | 发送失败 → Lua 回补 + 快速失败；消费端自动重连 |
| 消费积压 | 积压 > 10 万告警 → 扩容分区/消费实例 → 必要时限流降级 |
| 回查异常 | 连续 15 次 UNKNOWN → 告警人工介入 |
| 消息乱序 | 不依赖顺序，状态机幂等兜底 |
| 消费死循环 | 重试上限 + 不可重试分类，杜绝无限循环 |

监控指标：生产 TPS、消费 TPS/RT、积压量、重试中数量、DLQ 数量、回查次数。

## 12. 扩展方案

1. **定时消息**：超时关单可评估 RocketMQ 定时消息替代轮询扫描；
2. **批量消费/批量发送**：压测后按吞吐评估；
3. **分区扩容**：分区数 > 消费实例数，支持水平扩容；
4. **顺序性**：当前不依赖全局顺序；未来若需要，按 `orderId` 哈希选择队列。
