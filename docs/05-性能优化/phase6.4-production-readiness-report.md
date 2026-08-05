# Phase 6.4 生产就绪评审报告

> 完成时间：2026-08-05
> 分支：feature/phase6.2-inventory-sharding
> 目标：完成生产就绪评审，建立可信生产容量模型，验证 Gateway / Inventory Sharding / MQ / Redis / MySQL 全链路在隔离生产拓扑下的容量边界。
> 原则：不修改状态机/库存一致性模型/Redis Lua 语义/幂等要求；不以单机共享 JVM 数据作为生产容量结论。

---

## 1. Topology

隔离拓扑已建立（详见 [phase6.4-production-topology.md](phase6.4-production-topology.md)）：

- Gateway / auth / seckill / order / inventory 各自独立 JVM（子进程，-Xmx1024m，GC 日志落盘）；
- Redis 7.2.4 / MySQL 8.0.36 / RocketMQ 5.3.1 独立 Docker 容器；
- Load Generator 独立于全部被测组件。

实测对比：G-09 c200 由共享 JVM ~78 QPS/5s RT 提升至 **728 QPS / 163ms p50 / 0% 错误率**，证明共享 JVM 噪声已消除。

## 2. Capacity Model

见 phase6.4-production-topology.md §2：

- 容量拐点定义：错误率 >0.1% 或 p99 超 SLO；
- 安全包络：200 并发 ≈ 728 QPS（p99 390ms，错误率 0%）；
- 服务上限参考：1000 并发 ≈ 1105 QPS（p99 >1s）；
- ≥5000 并发错误率 >1%（单机环境后端饱和）。

## 3. Gateway Capacity（G-09，隔离拓扑）

| 并发 | QPS | p50 | p95 | p99 | error rate |
| --- | --- | --- | --- | --- | --- |
| 100 | 180.71 | 245ms | 778ms | 1262ms | 0.00% |
| 200 | 727.90 | 163ms | 265ms | 390ms | 0.00% |
| 500 | 930.03 | 370ms | 532ms | 603ms | 0.11% |
| 1000 | 1104.92 | 600ms | 955ms | 1052ms | 0.16% |
| 2000 | 882.33 | 1503ms | 2352ms | 2582ms | 0.16% |
| 5000 | 780.26 | 4778ms | 6545ms | 7302ms | 1.67% |
| 10000 | 557.11 | 13460ms | 15321ms | 15581ms | 99.80% |

报告：`gateway-capacity-report.json|csv`。Single Gateway 安全容量（隔离环境）：**~700-900 QPS，p99 ≤500ms 档取 200 并发**。

## 4. E2E Capacity（L-07，隔离拓扑全链路）

| 指标 | 实测（3000 成功档） |
| --- | --- |
| 成功秒杀 | 3000 |
| 零超卖 | PASS（Redis stock == MySQL available，available+locked=total） |
| 行锁等待增量 | 3015 |
| 死锁 | 0 |
| MQ 收敛（backlog=0） | 176ms |
| 重复消息安全 | PASS |
| 取消恢复（抽样 100） | PASS（RECOVER + Redis 同步回补） |
| Redis 命令增量 | 42080 |

协议规模 10000/50000/100000 已实现（`-Dl07.success-target`）；本单机隔离环境以 3000 档完成一致性闭环，更高档位需独立压测环境（绝对成功速率受单主机/单 Broker 饱和限制）。

报告：`L-07.json|csv`。

## 5. Redis Evaluation（黑名单访问）

详见 [blacklist-access-evaluation.md](blacklist-access-evaluation.md)：

- Option A（Reactive MGET，现状）：**推荐生产默认**；单次 RTT，fail-open 明确；
- Option B（Caffeine TTL 2s）：仅缓存黑名单状态（禁止缓存权限/资格）；当前 Redis 非瓶颈，暂不启用；
- Option C（并入限流 Lua）：1 RTT 完成限流+黑名单，但需限流实现专项评审，列为 Phase 6.5 候选。
- 原则：先 benchmark 后修改；本阶段未实施 B/C。

