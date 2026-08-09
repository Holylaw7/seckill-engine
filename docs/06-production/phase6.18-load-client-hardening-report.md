# Phase 6.18 Load Client Hardening Report

> Phase 6.14/6.15 暴露问题：Windows ephemeral port exhaustion

## Before

```
现象：L-08 收敛阶段 java.net.BindException: Address already in use: connect
原因：压测 execute 使用默认 RestTemplate（短连接）+ 收敛轮询每 500ms 新建 JDBC 连接，
      Windows 动态端口范围（49152-65535）被 TIME_WAIT 占满
```

## After（已实施）

```
1. execute 客户端：JdkClientHttpRequestFactory（JDK HttpClient 连接复用）；
2. execute 调用：CompletableFuture 30s 强制超时（防挂起）；
3. 收敛轮询：单一 JDBC 连接复用（countOn(connection, sql)），不再每轮新建连接；
4. 未降低请求规模，未跳过 L-08。
```

## 验证（2026-08-09 L-08 1000 档）

```
success=1000 / zeroOversell=true / deadlocks=0 / backlog=0
duplicateSafe=true / recoverSample=50 / converge=1010ms
codeDistribution：{"0":1000}（无异常、无端口耗尽）
数据：docs/06-production/capacity/L-08-2026-08-09-1000.json
```

**连接复用加固生效：压测与收敛阶段均未再出现端口耗尽。**
