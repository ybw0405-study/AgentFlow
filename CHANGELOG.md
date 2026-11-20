# 变更日志

记录对外可见的行为变化。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本；`0.x` 期间不保证向后兼容。

## [0.5.0] - 2026-11-20

### 不兼容变更

- `GET /api/v1/admin/usage` 的 `effectiveLimits` 改名为 `tenants`，字段随之扩展（限额来源、试跑份额、执行统计、`state`）；`budgetLedger` 与全时段的 `executions` 不再返回，改由 `tenants` + `totals` 提供账期口径的数字。管理端是本仓库内唯一调用方，已同步。

### 新增
- **补跑服从工作流并发上限**（`V016__workflow_concurrency.sql`）：`workflow_definition` 新增 `max_concurrent`（0 = 不限，上界 64），`PATCH /api/v1/workflows/{id}` 带 `maxConcurrent` 修改，审计 `WORKFLOW_CONCURRENCY_UPDATED`；触发页可直接设置。`CATCH_UP_ALL` 不再把一批档位一次塞进引擎：逐档检查余量，跑满就停在最后一条真的跑过的档位上，剩下的留到下一轮（审计 `TRIGGER_DEFERRED`，带 `pending` / `limit` / `active`）——「推迟」与「跳过」是两件事，前者还要补，后者不要了。手动触发与 webhook 在满额时直接 `429 WF-CONCURRENCY-001`（`EXECUTION_REJECTED` 审计），不会先插一行 `PENDING` 再失败（那会留下一条永远不会开始的执行）。只数 `PENDING` / `RUNNING`：`SUSPENDED` 在等人工审批，占名额会让流水线被一个搁置的审批锁死。这是保护性软限，不是配额结算（见 README 已知限制 11）。
- **管理端「模型 × 租户」交叉表**：`GET /api/v1/admin/usage` 增加 `cross`——行是租户、列是模型，每格给 token 与节点调用数，附列合计、行合计与 `grand`；列按消费降序、`未标注`（空模型）永远在最后，已删除租户的历史消耗仍然列出（名字回落到 id）。透视放在服务端而不是前端：前端自己算行合计会多出一个真源，和 `totals` 对不上时没人知道该信哪个；E2E 因此能直接断言「行合计 == 列合计 == grand == byModel 合计」。管理端新增「交叉表」页签，租户列横向滚动时固定在左侧。
- **不足 1% 的试跑段不再消失**：额度条原来按整数百分比画宽度，0.4% 会被抹成 0%，脚注却写着「占 <1%」——条上什么都没有，读者只能认为试跑没记账。现在百分比保留一位小数，且份额非零但不足 1% 时给该段挂最小宽度（2px）退化成一条可见刻度；份额真的是 0 时不挂，避免画出一个「有一点试跑」的假象。租户用量页与管理端额度条同一规则（`.af-usage-bar__fill--tick` / `.af-meter__fill--tick`）。

