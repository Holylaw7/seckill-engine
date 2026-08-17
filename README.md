# Seckill-Engine

高并发秒杀交易系统：面向瞬时高并发请求，提供**防超卖、最终一致性、可回滚、可观测**的分布式秒杀能力。

> 版本：`0.1.0-RC1`（分支 `release/RC1`）
> 定位：个人简历项目——完整实践高并发交易系统的架构、工程与发布验证体系。

---

## 一、系统架构

```text
                 ┌────────────────────────────────────────────┐
   HTTP          │                 Gateway (8080)              │
   ─────────────▶│  TraceId → JWT → 黑名单 → 限流 → Canary(5%)  │
                 └──────┬──────────┬──────────┬───────────────┘
                        │          │          │
              ┌─────────▼──┐  ┌────▼─────┐  ┌▼──────────┐
              │ auth(8081) │  │seckill   │  │ order     │
              │            │  │(8082)    │  │ (8083)    │
              └────────────┘  │ Redis Lua│  └────┬──────┘
                              │ 预扣+防重 │       │
                              └────┬─────┘       │
                                   │ RocketMQ 事务消息
                                   ▼             ▼
                        ┌──────────────────────────────────┐
                        │ inventory (8085)  分桶 N=8        │
                        │ bucket 行锁 + stock_flow 幂等流水  │
                        └──────────────────────────────────┘
                         payment (8084) / MySQL / Redis / RocketMQ
```

**核心链路**：

```text
execute ─▶ Gateway(鉴权/限流/Canary) ─▶ seckill(Redis Lua 原子扣减+防重)
        ─▶ RocketMQ 事务消息 ─▶ order(建单 WAIT_PAY) ─▶ inventory(DEDUCT 分桶+流水)
        ─▶ payment(创建支付/回调验签) ─▶ PAY_SUCCESS ─▶ order(PAY_SUCCESS)
        ─▶ 取消/超时 ─▶ RECOVER 回补（幂等）
```

**一致性设计**：

- Redis Lua 原子扣减，`seckill:user:{sku}:{uid}` 防重 → 杜绝超卖与重复购买；
- `inventory_bucket` 分桶（N=8）解除单 SKU 行锁热点；
- `stock_flow` 唯一幂等（biz_type + biz_id）→ 重复消息只生效一次；
- MySQL 为最终库存事实源，`Redis.available == SUM(bucket.available) == inventory.available` 由对账校验；
- `available_stock + locked_stock == total_stock` 作为库存不变量；
- Redis 丢失可按 MySQL `available_stock` 预热恢复，故障可 repair（REPAIR 流水留痕）；
- payment 回调完成验签、时间窗和金额校验后发布 `PAY_SUCCESS`，order-service 以 `paymentNo` 幂等消费并写入 `paid_at`。

---

## 二、技术栈

| 组件                 | 版本          | 用途                             |
| -------------------- | ------------- | -------------------------------- |
| JDK                  | 21 LTS        | 运行与编译                       |
| Spring Boot          | 3.2.5         | 微服务框架                       |
| Spring Cloud Gateway | 4.1.2         | 网关：JWT/限流/黑名单/Canary     |
| MyBatis Plus         | 3.5.7         | ORM                              |
| MySQL                | 8.0.36        | 业务数据落库                     |
| Redis                | 7.2.4         | 库存预扣、防重、限流             |
| RocketMQ             | 5.3.1         | 事务消息、最终一致性             |
| Testcontainers       | 1.21.4        | 集成测试真实中间件               |
| Maven / JaCoCo       | 3.9.x / 0.8.x | 构建与覆盖率门禁                 |
| GitHub Actions       | —             | 5 阶段 CI + dependency-scan 门禁 |

---

## 三、关键指标（实测证据）

| 指标         | 结果                                                |
| ------------ | --------------------------------------------------- |
| 单元测试     | 365 个，0 failure / 0 error                         |
| 集成测试     | 22 个 IT 类（真实 MySQL/Redis/RocketMQ）            |
| 故障演练     | 6 类（Redis/MySQL/MQ/服务）                         |
| Gateway 容量 | 安全档约 727.90 QPS（200 并发，p99=390ms，error=0） |
| Redis Lua    | 1847.57 QPS（单机 Testcontainers 基线）             |
| 分桶 N=8     | 约 360.18 QPS，5000 条约 13.9s 收敛                |
| E2E 10000    | 历史 L-07 实跑 PASS（零超卖 / 零死锁 / 幂等 / 恢复） |
| Canary 窗口  | 113 万请求 / 5% 分流 4.97% / error=0                |
| 回滚 RTO     | Gateway 100→0 < 5min（实测毫秒级）                  |
| 提交数       | 220+ commits（单一职责、可审计）                    |

> 以上容量数字来自隔离拓扑或本机 Testcontainers 压测，不能直接等价为生产容量。
> 当前回归重点是支付回调闭环和 Redis 丢失恢复，未重新执行容量压测，未补填 CPU、内存、Redis QPS 时间序列或 MQ TPS。

---

## 四、目录结构

