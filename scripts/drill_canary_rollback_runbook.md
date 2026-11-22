# 批次 4 / 灰度与回滚演练 runbook — AgentFlow（可执行步骤骨架）

> **诚实边界**：本机**没有真实生产环境、没有真实业务数据、没有真实凭据**，
> **未做真实灰度 / 真实回滚演练**，没有产生可归档的演练记录。本文件只交付
> 可执行步骤骨架与判据模板；真跑需在预发/生产由有权限的人执行并填写第 7 节。
> 本文件为新增脚手架；**不碰 AgentFlow 任何业务文件**，不改现有源码 / 脚本 /
> 配置 / `.github/**` / `ops/**` / `pom.xml` / `package.json`。

---

## 0. 前置条件（缺一不启动）

| # | 检查 | 依据 |
| --- | --- | --- |
| P1 | 制品按 **digest** 可部署（G17，当前仓库无镜像时此项为**阻断**） | 方案 5.3 / G17；`rollback` 要求「按 digest 部署上一版本」当前不可执行 |
| P2 | 版本单一来源，`pom.xml` / `package.json` / CHANGELOG / tag 一致 | G12 |
| P3 | 四个 `*Application` 的 `ProductionGuard` 强制显式 DB 账号、两个内部 token 不同且为强值 | AF-3 / AF-4 |
| P4 | `execution_outbox` 双写开关可回退（架构 4.1 第 1–3 步每步可独立上线、可回滚） | 方案 4.1 |
| P5 | SLO 与告警已加载、错误预算基线已取 | 同目录 `drill_slo_alerts.yaml` |
| P6 | 监控：引擎 readiness、租约接管时延、投影延迟、月度账期一致性、MQ 堆积 | — |
| P7 | 回滚窗口、审批人、旧 digest 已记录 | 第 3 节 |

> **架构迁移红线**：4.1 的 outbox 切流是「双写 → 单调守卫 → 切单通道 → 删旧路径」。
> 灰度期间**双写开关必须可一键回退**；在「切单通道并删除旧同步 submit」完成前，
> 回滚只需关掉新路径开关，不需要回退数据库结构。

---

## 1. 记录基线

```bash
CURRENT_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' agentflow-engine:<current-tag>)
NEW_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' agentflow-engine:<new-tag>)
echo "$CURRENT_DIGEST" | tee /var/log/agentflow/drill-canary/digest-before.txt
# 记录：QPS、执行成功率、p99、租约接管时延、投影延迟、账期一致性、MQ 堆积、错误预算余量
```

---

## 2. 灰度步骤（按实例 / 流量比例）

| 级别 | 动作 | 停留 | 晋级判据 |
| --- | --- | --- | --- |
| C0 5% | 按 digest 起 1 个新引擎实例，导入 5% 流量 | ≥ 15 min | 第 3 节全部指标；双写两侧结果一致 |
| C1 25% | 扩到 25%，打开 outbox 双写对比 | ≥ 30 min | 双写一致性 100%、无终态回写、preview 不续跑 |
| C2 50% | 扩到 50% | ≥ 30 min | 同上 + 租约接管时延 < SLO、跨月账期一致 |
| C3 100% | 全量切到新 digest，旧实例热备 1 个；**暂不删旧 submit 路径** | ≥ 1 h | 同上 |
| C4 切流 | 确认无误后再单独变更「切单通道 + 删除旧同步 submit」 | 独立 PR | 独立回归窗口，不与灰度同批 |

```bash
docker run -d --name agentflow-engine-canary --env-file /etc/agentflow/prod.env "$NEW_DIGEST"
curl -fsS http://127.0.0.1:8000/internal/health   # readiness 200 后才放量
# TODO: 按真实编排器设置 5%/25%/50%/100% 权重
```

---

## 3. 每级必看的观测与判据

