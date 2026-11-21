## 变更说明

<!-- 一句话说清改了什么、为什么改。关联 issue 用 closes #N。 -->

## 影响面

请勾选本次改动触及的范围，便于审查时聚焦：

- [ ] 仅内部实现，外部行为不变
- [ ] 接口契约（`docs/openapi/` 基线需同步）
- [ ] 数据库迁移（新增 `V***__*.sql`，已确认幂等且不修改历史迁移）
- [ ] 配置项 / 环境变量（README 环境变量表需同步）
- [ ] 安全边界（鉴权、租户隔离、脱敏、出网沙箱）

## 验证方式（必填）

<!-- 贴关键命令与真实输出/结论，而不是「已测试」。 -->

```
./scripts/verify-all.sh
```

- [ ] Java：`mvn verify`（含 Checkstyle 与 ArchUnit）
- [ ] Python：`ruff check`、`ruff format --check`、`pytest`
- [ ] 前端：`typecheck`、`lint`、`vitest`、`build`
- [ ] 端到端：`pytest e2e`（需先 `scripts/start-dev`）
- [ ] CI 新增门禁（SBOM / 版本下限 / 配置守卫 / 跨服务 e2e）均已跑绿

## 回滚方式（必填）

<!-- 必须写出「按 digest 部署上一版本」的可执行步骤，见 ops/rollback-runbook.md：
     1) 从上一版 Release 的 digests.txt / Release notes 取各服务镜像 digest；
     2) docker pull <repo>:<tag>@sha256:<digest>；
     3) 把部署清单的 image 切换到该 digest 并重新应用（不用 latest / 不用浮动 tag）；
     4) 跑「回退后验证清单」并确认 Flyway / 数据结构前向兼容。
     禁止 flyway clean、禁止删改 schema_history。若本次无制品变更，写明「无制品变更，按代码 revert PR」。 -->

## git diff --check 输出（必填）

<!-- 提交前执行 `git diff --check`，把真实输出粘贴于此；无输出请写「无输出，0 问题」。 -->

```
git diff --check
```

## 自查

- [ ] 新增行为有对应测试，且测试验证的是行为而非实现细节
- [ ] 错误路径有明确语义（可读文案 + 稳定错误码），未吞异常
- [ ] 无新增硬编码密钥、无凭据进入日志/事件/返回体
- [ ] 文档与实现一致（涉及接口、配置、限制时已同步更新）
- [ ] workflow 新增 action 已用 `git ls-remote` 核实 40 位 SHA 并加版本注释