- **执行看护：把「永远停在运行中」的执行收口**（`ExecutionReaper` + `V015__execution_reap_index.sql`）。执行状态是引擎事件的投影，于是「引擎没发终态事件」「派发在写行与提交之间断掉」这两种情况会让一行永远停在 `PENDING` / `RUNNING`：列表里一直显示运行中，用户既不知道它死了，也没法结束它（取消是转发给引擎的，引擎不认识这条执行）。新增的后台看护每 60 秒扫一次「过了 deadline 仍无终态」或「PENDING 超过派发宽限」的执行，**先问引擎再动手**：引擎说已终态/已开始/已挂起，就把事实抄到投影（`EXECUTION_RECONCILED`，自愈而不是制造状态）；引擎不认识或连不上，才收口——`PENDING` 记 `EXEC-DISPATCH-LOST`（派发丢失，从未送达），`RUNNING` 记 `EXEC-DEADLINE_EXCEEDED`（超时），审计 `EXECUTION_REAPED` 带 `cause`（`engine-unknown` / `engine-unreachable`）。引擎说还在跑就什么都不做：超期由引擎自己收口，控制面只兜底「无人收口」。新增管理端（平台 ADMIN）`GET /api/v1/admin/executions/stale` 与 `POST /api/v1/admin/executions/sweep`，界面在「管理端 → 看护」，候选按卡住时长排序并区分两种归因（前者重跑就好，后者要查引擎）。`EngineGatewayClient.findExecutionState` 用 `Optional` 区分「引擎明确不认识（404）」与「连不上」，两者动作相同但审计不同。顺带把定时触发与看护重复的租约代码收成 `RedisLease`，`markRunning` 的 `started_at` 改为 `IFNULL`（自愈补写时不覆盖首次开始时间）。配置：`AF_REAP_ENABLED` / `AF_REAP_INTERVAL_MS` / `AF_REAP_GRACE_MS` / `AF_REAP_PENDING_GRACE_MS` / `AF_REAP_BATCH`。
- **错过档位的处置策略（`V014__trigger_misfire.sql`）**：定时触发器新增 `misfirePolicy`，三档语义——`CATCH_UP_LATEST`（默认，等价于旧行为：只补最近一档）、`CATCH_UP_ALL`（按时间顺序回放最近 20 档，更老的丢弃并记 `skipped`）、`SKIP`（一档都不补，只推游标并记 `TRIGGER_SKIPPED` 审计）。起因是「只补最近一档」其实是一条业务判断：每分钟探活希望一档都不补，每小时对账希望补齐，每日提醒补出来是噪音，用一个布尔开关表达不了。三种策略共用同一段扫描（`dueSlots` 返回 `SlotPlan(slots, consumeTo, skipped)`），「哪一档算错过」只有一份口径。`SKIP` 只写 `last_slot_at` + `last_outcome='SKIP'`，不动 `last_fired_at`——跳过不是触发，界面显示「上次扫描跳过 … 档（未执行）」而不是把它冒充成一次执行；扫描游标相应改为档位游标，优先 `last_slot_at`、为空时才退回 `last_fired_at`（兼容 `V012` 前的历史行），不能取两者的较大值——`last_fired_at` 记的是扫描时刻，补跑被推迟时取大值会把没跑的档位一起跳过。写路径校验取值（拼错 409），读路径对脏数据回落到默认。`POST /api/v1/workflows/{id}/triggers` 与 `PATCH /api/v1/triggers/{id}` 接受该字段，`TriggerView` 增加 `misfirePolicy` 与 `lastOutcome`。
- **管理端成本中心变成可诊断视图**：`GET /api/v1/admin/usage` 的 `effectiveLimits` 换成 `tenants`。旧接口算了有效限额（沿祖先链取最小）却没有任何前端渲染它，页面上的「限额」列显示的是租户自己账本行写的数字——`Space Alpha` 写着 10,000，真正生效的是 ORG 的 1,000，管理员据此以为还有余量，而新的执行已经被拒。现在每行给出生效限额 + **限额来源**（`limitedByCall` / `limitedByToken` 与名字，两维分开记：它们未必由同一个祖先卡住）、试跑份额、本账期执行数与失败数、以及 `state`（`unbounded` / `ok` / `near` / `over`，判「超限」用原始数字而不是夹到 1.0 的利用率）。新增 `totals`（平台合计、试跑占比、告警租户数）与跨租户 `byModel`（同模型合并两来源，「几个租户在用」取并集而非相加）。整页统一账期口径——旧的 `executions` 是全部时间，和账本并排放会让人把「一共失败 8 次」读成「这个月失败 8 次」。账期改为强校验（`2026-8` 直接 400，不再静默退回本月）。前端 `AdminView` 重做：汇总卡片 + 租户额度表（两段额度条、继承来源、状态）+ 按模型表 + 错误原因展示（`describeError`，不再只给一句兜底文案）。
- **用量按模型分解 + 额度条区分试跑份额**（`V013__node_model.sql`）：`node_execution` 增加 `model`，投影器从 `NodeCompleted` 的 `output.model` 取 **provider 回落后的真实模型名**（占位名会被服务端替换，记请求名等于记错）；`GET /api/v1/usage/summary` 增加 `byModel`（执行侧按节点、试跑侧按 `preview_usage`，同模型合并成一行，空模型单独一桶不猜）与 `budget.previewCalls / previewTokens / previewTokenUtilization`（账本仍是一本账，试跑份额从明细里切出来）。用量页把额度条切成「执行 / 试跑」两段（同每日柱状图的颜色语义），并新增「按模型」表，空模型标注为「未标注 · 非 LLM 或输出未带模型」。
- **cron 预览与触发页可预期性**：`POST /api/v1/workflows/{id}/triggers/cron-preview` 只解析不落库，回 `{cron, timezone, times[]}`（5 段回填为 6 段规范形式，时间按触发器时区格式化）；`TriggerView` 增加 `lastSlotAt` / `nextFireAt` / `nextFireTimes`。触发页在输入 cron 时防抖调预览，写错当场看到原因；已存触发器显示「上次触发（09:00 档 · Asia/Shanghai）」与「下次 2026-11-21 09:00」，时间串格式按「真实瞬间用 ISO / cron 墙钟用触发器时区」分工。
- **Webhook / 定时触发**（`V010__trigger.sql`）：新增 `workflow_trigger` 表与两条外部入口。Webhook 走公开路径 `/api/v1/hooks/{token}`，token（24 字节 Base64URL）即入口凭据；可选 `X-AF-Signature: sha256=HMAC_SHA256(secret, 原始请求体)`（常量时间比较）、`X-AF-Timestamp`（±300 秒防重放）、`X-AF-Idempotency-Key`（重复投递只执行一次，回 `idempotentReplay`）。定时触发用 5 段或 6 段 cron（秒位必须为 0），调度器每 30 秒扫一轮、按最近到点的档位判点。两者都复用 `ExecutionService.dispatch` 的幂等、版本解析、政策钳制与 PII 脱敏，跑的是**已发布版本**；入口限流 60 次/分钟，停用返回 409，`POST /api/v1/hooks/{token}/probe` 可在不触发执行的前提下自检入口。画布工具条「触发」进入管理页，可建入口、复制地址与密钥、轮换令牌、停用、删除、配固定入参。
- **单节点试跑**：节点卡片与配置抽屉新增「试跑」。引擎把「目标节点 + 全部上游」裁成小图（`engine-api/app/preview.py`），交给同一个调度器、同一批执行器、同一份预算与错误分类真的跑一遍，回传该节点的输入 / 输出 / token / 耗时与整条上游链路状态。试跑**不落执行记录**、不产生版本，只留 `NODE_PREVIEWED` 审计；上游含 `loop` / `parallel` / `human_gate` 时返回 422 `PREVIEW-STRUCTURE-001` 并提示改用「运行」，因为裁剪会改变迭代、分支聚合与挂起续跑的语义。
- **运行入参表单化**：运行弹窗按 `variables` 的 `type` / `required` / `default` 渲染控件（布尔勾选框、数字框、文本框，`object` / `array` 走 JSON 编辑并即时提示解析错误），右上角可切回「JSON 原文」整块编辑。必填项缺失时「触发执行」禁用，不再先落一条注定 `EXEC-INPUT-001` 的执行记录。同一份表单复用到定时触发配置。
- **工作流级默认模型（`defaults.llm`）**：工具条「默认模型」把 `connectionId` + `model` 写进 DSL 的 `defaults.llm`，只用于新建 llm 节点的继承；可勾选批量应用到现有节点。执行始终以节点自身 `config` 为准，改默认值不会影响已经跑通的节点。
- **用量与成本中心（试跑入账）**（`V011__preview_usage.sql`）：建 `preview_usage` 明细表，每次试跑写一行（租户 / 工作流 / 节点 / 模型 / 节点数 / token / 耗时 / 状态），同时把 token 与节点数累加进同一本 `budget_ledger`——此前试跑没有 `workflow_execution` 行，`accrue` 按 executionId 取租户的写法会静默插不进任何东西，于是「调试十次」在账面上完全隐形。新增租户自助接口 `GET /api/v1/usage/summary?days=7|30|90`：区间内的执行与试跑分列统计、本月额度利用率（与平台管理员同一口径 `UsageService.ratio`）、按工作流分解、按天补齐的序列、最近 10 次试跑明细。新增「用量」页把上述口径画成卡片 + 每日堆叠柱状图 + 两张表；执行与试跑用不同颜色，额度用尽时直接写明「新的执行会被拒绝」。记账失败不影响试跑结果（记 warn 并按审计补账），试跑的调用口径按实际跑过的节点数计，不是一个请求算一次。
- **模型连接探测回能力字段**：`POST /api/v1/connectors/{id}/models/probe` 的返回从「只给模型 id」扩成`{ok, models, items, errorCode}`，每个模型带上游 `/models` 的 `contextWindow` / `maxOutputTokens` / `inputModalities` / `supportedParameters` / `reasoningEffort`，其余字段（owner、created、权限）一律丢弃——探测结果会被写进连接配置并落库，多搬一个就多一份会过期的数据。字段名按别名取第一个有值（`context_length` / `context_window` / `max_model_len`…，`top_provider` 下的输出上限也认），取不到留 null 而不是 0。前端把这些字段随模型清单一并写回 `config.modelItems`，画布标注能力时**连接自己的探测结果优先**、引擎清单只补连接里没有的模型——两条来源可能指向不同端点，同名模型的上下文长度未必相同。探测没回能力字段时整段清掉旧缓存，不留过期上限。标签规则不在 Java 里再写一份：服务端只做白名单搬运，`factsFromProbe` 在前端统一算。
- **模型能力标注**：`/internal/llm/models` 与 `/api/v1/llm/models` 增加 `items`，按上游 `/models` 的 `context_window` / `max_output_tokens` / `input_modalities` / `effort.supported_levels` / `supported_parameters` 标注推理型、长上下文、支持 JSON、图像输入；上游只回 id 时按模型名推断并标 `source=inferred`，不硬编码某家某代的能力。画布模型输入框下展示标签与上下文 / 输出上限，选到推理型而 `maxTokens` 偏小时给「设为 4096」的一键建议。

