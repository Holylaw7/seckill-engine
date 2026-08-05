# Phase 6.5.2 RC1 Build Report

> 版本：`0.1.0-RC1`
> 分支：`release/RC1`（从 `phase6.5` 冻结）
> 生成时间：2026-08-05

## 1. Build Manifest

| 项 | 值 |
| --- | --- |
| Version | 0.1.0-RC1 |
| Git commit | `79ed9b6`（冻结基线；RC 分支最终 HEAD 以 `git rev-parse HEAD` 为准） |
| Branch | phase6.5 → release/RC1 |
| 构建命令 | `mvn clean verify -DskipTests`（生产制品）/ `mvn clean test`（门禁） |
| JDK | Java 21（microsoft-jdk-21） |
| 打包 | Spring Boot fat jar（各服务模块） |

## 2. 依赖清单（冻结版本）

| 依赖 | 版本 |
| --- | --- |
| Spring Boot | 3.2.5 |
| Spring Cloud | 2023.0.1 |
| Spring Cloud Alibaba | 2023.0.1.0 |
| MyBatis-Plus | 3.5.7 |
| Redisson | 3.27.2 |
| RocketMQ client | 5.2.0 |
| rocketmq-spring-boot-starter | 2.3.1 |
| Nacos client | 2.3.2 |
| Testcontainers | 1.21.4 |
| JaCoCo | 0.8.12 |

## 3. 运行环境（中间件）

| 中间件 | 版本 |
| --- | --- |
| Redis | 7.2.4 |
| MySQL | 8.0.36 |
| RocketMQ | 5.3.1 |

## 4. Docker Image Tag（发布目标）

| 服务 | Tag |
| --- | --- |
| gateway | seckill-gateway:0.1.0-RC1 |
| auth-service | seckill-auth:0.1.0-RC1 |
| seckill-service | seckill-seckill:0.1.0-RC1 |
| order-service | seckill-order:0.1.0-RC1 |
| inventory-service | seckill-inventory:0.1.0-RC1 |

镜像构建与推送在 Production Launch 阶段执行（本阶段只冻结 manifest）。

## 5. 包含阶段

Phase 6.2（分桶）、6.3（Gateway 优化）、6.4（Production Readiness）、6.5（RC Hardening）。
