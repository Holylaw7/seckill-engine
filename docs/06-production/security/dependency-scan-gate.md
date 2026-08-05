# Dependency Scan Final Gate（Phase 6.7 Task 7）

## 门禁规则

| CVSS | 结果 |
| --- | --- |
| 0 - 6.9 | Warning（不阻塞） |
| >= 7.0 | FAIL（阻塞 release-gate） |
| Critical | Block（等价 FAIL，人工评审） |

## CI 执行

- Job：`dependency-scan`（Stage 2b），位于 compile → unit-test 之后、release-gate 之前；
- 命令：`mvn org.owasp:dependency-check-maven:10.0.4:check -DfailBuildOnCVSS=7 -Dformat=JSON,HTML`;
- 产物：
  - artifact `dependency-scan-report`：各模块 `target/dependency-check-report.{json,html}`；
  - artifact `release-security-audit`：归档为 `docs/06-production/security/dependency-report.{json,html}`（Task 7 要求）。
- release-gate 依赖 `quality-check` 与 `dependency-scan`：扫描 FAIL 时流水线终止，不进入 Release Check。

## 本地验证说明

本地开发机执行 dependency-check 时 NVD 数据源下载超时（15min 无产出），属网络/数据源问题，
**不以本地结果代替 CI 门禁**。生产发布前必须取得目标 commit 的 CI dependency-scan 绿色结果；
如需本地复跑，可配置 `-Dnvd.api.key` 或使用企业 NVD 镜像加速。

## 状态

- 门禁实现：✅（ci.yml，Phase 6.6.3 / 6.7.7）
- CI 绿色结果：⏳ 待目标 commit 在 GitHub Actions 上执行确认（本地无法替代）
- 审计归档：✅（CI artifact `release-security-audit` → docs/06-production/security/）
