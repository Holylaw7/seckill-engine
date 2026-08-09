# Phase 6.18 External Validation Environment Contract

> 原则：Unknown != PASS；Not executed != PASS；环境不足 = NOT SATISFIED

## Required Environment Matrix

| Component | Requirement | Current | Status |
| --- | --- | --- | --- |
| Load Generator | independent machine/container | 单机 Windows | TBD（NOT SATISFIED） |
| CPU/MEM isolation | no sharing | 共享 20 核/32GB | TBD（NOT SATISFIED） |
| Network isolation | independent egress | 回环共享 | TBD（NOT SATISFIED） |
| Ephemeral ports | no exhaustion | 已通过客户端加固缓解（L-08 1000 档 PASS） | ⚠️ 待独立环境确认 |
| Gateway | independent JVM | 独立 JVM 资产 | PASS（进程级） |
| Business services | independent JVM | 独立 JVM 资产 | PASS（进程级） |
| Redis | independent instance | 单机 Testcontainers | TBD |
| MySQL | independent instance | 单机 Testcontainers | TBD |
| RocketMQ Namesrv | production topology | 无（单机测试容器） | TBD |
| RocketMQ Broker | production topology | 无（单机测试容器） | TBD |
| CI | GitHub Actions evidence | 无 remote origin | TBD |
| Production DC | Canary capability | 无 | TBD |
| Operation Owner | Sign-off | 无生产团队 | TBD |

## 契约结论

环境契约未满足；所有 TBD 项在真实资源到位前保持 NOT SATISFIED，
不构成 PASS 依据。
