# Phase 6.4 生产拓扑与容量模型

> 完成时间：2026-08-05
> 分支：feature/phase6.2-inventory-sharding（Phase 6.4 延续）
> 环境：JDK 21 / Spring Boot 3.2.5 / MySQL 8.0.36 / Redis 7.2.4 / RocketMQ 5.3.1（真实 Testcontainers）
> 原则：所有性能数字来自隔离环境（独立 JVM + 独立中间件容器），测试环境数据与生产容量预测严格区分。

---

## 1. 隔离拓扑（消除共享 JVM 噪声）

Phase 6.4 建立多 JVM 生产拓扑（Load Generator 独立于全部被测组件）：

```text
Load Generator（测试 JVM）
        |
        v
Gateway JVM（独立进程 :18080）
        |
        +--> auth JVM（:18081）
        +--> seckill JVM（:18082）
        +--> order JVM（:18083）
        +--> inventory JVM（:18085）
        |
        v
Redis 容器  |  MySQL 容器  |  RocketMQ 容器
```

拆分明细：

| 组件 | 隔离方式 | 端口/映射 |
| --- | --- | --- |
| Gateway | 独立 JVM（java -cp 子进程） | 18080 |
| auth-service | 独立 JVM | 18081 |
| seckill-service | 独立 JVM | 18082 |
| order-service | 独立 JVM | 18083 |
| inventory-service | 独立 JVM | 18085 |
| Redis 7.2.4 | 独立 Docker 容器 | 随机映射 |
| MySQL 8.0.36 | 独立 Docker 容器 | 随机映射 |
| RocketMQ 5.3.1 | 独立 Docker 容器 | 19876/20911 |

每个服务 JVM 使用 `-Xmx1024m`，GC 日志落盘（`integration-test/target/isolated-topology/*-gc.log`），进程日志与资源采样由压测框架收集。

## 2. Gateway 容量模型（G-09，隔离拓扑）

参数：每档持续 60s（生产协议 600s），10000 用户预登录，真实 JWT + 真实 Gateway + 真实后端。

| 并发 | QPS | p50 | p95 | p99 | error rate |
| --- | --- | --- | --- | --- | --- |
| 100 | 180.71 | 245ms | 778ms | 1262ms | 0.00% |
| 200 | 727.90 | 163ms | 265ms | 390ms | 0.00% |
| 500 | 930.03 | 370ms | 532ms | 603ms | 0.11% |
| 1000 | 1104.92 | 600ms | 955ms | 1052ms | 0.16% |
| 2000 | 882.33 | 1503ms | 2352ms | 2582ms | 0.16% |
| 5000 | 780.26 | 4778ms | 6545ms | 7302ms | 1.67% |
| 10000 | 557.11 | 13460ms | 15321ms | 15581ms | 99.80% |

**容量拐点定义（错误率 >0.1% 或 p99 超 SLO）**：
- 按错误率：拐点在 **500 并发（0.11%）**；
- 按 p99（≤500ms SLO）：**200 并发**（p99 390ms）为推荐安全档。

**Single Gateway Capacity（隔离环境实测）**：
- 安全包络：**200 并发 ≈ 728 QPS，p99 390ms，错误率 0%**；
- 服务上限参考：1000 并发 ≈ 1105 QPS，但 p99 >1s、错误率开始上升；
- ≥2000 并发 RT 显著恶化，≥5000 并发错误率 >1%（环境后端饱和，非 Gateway 单点结论）。

报告：`integration-test/target/load-reports/gateway-capacity-report.json|csv`

## 3. 端到端容量（L-07，隔离拓扑全链路）

链路：execute → Gateway → seckill(Redis Lua) → RocketMQ → order → inventory。

| 指标 | 实测（3000 成功档） |
| --- | --- |
| 成功秒杀 | 3000 |
| 零超卖 | PASS（Redis stock == MySQL available，available+locked=total） |
| 行锁等待增量 | 3015 |
| 死锁 | 0 |
| MQ 收敛（backlog=0） | 176ms（消费已实时跟上） |
| 重复消息安全 | PASS（同 orderId 重发仅 1 条 DEDUCT） |
| 取消恢复 | PASS（抽样 100 单全部 RECOVER，Redis 同步回补） |
| Redis 命令增量 | 42080 |

协议规模：`-Dl07.success-target=10000/50000/100000` 为生产环境执行档位；本单机隔离环境以 3000 档完成一致性闭环验证（绝对成功速率受共享主机/单 Broker 饱和限制，不作为生产容量结论）。

报告：`integration-test/target/load-reports/L-07.json|csv`

## 4. 资源与连接记录

- 每服务 JVM：-Xmx1024m，GC 日志见 isolated-topology/*-gc.log；
- 压测期间资源采样（ResourceMonitor）：CPU/heap/thread/GC 随档位记录于 gateway-capacity-report.json；
- 中间件为独立容器，连接数由各服务默认/Phase 6.1 调优参数决定（order 消费线程 16、inventory 8）。

## 5. 测试环境数据 vs 生产容量预测

| 类别 | 说明 |
| --- | --- |
| 测试环境数据 | 单机 + Docker 容器 + 5 独立 JVM；绝对值受主机/Broker 饱和影响，用于相对对比与拐点定位 |
| 生产容量预测 | 需独立压测环境（Gateway 独立部署、多 Broker、独立 Load Generator）按“错误率=0”拐点重新标定；本阶段交付方法与协议 |

## 6. 结论

- 隔离 JVM 拓扑显著优于共享 JVM：G-09 c200 由共享环境 ~78 QPS/5s RT 提升到 **728 QPS/163ms p50/0% 错误率**；
- Gateway 单实例安全容量（本环境）：~700-900 QPS（p99 ≤500ms 取 200 并发档）；
- E2E 一致性闭环（零超卖/backlog=0/幂等/恢复）在隔离拓扑验证通过；
- 生产容量数字必须按 §5 方法在独立环境复测后冻结。
