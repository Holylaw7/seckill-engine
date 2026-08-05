# Phase 6.5 RC-02 Production Configuration Review

> 评审时间：2026-08-05；状态：已加固 + 冻结

## 1. 配置审查表

| 模块 | 配置 | 当前值（冻结） | 生产建议 | 风险 |
| --- | --- | --- | --- | --- |
| Gateway | server.netty.max-connections | 20000 | ≥预期并发峰值 ×2 | 过低导致连接拒绝 |
| Gateway | server.netty.connection-timeout | 5000ms | 5000ms | 过高导致半开连接堆积 |
| Gateway | httpclient.pool.max-connections | 2000 | 随并发线性扩展 | 后端连接排队 |
| Gateway | httpclient.acquire-timeout | 10000ms | 10000ms | 过长拖慢降级 |
| Gateway | httpclient.response-timeout | 30s | 30s | 后端挂起拖累网关线程 |
| Gateway | spring.data.redis.timeout | 3s（新增） | 3s | 依赖挂起拖垮请求（fail-open 依赖此超时） |
| Redis（业务服务） | spring.data.redis.timeout | 3s（新增） | 3s | Lettuce 默认 60s 会让故障请求长时间挂起 |
| MySQL（业务服务） | hikari.maximum-pool-size | 20（原默认 10） | 20-50 按连接池监控调整 | 池小导致 DB 排队 |
| MySQL | hikari.connection-timeout | 5000ms | 5000ms | 过长堆积请求 |
| MySQL | hikari.validation-timeout | 3000ms | 3000ms | — |
| MySQL | hikari.leak-detection-threshold | 10000ms | 10000ms | 连接泄漏难发现 |
| RocketMQ | seckill.producer.retry-times-when-send-failed | 2（有限重试） | 2-3，禁止无限重试 | 无限重试放大故障 |
| RocketMQ | seckill.producer.send-message-timeout | 3000ms | 3000ms | 过长拖慢 execute |
| RocketMQ | consumer maxReconsumeTimes | 默认 16 | 3-5 + DLQ 消费 | 无限重试导致消息堆积/重复副作用 |
| RocketMQ | DLQ | 未配置 | RocketMQ Dashboard 消费 %DLQ% | 重试耗尽消息丢失可观测性 |

## 2. 本阶段实际变更

- 5 个服务（gateway/auth/seckill/order/inventory）新增 `spring.data.redis.timeout=3s`；
- auth/seckill/order/inventory 新增 Hikari 显式配置（maximum-pool-size=20、connection/validation 超时、leak-detection）。

## 3. 未变更项（冻结语义）

- Redis Lua v1/v2 脚本不变；Redis Key 设计不变；
- RocketMQ Topic/消费组/消息格式不变；
- 不引入 Redis 连接池（Lettuce 共享连接 + 显式超时；生产如需池化需另评审）；
- 消费者 maxReconsumeTimes/DLQ 保持默认并在生产配置中心下发（本阶段仅登记建议）。

## 4. 结论

配置项已加固并显式化；剩余建议项（连接池化、DLQ）登记为生产发布后优化，不阻塞 RC。
