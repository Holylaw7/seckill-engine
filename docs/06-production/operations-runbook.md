# Seckill-Engine 操作手册（Runbook）

> 版本：v1.0（演示部署 + 测试 + 运维 + 回滚）
> 适用：个人项目本地/Docker 部署使用

---

## 1. 系统组件与端口

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| Gateway | 8080 | 唯一入口（JWT/限流/黑名单/Canary） |
| auth-service | 8081 | 登录、JWT |
| seckill-service | 8082 | 秒杀执行（Redis Lua） |
| order-service | 8083 | 建单、取消、超时关闭 |
| payment-service | 8084 | 支付创建/回调/退款（MOCK 渠道） |
| inventory-service | 8085 | 库存扣减/恢复/对账 |
| MySQL | 3306 | 业务库（5 个 schema） |
| Redis | 6379 | 库存/防重/限流 |
| RocketMQ namesrv / broker | 9876 / 10911 | 事务消息 |

---

## 2. 本地测试（无需 Docker 可跑单测）

```bash
export JAVA_HOME=<JDK21 路径>
mvn clean test                                    # 单元 + 默认集成套件
mvn -pl integration-test -am test                 # 真实中间件集成（需 Docker）
```

常用定向测试：

```bash
# 集成环境冒烟（MySQL/Redis/RocketMQ 连通）
mvn -pl integration-test -am test -Dtest=IntegrationEnvironmentSmokeIT

# 安全（内部接口 ACL / Repair 权限）
mvn -pl integration-test -am test -Dtest=InternalApiSecurityIT,RepairAuthorizationIT

# 全链路（登录→秒杀→建单→扣减→支付）
mvn -pl integration-test -am test -Dtest=SeckillFullFlowIT
```

---

## 3. Docker 一键部署（演示环境）

```bash
docker compose -f docker/docker-compose.yml up -d --build
docker compose -f docker/docker-compose.yml ps          # 查看状态
docker compose -f docker/docker-compose.yml logs -f     # 查看日志
docker compose -f docker/docker-compose.yml down        # 停止（保留数据卷）
docker compose -f docker/docker-compose.yml down -v     # 停止并清空数据
```

首次构建约 5-15 分钟（拉依赖/镜像）；MySQL 首次启动执行 `sql/` 初始化脚本（含演示种子）。

---

## 4. 关键配置说明

### 库存分桶（默认演示开启）

```yaml
inventory:
  sharding:
    enabled: true      # 生产目标；false 等价旧单行模型
    bucket-count: 8
```

### Canary 灰度（Gateway）

```yaml
seckill:
  gateway:
    canary:
      enabled: true
      weight: 5         # 0-100
      version: RC1
      control-enabled: true
      control-token: dev-canary-token
```

动态调整权重：

```bash
curl -X POST http://localhost:8080/actuator/canary \
  -H "Content-Type: application/json" \
  -H "X-Canary-Token: dev-canary-token" \
  -d '{"weight":25}'
curl http://localhost:8080/actuator/canary   # 查看当前权重
```

### 内部接口签名（服务间）

```yaml
seckill:
  internal-auth:
    enabled: true
    clients:
      order-service: dev-order-secret
      inventory-service: dev-inventory-secret
    admin-secret: dev-admin-secret
```

> 生产必须替换所有演示密钥/口令（Gateway JWT、internal-auth、MySQL root）。

---

## 5. 业务使用（演示流程）

### 登录

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tester","password":"Test@123"}'
```

### 秒杀

```bash
curl -X POST http://localhost:8080/api/v1/seckill/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-User-Id: 10001" \
  -d '{"sessionId":30001,"skuId":20001,"quantity":1}'
```

### 创建支付（MOCK）

```bash
curl -X POST http://localhost:8080/api/v1/payments/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"orderNo":"<ORDER_NO>","userId":10001,"amount":99.00,"channel":"MOCK"}'
```

### 取消订单 / 库存回补

```bash
curl -X POST http://localhost:8080/api/v1/orders/<ORDER_NO>/cancel \
  -H "Authorization: Bearer <TOKEN>"
```

---

## 6. 压测命令（Load 资产，默认跳过）

```bash
# Gateway 容量（G-09）
mvn -pl integration-test -am test -Dtest=GatewayProductionCapacityBenchmark \
    -Dload.enabled=true -Dgateway.perf.duration-seconds=30 -Dgateway.perf.levels=100,200

# E2E 容量（L-07 10000）
mvn -pl integration-test -am test -Dtest=EndToEndProductionCapacityTest \
    -Dload.enabled=true -Dl07.success-target=10000

# E2E 50000（需独立 Load Generator + 生产 MQ）
mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest \
    -Dload.enabled=true -Dl08.success-target=50000

# RocketMQ 容量探针
mvn -pl integration-test -am test -Dtest=RocketMqCapacityProbeIT \
    -Dload.enabled=true -Drocketmq.probe.total=5000 -Drocketmq.probe.concurrency=100
```

报告输出：`integration-test/target/load-reports/*.json|csv`。

---

## 7. 监控与告警

```text
Prometheus 端点：/actuator/prometheus（各服务）
健康检查：      /actuator/health
关键指标：      gateway_request_total / seckill_success_total /
               inventory_deduct_* / inventory_deadlock_total /
               inventory_reconcile_diff / inventory_redis_consistency_fail_total /
               gateway_canary_* / rocketmq_*（生产侧 exporter）
Grafana：       docs/06-production/grafana/seckill-production-overview.json
告警规则：      docs/06-production/production-alert-rule.md
```

---

## 8. 运维操作

### 库存对账

```bash
# 查询 diff（inventory-service 管理接口，需 admin 签名）
curl http://localhost:8085/api/v1/inventory/admin/reconcile?skuId=20001 \
  -H "X-Service-Name: admin" \
  -H "X-Service-Timestamp: $(date +%s%3N)" \
  -H "X-Service-Signature: <HMAC-SHA256(admin:timestamp, dev-admin-secret)>"
```

### Redis 预热（key 丢失恢复）

```bash
# 按 MySQL 重建：全局 stock + 分桶 key（由对账/预热脚本执行）
redis-cli SET seckill:stock:20001 1000
redis-cli SET seckill:stock:total:20001 1000
# 分桶 key：seckill:stock:bucket:20001:{0..7}
```

### 慢 SQL

```sql
SELECT start_time, query_time, sql_text FROM mysql.slow_log
WHERE start_time >= NOW() - INTERVAL 1 HOUR ORDER BY query_time DESC LIMIT 50;
```

---

## 9. 回滚

| 场景 | 操作 | RTO |
| --- | --- | --- |
| Gateway Canary | POST /actuator/canary {"weight":0} | <5min（实测毫秒级） |
| 库存分桶 | 关 `inventory.sharding.enabled=false`（N=1 等价旧模型） | 分钟级 |
| DB 迁移 | inventory_bak 备份恢复（演练 PASS） | 分钟级 |
| MQ | 重复投递幂等，无需人工补偿 | — |

---

## 10. 故障恢复

- Redis key 丢失：预热 + 对账 + repair（REPAIR 流水留痕）；
- MQ consumer 崩溃：重启后积压自动消费，重复消息只生效一次；
- 库存差异：`/api/v1/inventory/admin/reconcile/repair`（仅 admin）；
- 混沌演练：docs/06-production/recovery-drill-report.md、rollback-drill-report.md。

---

## 11. GA 状态与限制

工程/正确性验证全部完成（见 README §六）；剩余 GA 阻塞为外部生产验证资源。
个人项目演示部署不阻塞；生产发布按 personal-project-a1-a5-execution-guide.md 推进。
