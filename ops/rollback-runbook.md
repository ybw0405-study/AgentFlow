# AgentFlow 发布回滚 Runbook（按镜像 digest）

适用范围：AgentFlow 版本回退 / 滚动发布失败处置。
核心原则：**应用可回退，数据结构不自动回退**；回退前先判断「新代码写过的列 / 行」旧代码是否读得懂。

本 runbook 依赖 G14/G17 落地的两个事实，缺一不可：

1. 每个 Release 的 `images` job 会把本次全部镜像的
   `ghcr.io/<owner>/<repo>-<service>:<version>@sha256:<digest>` 写入 `digests.txt`，
   作为 Release asset 与同名 workflow artifact 归档（见 `.github/workflows/release.yml`）。
2. 每个镜像由根目录多阶段 `Dockerfile` 构建，运行阶段非 root，
   `HEALTHCHECK` 指向 readiness 端点（Java `/health`、引擎 `/internal/health/ready`）。

**禁止使用 `latest` 或浮动 tag 回退**；只认 `digests.txt` 里记录过的 digest。

## 0. 回滚前 5 分钟检查

- 记录现场：当前生效的 tag 与各服务镜像 digest、Flyway `flyway_schema_history` 最大版本、
  PG `execution_tracker` 终态分布、Redis 租约 / 预算键、RocketMQ 消费位点。
- 先判断能否用开关止血，不要动版本：限流、熔断、关闭触发器、摘流量。
  能用开关恢复就不回退版本。
- 摘流量并停止接收新执行，等在途执行与在途 MQ 消息消费完（观察 DLQ 无新增）。

## 1. 取回「上一版本」的 digest 清单（可执行）

`PREV_TAG` 为要回退到的上一个已发布 tag（必须是 `main` 的祖先，release 已校验过）。

```bash
export PREV_TAG=v0.5.0
export OWNER=ybw0405-study
export REPO=AgentFlow

# 从该 Release 下载 digests.txt（也可在 Actions 的 image-digests-<tag> artifact 里取）
gh release download "$PREV_TAG" --repo "$OWNER/$REPO" --pattern 'digests.txt' --dir ./rollback
cat ./rollback/digests.txt
```

`digests.txt` 每行形如：

```
ghcr.io/ybw0405-study/agentflow-workflow-service:0.5.0@sha256:0123...
ghcr.io/ybw0405-study/agentflow-engine-api:0.5.0@sha256:abcd...
```

把它解析成可复用的映射（不改写文件、不猜 digest）：

```bash
# 输出 service -> image@sha256:digest
awk -F'@' '{print $1"@"$2}' ./rollback/digests.txt
image_for() { grep -F -- "-$1:" ./rollback/digests.txt | head -1; }
```

## 2. 按 digest 拉取并校验（可执行）

```bash
for image in $(awk -F'@' '{print $1"@"$2}' ./rollback/digests.txt); do
  docker pull "$image"                     # digest 是内容寻址，不可被 tag 覆盖
done

# 逐个核对本地拿到的确实是该 digest
for image in $(awk -F'@' '{print $1"@"$2}' ./rollback/digests.txt); do
  got="$(docker image inspect "$image" --format '{{index .RepoDigests 0}}')"
  test "$got" = "${image%%:*}"'@'"${image##*@}" \
    || { echo "digest 不符: $image -> $got"; exit 1; }
done
```

> 说明：`docker image inspect --format '{{json .RepoDigests}}'` 是核对真实 digest 的权威命令；
> 与 `ops/docker-compose.infra.yml` 固定基础设施镜像用的是同一套口径。

## 3. 按 digest 部署上一版本（可执行）

部署清单（Kubernetes / Compose / 内部发布系统）中把每个服务的 `image` 改成
`digests.txt` 里的 `repo:tag@sha256:...` 全串，**不要**去掉 `@sha256:`。

以 compose 覆盖文件为例（仅示意，生产请用实际编排器）：

