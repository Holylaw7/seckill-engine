# Phase 5.5 压测与容量验证设计确认文档

版本：v1.0（待评审冻结）
分支：feature/phase5-test
基线：Phase 5.4（commit c40ed67，单元 237 + 集成 30 = 267 tests，failures=0，errors=0，工作区 clean）

---

## 0. 基线

### 0.1 冻结架构

```text
Nginx（限流/负载）
    │
    ▼
Gateway（鉴权/限流/黑名单/TraceId）
    │
    ▼
Service（auth → seckill → order / inventory / payment）
    │
    ├── MySQL 8.0.36（业务事实源）
    ├── Redis 7.2.4（热点库存 Lua、会话、防重标记）
    └── RocketMQ 5.3.1（事务消息、异步消费）
```

### 0.2 测试基线

| 项 | 数量 |
| --- | ---: |
| 单元测试 | 237 |
| 集成测试（5.3.1/5.3.2） | 17 |
| 故障演练（5.4） | 13 |
| 合计 | 267 |
| failures / errors | 0 / 0 |

### 0.3 本阶段红线

- 不修改状态机、不新增接口、不扩大业务边界、不修改数据库结构；
- 不修改任何业务模块生产代码（发现缺陷按 `fix(module): xxx` 单独提交）；
- 禁止 Mock Redis / MySQL / RocketMQ；
- 禁止简单循环请求：所有压测必须使用统一的并发负载驱动 + 预热/测量窗口 + 指标聚合；
- 压测用例默认不随 `mvn test` 执行（`@EnabledIfSystemProperty(load.enabled=true)`），单独按场景运行。

---

## 1. 测试目标

在真实中间件（Testcontainers）环境下量化并验证秒杀引擎六项能力：

1. **吞吐能力**：秒杀接口与 MQ 消费的稳定吞吐基线；
2. **延迟表现**：P50/P95/P99 RT 与消费延迟；
3. **库存正确性**：Redis Lua 与 MySQL 事实层零超卖；
4. **MQ 消费能力**：消费 TPS、消费延迟、堆积收敛、重试隔离；
5. **数据最终一致性**：订单/库存/支付/MQ 五域口径收敛；
6. **系统瓶颈定位**：通过 JVM、MySQL、RocketMQ、Redis 指标定位资源与锁竞争瓶颈。

说明：Testcontainers 单机环境的数值作为**环境基线**记录，不作为生产门禁；生产级门禁（如 10,000 QPS）留 Phase 5.8 真实环境压测（见 R-01/R-02/R-05）。

---

## 2. 压测范围

### 2.1 场景总览

| 编号 | 场景 | 测试类（规划） | 负载模型 |
| --- | --- | --- | --- |
| L-01 | 秒杀接口压力 | `SeckillApiLoadTest` | 1000 并发单轮 + 持续混合 |
| L-02 | Redis Lua 原子性 | `RedisLuaLoadTest` | 10000 并发直接压真实 Lua |
| L-03 | MQ 消费能力 | `MqConsumptionLoadTest` | 定速生产 5000 条，观测消费 |
| L-04 | 数据库压力 | `DatabaseLoadTest` | 多线程批量写入 + 业务写入统计 |
| L-05 | 30 分钟稳定压测 | `StabilityLoadTest` | 200 QPS 混合负载持续 30 分钟 |

### 2.2 L-01 秒杀接口压力

目标接口：`POST /api/v1/seckill/execute`

参数：

| 项 | 值 |
| --- | --- |
| 库存 | Redis 100 / MySQL 100 |
| 并发 | 1000（独立用户，1000 线程 CountDownLatch 同时放行） |
| 命名空间 | session 36001 / sku 26001 / user 26001~27000 |

断言：

- 成功数量 ≤ 100；
- Redis `seckill:stock:26001 ≥ 0`，且 = 100 − 成功数；
- 无超卖：`inventory.available + locked = total = 100`；
- order 数量 = 成功数量；DEDUCT 流水数 = 成功数量；用户标记数 = 成功数量；
- MQ 消费在 60s 内收敛，无重复订单。

