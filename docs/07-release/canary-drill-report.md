# Phase 6.5 RC-05 Canary Release Simulation

## Stage 0：旧模式（enabled=false, bucket-count=1）

- 证据：`mvn clean test` 全量旧路径 PASS（Phase 6.4/6.5 回归）；L-01/L-03/L-05 保持 PASS。
- 结论：旧模式基线稳定。

## Stage 1：enabled=true, bucket-count=4

- 证据：真实分桶基准 N=4（BucketShardingLoadTest）：consume QPS 295.28，死锁 0，桶内不变量 PASS；InventoryShardingConsistencyIT（分桶一致性）PASS。
- 指标：QPS≈295（接近 300 目标），zero oversell ✅，deadlock=0 ✅。

## Stage 2：enabled=true, bucket-count=8

- 证据：真实分桶基准 N=8：consume QPS 360.18，5000 条收敛 13.9s，死锁 0；
- L-07 E2E（隔离拓扑）：success≥3000 档完成，oversell=0，recover PASS；
- 结论：N=8 为生产目标档，零超卖/幂等/恢复门禁保持。

## Canary 执行顺序（生产）

1. 灰度 1 个 SKU：N=1 → N=4 观察 24h；
2. 全量 SKU：N=4 → N=8 观察；
3. 每步执行 H-02（一致性）+ L-07（E2E）+ SLO 告警确认。

## 结论

Canary 三阶段证据齐全，RC-05 通过。
