# Seckill-Engine Redis 设计（Phase 3）

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0（评审稿） |
| 状态 | 待评审 |
| 日期 | 2026-08-01 |
| 关联基线 | 需求基线 v1.0 / 架构基线 v1.0 |
| 变更记录 | v1.0 初版；v1.0-S2 冻结补充：Lua 并发语义、购买标记 TTL 策略、Redis 故障策略 |

---

## 1. 设计目标

1. **零超卖**：库存检查与扣减由 Lua 原子完成，任何并发下库存不为负；
2. **防重复**：用户购买标记 + Lua 内防重，杜绝同一用户重复抢购；
3. **高性能**：核心扣减单次往返完成，支撑单集群 10,000 QPS、P99 ≤ 200ms；
4. **可演进**：同一套代码适配单实例 → Sentinel → Cluster；
5. **可观测**：库存水位、key 命中、限流计数全部可监控、可对账。

## 2. 方案选择

| 决策点 | 方案 | 理由 |
| --- | --- | --- |
| 版本 | Redis 7.x + Redisson | 架构基线冻结 |
| 部署演进 | dev 单实例 / test Sentinel / prod Cluster | 同代码演进，Redisson 屏蔽拓扑差异 |
| 库存结构 | String + Lua | 单值原子 DECRBY 性能最优，序列化开销为零 |
| 持久化 | 生产 AOF（everysec）+ RDB | Redis 为热点层，库存可重算，不依赖其作为唯一事实源 |
| 分布式锁 | Redisson | 看门狗续期、公平锁、持有者校验开箱即用 |
| 限流 | Gateway Redis Lua + Sentinel 规则（Nacos） | 分布式计数统一在 Redis |

## 3. Key 规范

格式：`业务:模块:对象:唯一标识`

示例：`seckill:stock:{skuId}`、`seckill:user:{userId}:{skuId}`、`risk:rate:user:{userId}`

规范要求：

- 全小写，冒号分隔，禁止空格与中文；
- 模块命名见 Key 总表，新增 key 必须先登记；
- 一律带 TTL 或在生命周期结束时显式清理，禁止永久堆积；
- 禁止大 Value（库存等均为短字符串）；
- 未来分片 key 使用 hash tag（`{skuId}`）保证 Lua 多 key 同槽。

## 4. Key 详细设计（总表）

| Key | 类型 | Value | TTL | 用途 |
| --- | --- | --- | --- | --- |
| `seckill:activity:{activityId}` | Hash | `{name,startTime,endTime,status}` | 活动生命周期 | 活动元数据缓存 |
| `seckill:session:{sessionId}` | Hash | `{startTime,endTime,status,limitPerUser}` | 场次生命周期 | 场次元数据（开始/结束/状态） |
| `seckill:stock:total:{skuId}` | String | 总库存（整数） | 场次生命周期 | 回补上限校验 |
| `seckill:stock:{skuId}` | String | 剩余库存（整数） | 场次生命周期 | Lua 原子扣减 |
| `seckill:user:{skuId}:{userId}` | String | `1` | 场次结束 + 24h | 用户购买标记，防重复（tag=skuId，与库存 key 同槽） |
| `seckill:lock:order:{userId}:{skuId}` | Redisson Lock | - | 10s（看门狗续期） | 防重复下单/状态流转互斥 |
| `seckill:lock:preheat:{skuId}` | Redisson Lock | - | 30s | 预热任务互斥 |
| `seckill:lock:repair:{skuId}` | Redisson Lock | - | 60s | 人工修复与回补互斥 |
| `seckill:flow:{orderId}` | String | `DEDUCTED` | 24h | 预扣结果幂等标记 |
| `risk:rate:user:{userId}` | String | 计数 | 60s（滑动窗口近似） | 用户维度频次 |
| `risk:rate:ip:{ip}` | String | 计数 | 60s | IP 维度频次 |
| `risk:rate:api:{api}` | String | 计数 | 60s | API 维度频次 |
| `risk:blacklist:user:{userId}` | String | `1` | 长期 | 用户黑名单（网关直读） |

说明：

- Gateway 令牌桶计数 key（`request_rate_limiter.{前缀}.{key}`）由 Spring Cloud Gateway + Redis Lua 框架内部管理，本设计只约定前缀与容量参数；
- Sentinel 规则存 Nacos，统计默认应用内存；启用集群流控时使用 Redis token server（Phase 6 评估）；
- 场次结束后由清理任务删除场次相关 key，或依赖 TTL 自然过期。

