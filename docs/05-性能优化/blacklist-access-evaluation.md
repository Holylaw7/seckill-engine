# Gateway Redis 黑名单访问评估（Phase 6.4）

> 原则：禁止直接修改；必须先 benchmark。本文件为 Option A/B/C 的评估结论，未实施 B/C。

## 现状与数据

- 现状：每个请求经 `BlacklistGlobalFilter` 访问 Redis 一次 `MGET`（IP + 用户，Phase 6.3 已从 2 次 GET 合并为 1 次）。
- 实测（G-08 隔离/共享环境）：
  - 黑名单阶段 p99：246ms（共享 JVM，优化前）→ 166ms（MGET 后中间轮）；隔离拓扑下受主机负载波动；
  - 每请求 Redis 命令量：G-09 60s 档 Redis 命令增量与 QPS 成正比（约 3-7 命令/请求，含 Lua 预扣、黑名单、限流）。
- 瓶颈判断：黑名单 Redis 访问本身为单次 RTT（~亚毫秒级在空闲 Redis）；压测尖峰来自主机 GC/排队，非 Redis 单点。

## Option A：保持现状（Reactive MGET）

- 优点：实现简单、实时性最好、fail-open 语义明确；无一致性窗口。
- 验证：单次 `multiGet`（1 RTT），Reactive 非阻塞；连接池与 CPU 未成为拐点（G-09 数据）。
- 结论：**推荐作为生产默认**，配套 Redis 延迟监控（p99 <5ms 告警）。

## Option B：本地短缓存（Caffeine，TTL 1~5s）

- 设计：
  - 仅缓存黑名单状态（`ip/user -> blocked:boolean`），**禁止缓存用户权限/秒杀资格**；
  - TTL 1~5s（推荐 2s），缓存未命中回源 MGET；
  - 一致性窗口：黑名单解除最多延迟 TTL；封禁写入后最多 TTL 内生效（可接受，封禁通常小时级）。
- 收益：命中时零 Redis RTT，可减少 ~1 命令/请求。
- 风险：内存占用与淘汰策略；封禁即时性下降；多 Gateway 实例间缓存不一致（可容忍窗口）。
- 结论：**仅在 Redis 延迟成为瓶颈（p99 >5ms 且黑名单命令占比显著）时启用**；当前数据不满足启用条件。

## Option C：并入限流 Lua（1 RTT 完成限流+黑名单）

- 设计：将黑名单 key 检查并入 RedisRateLimiter 的 Lua（KEYS 增加黑名单 key），一次 RTT 完成“限流 + 黑名单”。
- 收益：每请求再省 1 RTT。
- 风险：
  1. 修改 SCG `RedisRateLimiter` 内部实现/替换为自定义 RateLimiter，属于限流实现改造（需保持放行/拒绝精确）；
  2. 黑名单与限流语义耦合，故障影响面扩大；
  3. 限流 Lua 变更需回归“无漏放/无误杀”（现有 GatewayRateLimitRegressionTest 门禁）。
- 结论：**列为 Phase 6.5 候选**，需独立评审限流语义后再实施。

## 最终建议

1. Phase 6.4 保持 Option A（默认），并上线 Redis 延迟监控；
2. 当监控显示黑名单命令贡献显著延迟时，优先启用 Option B（TTL 2s）；
3. Option C 需要限流实现专项评审，暂不实施。
