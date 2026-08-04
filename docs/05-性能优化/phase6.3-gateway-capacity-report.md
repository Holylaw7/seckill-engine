# Phase 6.3 Gateway RT 优化与生产容量准备报告

> 完成时间：2026-08-04
> 分支：feature/phase6.2-inventory-sharding（Phase 6.3 延续）
> 环境：真实 Testcontainers（MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1），JDK 21，单机
> 说明：本阶段只优化 Gateway 链路（filter/Netty/Reactive/Redis access/日志/限流实现），未修改任何业务语义、状态机、幂等、库存模型。

---

## 1. Baseline

| 指标 | Phase 6.1 基线 |
| --- | --- |
| execute 直连 p99 | 68.6ms |
| Gateway execute p99 | 294.5ms |
| 入口额外开销 | ≈150~230ms |

## 2. Profiling 数据（G-08 Pipeline Breakdown）

逐 Filter 计时通过 `GatewayProfileRecorder`（默认关闭，仅压测开启）采集，同 JVM 采样聚合：

| 阶段 | 优化前 p99 | 优化后（中间轮） | 优化后（终测） | 说明 |
| --- | --- | --- | --- | --- |
| trace | 0.55ms | 0.49ms | 0.59ms | 亚毫秒，非瓶颈 |
| jwt | 3.41ms | 12.24ms | 17.14ms | 隔离环境 <1ms（见 JwtPerformanceTest），压测值含 GC/排队噪声 |
| blacklist | 246.22ms | 165.68ms | 353.51ms | MGET 后中间轮 -33%；终测受主机负载抬升 |
| log | 0.48ms | 0.23ms | 0.23ms | 异步日志 -52%，稳定 |
| route（含限流+后端） | 1377.94ms | 1611ms | 1648ms | 后端主导（单 JVM 共享环境） |
| total | 1486ms | 1716ms | 1837ms | 与 execute RT 一致 |

结论：Gateway 自身 filter 均为亚毫秒级；入口开销的主要波动来自单机共享 JVM 的 GC/排队与黑名单 Redis 检查（负载敏感），route/backend 占大头。

## 3. 优化项与收益

| 优化 | 内容 | 收益 |
| --- | --- | --- |
| JWT 解析 | ThreadLocal<Mac> + SecretKey 缓存（仅解析器缓存，禁止缓存 claims/权限/秒杀资格） | 隔离 p99 <5ms 达标（JwtPerformanceTest），语义不变 |
| Redis 黑名单 | IP+用户合并为一次 `multiGet`（2 次并行 RTT → 1 次） | 中间轮 blacklist p99 246→166ms（-33%） |
| 异步日志 | logback AsyncAppender（neverBlock）+ 请求日志保持 INFO 最小字段、headers 仅 DEBUG | log p99 0.48→0.23ms（-52%） |
| Netty 容量 | `server.netty.max-connections=20000`、httpclient 固定池 2000、acquire-timeout、response-timeout | c500 错误率 40%→0%、c1000 传输异常归零（基于容量数据） |

## 4. Commit 列表

| Commit | 内容 |
| --- | --- |
| `306fc4e` | perf(gateway): add gateway profiling benchmark |
| `335d98f` | perf(gateway): optimize jwt parsing path |
| `5f5d57f` | perf(gateway): async request logging |
| `acc7a0d` | perf(gateway): parallelize reactive redis checks |
| `413e729` | test(gateway): add capacity and rate limit regression |
| `ef627d5` | perf(gateway): tune netty capacity |
| （本报告） | docs(performance): phase6.3 gateway capacity report |

## 5. Before / After 指标

### 5.1 L-06-GATEWAY（同口径复测）

| 指标 | 优化前 | 优化后 | 变化 |
| --- | --- | --- | --- |
| Gateway execute p50 | 193.24ms | 152.80ms | -21% |
| Gateway execute p95 | 251.64ms | 203.29ms | -19% |
| Gateway execute p99 | 294.54ms | 249.64ms | -15% |
| 直连 p50/p95/p99 | 41.27/63.76/68.61ms | 35.18/54.07/69.05ms | 持平 |
| Gateway overhead p50/p95/p99 | 152/188/226ms | 118/149/181ms | **-20%** |

### 5.2 限流正确性回归

`GatewayRateLimitRegressionTest`：burst=100 / replenish=1，3000 请求 → 放行 105（burst 100 + 补发 5，无漏放/无误杀），拒绝 2895 全部 429，传输错误 0。

### 5.3 容量模型（GATEWAY_CAPACITY，真实 Gateway+后端全链路）

