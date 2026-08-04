# Phase 5.7 工程质量体系建设报告

版本：v1.0
分支：feature/phase5-test
基线：Phase 5.6（267 tests，G-01~G-07 全 PASS）

---

## 1. 测试资产整理（Phase 5.7.1）

### 1.1 目录规范化

`integration-test/src/test/java/com/seckill/integration/`：

```text
com.seckill.integration
├── support      # 容器/HTTP/数据/MQ 支撑工具
├── integration  # 业务集成测试（17 项）
├── chaos        # 故障演练（13 项）
└── load         # 压测框架与 L-01~L-05
```

单元测试保持与生产包同构（`com.seckill.*`），通过 `@Tag("unit")` 分类，不迁移包名以避免破坏包级可见性。

### 1.2 Tag 体系

| Tag | 用途 | 默认执行 |
| --- | --- | --- |
| `unit` | 单元测试（51 个测试类） | 是 |
| `integration` | 集成测试（integration 包） | 是 |
| `chaos` | 故障演练（chaos 包） | 否（Surefire `excludedGroups=chaos`） |
| `load` | 压测（load 包） | 否（`@EnabledIfSystemProperty(load.enabled=true)`） |

执行矩阵：

```powershell
mvn test                                   # unit + integration（17 IT + 4 冒烟），chaos 排除、load 跳过
mvn test "-Dchaos.excluded="               # 放开故障演练（30 IT 全量）
mvn -pl integration-test -am test "-Dtest=StableLoadTest" "-Dload.enabled=true" "-Dload.duration-minutes=30"
```

### 1.3 文档

`docs/04-测试体系/testing-guide.md`：测试分类、执行方式、环境要求、Testcontainers 说明、压测与故障演练运行方式。

---

## 2. CI 流水线（Phase 5.7.2）

仓库无远端，采用 GitHub Actions（`.github/workflows/ci.yml`），五个阶段：

| Stage | 内容 |
| --- | --- |
| 1 build | JDK 21 `mvn clean compile` |
| 2 unit-test | `mvn test`（单元 + 默认集成，chaos 排除） |
| 3 integration-test | `mvn -pl integration-test -am test -Dchaos.excluded=`（30 IT） |
| 4 quality-check | `mvn jacoco:report` + 报告上传 |
| 5 release-gate | `bash scripts/release-check.sh`（PASS/FAIL） |

---

## 3. 覆盖率报告体系（Phase 5.7.3）

JaCoCo 0.8.12，仅统计 `src/main/java`，排除 DTO/VO/config/*Application；destFile/dataFile 使用 ASCII 临时目录规避 Windows 中文路径问题。

实测行覆盖率（`mvn clean test` + `mvn verify` 生成）：

| 模块 | 行覆盖率 | 门禁 | 结果 |
| --- | ---: | --- | --- |
| seckill-common | 93.27% | ≥70% | PASS |
| gateway | 79.69% | ≥70% | PASS |
| auth-service | 76.77% | ≥70% | PASS |
| seckill-service | 68.26% | ≥80% | **FAIL** |
| inventory-service | 84.79% | ≥80% | PASS |
| order-service | 76.88% | ≥75% | PASS |
| payment-service | 70.22% | ≥75% | **FAIL** |
| **合计** | **76.49%** | **≥70%** | **PASS** |

报告：`docs/04-测试体系/coverage-report.md`；HTML 见各模块 `target/site/jacoco/index.html`。

**Known Issue / Follow-up**：seckill-service（68.26% < 80%）、payment-service（70.22% < 75%）未达门禁。本阶段按约束不修改测试代码；需在 Phase 5.8/Phase 6 补充测试或经评审调整门禁后重新验证。

---

## 4. Release Check（Phase 5.7.4）

`scripts/release-check.sh` 检查项：

| 检查 | 结果（本次本地执行） |
| --- | --- |
| Git 工作区 | PASS |
| Surefire（64 个报告文件，failures=0，errors=0） | PASS |
| 覆盖率门禁（整体 76.49% PASS；seckill-service、payment-service FAIL） | FAIL |
| Release Gate | FAIL |

报告：`docs/04-测试体系/release-check-report.md`（Commit/Branch/Build time/Tests/Coverage/Gate）。

说明：release-check 对生成的 `coverage-report.md`、`release-check-report.md`、`.coverage-baseline` 自动容错，其余工作区变更视为 FAIL。

---

## 5. 结论

Phase 5.7 测试资产整理、CI 流水线、覆盖率体系、Release Check 脚本四项基础设施全部落地并本地验证：

- 默认 `mvn test`：258 运行 + 11 压测跳过，failures=0、errors=0；
- `-Dchaos.excluded=` 放开：30 项集成+演练全绿（沿用 Phase 5.6 基线）；
- JaCoCo 覆盖率报告生成正常，整体 76.49% 达标；
- release-check 四项检查中 git/tests 通过，覆盖率因 seckill-service、payment-service 未达门禁而 FAIL。

**进入 Phase 6 条件：不满足（覆盖率门禁未全部达标）。**
需先完成 Follow-up（补充 seckill-service/payment-service 测试或评审调整门禁）后重新执行 release-check 至 Gate=PASS。

---

## 6. 提交信息

```text
docs(test): complete phase5.7 quality engineering report
```
