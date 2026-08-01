# Seckill-Engine

金融级高并发秒杀交易系统：面向瞬时百万级请求，提供高并发、防超卖、最终一致性的分布式秒杀能力。

## 项目定位

- 瞬时百万级请求入口保护（Nginx + Gateway + 多层限流）
- Redis Lua 原子扣减库存，杜绝超卖
- RocketMQ 事务消息保障库存与订单的最终一致性
- 分布式锁、幂等设计、订单唯一约束，杜绝重复下单
- 全链路可观测、可追踪、可回滚

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 21 LTS | 运行与编译 |
| Spring Boot | 3.x | 微服务基础框架 |
| Spring Cloud | 与 Boot 3.x 对应的稳定版本 | 网关、服务治理 |
| MyBatis Plus | 3.5.x | ORM |
| MySQL | 8.0.x | 业务数据落库 |
| Redis | 7.x + Redisson | 库存预扣、分布式锁 |
| RocketMQ | 5.x | 事务消息、最终一致性 |
| Nginx | 最新稳定版 | 入口层限流与负载 |
| Docker / Kubernetes | 最新稳定版 | 容器化与编排 |

具体小版本在对应阶段锁定并写入 POM，避免依赖漂移。

## 目录结构

```text
seckill-engine
├── docs        # 各阶段文档
├── sql         # 数据库脚本（版本化）
├── scripts     # 运维/构建脚本
├── docker      # Docker 部署文件
├── pom.xml     # Maven 父工程
└── README.md
```

微服务模块（gateway、auth-service、seckill-service、order-service、
inventory-service、payment-service、common、api、monitor）将在编码阶段逐个加入。

## 开发流程

严格按以下生命周期推进，每个阶段完成后需评审通过方可进入下一阶段：

1. Phase 0：项目初始化
2. Phase 1：需求分析
3. Phase 2：系统架构设计
4. Phase 3：详细设计
5. Phase 4：编码实现（模块级：设计 → 编码 → Review → 测试 → 提交）
6. Phase 5：测试阶段
7. Phase 6：性能优化
8. Phase 7：生产部署设计

## 环境要求

- JDK 21 LTS（本机：`E:\Java\microsoft-jdk-21`，JAVA_HOME 已配置）
- Maven 3.9+
- Git 2.x
- Docker（部署阶段使用）

构建命令：

```powershell
mvn clean package
```

## 文档索引

见 [docs/README.md](docs/README.md)。