- **文档知识库 / 真实文档检索**：`V009__knowledge.sql` 建 `knowledge_base` / `knowledge_document` / `knowledge_chunk` 三表。新增「知识库」页：建库、粘贴或选择 `.txt` / `.md` 导入文档（写入时按标点/段落切片，中文按单字与相邻二元组建词项）、文档列表与删除、检索自测。引擎新增 `retrieval` 执行器与 `KnowledgeSearchClient`，经网关 `/internal/knowledge/search` 调控制面本地 BM25 打分，返回带出处的原文片段（`output.text` / `results` / `citations`），阶段门正式放行；画布可勾选知识库并配置 `queryTemplate` / `topK` / `minScore`，校验要求至少选一个，也可一键生成「文档检索 → 模型作答」工作流。跨租户 kbId 由服务端逐条复核后静默忽略，不泄露存在性。
- **内置示例与模板改走真实链路**：种子里的两条示例升级为 v4，llm 节点指向 OpenAI 兼容端点，工具节点换成 `retrieval` 并引用预置知识库 `kb-demo`（含三份真实文档切片）；模板、新建节点与知识库一键生成的工作流同样默认走真实端点。未注入 `AF_LLM_*` 时执行报 `LLM-NOT_CONFIGURED-001`，不再静默产出确定性示例文本。
- **模型清单 + 一键接入**：LLM 节点卡片上的「模型」从裸文本框改为可点开的常见模型清单（混元 Hy4 / DeepSeek / 智谱 GLM / Kimi / 通义千问 / MiniMax / 豆包 / 硅基流动 / Step / OpenAI / 本地 Ollama，带分组、搜索、厂商标识与标签）。选中即弹出预填 Base URL 与模型名的连接表单，只需填 API Key；「测试并连接」复用 `create → authorize → probeModels` 做真实出网校验，通过后自动写回 `connectionId` / `model`，节点立即可用，失败自动删掉临时连接并给出人话原因。清单外的服务走「自定义 OpenAI 兼容端点」。
- **模型清单补充进程默认端点**：选择器里的「内置演示」入口改为「进程默认端点（AF_LLM_*）」，指向启动脚本注入的真实端点；`displayModelName` 把历史草稿里的占位模型名标注为「旧版离线模型（需重新选择）」。
- 连接器页的模型连接构造逻辑抽到 `model-connection.ts`，与画布快速接入共用同一份规则；端点与密钥仍只在 connector-service 解密注入。
- **ComfyUI 式节点画布**：`WorkflowNode` 改为带分类色标题栏的卡片，模型 / 表达式 / 分支 / 超时等高频参数内联可编辑，端口按类别着色；`ConfigDrawer`（全字段配置）与 `DslSourcePanel`（JSON 视图）原样保留，收进「展开完整配置 / DSL」高级入口，字段逻辑一行未重写。
- **快速编排**：双击画布或按空格唤起中文节点搜索，落位时自动补连线；三个经过 `validateDslLocal` 校验的模板骨架（只替换草稿，不影响已发布版本）；「整理布局」按依赖层级折行排布；`Ctrl+C` / `Ctrl+V` 复制粘贴、`Shift` 框选；落线前即时拦下自连、`end` 往外连与重复连线。
- **执行详情图视图**：取执行冻结版本的不可变 `dslJson` 复用同一张画布，节点按状态着色、进行中的节点有呼吸边框；子图与循环轮次的状态按最严重值聚合到根层卡片，卡片内联输出摘要与失败原因。「节点列表」标签完整保留原扁平列表，SSE 实时态对两个视图同时生效；DSL 取不到时自动回退列表，不让页面留白。
- 设计系统新增 `--af-node-control` 分类令牌；状态色仍严格守强调 / 危险 / 中性三色，`design-redlines` 守卫不破。