输出：QPS、成功率、P50/P95/P99、错误码分布（0 / 30004 / 其他）、消费收敛耗时。

### 2.3 L-02 Redis Lua 原子性压力

直接调用 seckill-service 上下文中的真实 `StockService.preDeduct`（真实 Redis Lua，绕过 HTTP/Tomcat/MQ 以隔离 Redis 原子性指标）：

| 项 | 值 |
| --- | --- |
| 库存 | Redis 1000 / total 1000 |
| 并发 | 10000（独立用户，CountDownLatch 同时放行） |
| 命名空间 | sku 26002 / user 27001~37000 |

断言：

- 成功数 ≤ 1000；
- Redis `seckill:stock:26002 = 1000 − 成功数`，无负值；
- `seckill:user:26002:*` 标记数 = 成功数（无重复购买）；
- 预扣失败码分布（SUCCESS / STOCK_EMPTY）与预期一致。

说明：本场景不经过 MQ 与订单（纯 Redis 原子性验证），订单侧正确性由 L-01/L-03 覆盖。

### 2.4 L-03 MQ 消费能力

持续生产三类消息，观测消费：

| 消息 | 生产量 | 消费观测 |
| --- | --- | --- |
| CREATE_ORDER | 5000（200 msg/s × 25s） | order 建单 TPS、消费延迟、堆积 |
| CANCEL_ORDER | 5000（对应已建单订单） | RECOVER 流水 TPS、收敛 |
| PAY_SUCCESS | 200（payment 发布） | 测试侧消费者接收 TPS（边界：无 order 消费端） |

断言：

- 重复消息只生效一次（幂等表/流水唯一）；
- 最终收敛：order 数 = CREATE_ORDER 数、DEDUCT 数 = 5000、RECOVER 数 = 5000、堆积 → 0；
- PAY_SUCCESS 只验证 payment 发布能力（消息可达、字段完整），不验证订单侧联动（已知边界 R-03）。

堆积观测：`ROCKETMQ.execInContainer("sh mqadmin consumerProgress ...")` 解析消费位点差；消费延迟 = 发送时间戳 → 订单/流水落库时间差（抽样）。

### 2.5 L-04 数据库压力

两部分：

1. **业务写入统计**：复用 L-01/L-03 运行期统计 `seckill_order`、`stock_flow`、`payment_order` 三表实际写入 TPS；
2. **原生写压力**：JDBC 多线程批量 INSERT（测试命名空间唯一键），测量三表写入 TPS、`Innodb_row_lock_waits` / `Innodb_row_lock_time`、慢 SQL 数（`mysql.slow_log`，`long_query_time=0.2s`）、CAS 冲突近似率（`inventory.version` 总增量 vs DEDUCT 流水数）。

约束：

- 禁止修改数据库结构（表、字段、约束、索引）；
- 写入数据全部使用测试命名空间，结束后清理；
- 慢 SQL 阈值与锁等待仅记录，不作为本阶段失败标准（环境基线）。

### 2.6 L-05 30 分钟稳定压测

混合负载持续 30 分钟（可配置 `-Dload.duration-minutes=30`）：

- 秒杀 execute 目标 200 QPS（库存 100000，独立用户轮换）；
- 每 30s 采样：JVM Heap（used）、GC 次数/耗时、活跃线程、MQ 堆积、Redis DBSIZE、订单/流水计数；
- 每 5 分钟做一次一致性抽查：`available + locked = total`、对账接口无 issue、随机订单状态合法。

结束断言：

- 零超卖、订单数 = 成功数；
- MQ 堆积收敛为 0；
- Heap used 结束值 ≤ 开始值 + 256MB（防内存泄漏粗检）；
- Redis key 增长仅限用户标记/会话等预期键（DBSIZE 增长与成功数一致）。

---

## 3. 压测工具要求

### 3.1 工具选型：**自定义 Java Executor 负载驱动**