- **I2 提交语义**：`submitExecution` 返回体 `status/note` 被校验；引擎「接受但未派发」返回 409/5xx，不是 202。
- **I1 终态不可逆**：乱序/重放事件不把 CANCELLED 改回 SUCCEEDED；`execution_tracker` 终态后不回写 OPEN。
- **AF-6 preview**：preview 打标、finally 强制终态、恢复扫描不续跑、不重复计费。
- **AF-5 账期**：`PeriodClock` 统一算 period，Java 与 SQL 账期一致；跨月窗口账本 1 行、不超发、不少算。
- **I6 / B4 关停**：滚动关停在途任务先 drain，store/redis 在任务结束后才关闭。
- **AF-3 网关**：`/engine/**` 白名单外 404，客户端 `X-Internal-Token` 被剥离、改服务端注入。
- **AF-8 健康检查**：readiness 探 PG+Redis，依赖 down 返回 503 并被摘除。
- **错误预算**：任一级消耗超阈值立即停止晋级。

---

## 4. 回滚触发条件（任一命中即回滚）

1. 执行真相分裂：双写两侧结果不一致、控制面 PENDING 而引擎无执行；
2. 终态被回写 / 投影漂移（I1 破线）；
3. 跨月账期劈月、额度超发或少算（AF-5）；
4. 租约接管时延突破 SLO / 出现永久僵死执行（AF-7）；
5. preview 被恢复扫描续跑、重复计费（AF-6）；
6. readiness 持续 503 且无法自愈；
7. 引擎/网关内部凭据校验异常（AF-3/AF-4）。

---

## 5. 回滚步骤（按 digest）

```bash
# R1 关掉 outbox 新路径开关（双写阶段一键回退，不动库结构）
# TODO: 按你的配置中心/环境变量把 OUTBOX_CUTOVER 置回 false
# R2 流量切回旧 digest（热备实例还在）
docker run -d --name agentflow-engine-rollback --env-file /etc/agentflow/prod.env "$CURRENT_DIGEST"
# R3 验证：readiness 200、双写一致性、终态不可逆、账期一致
curl -fsS http://127.0.0.1:8000/internal/health
# R4 下线新实例，保留日志与报表归档
```

### 5.1 切流后的回滚（谨慎）

- 若已执行 C4「删除旧同步 submit 路径」，回滚**不是**简单关开关：需要恢复旧路径或
  回退该次变更（独立 PR），并确认 outbox 中未派发的行被补偿/重放，不丢执行。
- 任何「终态不可逆」「账期统一」相关的 DDL / 数据修正，回滚前先备份
  `workflow_execution` / `budget_ledger` / PG 事件流，按变更审批执行，不写进自动回滚脚本。
- 推荐：C4 与灰度分批、独立回归窗口，避免把「可回退的灰度」和「难回退的切流」绑在一起。

---

## 6. 机检产物（真跑时生成）

- Prometheus 快照（每级起止时间戳）；
- 跨进程 e2e 输出（`drill_e2e_crossprocess.py --execute --report ...`）；
- 月度额度跨月压测报表（`drill_loadtest_monthly_quota.py --execute --report ...`）；
- 故障注入观测（`drill_fault_injection.sh <case> --apply --target ...`）；
- 本 runbook 第 7 节填写的演练记录。

---

## 7. 演练记录模板（**本机未填写，未做演练**）

| 字段 | 值 |
| --- | --- |
| 演练日期 / 环境 | （未执行） |
| 当前 digest → 新 digest | （未执行） |
| 灰度级别 / 停留时长 | （未执行） |
| 每级 SLO 观测值 | （未执行） |
| outbox 双写一致性 / 切流开关状态 | （未执行） |
| 是否触发回滚 / 触发条件 | （未执行） |
| 回滚耗时 / 按 digest 验证结果 | （未执行） |
| 结论（成功 / 失败及原因） | （未执行） |
| 归档路径 | （未执行） |

> 对应方案第 6 节批次 4 完成定义「一次成功的回滚演练记录」：本机**未达成**，
> 只交付可执行 runbook；真实演练需在具备 digest 制品与预发环境后补做。