### 修复

- **界面上的时间普遍早了八小时**：后端时间字段是带 UTC 偏移的瞬间（`2026-11-20T13:20:49.404+00:00`，MySQL 以 UTC 存），管理端审计、看护候选、用量明细、评测、执行列表、执行详情、版本、工作流列表与 webhook 创建时间却各自用 `slice(0, 19)` 截串——偏移被一起截掉，13:20 这个 UTC 值就被当成本地 13:20 显示，还带着一个 `T` 让人以为是本地时间。现抽 `src/utils/instant.ts` 的 `formatInstant` 统一解析偏移后按浏览器时区输出 `YYYY-MM-DD HH:mm:ss`；不带偏移时补 `Z`（后端约定 DATETIME 一律 UTC），解析不了就原样返回，不伪造时区。`countdown` 原本就用 `new Date`，不受影响；cron 档位与「下次触发」是带自己 timezone 的墙钟，仍原样展示。新增 4 个单测锁住「不出现 T」「Z 与 +00:00 等价」「无偏移按 UTC 解释」。
- **CI 的全栈验收在移除离线 provider 后一直红**：产品侧不再注册离线 provider，CI 也没有模型密钥，于是所有含 llm 节点的用例都停在 `LLM-NOT-CONFIGURED-001`——`test_p0_happy_path`、`test_cost_center`、`test_evals`、`test_idempotent_trigger` 接连失败，红的是「CI 没有对端」而不是产品行为。现在由 `e2e/support/fake_openai.py` 在流水线内起一个 OpenAI 兼容端点（实现 `/models` 与 `/chat/completions`），通过 `AF_LLM_*` 注入给引擎：引擎仍走真实 HTTP、真实超时与错误分类，`usage` 由该端点给出，所以 token 计量、按模型分解与成本中心照常可断言；要求真实语义的用例（检索质量、推理模型行为）仍由 `AF_E2E_REAL_LLM=1` 门控。同一个提交里补上了 `docs/openapi/engine-api.json` 的基线（`/internal/nodes/preview` 此前只存在于代码里），`contract-drift` 恢复。
- **定时触发按「档位」而不是按扫描时刻**（`V012__trigger_slot.sql`）：`isDue` 只回答「有没有一档到点了」，真正派发时用的却是当前分钟，于是两个场景说不清楚——停机 37 分钟后恢复，那一跑记成 09:37 而不是「09:00 这一档迟到了 37 分钟」；派发成功但 `markFired` 前崩溃，重启后重算出新的分钟键，于是同一档跑两次。现在 `dueSlot` 返回「最近一个已到点的 cron 档位」，claim / 派发 / 幂等键 `sched-{id}-{档位}` 全部以它为准，`workflow_trigger.last_slot_at` 记下这一跑代表哪一档，审计带 `lateSeconds` / `compensated`。顺带明确了两条边界：停机恢复只**补跑最近一档**（回看 24 小时、只补一次，不会重放 180 条），秒位不为 0 的 cron 直接拒绝（分钟粒度的 claim 与幂等键注定吞掉同一分钟的第二个档位）。
- **定时触发在多副本下会重复执行**：`fireSchedule` 的幂等键用 `LocalDateTime.toString()`（含秒），两个副本在不同秒扫到同一条触发器时会得到两个不同的键，于是各派发一次——用户只配了一条 cron，账面却是两次执行。现在分钟键先截断到分钟；调度器再加两层：扫描租约（`af:sched:leader`，60 秒 TTL，续租按持有者判断）只让一个副本扫表，分钟 claim（`af:sched:claim:{id}:{分钟}`，2 分钟 TTL）只让一个副本派发。三层都 fail-open，Redis 不可用时退化为依赖幂等键，宁可多扫一轮也不漏跑。
- **运行执行的是已发布版本而非当前草稿**：引擎触发只认版本快照，此前「运行」直接触发会执行**已发布版本**，而接入向导只改当前草稿，两者不是同一份 DSL。现在运行先保存草稿，再复用（或冻结）同 checksum 的版本快照并显式带 `versionId` 触发，按画布所见执行；不改已发布版本、不走灰度分流、同内容草稿不重复冻结。画布与运行弹窗均写明该语义。
- **移除产品侧的离线 provider 与内置测试连接器**：`LlmConfig.provider` 的默认值与唯一取值改为 `openai-compatible`，`app/main.py` 不再注册离线 provider，`ConnectorRegistry` 只走 connector-service，`AF_AUTHORIZED_CONNECTORS` 默认为空；配置抽屉去掉 provider 的离线选项，tool 节点不再预填 `mock.*`。旧 DSL 的 `provider: mock` 会在校验期直接报错，并提示改为模型连接或进程端点。确定性夹具移到 `tests/support/`，单测与故障注入照常覆盖。
- **真实模型节点偶发空正文**：`deepseek-flash` 是推理模型，`max_tokens` 同时覆盖思维链与正文，给到 1024 时思维链把预算吃光、`content` 为空，节点以 `LLM-RESPONSE-001` 失败。内置示例、模板与新建节点默认改用非推理的 `deepseek-chat`，种子版本升到 v4；provider 在 `content` 为空且响应带 `reasoning_content` 时给出「调大 maxTokens」的明确原因，模型清单同时把默认模型并入下拉（它是上游接受的别名，不一定出现在 `/models` 里）。
- **知识库列表实机 500**：`findBase` 只查 6 列而 `BaseRow` 有 8 个字段，MyBatis 构造器映射失败。`BASE_SELECT` 统一补齐 `documentCount` / `chunkCount` 两个统计列，并加单测守住列数契约。
- **阶段门单测随 `retrieval` 放开更新**：默认白名单接受由 `test_retrieval.py` 守住；旧断言改为用自定义 `Settings` 摘掉白名单来验证「拒绝」逻辑本身，不再把已实现的节点写成未实现。
- **运行弹窗不再让执行在 `start` 节点就失败**：`initialVariables` 初值改为按 DSL 声明类型
  生成（`boolean → false`、`number` / `integer → 0`、`object → {}`、`array → []`），声明了
  `default` 时优先用 `default`。此前所有变量统一给 `""`，内置示例的 `urgent` 是布尔，
  用户不改弹窗直接点「触发执行」会立刻得到
  `EXEC-INPUT-001: variable 'urgent' expects type 'boolean'`。弹窗同时标出必填变量，
  并说明其余是流程内部变量、保持默认即可。

