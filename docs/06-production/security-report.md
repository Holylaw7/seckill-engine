# Phase 6.5.7 Security Report

## 1. JWT

- 缓存范围复核：仅 ThreadLocal<Mac> + SecretKeySpec（解析器缓存）；**未缓存 claims/roles/permission**；✅
- 验签（constant-time）+ 过期校验保持；黑名单每请求 MGET；✅

## 2. Gateway

- 黑名单 fail-open：Redis 不可用时请求穿透（H-05 F-02 实测 status=200 非 403）；✅
- 限流：SCG RedisRateLimiter Lua 未修改（语义冻结）；限流回归（放行/拒绝精确）PASS；✅

## 3. Dependency Vulnerability Scan

- 执行：`mvn org.owasp:dependency-check-maven:10.0.4:check`；
- 结果：**扫描未能在本环境完成**（NVD 数据库下载超时，15min 无报告产出）；
- 降级措施（已执行）：冻结版本清单人工复核（Spring Boot 3.2.5 / Spring Cloud 2023.0.1 / MyBatis-Plus 3.5.7 / Redisson 3.27.2 / RocketMQ 5.2.0 / rocketmq-spring 2.3.1 / Nacos 2.3.2 / Testcontainers 1.21.4）；
- 生产前置：CI 接入 dependency-check（失败门禁 CVSS≥7），发布前完成 NVD 在线扫描并留档。

## 4. 内部接口（生产前置）

| 接口 | 风险 | 缓解 |
| --- | --- | --- |
| /api/v1/seckill/internal/* | 中 | 内网隔离 + mTLS/ACL |
| /api/v1/inventory/admin/reconcile/repair | 高 | 上线前接入管理员鉴权（Phase 7 完善） |

## 5. 结论

应用层安全语义（JWT/黑名单/限流）无变化；依赖漏洞扫描为**生产 Launch 前置项**（本环境未能完成 NVD 下载，CI 接线后执行）。