> 说明（冻结）：`seckill:user:{skuId}:{userId}` 的 hash tag 取 `skuId`，与 `seckill:stock:{skuId}` 落在同一哈希槽，保证 Redis Cluster 下 Lua 多 key 原子脚本可执行（规避 CROSSSLOT 限制）；该格式在单机/哨兵环境同样统一使用，避免环境间 key 格式差异。

## 5. 秒杀库存设计

### 5.1 结构

- `seckill:stock:{skuId}`：String，值为剩余库存整数；
- `seckill:stock:total:{skuId}`：String，值为总库存整数；
- 预热时写入，扣减用 Lua `DECRBY`，回补用 Lua `INCRBY` 并校验不超过 total；
- 选用 String 而非 Hash：单值原子操作性能最好，无字段序列化开销。

### 5.2 读写方

- 写入：预热任务（seckill-service 侧）与回补/修复任务（inventory-service 侧）；
- 扣减：seckill-service 秒杀接口（Lua）；
- 读取：库存查询接口、对账任务。

## 6. 用户购买限制

- Key：`seckill:user:{skuId}:{userId}`（hash tag=skuId，与库存 key 同槽）；
- Value：`1`；TTL：场次结束 + 24h；
- 写入时机：Lua 扣减成功时一并写入（SET EX），保证“扣减与标记”原子；
- 重复判定：Lua 内先检查该 key 是否存在，存在即返回 `REPEAT_BUY`；
- 回补策略：按场次配置——允许再购（删除标记）或不允许再购（保留标记直至 TTL），默认允许再购。

### 6.1 TTL 与标记生命周期（冻结）

| 场景 | TTL / 标记处理 |
| --- | --- |
| 正常预扣成功 | 写入标记，TTL = 场次结束时间 + 24h（写入时换算剩余秒数） |
| 订单失败（预扣成功但建单失败 → 回补） | 回补完成且场次配置允许再购：删除标记；不允许再购：保留至 TTL 过期 |
| 支付超时（TIMEOUT） | 关单回补后按场次配置：默认删除标记（允许再购） |
| 用户取消（CANCEL） | 默认删除标记（允许再购） |
| 退款（REFUND） | 按业务策略：默认保留标记（防刷单）；策略允许再购时删除 |
| 支付成功（PAY_SUCCESS） | 保留标记直至 TTL 自然过期 |
| 场次结束 | 依赖 TTL 过期，清理任务兜底删除 |

规则：

- TTL 不得早于场次结束时间，避免场次内标记过期导致重复抢购；
- 删除标记与回补流水必须最终一致：先写流水后删标记，删除失败由对账任务兜底。

## 7. 秒杀活动缓存

- Key：`seckill:session:{sessionId}`（Hash），字段包含开始时间、结束时间、状态、限购数；
- 状态取值：`INIT / PREHEATING / READY / ONGOING / ENDED`；
- 读取路径：秒杀接口先读场次缓存校验状态与时间，未就绪快速失败；
- 更新路径：运营变更 DB 后删除缓存重建（Cache Aside）；状态流转由预热任务/定时任务更新；
- 防热点：场次元数据允许本地缓存（Caffeine，30s TTL）兜底，库存类数据严禁本地缓存。

## 8. 分布式锁（Redisson）

### 8.1 使用场景

| 场景 | Key | 说明 |
| --- | --- | --- |
| 防重复下单（二道防线） | `seckill:lock:order:{userId}:{skuId}` | 订单落库与状态流转互斥，Lua 已挡 Redis 层重复 |
| 库存预热 | `seckill:lock:preheat:{skuId}` | 防止预热任务重复执行 |
| 人工修复 | `seckill:lock:repair:{skuId}` | 防止修复与回补并发导致数量错误 |

### 8.2 参数与释放

- 粒度：`userId + skuId`（细粒度，不锁全局）；
- 等待：秒杀路径 `waitTime=0`（抢不到立即失败，不排队）；
- 超时：`leaseTime=10s`，配合看门狗自动续期，防止业务未完成锁被提前释放；
- 释放：finally 中释放，且校验持有者，防止误删他人锁；
- 不适用场景：Redis 预扣本身不需要锁——Lua 单线程原子执行已保证正确性。

## 9. 限流数据

### 9.1 Gateway 限流（Redis Lua 令牌桶）

