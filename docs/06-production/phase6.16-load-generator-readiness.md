# Load Generator Readiness（Phase 6.16 Task 2）

## 检查

| 条件 | 本环境实测 | 判定 |
| --- | --- | --- |
| 独立机器或独立容器 | 无（单机 Windows） | ❌ |
| 独立 CPU/MEM | 与业务共享 20 核/32GB | ❌ |
| 独立网络 | 回环共享 | ❌ |
| 不运行业务 JVM | surefire 独立 JVM（进程隔离） | ⚠️ 部分 |
| 无 ephemeral port exhaustion | 8/9 实测收敛阶段 BindException（端口耗尽） | ❌ |

```
OS:       Windows 10
Runtime:  JDK 21 / Maven 3.9.16 / surefire JVM
CPU:      20 逻辑核（共享）
Memory:   32GB（共享）
Network:  localhost 回环
```

**Load Generator = NOT SATISFIED → BLOCK-03 保持 NOT PASS**