- **错误提示丢掉后端给的具体原因**：`describeError` 增加 `detail`（problem+json 的 `detail`），触发页把它作为第二行「后端说明」显示。此前 cron 写法错误、入口已停用这类 409 只能看到「当前状态不允许此操作」，用户不知道该改哪里。正文与 hint 仍是翻译后的固定文案，只有业务侧显式写给人看的那句才透出，普通异常的 `message` 依旧不外泄。

### 文档

- README「接入真实模型」补一段：模型连接探测会把能力字段随清单写进连接配置，连接建得早时点一次「拉取模型」即可补齐；功能矩阵与已知限制同步。`docs/architecture.md` 新增 §23，记录「Java 只搬运、标签只在前端算」的取舍与连接优先于引擎清单的原因。
- README 新增「用量与成本」章节（数据来自哪张表、试跑为什么计入额度、调用口径按实际跑过的节点数），功能矩阵、界面与已知限制同步：试跑不再被写成「不计成本」，定时触发改写为三层防线，限流注明是固定窗口；`docs/architecture.md` 新增 §22，记录试跑记账的漏洞、`accrueByTenant` 的必要性，以及扫描租约 / claim / 幂等键的分工。
- README 新增「调试与触发」章节：单节点试跑的语义与限制、运行入参表单、工作流默认模型、Webhook 签名 / 幂等 / 限流与定时触发口径（含可直接粘的 curl）；功能矩阵补 4 行，已知限制补定时单实例扫描、试跑不计成本、能力标签非权威数据三条。`docs/architecture.md` 新增 §21，记录试跑裁剪语义、外部触发的凭据模型与信任边界、`defaults.llm` 只影响新建节点、能力标注的来源分级与错误展示的 detail 补充行。
- README 功能矩阵、界面、接入真实模型、已知限制同步更新，并新增「文档检索（知识库）」章节；`docs/architecture.md` 新增 §20，记录 V009 数据模型、BM25 口径、内部端点信任边界与「运行按草稿」的版本语义。内置示例升级 v4 与真实模型接入方式一并写入。
- README「界面」与「接入真实模型」改写：画布一键接入为推荐路径，连接器页与配置抽屉明确为高级模式；补模型清单、连接向导、连接失败反馈三张实机截图，并说明预设端点可编辑、以服务商控制台为准。
- README 新增「第一次使用：5 步跑通内置示例」：登录 → 打开示例画布 → 运行并填变量 →
  审批通过 → 看执行结果，附正确的变量 JSON、执行详情页三栏说明与常见卡点对照表。

## [0.4.0] - 2026-11-20

交付前的最后一轮打磨：把「看得见的错」补齐（节点失败原因、表达式字面量、子图规则），
并用一条真正跑得通的复杂示例替掉「只能看不能跑」的演示。

### 新增

- **节点失败原因可见**（`V008__node_error_message.sql`）：`node_execution` 增
  `error_message`，Projector 把 `NodeFailed.message` 一并落库；执行详情抽屉在错误码下面
  直接给出原因。此前只有 `EXPR-EVAL-001` 这类码，排查得回去翻日志与事件流。
- **内置示例「客户工单分诊」**（`wf-demo-triage`）：分诊 → 加急判定（`condition`）→
  并行取证（`parallel`，两支各两节点）→ 人工审批（`human_gate` + `EXPRESSION` 审批人）→
  逐条润色（`loop`，body 两节点）→ 回复，另有一条驳回支路。`scripts/gen-seed.py` 改为
  按清单生成多个示例，canonical 串与 checksum 仍由脚本计算，不手写 SQL。
