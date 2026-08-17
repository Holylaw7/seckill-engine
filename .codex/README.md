# Seckill-Engine Codex 工作规范

本目录保存 Seckill-Engine 的 Codex 主控提示词和日常开发模板。

## 使用顺序

1. 项目初始化或阶段切换：使用 `prompts/phase-0-project-init.md`。
2. 开发具体模块：使用 `prompts/daily-development.md`。
3. 进行架构与代码质量检查：使用 `prompts/code-review.md`。
4. 进入容量或性能验证：使用 `prompts/load-test-optimization.md`。

## 强制工作流

```text
说明当前阶段
→ 说明目标
→ 给出设计方案
→ 等待确认
→ 执行开发
→ 运行测试
→ 检查 Git 状态
→ 提交 Git
→ 输出 commit 编号
```

## 项目边界

- 不改变现有业务逻辑，除非用户明确确认。
- 不跳过设计阶段。
- 不一次性生成全部项目。
- 没有测试结果不提交代码。
- 发现架构问题时先提出重构方案，不直接修改业务代码。
- 不回滚或覆盖用户已有的未提交修改。
- 修改范围应与当前任务直接相关。

## 当前项目基线

- 项目根目录：`seckill-engine`
- 当前分支：`release/RC1`
- 构建方式：Maven 多模块
- 主要服务：gateway、auth-service、seckill-service、order-service、inventory-service、payment-service
- 共享模块：seckill-common、seckill-api、test-support
- 测试模块：integration-test
- 设计文档：`docs/01-需求分析` 至 `docs/07-release`

## 文档映射

项目已有对应设计文档，不在 `.codex` 中重复生成业务设计：

- README：`README.md`
- 架构设计：`docs/02-架构设计/架构设计.md`
- 数据库设计：`docs/03-详细设计/数据库设计.md`
- 接口设计：`docs/03-详细设计/接口设计.md`
- 部署与生产运维：`docs/06-production`
- 压测报告：`docs/05-性能优化`、`docs/06-production`
- 性能优化记录：`docs/05-性能优化`