三选一结论：采用基于既有 integration-test 基建的**自定义 Java 负载驱动**，理由：

1. **深度集成**：服务上下文（Spring Boot 随机端口）与 Testcontainers 均由测试进程内启动，自定义驱动可直接复用 `ServiceSupport`、`TestHttp`、真实 MQ 消费者与 JDBC/Redis 访问，避免 JMeter/Gatling 外部进程与容器端口、结果文件、JVM 指标采集的割裂；
2. **精确并发模型**：支持 CountDownLatch 同时放行、固定 QPS 注入（令牌桶节奏）、预热/测量窗口分离、背压控制，满足“禁止简单循环请求”；
3. **指标闭环**：同一进程内直接采集 JVM（Heap/GC/Thread）、请求延迟分位数与中间件状态，输出统一 JSON/CSV。

取舍说明：JMeter/Gatling 的脚本生态与分布式能力更强，留作 Phase 5.8 生产环境压测工具；本阶段输出格式（JSON 字段）与 JMeter CSV 字段对齐，便于后续替换。

### 3.2 负载驱动组件（规划）

`integration-test/src/test/java/com/seckill/integration/load/`：

| 组件 | 职责 |
| --- | --- |
| `LoadDriver` | 并发模型（固定并发 / 固定 QPS）、预热/测量窗口、背压控制 |
| `LoadMetrics` | 请求计数、错误码分布、RT 分位数（P50/P95/P99）、QPS/成功率聚合 |
| `LoadReportWriter` | 输出 `target/load-reports/{scenario}.json` + CSV + 控制台摘要 |
| `EnvironmentProbe` | JVM Heap/GC/Thread、进程 CPU、MySQL/RocketMQ/Redis 状态采样 |
| `MqBacklogProbe` | `mqadmin consumerProgress` 解析消费位点与堆积 |

### 3.3 指标输出格式

每个场景输出 JSON（字段固定，与 JMeter CSV 对齐）：

```json
{
  "scenario": "L-01",
  "requestTotal": 1000,
  "success": 100,
  "fail": 900,
  "qps": 1234.5,
  "avgRtMs": 42.1,
  "p50Ms": 35.0,
  "p95Ms": 88.2,
  "p99Ms": 120.5,
  "errorCodeDistribution": {"0": 100, "30004": 900},
  "inventory": {"total": 100, "available": 0, "locked": 100},
  "orderCount": 100,
  "mq": {"produced": 100, "consumed": 100, "backlog": 0},
  "consistency": "PASS"
}
```

---

## 4. 环境要求

### 4.1 中间件

继续复用 Phase 5.1 test-support：

| 中间件 | 版本 | 说明 |
| --- | --- | --- |
| MySQL | 8.0.36 | 5 schema，`slow_log`/状态变量采样 |
| Redis | 7.2.4 | 真实 Lua 执行 |
| RocketMQ | 5.3.1 | namesrv 19876 / broker 20911 |

### 4.2 环境指标记录

每个场景开始/结束各记录一次：

| 维度 | 采集方式 |
| --- | --- |
| CPU | 进程 `OperatingSystemMXBean`；容器 `docker stats` 采样（docker-java，失败则登记 R-01/R-02） |
| Memory | JVM `MemoryMXBean`（heap/non-heap）、容器内存统计 |
| Network | 容器网络收发字节采样（docker stats） |
| JVM Heap | `MemoryMXBean` 周期采样 |
| GC | `GarbageCollectorMXBean`（次数/耗时） |
| Thread | `ThreadMXBean`（活跃线程、峰值） |

### 4.3 执行方式

压测用例默认禁用（`@EnabledIfSystemProperty(named = "load.enabled", matches = "true")`），按场景独立执行：

```powershell
mvn -pl integration-test -am test "-Dtest=SeckillApiLoadTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dload.enabled=true"
```

30 分钟稳定压测额外传参：

```powershell
... "-Dtest=StabilityLoadTest" "-Dload.enabled=true" "-Dload.duration-minutes=30"
```

