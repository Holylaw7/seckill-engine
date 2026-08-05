# Phase 6.5.4 Canary Release Report

> 执行：CanaryDrillIT（真实 seckill + inventory，Redis Lua v2 + 分桶 DEDUCT）。

## Stage 0：旧模式（enabled=false, bucket-count=1）

- 证据：`mvn clean test` 全量旧路径 PASS（Phase 6.4/6.5 回归）。

## Stage 1：enabled=true, bucket-count=4

- 真实 150 并发秒杀：成功数 ≤1000，零超卖；
- `SUM(bucket.available) == Redis stock == 1000 - success`；
- 每桶 `available + locked == total`；
- 既有基准：N=4 consume QPS 295.28，deadlock=0（BucketShardingLoadTest）。

## Stage 2：enabled=true, bucket-count=8

- 真实 150 并发秒杀：零超卖、分桶不变量、Redis 一致性全部成立；
- 既有基准：N=8 consume QPS 360.18，5000 条收敛 13.9s，deadlock=0；
- L-07 E2E（隔离拓扑）3000 成功档：oversell=0、recover PASS。

## 结论

灰度三阶段（0/4/8）验证通过，生产目标 N=8 保持零超卖/一致性/幂等/恢复门禁。
