# Load Generator Environment Report

> Phase 6.14 Task 3 — 独立 Load Generator 条件

## 本环境现状

```
Load Generator:  Maven/surefire JVM（与业务服务共享单机 Windows）
CPU:             20 逻辑核（全部组件共享）
Memory:          32GB（空闲 ~16GB，全部组件共享）
Network:         回环（localhost）
Load TPS:        受单机资源限制（L-08 5000 实测客户端约 10-17 有效 QPS）
```

## 强制项对比

| 要求 | 实际 | 满足 |
| --- | --- | --- |
| 独立机器或独立容器 | 无（单机 Windows） | ❌ |
| 不与业务 JVM 共享 CPU/Memory | 共享 | ❌ |
| 独立执行压测客户端 | surefire 独立 JVM（进程隔离） | ⚠️ 部分 |

## 已知客户端瓶颈（2026-08-09 实测）

- Windows 临时端口耗尽：L-08 5000 收敛阶段 `BindException: Address already in use: connect`
  （动态端口范围被 TIME_WAIT 占满）→ 收敛断言失败；
- 已缓解：RealCanaryWindowIT 使用 JDK HttpClient 连接复用（113 万请求无端口问题）；
  L-08 客户端仍需连接复用改造（登记为待办）。

## 状态

**Load Generator 独立条件 = NOT SATISFIED（本环境）**；独立验证需真实独立机器/容器执行。