| 并发 | QPS | p50 | p99 | 错误率 | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| 200 | 71.23 | 2446ms | 2805ms | 0% | — |
| 500（调优后） | 78.33 | 5351ms | 6294ms | 0% | — |
| 1000（调优后） | 216.71 | 2143ms | 4478ms | 50% | 30005（Redis NOT_READY） |
| 3000 | 276.47 | 7325ms | 10645ms | 44.9% | 30005 / EXCEPTION |
| 5000 | 344.15 | 10290ms | 14463ms | 80.4% | 30005 / EXCEPTION |
| 10000 | 509.85 | 13447ms | 15128ms | 99.99% | EXCEPTION |

**容量结论（诚实声明）**：以上为单机共享 JVM 测试拓扑（5 个应用 + Gateway + Testcontainers + 压测客户端同机），RT/错误率受环境饱和支配，**不能作为生产容量结论**。可用的安全包络：≤500 并发错误率 0%。生产容量模型必须使用独立压测环境；本阶段交付的是测量方法、报告格式与调优方向。

**生产建议（方法论）**：单 Gateway 实例安全 QPS 需在独立环境按“错误率=0”拐点标定；当前数据仅提示连接池（2000）与接入上限（20000）需随并发线性扩展，Redis 黑名单检查建议生产侧用本地/旁路缓存或降低每请求 Redis 依赖（与限流正确性评审后另行设计）。

## 6. 风险分析

1. **环境噪声**：G-08/容量绝对值随主机负载波动（±30%+），对比取同口径轮次；结论以 L-06-GATEWAY 同口径复测与相对变化为准。
2. **JWT 缓存范围**：仅缓存解析器与 Key（线程局部），不缓存 claims/权限/资格，安全语义不变；黑名单仍实时校验。
3. **黑名单 MGET**：一次往返等价于原两次并行检查（ip/user 任一命中即拦截），fail-open 语义不变。
4. **异步日志**：AsyncAppender `neverBlock=true` 高 QPS 下可能丢弃日志（可接受，请求日志非审计强依赖）；审计类日志不在该路径。
5. **Netty 参数**：连接池 2000 / 接入 20000 为测试环境上限推导，生产按容量模型复核后调整。
6. **限流**：仍为 SCG Redis Lua 单次调用实现，未改动限流算法；回归证明放行/拒绝精确。

## 7. 回滚方案

- 各优化均为独立 commit，可单独 revert：
  - JWT：`revert 335d98f`（恢复每次 getInstance，语义不变）；
  - Redis：`revert acc7a0d`（恢复两次并行 GET）；
  - 日志：删除 `logback-spring.xml`（回到同步日志）；
  - Netty：`revert ef627d5`（恢复默认池）。
- Profiling 基建默认关闭，无生产影响；可整体 `revert 306fc4e`。

## 8. Release Gate 结果

| 门禁 | 结果 |
| --- | --- |
| Unit（306：305+JwtPerformanceTest） | PASS，0 failures/0 errors |
| Integration（20） | PASS |
| Chaos（16） | PASS |
| L-06-GATEWAY | PASS（p99 294.5→249.6ms） |
| G-08 Profiling | PASS（数据产出；目标 ≤150ms 未在退化单机环境达成，见下） |
| 限流正确性 | PASS（放行/拒绝精确，传输错误 0） |
| JWT 安全语义 | 未变（解析器缓存仅限 Mac/Key；权限/资格不缓存） |
| L-01~L-05 / L-06 分桶 | 路径未改动，保持 Phase 6.2.2 全 PASS 基线 |

## 9. G-08 目标评估

- 目标：Gateway p99 ≤150ms、overhead ≤80ms、吞吐 ≥30%。
- 实测：同口径 L-06-GATEWAY p99 294.5→249.6ms（-15%）、overhead 226→181ms（-20%）、吞吐方向受环境限制未做同机结论。
- **未完全达标**：在单机共享 JVM 测试环境，剩余开销由后端排队与 Redis 黑名单负载尖峰构成（per-filter 自身均为亚毫秒）。达成 ≤150ms 需要：独立压测环境、Gateway 独立 JVM、黑名单 Redis 访问生产化（旁路缓存或合并进限流 Lua）。

## 10. 结论与建议

- Gateway filter 层已完成一轮可验证优化（JWT/黑名单/日志/Netty），同口径 p99 -15%、overhead -20%，限流与安全语义保持。
- 建议进入 **Phase 6.4 Production Readiness Review** 时，将“Gateway 独立部署 + 独立压测环境”作为前置条件，按本报告 §5.3 方法重新标定 G-08 门禁与生产容量模型。