```yaml
# ops/rollback/prev-version.override.yml —— 由 digests.txt 生成，勿手写 digest
services:
  workflow-service:
    image: ghcr.io/ybw0405-study/agentflow-workflow-service:0.5.0@sha256:0123...
  engine-api:
    image: ghcr.io/ybw0405-study/agentflow-engine-api:0.5.0@sha256:abcd...
```

```bash
# 生成覆盖文件（从 Release 的 digests.txt 抄真实值，不编造 sha256）
python - <<'PY' > ops/rollback/prev-version.override.yml
from pathlib import Path
lines = [l.strip() for l in Path('rollback/digests.txt').read_text().splitlines() if '@sha256:' in l]
print('services:')
for line in lines:
    name = line.split('/')[-1].split(':')[0].removeprefix('agentflow-')
    print(f'  {name}:\n    image: {line}')
PY

# 重新应用，让编排器按 digest 拉取并替换
docker compose -f <生产编排文件> -f ops/rollback/prev-version.override.yml up -d
```

停机/启动顺序（与 `e2e.yml` 一致）：网关 → 控制面服务 → 引擎；反向启动时
中间件 → 引擎 → 控制面 → 网关。切换期间 readiness 未 200 不接流量：

```bash
curl -fsS http://127.0.0.1:8080/health            # Java，依赖 down 应 503
curl -fsS http://127.0.0.1:8000/internal/health/ready  # 引擎，真探 PG + Redis
```

## 4. 数据库 / 迁移兼容

- 迁移只向前。需要撤销结构时写新的补偿迁移，不修改已应用的脚本。
- 回退前跑 `flyway validate`；若报「已应用但本地缺失的迁移」，说明库结构比目标版本新，
  逐项判断旧代码能否读新结构。**禁止 `flyway clean`、禁止删改 `flyway_schema_history` 行**。
- 新增 `NOT NULL` 且无默认值的列会让旧代码 INSERT 失败，此时必须补默认值或保留双写，
  不能只回退应用。
- 事件流 PG 是执行真相唯一权威：终态不可逆，回退应用不得重放历史事件改写终态。
- `db/mysql/migration` 的幂等重放由 `e2e.yml` 的迁移门禁覆盖，不在回退时手工改表。

## 5. 配置与密钥回退

- `AF_INTERNAL_TOKEN` / `AF_ENGINE_INTERNAL_TOKEN` 必须两端同时改且互不相同；
  只改一端会让引擎回调或网关代理全部 401。生产缺 token 会 fail-fast 拒绝启动。
- `AF_JWT_SECRET` 轮换 / 回退会让全部在途 access token 失效（上限 15 分钟），
  refresh 按家族吊销；操作前确认用户可接受一次全量重新登录。
- Redis 中的租约、预算账期、幂等键：清库会导致额度归零与执行被误判崩溃，
  仅在确认消费端幂等完好时允许，并记录清理时间点。
- 不要把配置带回 `dev-internal-token` 等不安全默认值。

## 6. 回退后验证清单

- [ ] 每个服务镜像 `docker image inspect` 的 RepoDigest == `digests.txt` 记录值
- [ ] `/health`（Java）与 `/internal/health/ready`（引擎）均 200
- [ ] 登录 / 触发一次执行 / 审批续跑 / SSE 事件流 / 跨租户 404
- [ ] `pytest -q e2e` 在回退版本上通过（见 `.github/workflows/e2e.yml`）
- [ ] `python scripts/dump-openapi-java.py --check` 无漂移
- [ ] Flyway `validate` 通过，无孤儿迁移
- [ ] MQ 无堆积、DLQ 无新增，审计事件只追加
- [ ] 指标回到基线：执行 P99、PG 连接数、Redis 命中率

## 7. 复盘必填项

记录触发条件、实际止血手段、回退到的 tag 与 `digests.txt` 全文、数据是否发生不可逆变化、
补偿脚本编号，以及下一次发布必须满足的前向兼容约束。