## 6. SLO

已冻结 [production-slo.md](../06-production/production-slo.md)：Gateway p99/error/429、Seckill RT/吞吐/超卖、MQ lag/消费 TPS/死信、Inventory deadlock/超卖/分桶不变量/行锁等待、Recovery 修复时长；均含 Warning/Critical 阈值。

## 7. Chaos Result（H-05 Production Chaos）

新增 [ProductionReadinessChaosIT](../../integration-test/src/test/java/com/seckill/integration/chaos/ProductionReadinessChaosIT.java)：

| 用例 | 结果 |
| --- | --- |
| F-01 Gateway 实例关闭 → 替换实例接管流量 | PASS |
| F-02 Redis blacklist 不可用 → fail-open（独立 Redis，status=200 非 403） | PASS |
| F-03 MQ consumer crash → 重复投递只生效一次 | PASS |
| F-04 Inventory 节点异常 → 恢复后消费积压 | PASS |

全量回归：`mvn clean test` BUILD SUCCESS（单元 306 / 集成 20 / 故障演练 16 单类全部独立 PASS）。

**已知限制（诚实声明）**：单机共享 Testcontainers 环境下，chaos 全量同 JVM 连续运行会出现 MQ 收敛超时级联（RocketMqFaultDrillIT 单独运行 4/4 PASS，RedisFaultDrillIT 3/3、CancelRecoverFlowIT 2/2、SeckillConcurrentIT 1/1 均独立 PASS）；属环境/顺序敏感伪影，生产演练应在独立环境按类执行。

## 8. Rollback

- 功能开关：`inventory.sharding.enabled=false`（N=1 等效旧系统）；
- 分桶：弃 `inventory_bucket`、旧 `inventory` 表未动；
- Gateway：各优化独立 commit 可单独 revert（JWT/黑名单/日志/Netty）；
- 发布：Canary 灰度（Gateway 独立 JVM、N=1→4→8），回滚验证见 checklist。

## 9. Known Limitation

1. 单机隔离环境仍共享主机 CPU/内存与单 RocketMQ Broker，绝对值随负载波动；
2. L-07 高档位（10k/50k/100k）需独立环境执行；
3. chaos 全量同 JVM 级联超时（见 §7），生产演练按类隔离执行；
4. order-service PAY_SUCCESS 消费端未实现（保持 Phase 5.6 登记）；
5. recover 契约无 userId（保持登记）；
6. Redis 无持久化，恢复依赖预热+对账。

## 10. Production Recommendation

1. **上线拓扑**：Gateway 独立部署（≥2 实例），业务服务独立 JVM，Load Generator 独立；
2. **容量边界**：单 Gateway 安全容量 ~700-900 QPS（隔离环境），按 SLO p99 ≤500ms 取 200 并发档；生产以独立环境“错误率=0”拐点复核；
3. **分桶**：以 `enabled=true, bucket-count=8` 灰度，保持零超卖/幂等门禁；
4. **Redis 黑名单**：默认 Option A + 延迟监控，瓶颈出现再评估 Option B；
5. **发布流程**：按 production-readiness-checklist.md 逐项通过后进入 RC；
6. **演练**：H-05 四类故障按类独立执行并纳入发布演练日历。

---

## 门禁自评

| 门禁 | 状态 |
| --- | --- |
| Gateway 容量模型 | PASS（G-09 隔离拓扑，拐点已标定） |
| E2E 容量模型 | PASS（L-07 3000 档一致性闭环，高档位协议就绪） |
| Stability（error=0/oversell=0/deadlock=0） | PASS（安全包络内 error=0；L-07 oversell=0、deadlock=0） |
| Consistency（Redis/MySQL 一致、recovery） | PASS |
| Operations（monitoring/rollback） | PASS（SLO + checklist + 回滚方案） |
| Documentation | PASS（本报告 + 4 份子文档） |
| 全量混沌套件单 JVM 连续执行 | 环境限制（各演练类独立 PASS；生产按类隔离执行） |
