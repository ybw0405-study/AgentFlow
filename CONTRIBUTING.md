# 参与开发

## 环境

JDK 17、Maven 3.9+、Node ≥22、Python ≥3.11、Docker Desktop。首次准备：

```powershell
cd engine-api; python -m venv .venv; .venv/Scripts/pip install -e ".[dev]"; cd ..
cd frontend; npm install; cd ..
```

日常开发用 `./scripts/start-dev.ps1`（WSL2 用 `bash scripts/start-dev.sh`）拉起基础设施与全部服务，
`./scripts/stop-dev.ps1` 停止。脚本只处理本仓库的进程，不会动同机其他项目。

## 分支与评审（强制）

仓库早期历史是集中式导入（108 个提交全部直推 `main`、无 PR、无 review），这段历史不可追溯，
**也不代表推荐流程**。自此之后所有变更必须走可评审单元：

- 从 `main` 切短生命周期分支（`fix/<scope>-<简述>` / `feat/<scope>-<简述>`），一个 PR 只表达一个意图；
  格式化、依赖升级与业务改动分开提交，便于二分定位。
- `main` 启用分支保护：required status checks 指向 `java-ci` / `python-ci` / `frontend-ci` / `contract-drift`，
  禁 force push、要求线性历史；涉及契约或全栈行为时手动跑 `e2e` 并把结果贴进 PR。
- 涉及数据库迁移、鉴权、跨语言契约、审计表结构的 PR 需第二人复核；单人维护也必须在 PR 描述里写清
  「验证方式 + 回滚方式」，并附门禁输出与 `git diff --check` 结果。
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

按 `.github/workflows/` 的五条流水线自查（`java-ci` / `frontend-ci` / `python-ci` /
`contract-drift` / `e2e`），并在 PR 描述里写清影响面（改到哪些服务、是否需要迁移、
是否影响线上契约）。涉及安全的改动请先看 [SECURITY.md](SECURITY.md)。
