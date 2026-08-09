# Phase 6.22 Load Generator Final Readiness

## Before（Phase 6.14/6.15 实测）

```
Windows ephemeral port exhaustion：
  L-08 收敛阶段 java.net.BindException: Address already in use: connect
  根因：execute 短连接 + 收敛轮询每轮新建 JDBC 连接，TIME_WAIT 占满动态端口
```

## After（Phase 6.18 已实施并验证，2026-08-09 14:48 当前实证）

```
connection reuse：      execute 改用 JdkClientHttpRequestFactory（JDK HttpClient 复用）
HTTP client pool：      JDK HttpClient 连接复用
ephemeral port usage：  压测与收敛阶段不再新建短连接（收敛轮询复用单一 JDBC 连接）
concurrency model：     SustainedLoadExecutor 固定并发 + 目标达成早停
JVM memory：            surefire 独立 JVM（-Xmx 由 surefire 配置）
验证：L-08 1000 档 PASS（success=1000，converge=1010ms，无端口耗尽）
证据：docs/06-production/capacity/L-08-2026-08-09-1000.json
```

**状态：客户端端口耗尽已消除（单机实证）；独立 Load Generator 主机仍 NOT SATISFIED**