禁止与其他测试类同 JVM 混跑；涉及大并发/长时长的用例使用独立 JVM。

---

## 5. 必须输出指标（每个场景）

| 指标 | 来源 |
| --- | --- |
| 请求总量 / 成功 / 失败 | LoadMetrics |
| QPS / 平均RT / P95 / P99 | LoadMetrics |
| 库存 total / available / locked | Redis + MySQL 终态断言 |
| 订单数量 | seckill_order 计数 |
| MQ 生产 / 消费 / 积压 | 发送计数 + 落库计数 + consumerProgress |
| 一致性 | 五域口径 PASS/FAIL |

---

## 6. 风险登记

| 编号 | 风险 | 影响 | 对策 |
| --- | --- | --- | --- |
| R-01 | 机器资源不足导致压测数据失真 | QPS/RT 不代表真实容量 | 记录环境指标（CPU/内存/GC）作为基线；数值仅登记，不设生产门禁 |
| R-02 | Testcontainers 性能低于生产环境 | 容器化开销、单机限制 | 压测结果标注“环境基线”；生产门禁移交 Phase 5.8 |
| R-03 | PAY_SUCCESS 消费端未实现 | 无法验证订单支付联动 | 只验证 payment 发布能力（消息可达、字段完整）；订单联动继续登记 Phase 后续 |
| R-04 | Redis 无持久化 | 稳定性测试不覆盖持久化恢复 | 本阶段不注入 Redis 重启（Phase 5.4 已覆盖）；稳定性观察键增长与泄漏 |
| R-05 | 单机环境无法模拟百万级真实流量 | 10,000 QPS 等目标无法在本环境达成 | 压测规模取单机可承载值（1000 并发/10000 Lua 并发/5000 消息），生产环境 Phase 5.8 放大 |
| R-06 | 30 分钟压测触发测试数据膨胀 | 内存/磁盘增长、清理耗时 | 命名空间隔离 + 滚动清理；结束后全量清理并记录 DBSIZE/行数 |
| R-07 | 高并发下消费者 CAS 竞争 | 消费 TPS 下降、重试增多 | 观测锁等待/CAS 冲突率并记录；若出现系统性失败按 `fix(inventory)` 处理 |

---

## 7. 提交规范

### 7.1 设计阶段（本提交）

```text
docs(test): phase5.5 load test design confirmation
```

### 7.2 编码阶段（评审通过后，按场景拆分）

```text
test(load): add load framework and metrics support
test(load): add seckill api load test
test(load): add redis lua load test
test(load): add mq consumption load test
test(load): add database load test
test(load): add stability load test
```

### 7.3 生产缺陷

```text
fix(module): xxx
```

随后补验证提交；禁止测试代码与生产修复混合提交。

---

## 8. 验收标准

### 成功标准

1. L-01：1000 并发 / 库存 100，成功 ≤ 100、零超卖、订单数 = 成功数、MQ 60s 内收敛；
2. L-02：10000 并发 / 库存 1000，成功 ≤ 1000、Redis 无负值、用户标记数 = 成功数；
3. L-03：5000 条 CREATE_ORDER/CANCEL_ORDER 全部消费且幂等，堆积收敛为 0，PAY_SUCCESS 发布可达；
4. L-04：三表写入 TPS、锁等待、慢 SQL、CAS 冲突率全部记录且无结构性失败（死锁/重复键超预期）；
5. L-05：30 分钟混合负载零超卖、无泄漏粗检通过、MQ 收敛、一致性抽查 PASS；
6. 每场景输出完整 JSON 报告与环境指标。

### 失败标准

- 出现超卖、重复订单、重复流水、重复回补；
- 状态非法跳转被接受；
- MQ 消息丢失导致最终不一致且无冻结策略兜底；
- 为通过压测修改状态机、接口、数据库结构或扩大业务边界。

---

本设计确认文档提交信息：

`docs(test): phase5.5 load test design confirmation`
