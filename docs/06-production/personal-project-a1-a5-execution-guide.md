# 个人项目 A1～A5 执行指南（实操手册）

> 版本：v1.0
> 适用：本地个人项目（单机开发）推进 GA 验证
> 核心策略：1 台云主机承载业务+中间件，本机作为独立 Load Generator（连接复用已就绪），
>           GitHub 免费仓库承载 CI，Owner 由本人担任并如实签核。

---

## A1 远程 CI（零成本，最快关闭 BLOCK-01）

1. 注册 GitHub 账号并创建私有仓库（如 `seckill-engine`）；
2. 生成 Personal Access Token（Settings → Developer settings → Tokens，勾选 repo/workflow）；
3. 本地关联并推送：

```bash
cd seckill-engine
git remote add origin https://<TOKEN>@github.com/<user>/seckill-engine.git
git push -u origin release/RC1
git tag 0.1.0-RC1
git push origin 0.1.0-RC1
```

4. GitHub Actions 自动运行（ci.yml 已含 dependency-scan + release-gate）；
5. 采集证据：Actions 运行页 → workflow id、commit SHA、artifact（dependency-scan-report）；
6. 将证据填入 `phase6.16-dependency-scan-final-report.md` → BLOCK-01 关闭。

> 安全提示：Token 不要写进仓库；建议用 `gh auth login` 或凭据管理器，remote 使用无 Token URL。

---

## A2 独立 Load Generator（本机 + 公网压测）

**现实方案：本机作为 Load Generator，云主机作为业务环境。**

- 本机优势：与业务 JVM 完全分离（业务在云主机）；网络出口独立（公网）；
- 端口耗尽已解决（连接复用 + 收敛连接池，L-08 1000 档 PASS）；
- 压测命令（连接云主机 Gateway）：

```bash
mvn -pl integration-test -am test \
  -Dtest=ProductionScaleValidationTest \
  -Dload.enabled=true -Dl08.success-target=50000
```

- 若本机资源不足（内存/CPU），备选：第二台旧电脑或 2C4G 轻量云主机仅跑压测客户端。

---

## A3 生产规格 RocketMQ（云主机 Docker Compose）

在云主机创建 `rocketmq-compose.yml`（独立 Namesrv + Broker 容器）：

```yaml
services:
  namesrv:
    image: apache/rocketmq:5.3.1
    container_name: rmq-namesrv
    command: sh mqnamesrv
    ports: ["9876:9876"]
  broker:
    image: apache/rocketmq:5.3.1
    container_name: rmq-broker
    depends_on: [namesrv]
    environment:
      JAVA_OPT_EXT: "-Xms1g -Xmx2g -XX:+UseG1GC"
    volumes:
      - ./broker.conf:/home/rocketmq/broker.conf
    ports: ["10911:10911", "10909:10909"]
```

`broker.conf` 关键项（brokerIP1 填云主机公网 IP，供本机压测客户端直连）：

```ini
brokerClusterName=DefaultCluster
brokerName=broker-a
brokerIP1=<云主机公网IP>
listenPort=10911
namesrvAddr=namesrv:9876
autoCreateTopicEnable=true
sendMessageThreadPoolNums=64
pullMessageThreadPoolNums=64
```

验证：

```bash
mvn -pl integration-test -am test -Dtest=RocketMqCapacityProbeIT \
  -Dload.enabled=true -Drocketmq.probe.total=50000 \
  -Drocketmq.probe.concurrency=100
```

Gate：send failure=0 / DLQ=0 / backlog→0 → BLOCK-MQ 关闭。

---

## A4 生产数据中心（云主机全链路部署 + Canary）

在云主机部署全链路（Docker Compose）：

- 中间件：Redis 7.2.4、MySQL 8.0.36（初始化仓库 `sql/` 脚本）、RocketMQ（A3）；
- 业务服务：gateway / auth / seckill / order / inventory / payment（`0.1.0-RC1` 镜像或 java -jar）；
- 关键配置：
  - `inventory.sharding.enabled=true`、`inventory.sharding.bucket-count=8`；
  - `seckill.gateway.canary.enabled=true`、`weight=5`、`control-enabled=true`（运维平台）；
  - internal-auth 密钥（配置中心/环境变量下发）；
- Canary 执行：本机压测流量经云主机 Gateway 分流（weight 5→25→50→100），
  每档观察 ≥10000 requests（任务书"或"条件；个人项目无真实用户流量，用压测流量满足请求量条款）；
- Health Gate：WARNING→PAUSE；CRITICAL→AUTO ROLLBACK（ProductionCanaryManager 已就绪）。

> 说明：个人项目的"生产验证"= 真实公网部署拓扑下的验证（非本机 localhost），
> 并在报告中如实标注流量来源为压测流量。

---

## A5 运营 Owner（本人签署，如实标注）

- 本人担任：Release Owner / SRE Owner / Database Owner / Rollback Owner；
- 按 `phase6.16-operation-signoff.md` 模板填写 Name/Role/Timestamp/Approval；
- 补充备注："个人项目，Owner 为项目所有者本人，无独立值班团队"；
- 可选：邀请一位协审者（朋友/导师）对签，提高可审计性。

---

## 执行顺序与验收（T-0）

```
A1(CI GREEN) → A2(Load Gen 就绪) → A3(MQ failure=0) → E2E 50000
→ A4(Canary 5→25→50→100) → A5(Sign-off) → GA Decision
```

每步证据归档至 `docs/06-production/`；任何 Gate 未 PASS 保持 RC1 STABLE。