- **画布变量面板可折叠**：变量超过 6 个时默认收起，展开后列表限高滚动。此前 5 个变量就能
  把画布压成一条缝，节点被裁到视口外。
- `CODE_OF_CONDUCT.md`（Contributor Covenant 2.1 中文本地化）。

### 修复

- **表达式字面量三处口径统一**：发布期 `ast` 白名单把 `vars.urgent == true` 里的 `true`
  当成未知标识符拒绝，而画布与 Java `GateExpression` 都放行；引擎 `simpleeval` 又缺
  `true/false/null` 别名，同一句在运行期报 `EXPR-EVAL-001`。现在三处一致：字面量别名可用，
  关键字大小写不敏感，标识符（`user` / `space` / 属性名）区分大小写。
- **子图 `end` 规则与引擎对齐**：`loop.body` / `parallel.branches` 既不要求也不允许依赖
  `end`——出口由上层节点收口，只有整图必须留一个 `end`。此前画布强制子图有 `end`，
  合法图被报「3 处校验问题」；子图内删 `end` 也被末位保护挡住。
- **单节点子图在提交前拦下**：引擎按 `WorkflowDsl` 解析子图（`nodes` 有 `minItems: 2`），
  单节点分支能发布成功但一执行就 `PARALLEL-BRANCH-FAILED`。发布期与画布现在同时拒绝，
  提示「子图至少需要 2 个节点」。

## [0.3.1] - 2026-11-19

0.3.0 首次在 GitHub 上跑全栈验收时暴露的问题：把 MQ 通道的正常形态当成了故障。

### 修复

- 引擎的裁决消费端把「迟到的裁决」计成失败：HTTP 快路径先续跑并收口后，MQ 这条消息
  到达时 `resolve_gate` 返回 `ALREADY_TERMINAL`，旧分类归入 `failed` 并打 WARNING。
  结果是告警长期变红、真故障被淹，e2e 里 `duplicate` 为 0 直接导致断言失败。现在按
  三种非故障出口计数：`applied`（本通道推进了状态机）/ `duplicate`（节点已裁决）/
  `stale`（执行已收口或节点不在挂起态）；`NOT_FOUND`、`INVALID_DECISION` 归 `invalid`；
  只有 `BUSY` 重试耗尽与真实异常才算 `failed`。
- `BUSY` 先本地重试 3 次再计数：租约通常正被 HTTP 侧持有，等一拍就会落成 `duplicate`。
- e2e 的 MQ 用例不再锚定 HTTP 与 MQ 的先后（`必须 duplicate` 是随机翻车的写法），
  改为断言「消费到了 + 有账可查（applied/duplicate/stale 有增量）+ invalid/failed 不增长」。

## [0.3.0] - 2026-11-19

把上一版列在「已知限制」里的四条硬伤全部做掉：审计不再「MQ 挂掉只记一行 WARN」，
引擎真的会消费 MQ，`EXPRESSION` 审批人在三处同文法可用，子图有了自己的画布。

### 新增

- **审计 outbox + relay**（`V007__audit_outbox.sql`）：审计事件与业务写同库同事务落
  `audit_outbox`，`AuditOutboxRelay` 按 `min(5s×attempts, 60s)` 退避重投，**没有“放弃投递”**
  这一态（`status` 只有 `PENDING` / `SENT`）——审计放弃就等于没留痕。连续失败达
  `AF_AUDIT_DEGRADE_AFTER_ATTEMPTS`（默认 3）次后切到 `direct:audit_event` 直写库；
  `AF_AUDIT_SINK` 可选 `auto` / `mq` / `direct` 强制出口。新增
  `GET /api/v1/admin/audit-outbox` 暴露积压水位（pending / retrying / 最老待投时长），
  已发送行保留 7 天后清理。`RocketMqAuditPublisher` / `NoopAuditPublisher` 删除。
- **`human_gate` 支持 `EXPRESSION` 审批人**：一份受限文法，三处同构——发布期引擎用
  `ast` 白名单静态校验（拒函数调用、下标、未知属性），运行期控制面 `GateExpression`
  逐候选求值，画布 `approver-expr.ts` 即时体检。属性限
  `user.id/role/rank/platformAdmin` 与 `space.id`，支持比较、`in`/`not in`、`and/or/not`
  与字符串/整数/布尔/null/列表字面量。空或非法一律 fail-closed（无人可批 + ERROR 日志）。
- **引擎内置 RocketMQ 消费端**（`engine-api/app/messaging/`）：控制面裁决后向
  `af-gate-decision` 广播，引擎与 HTTP 回调**并行**消费，重复裁决由既有幂等三态吸收。
  按 5.x remoting 协议用标准库自行实现（不依赖需要 `librocketmq` 的 C++ 客户端）：
  短轮询、不发心跳、每次重连先 `close()`。状态经 `GET /internal/mq/status` 暴露。
- **子图画布**：`loop.body` / `parallel.branches` 可在画布上逐层进入编辑（顶部面包屑回退），
  层内拖节点、连边、选中即配；子图内 `start` 置灰、最后一个 `end` 不可删；校验会下钻到
  `loop_1.body#body_end` 这一级；抽屉里的 JSON 编辑保留为「高级」入口并与表单实时双向同步。
- `start-dev.ps1` / `start-dev.sh` / `e2e` 流水线预建 `af-audit-event` 与 `af-gate-decision`
  两个 topic（5.x 的 topic 要等首条消息才自动创建，否则消费端首轮拿到 `code=17`）。
- 新增 e2e 用例：`test_mq_consumer_connects_to_broker`、
  `test_approval_decision_is_consumed_from_mq`（先断言 `consumed` 增长，再断言
  `duplicate >= 1`，即「真的从 MQ 收到并幂等吸收」）。

