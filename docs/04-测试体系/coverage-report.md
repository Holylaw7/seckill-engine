# Seckill-Engine 覆盖率报告

生成时间：2026-08-04 14:37:50 +0800
统计口径：JaCoCo 行覆盖率（LINE），仅生产代码 src/main/java，排除 DTO/VO/config/*Application。

| 模块 | 行覆盖率 | 趋势 | 门禁 | 风险说明 |
| --- | --- | --- | --- | --- |
| seckill-common | 93.27% | - | ≥70% | PASS |
| gateway | 79.69% | - | ≥70% | PASS |
| auth-service | 76.77% | - | ≥70% | PASS |
| seckill-service | 68.26% | - | ≥80% | 低于门禁 |
| inventory-service | 84.79% | - | ≥80% | PASS |
| order-service | 76.88% | - | ≥75% | PASS |
| payment-service | 70.22% | - | ≥75% | 低于门禁 |
| **合计** | **76.49%** | - | **≥70%** | **PASS** |

风险说明：覆盖率低于门禁的模块需在后续阶段补充测试；数据为本地/CI 实测，不作为生产门禁替代。