- 依赖 Spring Cloud Gateway `RequestRateLimiter` + Redis RateLimiter；
- 维度：IP、用户、API，配置 `replenishRate`（每秒补充令牌）与 `burstCapacity`（桶容量）；
- 计数 key 由框架管理：`request_rate_limiter.{prefix}.{dimensionKey}`；
- 规则：Nacos 动态下发，Gateway 监听刷新。

### 9.2 auth-service 风控频次（固定窗口计数）

- Key：`risk:rate:user:{userId}` / `risk:rate:ip:{ip}` / `risk:rate:api:{api}`；
- 操作：`INCR` + 首次 `EXPIRE 60`；
- 超阈值：拒绝或触发验证码（验证码扩展接口预留）；
- 黑名单：`risk:blacklist:user:{userId}`，网关与 auth-service 均直读。

### 9.3 Sentinel

- 规则（QPS 阈值、熔断参数）存 Nacos，动态下发；
- 单机统计默认内存；分布式限流（Phase 6 评估）使用 Redis token server 模式。

## 10. Lua 伪代码设计

输入：`skuId`、`userId`（另含 quantity、userKeyTtl 参数）

```text
KEYS[1] = seckill:stock:{skuId}
KEYS[2] = seckill:user:{userId}:{skuId}
ARGV[1] = userId
ARGV[2] = quantity（默认 1）
ARGV[3] = userKeyTtl（购买标记 TTL）

执行：
1  检查 KEYS[2]（购买标记）是否存在
    存在 → 返回 REPEAT_BUY
2  读取 KEYS[1]（剩余库存）
    key 不存在 → 返回 NOT_READY（未预热/场次异常，区别于售罄）
    数值 < ARGV[2] → 返回 STOCK_EMPTY
3  DECRBY KEYS[1] ARGV[2]（原子扣减）
4  SET KEYS[2] 1 EX ARGV[3]（写入购买标记）
5  返回 SUCCESS
```

返回码约定：

| 返回 | 含义 | 对外表现 |
| --- | --- | --- |
| `SUCCESS` | 预扣成功 | 生成 orderId，发事务消息 |
| `STOCK_EMPTY` | 库存不足 | “已售罄”，快速失败 |
| `REPEAT_BUY` | 重复抢购 | “请勿重复抢购”，快速失败 |
| `NOT_READY` | 库存未预热/场次异常 | 系统繁忙提示 + 告警（对 SUCCESS/STOCK_EMPTY/REPEAT_BUY 的补充返回） |

设计要求：脚本一次调用完成检查、扣减、标记；禁止分步执行（先 GET 再 DECR 的竞态）；脚本内不包含业务时间判断（时间校验在 Redis 前完成）。

### 10.1 并发语义说明（冻结）

- Redis 采用**单线程事件循环**执行命令：所有命令（含 Lua 脚本）进入队列串行执行，同一时刻只有一条命令在处理；
- Lua 脚本作为**一个整体原子执行**：脚本执行期间 Redis 不处理任何其他命令，因此“检查库存 → 检查用户 → 扣减库存 → 写入购买标记”四条操作在并发请求下天然串行化，等价于一次不可分割的事务；
- 因此预扣场景**无需分布式锁**即可保证零超卖与防重复；若拆分为多次 RPC（先 GET 再 DECR），并发下必然出现竞态超卖；
- Cluster 约束：Redis Cluster 要求多 key 脚本的所有 key 位于**同一哈希槽**。本设计使用 hash tag：`seckill:stock:{skuId}` 与 `seckill:user:{skuId}:{userId}` 均以 `skuId` 计算槽位，保证生产集群下脚本合法执行；单机/哨兵环境不受此约束，但统一采用同格式避免环境差异。

## 11. 库存一致性设计

职责边界：

| 层 | 角色 | 写入方 |
| --- | --- | --- |
| Redis | 热点库存（预热/预扣/回补） | seckill-service（预扣）、inventory-service（回补/修复） |
| MySQL `inventory` | 最终库存事实源 | inventory-service |
| MySQL `stock_flow` | 不可变流水 | inventory-service |

### 11.1 库存初始化（预热）流程

```text
1 运营创建场次与商品（DB）
2 T-30min 预热任务：获取预热锁 seckill:lock:preheat:{skuId}
3 从 inventory 读取事实库存，校验场次状态
4 写入 seckill:stock:total:{skuId} 与 seckill:stock:{skuId}（SETNX 防重复）
5 校验 Redis 值与 MySQL 值一致
6 场次状态置 READY；失败 → 告警并禁止开场
```