### 变更

- `AF_MQ_ENABLED=false` 的含义变了：不再是「审计只写进程日志」，而是「审计出口改走
  `direct:audit_event` 直写库」，outbox 与重投照旧。README 的环境变量表与已知限制已同步。
- 引擎新增 `AF_MQ_CONSUME` / `AF_MQ_GATE_TOPIC` / `AF_MQ_GATE_GROUP`；控制面新增
  `AF_ROCKETMQ_GATE_TOPIC` / `AF_AUDIT_SINK` / `AF_AUDIT_RELAY_INTERVAL_MS` /
  `AF_AUDIT_DEGRADE_AFTER_ATTEMPTS`。

### 测试

- Java 193 项（新增 `GateExpressionTest` 9 项、`GateApprovers` 4 项、
  `AuditOutboxRelayTest` 5 项）；引擎 284 项（新增表达式校验 6 项、remoting 编解码 9 项）；
  前端 109 项（新增 `subgraph.spec.ts` 25 项、审批人表达式 4 项）；全栈 e2e 34 项。

## [0.2.2] - 2026-11-19

### 修复

- 评测运行会「假完成」：`createRun` 先写 `eval_run` 行、再逐条写用例结果，
  轮询正好落在中间时 `recount` 看到 0 条结果就判定全部收口，把 run 标成
  `SUCCEEDED` 并把 `total` 改写成 0——前端显示 0/0 通过，用例其实还在跑
  （CI 上实测复现）。现在结果条数不足 `run.total()` 时不再收口，收口时
  `total` 以 `run.total()` 为准；e2e 的等待逻辑也只认 `SUCCEEDED` / `FAILED` /
  `CANCELLED` 三种终态。

## [0.2.1] - 2026-11-19

0.2.0 首次在 GitHub 上跑全栈验收时暴露的问题：审计链路在本地与 CI 都是关的，
而验收用例靠历史数据蒙过去了。

### 修复

- 本地与 CI 都没开 `AF_MQ_ENABLED`，`workflow-service` 走 `NoopAuditPublisher`：
  灰度、发布、执行触发这些审计事件一条都没落库。现在 `start-dev` 与 `e2e` 流水线
  默认开启 MQ，并等 RocketMQ broker healthy 才拉起服务（compose 里 broker 此前
  没有健康检查，服务连上去才发现它还在启动）。
- 灰度审计断言只按事件类型过滤，审计表是跨轮次累积的，MQ 断链也能「通过」；
  改为按本轮工作流的 `definitionId` 命中载荷。
- 审计查询的 `payload_redacted AS payloadRedacted` 与 `AuditRow.payloadJson` 名不对号，
  接口返回的 `payloadJson` 恒为 `null`，前端审计详情看不到载荷；别名改为与 DTO 一致。
- 仓库根目录补 `pytest.ini`：根下 `pytest` 即全栈 e2e 套件，同时消掉 pytest-asyncio
  的 loop scope 弃用告警（此前只有 `engine-api/` 下有配置）。

## [0.2.0] - 2026-11-19

把引擎早已支持、但画布够不着的节点能力放出来，同时修掉子图校验的两个作用域问题。

### 新增

- 画布节点面板由 4 类扩到 9 类：`condition` / `transform` / `subworkflow` / `loop` /
  `parallel` / `human_gate` 都能直接拖入，并各有配置表单（条件 case 列表、映射行编辑、
  审批人规则与超时动作、子工作流版本策略等）。`retrieval` 仍不提供——引擎没有它的执行器。
- 分支连线可在画布上编辑：选中从 `condition` / `human_gate` 出发的连线，浮条出现 `branch`
  输入框，候选来自该节点的 case 名（审批节点固定 `approved` / `rejected`）；同一节点分支
  名重复会被拒绝，避免一条分支同时点亮两个下游。
- `loop.body` / `parallel.branches` 提供 JSON 编辑区，解析失败时保留输入但不写回草稿。
- 新增 `e2e` 流水线：起真基础设施与五个服务，跑 `e2e/` 32 个全栈验收用例，失败时上传日志。
  其中新增一组画布节点用例（`condition` 分支路由、`loop` 迭代 + `collect`、`parallel` 汇合）
  与运维探针用例（`/health` 无凭据可访问且如实反映依赖）。

### 修复

- 子图作用域：`loop.body` 里写 `vars.item` 会被判成「未声明变量」，带数据引用的 loop
  因此**根本发布不了**；`parallel` 分支内引用子图内节点也同样被判未知。校验改为按子图
  作用域递归：外层变量 + 迭代变量可见，节点引用可以是子图内上游或外层上游。
- 子图内的 `branch` 边此前完全不校验，写错分支名要等运行期才炸；现在与顶层同规则。
- `transform.mappings[].target` 与 `loop.collect.into` 是变量**定义**，此前被当成引用，
  导致「先算一个临时变量」必须先在工作流变量里声明一遍；现在它们对下游直接可见
  （兄弟分支仍不可见，与运行期一致）。
- 分支工作流会丢掉最终产出：收口取的是「定义里的第一个 `end`」，当两条分支各有自己的
  `end` 时，被 SKIPPED 的那个会顶上来，`ExecutionCompleted.result` 恒为空对象，
  执行详情页只能看到空结果。改为只收真正 `SUCCEEDED` 的 `end`（子图内同样修正）。
- 用量占比 `tokenUtilization` 会超过 1（累计用量超过限额后线性增长），进度条量程溢出；
  改为夹在 `[0,1]`，真实超额看 `tokensUsed` / `effectiveTokenLimit` 两个原始字段。
