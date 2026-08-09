# Phase 6.20 CI Release Gate Plan

## 需要

```
git remote（存在性验证：git remote -v）
GitHub Actions workflow 启用
```

## 最终证据

```
workflow id
commit SHA
timestamp
artifact URL
dependency report
```

## 规则

```
CVSS >= 7 → FAIL
CVSS < 7  → PASS
release-gate needs [quality-check, dependency-scan]（ci.yml 已接线）
```

## 当前状态

```
git remote：无 → BLOCK-01 = PENDING（本地测试不作为最终证据）
```