### 11.2 库存扣减流程

```text
1 秒杀接口校验场次/资格
2 Lua 原子扣减（第 10 章），失败快速返回
3 成功 → 本地事务写 seckill_pre_deduct（message_id 唯一）→ 发送事务消息
4 order-service 消费建单（幂等）
5 订单落库成功后，inventory-service 写 DEDUCT 流水并更新 inventory 事实库存
```

### 11.3 订单失败回补流程

```text
1 消费失败 → 延迟重试 16 次 → DLQ 告警
2 对账任务发现“有预扣、无订单”
3 触发 STOCK_RECOVER（或人工审批后修复）
4 inventory-service 写 RECOVER 流水（uk_biz 幂等）→ 更新事实库存
5 Lua INCRBY 回补 Redis，校验回补后 ≤ total
6 按场次配置删除用户购买标记（允许再购时）
```

### 11.4 人工修复流程

```text
1 管理员发起修复申请（原因、数量、目标）
2 审批通过 → 获取修复锁 seckill:lock:repair:{skuId}
3 写 REPAIR 流水（幂等）→ 更新 inventory 事实库存
4 同步/异步回补 Redis（校验 ≤ total）
5 释放锁，记录操作人与审计日志
```

### 11.5 对账流程

```text
数据源：seckill_pre_deduct（预扣流水）｜ seckill_order（订单）｜ stock_flow（库存流水）
分钟级核对：
  有预扣、无订单      → 触发 STOCK_RECOVER 回补
  有订单、无预扣      → 冻结订单 + 告警 + 人工核查
  扣减/回补数量不平    → 修复任务 + 人工审批
Redis 剩余库存 ≠ MySQL available_stock → 以 MySQL 为准校准 Redis（告警 + 修复流水）
```

## 12. 异常场景

| 场景 | 处理 |
| --- | --- |
| Redis 宕机 | 秒杀预扣熔断快速失败，**禁止降级为 DB 直扣**；查询降级本地缓存/DB |
| Sentinel 切换 / Cluster 故障转移 | Redisson 自动重连，客户端感知短暂抖动，Lua 重试一次后快速失败 |
| Lua 脚本执行异常 | 视为系统异常，不扣减、不标记，返回快速失败并告警 |
| 未预热/库存 key 不存在 | 返回 NOT_READY，快速失败 + 告警，禁止视为售罄 |
| 回补超过 total | Lua 校验拒绝 + 告警，走人工修复 |
| 热 key | 单 key 扣减可支撑万级 QPS；超出后启用分片库存（扩展方案） |
| 大 Value | 规范禁止；监控大 key 告警 |

## 13. 扩展方案

1. **分片库存**：`seckill:stock:{skuId}:{slot}`，slot 数按压测确定；使用 hash tag `{skuId}` 保证 Lua 多 key 同槽；扣减采用随机/轮询 slot，汇总库存另存 `seckill:stock:total:{skuId}`；
2. **本地缓存**：仅场次元数据可本地缓存（30s TTL），库存类数据禁止本地缓存；
3. **集群流控**：Sentinel 集群流控 token server 模式接入 Redis；
4. **监控**：慢日志、big key、命中率、连接数、库存水位指标接入 monitor；
5. **读写分离**：Redisson 支持读写分离配置，读多写少场景可评估。

---

## 14. Redis 故障策略（冻结）

| 阶段 | 策略 |
| --- | --- |
| 快速失败 | 预扣接口直接返回“系统繁忙”（BUSY），**禁止降级为 MySQL 直接扣库存**（超卖风险） |
| 降级 | 只读接口降级：场次元数据读本地缓存（30s TTL）或 DB；库存查询返回缓存快照或失败 |
| 熔断 | Sentinel 对预扣资源熔断（如 10s 窗口错误率 > 50% 熔断 30s），熔断期间快速失败，避免流量击穿 |
| 恢复 | Redis 恢复 → 哨兵/集群自动切换 → Redisson 重连 → 熔断器半开探测 → 逐步放量 |
| 恢复后一致性 | 预热/修复任务重新校验 Redis 与 MySQL 库存一致性；差异以 MySQL 为准校准（REPAIR 流水） |

明确：

- 库存 key 丢失（如 RDB/AOF 恢复不完整）时**禁止直接写入初始化库存**，必须走预热/修复流程校验后重建；
- 故障期间对账任务持续运行，恢复后先收敛数据再开放秒杀入口。
