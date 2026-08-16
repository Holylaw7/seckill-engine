# 距离 GA 发布与项目完成的实施路线图

> 版本：v1.0（2026-08-16）
> 前置：RC1 STABLE；工程/正确性验证全部完成；剩余阻塞全部为外部验证资源
> 目标：个人项目按 A1→A5 关闭 GA Blocking，输出最终 GA Decision

---

## 0. 当前状态快照

```
GA Gate：
  PASS      Inventory Consistency / Monitoring / Rollback
  NOT PASS  E2E 50000
  PENDING   Dependency Scan / MQ Stability / Production Canary / Operations Sign-off
决策规则：ALL PASS → GA READY；任何 FAIL/PENDING → RC1 STABLE（禁止 Conditional GO）
```

## 1. 剩余工作总览

| 里程碑 | 内容 | 关闭项 | 预计耗时 |
| --- | --- | --- | --- |
| M0 准备 | 云主机开通、GitHub 仓库、网络/端口/公网 IP | — | 0.5-1 天（含开通等待） |
| M1 | A1 远程 CI | BLOCK-01 | 约 30-60 min |
| M2 | 云主机中间件 + 业务全链路部署 | 环境准入前置 | 2-4 h |
| M3 | A3 RocketMQ 生产容量验证 | BLOCK-MQ | 1-2 h |
| M4 | A2 本机 Load Generator + E2E 50000 | BLOCK-03 | 1-3 h |
| M5 | A4 生产 Canary 窗口（5→25→50→100） | BLOCK-02 | 2-4 h |
| M6 | A5 Operations Sign-off | BLOCK-04 | 0.5 h |
| M7 | GA Decision + 收尾 | 全部 | 1 h |

**合计：约 2-4 个工作日**（主要等待项为云主机开通与压测执行）。

---

## 2. 详细步骤

### M0 准备（需要你提供外部输入）

1. **云主机**（建议阿里云/腾讯云轻量 4C8G，Ubuntu 22.04）：开通后提供公网 IP 与 SSH 登录方式；
2. **GitHub 仓库**：注册/创建私有仓库后提供 URL（或授权我用 `gh` 创建）；
3. **本地环境**：确认 JDK21 + Maven + Docker 可用（已具备）。

### M1 A1 远程 CI（关闭 BLOCK-01）

```bash
cd seckill-engine
git remote add origin https://github.com/<user>/seckill-engine.git
git push -u origin release/RC1
git tag 0.1.0-RC1 && git push origin 0.1.0-RC1
```

- 触发 GitHub Actions（ci.yml 已含 build → unit → integration → quality → dependency-scan → release-gate）；
- 验收：Actions 运行页取得 `workflow id`、`commit SHA`、`dependency-scan` job GREEN、artifact 存在；
- 将证据填入 `phase6.16-dependency-scan-final-report.md` → **BLOCK-01 关闭**。

### M2 云主机全链路部署（环境准入前置）

在云主机执行：

```bash
git clone https://github.com/<user>/seckill-engine.git
cd seckill-engine
docker compose -f docker/docker-compose.yml up -d --build
```

部署前按生产化调整（与仓库演示 compose 的差异）：

| 项 | 演示默认 | 云主机生产化 |
| --- | --- | --- |
| brokerIP1 | rocketmq-broker | 云主机公网 IP（本机压测直连） |
| MySQL root 口令 | seckill-root | 强随机口令（环境变量注入） |
| 内部签名密钥 | dev-* | 强随机（JWT/internal-auth/admin） |
| 分桶 | N=8 已开 | 保持 N=8 |
| Canary | weight=5 | 保持 5（M5 再升级） |
| 服务端口 | 8080/8081... | 保持（安全组仅开 8080 与 22） |

初始化：执行 operations-runbook.md §3 的演示初始化（用户 + 分桶行 + Redis 预热）。

验收：

```text
docker compose ps 全部 healthy；
Gateway http://<公网IP>:8080/actuator/health = UP；
登录 → 秒杀 → 订单 WAIT_PAY → Redis==MySQL（demo-script.md 可复现）
```

### M3 A3 RocketMQ 生产容量验证（关闭 BLOCK-MQ）

在云主机（或本机连 broker）执行：

```bash
mvn -pl integration-test -am test -Dtest=RocketMqCapacityProbeIT \
  -Dload.enabled=true -Drocketmq.probe.total=50000 \
  -Drocketmq.probe.concurrency=100
```

