# Seckill-Engine 系统定义（简历版 v2）

> 版本：v2.0（2026-08-09，基于代码与实测证据校正）
> 定位：替代初稿中与实现不符/无实测支撑的表述，面试可完整自洽

---

## 一、初稿 vs 实际（逐条校正）

| 初稿表述 | 实际实现 | 校正建议 |
| --- | --- | --- |
| 网络接入层结合 Nginx 进行 URL 动态化与黑名单拦截 | 仓库无 Nginx 配置；黑名单由 Gateway `BlacklistGlobalFilter`（Redis MGET）实现 | 删 Nginx/URL 动态化；改为"网关层 IP/用户黑名单拦截" |
| 基于令牌桶与漏桶算法实现多级安全流量控制 | 实现为 Spring Cloud Gateway `RequestRateLimiter`（Redis 令牌桶 replenishRate/burstCapacity） | 删漏桶；表述为"Redis 令牌桶限流 + 黑名单" |
| 过滤超过 90% 的无效访问 | 无此实测口径（压测中 30005/30004 为业务码，非"过滤率"） | 删除；改为可验证的入口削峰表述 |
| 单机 10,000+ QPS 压测下绝对零超卖 | 实测：L-01 入口 741 QPS；G-09 单 Gateway 安全容量 700-900 QPS（200 并发 728 QPS / p99 390ms / error 0）；L-02 Redis Lua 1847 QPS；隔离环境 1000 并发 1105 QPS（p99>1s） | 改为真实数字：700-900 QPS 安全容量，零超卖、零死锁 |
| 分布式互斥锁将校验与扣减原子化 | 核心是 Redis Lua 脚本（校验+扣减+防重在一个原子脚本内），无独立分布式锁组件 | 表述为"Redis Lua 原子脚本封装校验/扣减/防重" |
| 消费端结合数据库幂等防重表 | 实现为 `stock_flow` 唯一键（biz_type+biz_id）+ exists 幂等双保险 | 表述为"库存流水唯一键 + 幂等检查" |
| 失败指数退避重试 | 实现为 RocketMQ 延迟等级重试（RECONSUME_LATER，maxReconsumeTimes=16）+ DLQ | 改为"按延迟等级重试，耗尽进 DLQ" |

## 二、系统定义（简历/面试可用 v2）

**标志性高并发秒杀交易系统（Seckill-Engine）｜分布式高并发微服务｜核心开发**

面向瞬时高并发、严防超卖、保证最终一致性的交易场景，独立设计并实现高可用分布式秒杀全链路（Gateway / auth / seckill / order / inventory / payment 六服务 + Redis / MySQL / RocketMQ）。

**核心工作与量化结果：**

1. **入口削峰与安全控制**：Gateway 统一鉴权（JWT）、Redis 黑名单拦截、Redis 令牌桶限流，将流量在校验阶段拦截，隔离环境实测单实例安全容量 **700-900 QPS**（200 并发 728 QPS / p99 390ms / error 0）。
2. **原子防超卖**：采用"Redis 内存预扣减 + 异步落库"架构；**Redis Lua 原子脚本**封装库存校验、扣减、用户防重；**库存分桶（N=8）**解除单 SKU 行锁热点（分桶消费实测 360 QPS）。全链路压测**零超卖、零死锁**（L-07 10000 成功档实测）。
3. **最终一致性**：RocketMQ **事务消息**保证预扣减与消息发送原子性；order 建单、inventory 分桶扣减、`stock_flow` 唯一键幂等（重复消息只生效一次）；消费失败按延迟等级重试，耗尽进 DLQ 可审计。
4. **回滚与恢复**：取消/超时触发幂等 RECOVER 回补，Redis 与 MySQL 最终一致；Gateway 灰度权重 100→0 回滚 **RTO<5min**（实测毫秒级）。
5. **可观测与发布工程**：Prometheus 指标冻结 + Grafana 面板 + 告警规则；Canary 灰度体系（5→25→50→100，WARNING 暂停 / CRITICAL 自动回滚）；CI 五阶段门禁 + 依赖扫描；**365 个单元测试、22 个集成测试类、6 类故障演练、220+ 提交全部通过**。

**量化证据**：单 Gateway 700-900 QPS；L-01 入口 741 QPS；Redis Lua 1847 QPS；E2E 10000 成功零超卖；Canary 窗口 113 万请求 / error 0；RTO<5min。

## 三、面试口径（诚实声明）

- 单机/隔离环境数据为开发机 + Testcontainers 实测，生产容量需独立环境复核（已在仓库如实标注 PENDING）；
- 网关未引入 Nginx（设计可扩展，代码未实现）；
- 限流为 Redis 令牌桶（RequestRateLimiter），未实现漏桶；
- MQ 重试为 RocketMQ 延迟等级机制，非自研指数退避。

> 原则：简历只写有代码与数据支撑的能力；"零超卖/零死锁/幂等/回滚"均有真实测试与演示证据。
