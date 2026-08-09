# Phase 6.18 Dependency Scan Readiness

## 当前状态

```
git remote -v：空（无 remote repository）
GitHub Actions：未启用
```

**BLOCK-01 = PENDING**（禁止生成 GREEN）。

## CI Required Evidence（关闭 BLOCK-01 所需）

```
workflow id
commit SHA
scan timestamp
artifact location
dependency report
```

## 规则

```
CVSS >= 7 → FAIL
CVSS < 7  → PASS
```

## 就绪项

- ci.yml dependency-scan job（OWASP Dependency Check 10.0.4，failBuildOnCVSS=7）；
- DependencyGateVerificationTest（门禁配置静态验证，不作为 CI 最终证据）；
- artifact 归档路径：docs/06-production/security/dependency-report.{json,html}。