```text
seckill-engine
├── gateway          # API 网关（JWT/限流/黑名单/Canary）
├── auth-service     # 认证（登录/JWT/风险控制）
├── seckill-service  # 秒杀（Redis Lua 预扣/防重/MQ 事务）
├── order-service    # 订单（建单/超时关闭/取消）
├── inventory-service# 库存（分桶扣减/恢复/对账/repair）
├── payment-service  # 支付（创建/回调/退款）
├── seckill-common   # 公共组件（结果码/异常/安全签名/Canary 评估）
├── test-support     # 集成测试容器基座
├── integration-test # 集成/压测/混沌/演练
├── sql              # 版本化建库脚本（V1.0 + V2/V3 分桶）
├── docker           # Docker 一键部署（compose + Dockerfile）
├── scripts          # release-check.sh 等运维脚本
└── docs             # 全阶段文档（需求/架构/设计/测试/性能/发布）
```

---

## 五、快速开始

### 方式一：本地测试（不需要 Docker 也可跑单测）

```bash
export JAVA_HOME=<JDK21>
mvn clean test                         # 单元 + 默认集成套件
mvn -pl integration-test -am test      # 真实中间件集成（需 Docker）
```

### 方式二：Docker 一键部署（全链路演示）

```bash
docker compose -f docker/docker-compose.yml up -d --build
# 首次构建需联网拉取 Maven 依赖与镜像
```

可选：复制 `.env.example` 为 `.env`，替换数据库密码、JWT、内部接口和 Canary 控制密钥。
Compose 默认创建 MySQL 数据卷、Redis AOF 数据卷和 RocketMQ broker store/logs 数据卷；
`docker compose down` 保留数据，`down -v` 才会清空数据。

启动后：

```text
Gateway    http://localhost:8080
MySQL      127.0.0.1:3306（root/由 MYSQL_ROOT_PASSWORD 决定，默认 seckill-root）
Redis      127.0.0.1:6379
RocketMQ   9876（namesrv）/ 10911（broker）
```

默认种子：场次 30001、SKU 20001、库存 1000（README 演示用），
登录用户由 test-data 脚本提供（见 docs/06-production/operations-runbook.md）。

### 方式三：压测（需独立 Load Generator，详见操作手册）

```bash
mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest \
    -Dload.enabled=true -Dl08.success-target=50000
```

---

## 六、GA 状态（诚实标注）

| Gate                  | 状态        | 说明                                              |
| --------------------- | ----------- | ------------------------------------------------- |
| Inventory Consistency | ✅ PASS     | 零超卖、Redis==MySQL、不变量                      |
| Monitoring / Rollback | ✅ PASS     | Prometheus + RTO<5min                             |
| Dependency Scan       | ⏳ PENDING  | 需远程 GitHub Actions 实际证据                    |
| E2E 50000             | ❌ NOT PASS | 需独立 Load Generator + 生产 MQ（个人环境未具备） |
| MQ Stability          | ⏳ PENDING  | 需生产规格 RocketMQ                               |
| Production Canary     | ⏳ PENDING  | 需生产数据中心流量窗口                            |
| Operations Sign-off   | ⏳ PENDING  | 需运营 Owner 签署                                 |

**结论：当前代码已完成支付成功到订单状态的闭环，并通过对应单测和集成回归；
剩余门禁主要是独立容量、生产规格 MQ、真实生产 Canary、依赖扫描和运营签核。**
这些条件不能由本地 Docker 测试替代。个人项目可按 [A1-A5 执行指南](docs/06-production/personal-project-a1-a5-execution-guide.md)
完成可演示部署，但不应据此宣称生产 GA。

---

## 七、已知限制

- `REFUND_SUCCESS` 到 order-service `REFUND` 的事件闭环尚未接入，退款仍是后续工作；
- Docker 演示环境已开启 Redis AOF `everysec`，但 Redis 仍不是库存最终事实源；全量丢失仍必须按 MySQL `available_stock` 预热、对账后放量；
- recover 契约无 userId（登记项）；
- 混沌全量同 JVM 连续执行存在 MQ 收敛级联伪影（生产演练按类隔离执行）；
- 演示部署使用 root 账号与演示密钥，生产必须替换。

---

## 八、文档导航

| 文档           | 位置                                                                                 |
| -------------- | ------------------------------------------------------------------------------------ |
| 需求/架构/设计 | `docs/01-需求分析`、`docs/02-架构设计`、`docs/03-详细设计`                           |
| 测试报告       | `docs/04-测试报告`、`docs/04-测试体系`                                               |
| 性能优化       | `docs/05-性能优化`（Phase 6.0-6.5 报告、性能证据矩阵）                                  |
| 生产/发布      | `docs/06-production`（GA 审计、Canary、回滚、操作手册）                              |
| Release        | `docs/07-release`                                                                    |
| 证据索引       | [docs/06-production/INDEX.md](docs/06-production/INDEX.md)                           |
| 操作手册       | [docs/06-production/operations-runbook.md](docs/06-production/operations-runbook.md) |
