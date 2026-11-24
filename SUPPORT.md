# 技术支持

本仓库由单人维护者在业余时间维护，不承诺固定 SLA。提问前请先自助排查，
并在描述里一次性给全上下文，能显著缩短来回确认的轮次。

## 先看文档

- 环境与首次准备：[CONTRIBUTING.md](CONTRIBUTING.md)「环境」一节
- 启动 / 停止基础设施与全部服务：`scripts/start-dev.ps1`（WSL2 / Git Bash 用
  `bash scripts/start-dev.sh`）、`scripts/stop-dev.ps1`
- 本地门禁自查：`.github/PULL_REQUEST_TEMPLATE.md` 与 `scripts/verify-all.ps1`
  （WSL2 / Git Bash 用 `bash scripts/verify-all.sh`）
- 架构与已知限制：[docs/architecture.md](docs/architecture.md)、[README.md](README.md)「已知限制」
- 契约基线：`docs/openapi/` 下的 `engine-api.json` 与四份 `java-*.json`

## 提问渠道

| 类型 | 渠道 |
| --- | --- |
| 缺陷报告 | [GitHub Issues](https://github.com/ybw0405-study/AgentFlow/issues)（`Bug 报告` 模板，即 `.github/ISSUE_TEMPLATE/bug_report.yml`） |
| 功能建议 / 改进 | [GitHub Issues](https://github.com/ybw0405-study/AgentFlow/issues)（`需求 / 改进` 模板，即 `.github/ISSUE_TEMPLATE/feature_request.yml`） |
| 使用与环境配置问题 | 直接开普通 Issue（仓库未配置「问题咨询」专用模板或 contact_links） |
| 安全漏洞 | 见 [SECURITY.md](SECURITY.md)，**不要开公开 Issue**，发邮件到 1448943162@qq.com |

## 提问前请准备

- 版本或提交：根 `pom.xml` 与 `frontend/package.json` 的版本号（当前均为 `0.6.0`），
  以及 `git rev-parse --short HEAD` 的输出
- 环境：操作系统、JDK 17 / Maven 3.9+ / Node ≥22 / Python ≥3.11 / Docker Desktop 的实际版本
- 复现步骤、期望结果与实际结果；若与执行 / 迁移相关，附涉及的迁移版本号（`V0xx`）
- 相关日志（先脱敏：去掉 Token、口令、连接串、JWT、租户数据；`logs/` 默认 gitignored）
- 已跑过的门禁结论（`scripts/verify-all.ps1` 输出、`git diff --check` 结果）

## 响应预期

- 一般 Issue：通常在 3 个工作日内回复，不保证修复时限。
- 安全问题：响应时间与披露窗口见 [SECURITY.md](SECURITY.md)。
- 单人维护仓库不存在「第二人复核」的物理条件，详见 [CONTRIBUTING.md](CONTRIBUTING.md)
  「分支与评审」中对评审边界的如实说明。
