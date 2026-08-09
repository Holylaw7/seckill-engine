# RocketMQ Independent Capacity Report

> Phase 6.14 Task 2 — RocketMQ 独立容量环境

## 拓扑资产（已实现）

`RocketMqIndependentTopology`：namesrv 与 broker **各自独立容器**（禁止单容器），
broker 配置：独立网络 alias、1GB G1GC 堆、send/pull 线程池 64、store/dispatch 32、
advertise 地址 127.0.0.1:{映射端口}。

## 实测（2026-08-09，单机 Docker）

| 拓扑 | total | sent | failed | failure | sendTPS | txP99 | consumeTPS | converge |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 单容器（基类） | 5000 | 3848 | 1152 | 23.0% | 908 | 312ms | 273 | 14.1s |
| 独立双容器（无预热） | 5000 | 1510 | 3490 | 69.8% | 160 | 358ms | 172 | 8.8s |
| 独立双容器（线程池调整后） | 5000 | 961 | 4039 | 80.8% | 101 | 385ms | 82 | 11.7s |
| 独立双容器（+预热等待路由） | 5000 | — | 全部失败 | 100% | — | — | — | 预热 60s 路由未就绪 |

## 分析

- 单机 Docker 双容器共享主机资源，性能反而低于单容器；
- broker boot success 后注册 namesrv 的路由在双容器网络下 60s 未就绪（预热断言失败）；
- 结论：**单机 Docker 无法达到生产规格 RocketMQ 的 transaction failure=0**。

## 状态

```
MQ Stability = PENDING（生产规格 RocketMQ 未验证；独立拓扑资产已就绪，
              需真实多机/生产规格环境执行）
```
