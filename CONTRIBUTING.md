# 参与开发

## 环境

JDK 17、Maven 3.9+、Node ≥22、Python ≥3.11、Docker Desktop。首次准备：

```powershell
cd engine-api; python -m venv .venv; .venv/Scripts/pip install -e ".[dev]"; cd ..
cd frontend; npm install; cd ..
```

日常开发用 `./scripts/start-dev.ps1`（WSL2 用 `bash scripts/start-dev.sh`）拉起基础设施与全部服务，
`./scripts/stop-dev.ps1` 停止。脚本只处理本仓库的进程，不会动同机其他项目。

## 分支与评审（约定与现状，如实说明）

仓库早期历史是集中式导入（108 个提交全部直推 `main`、无 PR、无 review），这段历史不可追溯，
**也不代表推荐流程**。

### 远端现状（2026-11-21 实测，已由本轮启用）

- `main` 分支保护**已启用**（原为 `404 Branch not protected`）：`strict=true`、
  required status checks 取 PR #11 实测名（`verify` / `engine-openapi` /
  `Java 路由粗筛（秒级，无需起服务）` / `最小授权脚本与迁移表集合对账`）、
  `enforce_admins=true`、线性历史、禁 force push / 删除、`dismiss_stale_reviews=true`、
  要求解决所有 conversation。
- **审批门槛为 1**：`required_approving_review_count=1`、`require_code_owner_reviews=true`。
  个人项目由备用账号 `Y-super000` 履行第二人 review 与批准（模拟双人流程）；
  作者账号不能批准自己的 PR，故合并一律经该备用账号审批。
- 仓库级安全开关**已启用 3 项**：`dependabot_security_updates` / `secret_scanning` /
  `secret_scanning_push_protection`（可用 `gh api repos/ybw0405-study/AgentFlow --jq .security_and_analysis` 反查）。
  `secret_scanning_non_provider_patterns` 与 `secret_scanning_validity_checks` 经 API 提交后
  仍回 `disabled`，需到 Settings → Code security and analysis 网页确认是否受账户计划限制。
- `.github/workflows/` 的流水线与 `security.yml`、`CODEOWNERS` 仍**需合并推送到 `main`**
  才会对 PR 生效；文件入仓 ≠ 已经跑过。
- 历史 PR #11 与 DocQA PR #26 曾打 `do-not-merge` 标签并附发布顺序说明（已合并关闭）。

### 回读校验 / 剩余待办

```bash
gh api repos/ybw0405-study/AgentFlow/branches/main/protection \
  --jq '{contexts:.required_status_checks.contexts, strict:.required_status_checks.strict,
         reviews:.required_pull_request_reviews, enforce_admins:.enforce_admins.enabled,
         linear:.required_linear_history.enabled}'
gh api repos/ybw0405-study/AgentFlow --jq '.security_and_analysis'
```

> `contexts` 必须与 PR 上**实际出现的检查名逐字一致**；新增 workflow 时先跑
> `gh pr checks <PR 号>` 核对，再同步更新分支保护，否则 required check 会永远 pending。

补强审批门槛（**需先有第二个 GitHub 账号**，否则会卡死合并）：

```bash
gh api -X PATCH \
  repos/ybw0405-study/AgentFlow/branches/main/protection/required_pull_request_reviews \
  -H "Accept: application/vnd.github+json" --input - <<'JSON'
{
  "dismiss_stale_reviews": true,
  "require_code_owner_reviews": true,
  "required_approving_review_count": 1
}
JSON
```

### 评审边界：单人仓库无法做到「第二人复核」

**本仓库由单人维护，物理上不存在第二个账号来做独立复核**，不能假装做到了：

- 即便在远端配好 `required_approving_review_count=1` + `require_code_owner_reviews=true`，
  单人仓库也会变成「自己审自己」或因无人可审而卡死。替代方案：自审 PR +
  强制检查清单（验证方式 / 回滚方式 / `git diff --check` 输出）+ 延迟合并（留冷却期回看）
  + 依赖 6 条 CI 全绿作为客观门禁。
- 涉及数据库迁移、鉴权、跨语言契约、审计表结构的 PR 仍必须在描述里写清
  「验证方式 + 回滚方式」并附门禁输出与 `git diff --check` 结果；这是**人工纪律**，
  在分支保护启用前没有机器强制力。

### 其余约定

- 从 `main` 切短生命周期分支（`fix/<scope>-<简述>` / `feat/<scope>-<简述>`），一个 PR 只表达一个意图；
  格式化、依赖升级与业务改动分开提交，便于二分定位。
- 涉及契约或全栈行为时手动跑 `e2e` 并把结果贴进 PR。
- 新增迁移时同步 `db/mysql/grants/least-privilege.sql`，CI 的授权表集合守卫会拦截漏授权。

## 提交前必须跑门禁

```powershell
./scripts/verify-all.ps1
```

