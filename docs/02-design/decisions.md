# 架构决策记录

> 本文件由 `docs/architecture.md` 按主题拆分归档，正文保持原文（含原章节编号）。
> 章节索引与 `§` 交叉引用对照见 [docs/README.md](../README.md)。

## 0. 关键设计决策记录（ADR 摘要）

| # | 决策 | 理由 |
|---|---|---|
| D1 | Java 侧对 Python 的唯一调用路径：workflow-service → gateway 内部路由 `/engine/**` → engine-api。engine-api 仅监听内网网卡，所有 internal 调用要求 `X-Internal-Token` + `traceparent` | 满足 §5.2「唯一调用入口」与 ArchUnit 依赖方向；治理（超时/deadline）集中在 gateway |
| D2 | 事件回流三通道：① 权威投影 = workflow-service Projector 轮询 engine `GET /internal/events?afterSeq=`（游标存 MySQL `projector_cursor`）；② 实时推送 = engine 追加事件后发布 Redis 频道 `af:exec-events`（at-most-once），SSE Hub 订阅扇出，断线用 `afterSeq` 追平；③ 审计兜底 = Projector 转发 RocketMQ `af.audit.events` | PG 由 Python 独占写入，Java 不直连事件库；SSE 不依赖 MQ 即可低延迟；MQ 只兜审计 |
| D3 | 工具副作用路径：P0–P1 用 engine 内置 `mock.*` 连接器（开发装置，无凭据、无外网，调用计数落 Redis，按 `callId` 去重）；P2 起真实工具调用由 engine 发 `ToolCommand` → gateway → connector-service 执行，**凭据只在 connector-service 解密，Python 永不见明文** | 满足 §5.2「Python 绝不可以直连外部服务明文凭据」，同时让 P0 的 tool 节点可演示 |
| D4 | 崩溃恢复：engine 启动时扫描自有 PG 表 `execution_tracker`（非终态 execution），逐个尝试获取租约续跑；workflow-service 另设兜底任务，对 MySQL 中超期 RUNNING 的执行做幂等 re-drive | 恢复不依赖业务库；双保险 |
| D5 | `execution_event.payload` 口径：`NodeCompleted.payload.output` 存**完整且已脱敏**的输出（重放所需），其余事件存摘要+digest；原始提示词全文、未脱敏内容永不入事件；单事件 payload 上限 256KB，溢出落 `node_io` 并仅在事件存引用+digest | 调和 §6.2「只含摘要」与 §6.3「重放所需全部字段」 |
| D6 | 主键统一 UUID（CHAR(36)）；每张需分页/排序的表另加 `seq BIGINT AUTO_INCREMENT UNIQUE` 作为游标 | UUID 不可排序，cursor 分页需要稳定序 |
| D7 | 软删除唯一约束用 MySQL 8 生成列模式：`uq_x = IF(deleted_at IS NULL, <col>, NULL)`，唯一索引建在生成列上 | MySQL 唯一索引中 NULL 互不相等，普通复合唯一索引无法约束未删除行 |
| D8 | 灰度发布：`workflow_definition.traffic_json = [{versionId, weight}]`，触发时按 `hash(idempotencyKey)` 确定性分桶选版本；NULL = 100% current | 同一幂等键永远路由同一版本，天然防重 |
| D9 | retrieval 节点走 `RetrievalProvider` 抽象；P0 提供 mock provider（离线固定语料），P2 接 pgvector / DocQA RAG 服务 | §4 栈未列 pgvector，P0 须可离线演示 |
| D10 | 表达式引擎用 simpleeval 白名单（禁下划线属性、禁 import、禁 lambda）；模板用 Jinja2 `SandboxedEnvironment`；CI 用 Ruff S 规则 + grep 禁 `eval`/`exec` | §9 安全约束 |
| D11 | 预算硬停止实现：发出任何 Command 前，引擎用 Redis 原子 `INCRBY` 预占 `min(node.maxTokens, 剩余预算)`，并把该 Command 的 `maxTokens` 钳到剩余额度；完成后按实际结算回冲预占 | 保证 `tokenUsed ≤ tokenBudget` 恒成立 |

---