- `start-dev.ps1` 的就绪探测每次都误报「未在 120s 内就绪」：Windows PowerShell 5.1 的
  `Invoke-WebRequest` 默认走 IE DOM 解析，非交互模式下一律抛错，探测永远不成立。补
  `-UseBasicParsing`，并打印实际就绪耗时（实测约 17s）。
- 依赖断连时 gate 扫描每 15s 打一屏 traceback，降为单行 WARNING，堆栈落到 DEBUG。

## [0.1.1] - 2026-11-19

### 修复

- 评测收口会漏掉「轮询先于执行结束」的用例：`advance` 把结果标记为 `RUNNING` 后，
  下一轮只扫 `PENDING` 就再也扫不到它，`eval_run` 永久停在 `RUNNING`（全量 e2e 实测复现）。
  改为重扫 `PENDING` 与 `RUNNING`，历史卡住的记录在服务重启后自动收敛。
- 本地启动脚本只放宽了触发限流、没放宽登录限流，与 README 描述不符：全量 e2e 一轮就有十几次登录，
  连跑两轮即触发 10 次/60s 的 `429`。`start-dev.ps1` / `start-dev.sh` 现同时设置
  `AF_RATE_LIMIT_LOGIN_MAX=300`（产品默认值不变）。

## [0.1.0] - 2026-11-19

首个可运行版本：P0 编排主链路完整，P1/P2/P3 能力按阶段落地。

### 新增

**控制面（Java 17 / Spring Boot 3.5，Maven 多模块）**

- `agent-gateway`：JWT 认证与 refresh 轮换、租户上下文注入、固定窗口限流（Redis 原子计数）、
  熔断、KillSwitch，`/engine/**` 为引擎唯一入口。
- `workflow-service`：工作流 CRUD / 草稿 / 版本冻结（不可变 + checksum）/ 发布 / 回滚；
  执行索引与事件投影（游标轮询）、SSE 实时推送与断线追平；审批中心收件箱与裁决；
  评测集与 A/B 对比。
- `audit-service`：只追加审计（DB 触发器 + 最小授权账号 + RocketMQ 消费），审计查询接口。
- `connector-service`：连接器目录、AES-256-GCM 凭据加密与主密钥轮换、OAuth2 换取、
  出网沙箱（域名白名单 + 解析期私网拦截）、OpenAI 兼容模型连接的模型清单拉取。
- `common`：统一响应体与 ProblemDetail（含 `traceId`）、游标分页、ORG → TEAM → SPACE
  三级租户继承。

**数据面（Python 3.11+ / FastAPI）**

- DSL 校验与编译、拓扑分层调度、并行上限、重试 / 超时 / 取消。
- 事件溯源：PostgreSQL `event_log` 只追加，`execution_spec` 自足，Checkpoint + 启动/周期
  扫描实现崩溃恢复与确定性重放。
- 预算预占与硬停止（发出 Command 前钳制 maxTokens）、Redis 租约、`human_gate` 挂起与
  超时自动裁决、`gate_resolve_outbox` 重投。
- LLM provider：mock / OpenAI 兼容 / 经连接器转发；执行详情记录实际下发的模型名。
- Prometheus 指标、结构化日志、`traceparent` 透传。

**前端（Vue 3 + Vue Flow）**

- 画布编排：start / llm / tool / end 四类节点，DSL 双向转换、自动保存、内联校验、
  节点与连线删除（删节点级联删边）。
- 执行详情（SSE 实时节点状态 + 事件追平）、审批中心、连接器管理、评测与 A/B、管理后台。

**工程与交付**

- 一键启动 / 停止脚本（Windows PowerShell 与 WSL2 bash），自动完成基础设施编排、迁移、
  种子与四服务拉起。
- 三端门禁 `scripts/verify-all.*`：`mvn verify`（测试 + Checkstyle + ArchUnit）、
  `ruff` + `pytest`、`tsc` + `eslint` + `vitest` + `build`。
- GitHub Actions：`java-ci` / `python-ci` / `frontend-ci` / `contract-drift`，
  Action 固定到 commit SHA、最小 `contents: read` 权限、按 ref 并发取消。
- 架构文档（1226 行，含 DSL Schema 与错误码目录）、引擎 OpenAPI 基线、E2E 验收套件、
  SECURITY、MIT LICENSE、PR 模板、EditorConfig 与 LF 行尾约定。

### 变更

- 接入真实模型的主路径改为「先在连接器页建模型连接」，端点与密钥只存在服务端，
  画布只引用连接 id；环境变量单端点路径保留为本地调试手段。
- 画布不再产出 mock 节点：模型下拉来自服务端清单，也允许手填清单外的模型名。

### 修复

- SSE 实时推送失效，补齐事件追平与链路测试。
- Windows 下运行中的 jar 独占导致 `spring-boot:repackage` 失败：`stop-dev` 在构建前真正停服
  并清理残留 `*.jar.original`。
- 网关限流计数改为原子操作，避免并发下的超额放行。
- 引擎截止时间锚定 `ExecutionCreated` 时刻，崩溃恢复与审批续跑不再重置超时预算。
- 跨租户访问统一 404（不泄露资源存在性）；生产守护改为多信号判定，未满足即 fail-fast。
- 连接器出网响应体改为流式限长读取，避免大响应打满内存。

### 已知限制

见 [README「已知限制」](README.md#已知限制)，主要包括：审计依赖 MQ 开启、沙箱未做 DNS 钉扎、
Java 侧 OpenAPI 未入库、画布面板只暴露四类节点（其余走 DSL 编辑）。
