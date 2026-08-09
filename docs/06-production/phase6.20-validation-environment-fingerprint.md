# Phase 6.20 Validation Environment Fingerprint

> 2026-08-09 实测（当前环境基线；资源到位后须与目标环境指纹比对，防漂移）

## Runtime

```
OS:               Microsoft Windows 11 家庭版 中文版 10.0.26200
CPU:              20 逻辑核
Memory:           33,968,349,184 bytes（~32GB）
Java Version:     OpenJDK 21.0.12 LTS（Microsoft build）
Docker Version:   29.6.1（Docker Desktop）
Container Runtime: Testcontainers 1.21.4 / Docker Desktop
```

## Service

```
Gateway / Auth / Seckill / Inventory / Order / Payment：0.1.0-SNAPSHOT（RC1 构建）
```

## Infrastructure

```
Redis:    7.2.4（Testcontainers 镜像）
MySQL:    8.0.36（Testcontainers 镜像）
RocketMQ: 5.3.1（Testcontainers 镜像）
Network:  localhost 回环（无独立网络出口）
```

## Git

```
commit SHA: 9631b54
branch:     release/RC1
tag:        0.1.0-RC1
```