它等价于 CI 的静态门禁：`mvn verify`（单测 + Checkstyle + ArchUnit）、`ruff check` +
`ruff format --check` + `pytest`、`tsc` + `eslint` + `vitest` + `build`，外加两条契约检查
（引擎 OpenAPI 基线 diff、Java 路由静态粗筛）。只改一端时可加
`-SkipJava` / `-SkipPython` / `-SkipFrontend` / `-SkipContract` 缩短反馈。

## 契约变更

引擎侧**新增或修改路由、请求/响应模型**后，OpenAPI 基线必须同一次提交内刷新，否则
`contract-drift` 会红：

```powershell
engine-api/.venv/Scripts/python.exe scripts/dump-openapi.py
git add docs/openapi/engine-api.json
```

基线里的 `info.version` 取自**已安装包**的元数据，不是 `pyproject.toml` 的静态值。改版本号后
本地直接重跑 `dump-openapi.py` 仍会写出旧版本（`pip install -e .` 没重装），于是本地干净、
CI 上红。改版本号的同一次提交要执行：

```powershell
cd engine-api
.venv/Scripts/python.exe -m pip install -e . -q
.venv/Scripts/python.exe ../scripts/dump-openapi.py
```

Java 侧的 Controller 改动也要刷基线，但它需要服务在跑（springdoc 在运行时生成 spec）：

```powershell
scripts/start-dev.ps1
python scripts/dump-openapi-java.py
git add docs/openapi/java-*.json
```

`python scripts/dump-openapi-java.py --check` 只比对不写文件，`e2e` 流水线用它做漂移检查。
改版本号会写进 `info.version`，四份 Java 基线与引擎侧基线要一起刷。

不等服务起来还有一层秒级粗筛：`scripts/check-java-routes.py` 从 Controller 源码抽出路由
集合，与四份入库基线双向比对；`contract-drift` 的 `java-route-guard` 在每个 PR 都跑，
纯改接口或 pom、`e2e` 没触发时也能立刻发现「没刷基线」。它只管路由集合，请求 / 响应
schema 仍由 `e2e` 的运行时 `--check` 负责。本地可直接跑：

```powershell
python scripts/check-java-routes.py
```

粗筛只认 `@GetMapping` / `@GetMapping()` / `@GetMapping("/x")` 这类字面量写法；写成
`value = "..."`、路径数组或常量时会直接报错，避免静默漏掉一条路由。

## 数据库迁移

`db/mysql` 与 `db/postgres` 由 Flyway 管理，**已合入的迁移文件禁止修改**，只能追加新版本号。
迁移与对应实体/Mapper 变更放在同一个提交里，便于回滚。

- **CI 强制**：`scripts/check_migration_immutability.py`（由 `config-guard` workflow 调用）会对比最近一个发布 tag，**任何在 tag 时已存在的迁移文件被修改都会让 CI 失败**。改动排序规则、索引、列定义等一律新增 `V+1__<desc>.sql`，用 `ALTER` / `CREATE INDEX` 承载。
- **本地 dev 库漂移**：若本地库在校验和与当前迁移文件不一致（通常因为拉取了改过迁移的分支），用 `docker compose -f ops/docker-compose.infra.yml run --rm flyway repair` 对齐 `flyway_schema_history`，再 `migrate`。`repair` 只改 schema history 表、不动业务数据。
- **镜像与迁移一致性**：Java 服务 jar 内打包了 `db/mysql/migration`（`workflow-service/pom.xml` 的 `resources`），改了迁移后必须 `docker compose build` 重建 Java 服务镜像，否则内嵌 Flyway 与挂载目录的校验和不一致会让服务启动失败。
- **镜像-源码新鲜度预检**：`scripts/check_image_freshness.py`（由 `start-dev.ps1 -Containers` 自动调用）对比 workflow-service 镜像创建时间与最新迁移文件 mtime，过期即警告重建。本地若曾手动 `docker compose build` 后又改了源码，重跑前请先 `build` 或留意该警告。

## 提交信息

采用 Conventional Commits，描述用中文、动词开头：

```
feat(engine): llm 节点记录实际下发的模型名
fix(gateway): 限流计数改为原子操作，并补齐可观测性
docs(readme): 说明重跑 start-dev 前无需手动停服
```

scope 取模块名（`engine` / `gateway` / `workflow` / `connector` / `audit` / `frontend` /
`scripts` / `db` / `ci`）。一个提交只做一件事，重构与行为变更不要混在一起。

## 依赖升级

Java 走根 `pom.xml` 的 `dependencyManagement`，升级后必须 `mvn verify` 全绿；
前端 `npm install` 后提交 `package-lock.json`；Python 依赖锁文件由已安装环境导出：

```powershell
engine-api/.venv/Scripts/pip freeze > engine-api/requirements.lock.txt
```

依赖升级单独提交，不夹带功能改动。

## Pull Request

按 `.github/workflows/` 的六条流水线自查（`java-ci` / `frontend-ci` / `python-ci` /
`contract-drift` / `e2e` / `security`），并在 PR 描述里写清影响面（改到哪些服务、是否需要迁移、
是否影响线上契约）。`security` 是本轮新增的依赖 / 代码扫描门禁（G9），合并到 `main` 后方生效。
涉及安全的改动请先看 [SECURITY.md](SECURITY.md)。
