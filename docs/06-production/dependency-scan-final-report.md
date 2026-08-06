# Dependency Scan Final Report

> Phase 6.10 Task 1 — CI Dependency Security Gate Closure（BLOCK-01）

## 执行信息

```
Commit:        release/RC1（HEAD 见 phase6.10-ga-approval-report.md）
Workflow Run:  GitHub Actions —— 本仓库无远程 origin，无法从本地触发实际 Run；
               CI 配置已就绪，实际 GREEN 需在远程仓库执行确认
Scanner:       OWASP Dependency Check
Version:       10.0.4
```

## 结果

```
Result:        Gate implemented + local static verification PASS
High Severity: 由 CI dependency-scan job 判定（CVSS >= 7 → FAIL）
Critical:      由 CI dependency-scan job 判定（Block）
CVSS Policy:   0-6.9 = Warning；>=7.0 = FAIL，阻塞 release-gate
Release Gate:  PASS（门禁已接线：release-gate needs [quality-check, dependency-scan]）
```

## 本地验证证据

- `DependencyGateVerificationTest` PASS：断言 ci.yml 包含 `dependency-scan`、
  `org.owasp:dependency-check-maven`、`-DfailBuildOnCVSS=7`、
  `release-gate needs: [quality-check, dependency-scan]`；
- 本地 NVD 数据源下载超时（历史 15min 无产出），本地不替代 CI 门禁；
- CI artifact：`dependency-scan-report` + `release-security-audit`
  （归档至 `docs/06-production/security/dependency-report.{json,html}`）。

## 结论

门禁实现与静态验证 PASS；**实际 CI GREEN 待远程 GitHub Actions 运行确认**
（当前仓库无 remote，无法在本环境关闭该项）。GA 放行前必须取得目标 commit 的
`dependency-scan` job 绿色结果。
