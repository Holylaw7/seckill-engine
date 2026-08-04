# Seckill-Engine 测试执行指南

版本：v1.0
分支：feature/phase5-test

---

## 1. 测试分类

| 分类 | Tag | 位置 | 说明 |
| --- | --- | --- | --- |
| 单元测试 | `unit` | 各模块 `src/test/java`（与生产包同构） | Mockito 单测，不依赖外部中间件 |
| 集成测试 | `integration` | `integration-test/.../integration/` | Testcontainers 真实 MySQL/Redis/RocketMQ 业务链路 |
| 故障演练 | `chaos` | `integration-test/.../chaos/` | 停容器/权限注入/服务下线等故障场景 |
| 压测 | `load` | `integration-test/.../load/` | L-01~L-05，`@LoadTest` 门控 |
| 测试支撑 | - | `integration-test/.../support/` | 容器、HTTP、数据、MQ 工具类 |

默认 `mvn test` 执行 `unit + integration`，通过 Surefire `excludedGroups=chaos` 排除故障演练；
压测类通过 `@EnabledIfSystemProperty(load.enabled=true)` 默认跳过。

---

## 2. 执行方式

### 2.1 单元测试

```powershell
mvn test
```

预期：237 个单元测试全绿。

### 2.2 集成测试（默认）

```powershell
mvn -pl integration-test -am test
```

预期：17 个集成测试（integration 包）+ 4 个框架冒烟全绿；chaos 被默认排除。

### 2.3 故障演练（chaos，需显式放开）

```powershell
# 放开 chaos 排除后运行全部（单元 + 集成 + 演练）
mvn test "-Dchaos.excluded="

# 或单独运行某个演练类
mvn -pl integration-test -am test "-Dtest=RedisFaultDrillIT" "-Dchaos.excluded="
```

预期：13 个演练场景全绿（R-01~R-03 / M-01~M-03 / Q-01~Q-04 / S-01~S-03）。
注意：涉及停容器的演练类建议独立 JVM 运行，避免与其他测试混跑。

### 2.4 压测（load，需显式开启）

```powershell
# L-01 秒杀接口压测
mvn -pl integration-test -am test "-Dtest=SeckillLoadTest" "-Dload.enabled=true"

# L-02 Redis Lua 原子性
mvn -pl integration-test -am test "-Dtest=RedisLuaAtomicityLoadTest" "-Dload.enabled=true"

# L-03 MQ 消费能力
mvn -pl integration-test -am test "-Dtest=MqConsumerLoadTest" "-Dload.enabled=true"

# L-04 数据库压力
mvn -pl integration-test -am test "-Dtest=DatabaseLoadTest" "-Dload.enabled=true"

# L-05 稳定性（开发 1 分钟 / 正式 30 分钟）
mvn -pl integration-test -am test "-Dtest=StableLoadTest" "-Dload.enabled=true" "-Dload.duration-minutes=1"
mvn -pl integration-test -am test "-Dtest=StableLoadTest" "-Dload.enabled=true" "-Dload.duration-minutes=30"
```

压测报告输出：`integration-test/target/load-reports/*.json|csv`（目录可用 `-Dload.report.dir` 覆盖）。

---

## 3. 环境要求

- JDK 21 LTS（本机 `E:\Java\microsoft-jdk-21`）；
- Maven 3.9+；
- Docker Desktop（集成/演练/压测必须，Testcontainers 自动拉起容器）；
- 禁止使用本机开发库/本地 Redis/RocketMQ 实例；
- 执行前确认工作区干净（`git status`）。

## 4. Testcontainers 说明

基础容器由 `test-support` 冻结：

| 中间件 | 镜像 | 端口 |
| --- | --- | --- |
| MySQL | mysql:8.0.36 | 动态映射 |
| Redis | redis:7.2.4 | 动态映射 |
| RocketMQ | apache/rocketmq:5.3.1 | namesrv 19876 / broker 20911（固定映射） |

- 容器为类级单例，同一 JVM 内复用；
- 涉及停容器/停服务的演练类独立运行；
- Redis 无持久化：重启后热点库存键丢失，需按测试命名空间重新预热。

## 5. 覆盖率与发布检查

```powershell
# 生成 JaCoCo 报告（各模块 target/site/jacoco/）
mvn test
mvn jacoco:report

# Release Check（git 状态 / surefire 结果 / 覆盖率门禁 / 版本信息）
bash scripts/release-check.sh
```

输出：`docs/04-测试体系/coverage-report.md`、`docs/04-测试体系/release-check-report.md`。

---

## 6. 质量门禁摘要

| 门禁 | 要求 |
| --- | --- |
| G-01 单元测试 | failures=0 |
| G-02 集成测试 | failures=0（含 chaos 放开后 30 项） |
| G-03 库存一致性 | available + locked = total |
| G-04 超卖 | 成功订单 ≤ 库存 |
| G-05 MQ | backlog=0 |
| G-06 幂等 | 重复消息只生效一次 |
| G-07 稳定性 | 30 分钟无持续错误增长、Heap 无泄漏趋势 |

详细门禁定义见《docs/04-测试报告/phase5.6测试报告与质量门禁.md》。
