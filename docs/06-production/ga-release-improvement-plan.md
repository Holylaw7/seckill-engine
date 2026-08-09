# GA Release Improvement Plan（审计稿）

> 版本：v1.0（供审计，未进入执行）
> 依据：Phase 6.10～6.23 全链路审计（2026-08-09）
> 结论：系统能力与验证资产已就绪；剩余阻塞全部来自外部生产验证资源缺失，
>       以及 1 项测试工具残余风险。方案按"资源供给 → 执行闭环 → 工程微调"分层。

---

## 1. 当前阻塞根因（审计结论）

| 阻塞 | 根因分类 | 是否外部依赖 |
| --- | --- | --- |
| BLOCK-01 Dependency Scan | 无 remote origin / GitHub Actions | ✅ 外部 |
| BLOCK-02 Production Canary | 无生产数据中心 | ✅ 外部 |
| BLOCK-03 E2E 50000 | 无独立 Load Generator + 生产 MQ | ✅ 外部 |
| BLOCK-MQ | 无生产规格 RocketMQ（单机 failure>0） | ✅ 外部 |
| BLOCK-04 Operations Sign-off | 无生产 Owner | ✅ 外部 |

工程侧已闭环：一致性模型、N=8 分桶、Auth 登录卡顿、RocketMQ 通告端口、
压测客户端端口耗尽（L-08 1000 档实证 PASS）——均无需再开发。

## 2. 改进方案

### A. 资源供给层（阻断项，需外部动作）

| 序号 | 动作 | 目标证据 | 关闭 Gate |
| --- | --- | --- | --- |
| A1 | 创建远程仓库并推送 `release/RC1`，启用 GitHub Actions | workflow id / commit SHA / timestamp / artifact URL / dependency report（CVSS>=7 FAIL） | BLOCK-01 |
| A2 | 提供独立 Load Generator（建议 ≥4 核/8GB 独立容器或机器；客户端已具备连接复用与 30s 超时） | hostname / cpu / memory / network / 无端口耗尽 | 环境准入 + BLOCK-03 前置 |
| A3 | 提供生产规格 RocketMQ（独立 Namesrv + Broker，生产配置） | probe 50000 消息/100 并发：send failure=0 / DLQ=0 / backlog→0 | BLOCK-MQ |
| A4 | 生产数据中心部署 RC1（分桶 N=8 开启） | 5%→25%→50%→100% 窗口，每档 ≥30min 或 ≥10000 requests | BLOCK-02 |
| A5 | 指派并签署 Release / SRE / Database / Rollback Owner | 四份 sign-off（含发布窗口、回滚 RTO、监控告警、备份恢复） | BLOCK-04 |

### B. 执行闭环层（已冻结，资源到位后按序启用）

1. T-0 清单逐项确认（phase6.20-ga-validation-t0-checklist.md）；
2. RocketMQ 生产容量验证（phase6.22-rocketmq-execution-package.md）；
3. E2E 50000（phase6.20-e2e50000-trigger-package.md，失败按 6 类分类定位）；
4. Dependency Scan 证据；5. Production Canary；6. Operations Sign-off；7. GA Decision。

### C. 工程微调（不阻塞 GA 前提，建议同步完成）

| 项 | 内容 | 优先级 |
| --- | --- | --- |
| C1 | 将"收敛轮询单一 JDBC 连接"模式推广到 IntegrationTestBase（其余测试复用），消除残余端口风险 | P2 |
| C2 | RocketMqCapacityProbeIT 生产端点注入（namesrv/broker 地址已可配置化，无需改动） | 已完成 |
| C3 | CI 依赖扫描报告归档（ci.yml 已接线，待远程运行） | 已完成 |

## 3. 执行顺序与验收

```
A1(CI) → A2(Load Gen) → A3(MQ) → E2E 50000 → A4(Canary) → A5(Sign-off) → GA
```

每项验收：

- A1：GitHub Actions dependency-scan GREEN + artifact 存在；
- A2：Load Generator 独立主机/容器证据 + 压测无端口耗尽；
- A3：probe 50000/100 并发 send failure=0 / DLQ=0 / backlog→0；
- E2E：success=50000 / oversell=0 / deadlock=0 / inventory_diff=0 / duplicate safe / recover PASS；
- A4：5→25→50→100% 每档 Health Gate PASS（WARNING PAUSE / CRITICAL ROLLBACK）；
- A5：四项 Owner SIGNED。

## 4. 风险与回滚

- 独立环境 E2E 失败：按失败分类（MQ Infrastructure / Load Generator / Critical Business /
  Inventory / Database / Order Consistency）定位；不修改业务模型，除非发现真实生产缺陷（需评审）；
- CI 扫描 CVSS>=7：依赖升级或风险接受评审（阻断发布）；
- Canary CRITICAL：AUTO ROLLBACK 至上一稳定档，RTO<5min（已验证）。

## 5. 审计建议

1. 批准 A1～A5 资源供给责任分配（需外部资源方确认）；
2. 批准 C1 工程微调（测试基础设施，不涉及业务逻辑）；
3. 资源到位后按 T-0 执行；任何 Gate 未 PASS 保持 RC1 STABLE。

---

**结论：改进路径明确，无代码正确性阻塞；GA 放行取决于外部资源到位后的真实验证结果。**
