# 参与开发

## 环境

JDK 17、Maven 3.9+、Node ≥22、Python ≥3.11、Docker Desktop。首次准备：

```powershell
cd engine-api; python -m venv .venv; .venv/Scripts/pip install -e ".[dev]"; cd ..
cd frontend; npm install; cd ..
```

日常开发用 `./scripts/start-dev.ps1`（WSL2 用 `bash scripts/start-dev.sh`）拉起基础设施与全部服务，
`./scripts/stop-dev.ps1` 停止。脚本只处理本仓库的进程，不会动同机其他项目。

## 提交前必须跑门禁

```powershell
./scripts/verify-all.ps1
```

它等价于 CI 的三条流水线：`mvn verify`（单测 + Checkstyle + ArchUnit）、`ruff check` +
`ruff format --check` + `pytest`、`tsc` + `eslint` + `vitest` + `build`。只改一端时可加
`-SkipJava` / `-SkipPython` / `-SkipFrontend` 缩短反馈。

## 契约变更

引擎侧**新增或修改路由、请求/响应模型**后，OpenAPI 基线必须同一次提交内刷新，否则
`contract-drift` 会红：

```powershell
engine-api/.venv/Scripts/python.exe scripts/dump-openapi.py
git add docs/openapi/engine-api.json
```

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

按 `.github/workflows/` 的四条流水线自查，并在 PR 描述里写清影响面（改到哪些服务、是否需要
迁移、是否影响线上契约）。涉及安全的改动请先看 [SECURITY.md](SECURITY.md)。
