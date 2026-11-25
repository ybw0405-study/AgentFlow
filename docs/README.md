# 文档索引

本目录按「基础设施 → 需求 → 设计」组织，所有命令都可以从仓库根目录直接复制执行。

## 00-infra · 运行、构建与落地记录

| 文档 | 内容 |
|---|---|
| [implementation-log.md](00-infra/implementation-log.md) | 各阶段落地对齐记录与偏差修正（按时间顺序归档） |
| [CHANGELOG-detailed.md](00-infra/CHANGELOG-detailed.md) | 逐条变更的完整历史 |
| [rollback-runbook.md](../ops/rollback-runbook.md) | 灰度回滚操作手册 |

## 02-design · 设计

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 一页式架构总览：系统边界、关键链路、设计原则 |
| [decisions.md](02-design/decisions.md) | 关键设计决策与理由 |
| [system-and-modules.md](02-design/system-and-modules.md) | 组件图、Maven 模块依赖方向、Python 包与前端分层 |
| [data-model.md](02-design/data-model.md) | MySQL / PostgreSQL 完整 DDL 与迁移约束 |
| [api-contract.md](02-design/api-contract.md) | 对外 `/api/v1` 与内部 `/engine/**` 契约 |
| [execution-engine.md](02-design/execution-engine.md) | 状态机、事件目录、重放协议、调度、租约、预算 |
| [dsl-and-contracts.md](02-design/dsl-and-contracts.md) | DSL Schema、编译期校验、输出契约、错误码、可观测 |

## 根目录文档与资源

| 文档 | 内容 |
|---|---|
| [known-limitations.md](known-limitations.md) | 能力边界与有意取舍的逐条说明 |
| [product-guide.md](product-guide.md) | 功能矩阵、画布交互、模型清单、用量页 |
| [migration-comment-codes.md](migration-comment-codes.md) | 历史迁移注释代号索引（仅供追溯 Flyway 注释，不作为设计语言） |
| [openapi/](openapi/) | 引擎与四个 Java 服务的入库 OpenAPI 基线 |
| [images/](images/) | README 引用的界面截图 |

## 其他

- [README](../README.md)：项目介绍与快速开始
- [CONTRIBUTING](../CONTRIBUTING.md)：贡献指南
- [SECURITY](../SECURITY.md)：安全策略
- [CHANGELOG](../CHANGELOG.md)：版本级变更摘要

## 文档路径约定

- 文档内链接一律使用相对路径；从仓库根目录复制的命令必须可直接执行。
- 新增设计文档放 `02-design/`，需求与验收放 `01-requirements/`，运行、CI、构建与运维放 `00-infra/`，按主题命名，不用阶段代号。
- `docs/openapi/` 是 CI 契约基线，路径固定，不随文档整理移动。
- 已发布的历史文档保留原路径以稳定外部链接；新文档遵循编号分层。
- 交叉引用以本索引为准。设计决策写明取舍与代价，不用编号代替解释。

> 文档与实现冲突时，以「实现 + 测试」为准，并在同一个 PR 中修正文档。