- 探针需指向云主机 broker（brokerIP1=公网 IP；探针 namesrv 地址通过参数注入）；
- 验收：输出 `rocketmq-production-validation.json`，**send failure=0 / DLQ=0 / backlog→0** → **BLOCK-MQ 关闭**；
- 若仍有失败：采集 broker 日志与资源（磁盘/CPU/连接数）分类定位，不修改业务语义。

### M4 A2 本机 Load Generator + E2E 50000（关闭 BLOCK-03）

前置：M2/M3 PASS + 本机压测客户端已具备连接复用（L-08 1000 档实证）。

本机执行（Load Generator 与业务 JVM 分离、网络出口独立）：

```bash
mvn -pl integration-test -am test -Dtest=ProductionScaleValidationTest \
  -Dload.enabled=true -Dl08.success-target=50000 \
  -Dtestcontainers.rocketmq.namesrv-port=39876 \
  -Dtestcontainers.rocketmq.broker-port=40911
```

> 注：压测目标为云主机 Gateway 时，需将测试的 gatewayBaseUrl 指向公网地址
> （当前资产默认 localhost，需在压测参数/环境变量注入目标地址，实施时确认最小改动点）。

验收：

```text
success=50000 / oversell=0 / deadlock=0 / inventory_diff=0 /
duplicate consume safe / recovery PASS
```

→ 生成 `L-08-2026-xx-50000.json` → **BLOCK-03 关闭**。
失败按 6 类分类（MQ Infrastructure / Load Generator / Critical Business / Inventory / Database / Order Consistency），
不降标准。

### M5 A4 生产 Canary 窗口（关闭 BLOCK-02）

前置：M1/M3/M4 全部 PASS。

```text
5%  → 观察 ≥30min 或 ≥10000 requests（本机压测流量满足请求量条款，报告注明来源）
25% → 观察 ≥30min
50% → 观察 ≥30min
100% → 最终窗口
```

权重调整：

```bash
curl -X POST http://<公网IP>:8080/actuator/canary \
  -H "Content-Type: application/json" -H "X-Canary-Token: <token>" \
  -d '{"weight":25}'
```

每阶段 Health Gate：WARNING（error>0.1% / p99>500ms / MQ lag>30s / Redis latency>2×baseline）→ PAUSE；
CRITICAL（oversell / deadlock / inventory_diff / DLQ 增 / duplicate failure / error>1%）→ AUTO ROLLBACK。

验收：4 阶段全部 Health PASS → **BLOCK-02 关闭**。

### M6 A5 Operations Sign-off（关闭 BLOCK-04）

- 本人填写 Release / SRE / Database / Rollback Owner 四份 sign-off（Name/Role/Timestamp/Approval）；
- 备注"个人项目、Owner 为项目所有者本人"；可选邀请协审者；
- 确认项：发布窗口、监控、告警、备份、回滚方案、RTO<5min。

### M7 GA Decision + 收尾

1. 汇总 8 项 Gate → 输出 `phase6.24-ga-final-decision.md`：

```text
ALL PASS → GA READY（可标记为"个人项目演示级 GA"，不虚构生产流量）
任何 FAIL/PENDING → RC1 STABLE（继续等待）
```

2. 收尾（可选）：

- README 增加"部署状态：云主机演示运行中"与访问地址；
- 录制 2-3 分钟端到端演示视频（面试用）；
- 更新简历"量化证据"（如 E2E 50000 通过、CI workflow 链接）；
- 沉淀 `docs/06-production/phase6.24-ga-final-report.md`。

---

## 3. 风险与回退

| 风险 | 应对 |
| --- | --- |
| 云主机与压测客户端网络延迟影响 RT | 记录为环境因素；容量结论以隔离/同地域验证为准 |
| E2E 50000 失败 | 按失败分类定位；MQ 容量不足则扩容 broker 资源重测 |
| Canary CRITICAL | 自动回滚上一档（RTO<5min），排查后重新观察 |
| GitHub Actions 慢/失败 | 查看日志定位（依赖扫描 NVD 下载慢可配 API Key） |
| 成本 | 4C8G 轻量约 50-100 元/月；验证完成后可停机省钱 |

## 4. 需要你提供的外部输入

1. GitHub 仓库 URL（或授权我创建）；
2. 云主机公网 IP 与登录方式；
3. 是否接受"Canary 窗口用压测流量"（个人项目无真实用户流量）；
4. 预算确认（云主机规格）。

---

**下一步**：确认 M0 输入后，从 M1（A1 CI）开始逐项执行；每步 PASS 才进入下一步，全部 PASS 后输出 GA Decision。
