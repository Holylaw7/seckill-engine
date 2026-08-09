# Seckill-Engine 简历描述（最终版）

> 版本：v3.0（2026-08-09，与代码/实测数据一致，可直接用于简历）

---

## 浓缩版（推荐投递，约 200 字）

**Seckill-Engine｜AI Agent 协同研发的高并发分布式秒杀交易系统**（分布式微服务 / 核心开发 · Java 21 · Spring Boot 3.2 · MySQL · Redis · RocketMQ）

主导六服务秒杀系统架构、实现与全链路验证：基于 ADR 冻结服务边界与一致性方案，AI Agent 辅助编码、人负责核心决策与 Review；220+ 可审计提交、365 单测、22 集成、6 故障演练全绿。

- **防超卖**：Redis Lua 原子预扣减 + 库存分桶 N=8 解除单行锁热点，E2E 10000 请求**零超卖、零死锁**，Redis==SUM(bucket)==MySQL；
- **最终一致**：RocketMQ 事务消息 + stock_flow 唯一键幂等 + RECOVER 回补，取消后 Redis/MySQL 一致，灰度回滚 **RTO<5min**；
- **容量与工程**：入口 741 QPS / Gateway 700-900 QPS / Lua 1847 QPS / Canary 113 万请求 error=0；Canary 5→100 自动回滚、Prometheus/Grafana、CI 门禁、Docker 一键演示。

数据为隔离环境实测（报告归档），生产级验证如实标注 PENDING，可现场演示登录→秒杀→防重→支付→取消回补全链路。

---

## 完整版

## 简历条目

**Seckill-Engine｜AI Agent 协同研发的高并发分布式秒杀交易系统**（分布式高并发微服务 / 核心开发）

面向瞬时高并发、库存强一致和最终一致性场景，主导完成系统架构设计、技术方案制定、任务拆解、代码审查及全链路验证，交付 Gateway / auth / seckill / order / inventory / payment 六服务分布式秒杀系统（Java 21 + Spring Boot 3.2 + MySQL + Redis + RocketMQ）。

### 核心工作与量化成果

**1. 架构设计与 AI Agent 协同工程**

- 基于 ADR（Architecture Decision Record）拆解服务边界、状态机与一致性方案（分桶、事务消息、幂等流水等 8+ 关键决策均有冻结文档）；
- AI Agent 辅助模块实现与任务拆解，人负责核心技术决策、代码 Review 与工程验证；
- 全流程产出 220+ 可审计提交、10K+ 行设计/测试/发布文档，支持 Docker 一键部署与端到端演示。

**2. 库存一致性：防超卖**

- 设计"Redis Lua 原子预扣减 + 异步落库"架构，将库存校验、扣减、用户防重封装为单个原子脚本；
- 设计**库存分桶 N=8**（inventory_bucket + bucket 行锁 + 对账），解除单 SKU 行锁热点，分桶消费实测 360 QPS；
- 完成 E2E 10000 成功请求全链路验证：**零超卖、零死锁**，Redis == SUM(bucket) == MySQL。

**3. 最终一致性：事务消息 + 幂等 + 恢复**

- 设计 RocketMQ 事务消息保障预扣减与消息发送原子性；
- 设计 `stock_flow` 唯一键幂等模型（重复消息只生效一次）+ RECOVER 幂等回补流程；
- 验证取消/超时回补后 Redis/MySQL 最终一致，Gateway 灰度 100→0 回滚 **RTO<5min**（实测毫秒级）。

**4. 自动化验证与容量工程**

- 建立 365 单元测试、22 集成测试类（真实 MySQL/Redis/RocketMQ）、6 类故障演练、压测资产与 CI 五阶段门禁；
- 隔离拓扑实测：入口 **741 QPS**（L-01）、单 Gateway 安全容量 **700-900 QPS**、Redis Lua **1847 QPS**、Canary 窗口 **113 万请求 error=0**；
- 建立 Canary 灰度体系（5→25→50→100，WARNING 暂停 / CRITICAL 自动回滚）、Prometheus/Grafana 可观测与依赖扫描门禁。

### 技术栈

Java 21 / Spring Boot 3.2 / Spring Cloud Gateway 4.1 / MyBatis-Plus / MySQL 8.0 / Redis 7.2（Lua）/ RocketMQ 5.3（事务消息）/ Testcontainers / Maven / GitHub Actions。

### 量化证据

```
生产代码 7.4K 行 Java（六服务）+ 测试代码 17.3K 行（2.3 倍于生产）
365 单测 / 22 集成 / 6 故障演练 / 220+ 提交 / 10K+ 行文档
入口 741 QPS / Gateway 700-900 QPS / Lua 1847 QPS / 分桶 360 QPS
E2E 10000 零超卖零死锁 / Canary 113 万请求 error=0 / RTO<5min
```

### 面试口径（诚实声明）

- 容量与一致性数据为开发机 + Testcontainers 隔离环境实测（全部有报告与 JSON 归档）；
- 生产级验证（独立 Load Generator / 生产规格 RocketMQ / 真实 CI）在仓库中如实标注 PENDING，未虚报；
- 可现场演示：Docker 一键部署 + 登录→秒杀→防重→支付→取消回补全链路（demo-script.md）。

---

## 与 v3 草案的差异说明

| v3 草案 | 最终版 |
| --- | --- |
| "Gateway 741 QPS" | 精确为：入口 L-01 741 QPS + 单 Gateway 安全容量 700-900 QPS |
| "E2E 10000 请求零超卖" | 补充零死锁、Redis==SUM(bucket)==MySQL、分桶 360 QPS |
| 未提可演示/回滚 | 补充 RTO<5min、Docker 一键部署、端到端演示脚本 |
| 未提代码规模 | 补充 7.4K 生产 / 17.3K 测试 / 220+ 提交 |
