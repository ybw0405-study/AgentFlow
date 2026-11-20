# AgentFlow · 可视化 Agent 工作流编排平台

[![java-ci](https://github.com/ybw0405-study/AgentFlow/actions/workflows/java-ci.yml/badge.svg)](https://github.com/ybw0405-study/AgentFlow/actions/workflows/java-ci.yml)
[![python-ci](https://github.com/ybw0405-study/AgentFlow/actions/workflows/python-ci.yml/badge.svg)](https://github.com/ybw0405-study/AgentFlow/actions/workflows/python-ci.yml)
[![frontend-ci](https://github.com/ybw0405-study/AgentFlow/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/ybw0405-study/AgentFlow/actions/workflows/frontend-ci.yml)
[![contract-drift](https://github.com/ybw0405-study/AgentFlow/actions/workflows/contract-drift.yml/badge.svg)](https://github.com/ybw0405-study/AgentFlow/actions/workflows/contract-drift.yml)
[![e2e](https://github.com/ybw0405-study/AgentFlow/actions/workflows/e2e.yml/badge.svg)](https://github.com/ybw0405-study/AgentFlow/actions/workflows/e2e.yml)
[![release](https://img.shields.io/github/v/release/ybw0405-study/AgentFlow)](https://github.com/ybw0405-study/AgentFlow/releases)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

企业级可视化 Agent 工作流编排：画布拖拽编排 DAG（LLM / 工具 / 检索 / 条件 / 循环 / 人工审批），事件溯源执行引擎（崩溃可恢复、副作用恰好一次），全链路治理（租户隔离、预算硬停止、PII 双层脱敏、审计只追加）。

## 架构

```mermaid
flowchart LR
    FE[Vue3 + Vue Flow 前端] --> GW[agent-gateway :8080<br/>认证·限流·KillSwitch·熔断]
    GW --> WF[workflow-service :8084<br/>工作流/版本/执行索引/投影/SSE]
    GW --> AUD[audit-service :8086<br/>MQ 消费 + 审计查询]
    GW --> CONN[connector-service :8085<br/>连接器/凭据加密/出网沙箱]
    WF -->|/engine/** 唯一入口| GW
    GW -->|X-Internal-Token| ENG[engine-api :8000<br/>DSL 编译·拓扑调度·事件溯源·重放]
    ENG -->|/internal/connectors 工具调用| GW
    ENG --> PG[(PostgreSQL event_log<br/>append-only)]
    ENG --> RD[(Redis 租约/预算/事件频道)]
    WF --> MY[(MySQL 业务库<br/>含 audit_outbox)]
    AUD --> MY
    WF -->|relay af-audit-event| MQ[[RocketMQ]]
    WF -->|af-gate-decision| MQ
    MQ -->|审计落库| AUD
    MQ -.->|裁决消费（与 HTTP 回调并行）| ENG
    RD -->|pub/sub 事件流| WF
```

- **事件即唯一真相**：MySQL 的 execution/node_execution 只是 PG 事件流的查询投影（Projector 轮询 + 游标）。
- **恰好一次**：Command 与结果成对落事件；`callId = executionId/nodeKey/attempt` 确定性幂等；`aggregate_version` 乐观并发兜底；Redis 租约做冲突降级。
- **预算硬停止**：发出 Command 前 Redis 预占并把 maxTokens 钳到剩余额度，`tokenUsed ≤ tokenBudget` 恒成立。

## 功能矩阵

| 能力 | 状态 |
|---|---|
| 工作流 CRUD / 草稿 / 版本冻结（不可变+checksum）/ 发布 / 回滚 | P0 |
| 画布编辑器（Vue Flow，9 类节点、分支连线、DSL 双向、自动保存、校验内联） | P0 |
| 执行引擎（拓扑调度、并行上限、重试、超时、取消、checkpoint） | P0 |
| 事件溯源 + 崩溃恢复（execution_spec 自足 + 启动/周期扫描） | P0 提前实现 |
| SSE 实时节点状态 + 事件追平 | P0 |
| JWT + refresh 轮换 + 租户隔离（跨租户 404）+ RBAC | P0 |
| 健康检查（各服务 `/health`，依赖不可用返回 503） | P0 |
| 预算钳制 / 硬停止、PII 双层脱敏（pii-v2 双端同规则） | P0 |
| 审计只追加（触发器 + 最小授权 + outbox/relay，不可达时降级直写） | P0 |
| 真实模型调用（进程级 OpenAI 兼容端点 / 连接器注入凭据），密钥不落库不进日志 | P0 |
| 单节点试跑：目标节点 + 全部上游真实跑一遍，不落执行记录，结构不支持时明确拒绝 | 已实现 |
| 运行入参表单（按变量声明渲染控件，必填前置拦截）；工作流级默认模型 `defaults.llm` | 已实现 |
| Webhook / 定时触发：令牌入口、HMAC 签名、幂等键、限流、审计（V010） | 已实现 |
| 模型能力标注：推理型 / 长上下文 / 支持 JSON / 输出上限，推理型给 `maxTokens` 建议 | 已实现 |
| 文档知识库：建库 / 导入 `.txt`·`.md` / 切片 / 本地 BM25 检索 / 租户隔离（V009） | 已实现 |
| `retrieval` 节点：真实检索用户文档，`output.text` / `results` / `citations` 供下游引用 | 已实现 |
| condition / loop / parallel / transform / subworkflow 节点（画布表单配置，子图有独立画布） | P1 |
| 灰度发布、故障注入测试套件 | P1 |
| 三级租户继承（ORG → TEAM → SPACE，OWNER 降级继承） | P2 |
| connector-service：CRUD / AES-256-GCM 凭据 / OAuth2 / 出网沙箱 | P2 |
| 成本中心：budget_ledger 投影累加、有效限额继承、预算对账 | P2 |
| human_gate 人工审批：挂起/恢复、超时自动裁决、裁决幂等；审批人 ROLE / USERS / EXPRESSION（V006） | P3 |
| 审批中心：待办收件箱、通过/驳回、审批人通知、outbox 重投；裁决经 HTTP + MQ 双通道送达引擎 | P3 |
| 评测集：数据集/用例、真实执行跑分、四种断言、轮询收口 | P3 |
| A/B 对比：同数据集双版本、通过率→耗时→token 结论口径 | P3 |

画布交互：单击选中节点或连线，`Delete` 删除（删节点会级联删掉它的进出连线），`Esc` 或点空白处取消选中，底部浮条会显示当前选中项并提供删除按钮。`start` 全局唯一：已有 start 时节点面板里的 start 置灰，避免建出第二个起点。

快速编排：双击画布空白处或按 `空格` 唤起节点搜索，输入「审批」「并行」「循环」等中文名回车即落位；落位前若已选中节点，会自动补一条连线。「模板」提供三个经过本地校验的常用骨架（只替换草稿，不影响已发布版本）；「整理布局」按依赖层级折行排布，长链不再被缩成一条细线；`Ctrl+C` / `Ctrl+V` 复制粘贴选中节点（`start` 仍全局唯一）；`Shift` 拖拽框选、`Ctrl` 多选；落线前即时拦下自连、`end` 往外连、重复连线等非法操作。卡片只放高频字段，点「展开完整配置」进原抽屉；「DSL」仍是同一份数据的 JSON 视图。

分支连线：选中从 `condition` / `human_gate` 出发的连线，浮条里会多出 `branch` 输入框，下拉给的是该节点的 case 名（审批节点固定 `approved` / `rejected`），也可直接填。同一节点的分支名不允许重复；分支名不在 cases / elseBranch 里时，校验会提示该分支永远走不到。

子图画布：选中 `loop` / `parallel` 节点，在配置抽屉里点「在画布中编辑」进入该节点的 `body` / 某个 `branches` 分支——画布顶部出现面包屑，点根节点可退回整图。子图内是同一个编辑器：拖节点、连边、选中即配；不能放 `start`（入口由上层节点决定），也不需要 `end`（出口由上层节点收口，与引擎一致）。整图则必须留一个 `end`，删掉最后一个会被拒绝并说明原因。子图里的配置仍写回同一份 DSL，退出/刷新都不丢；「高级」里的 JSON 与表单是同一份数据的两个视图。

## 界面

画布编排（内置示例「客户工单分诊」：分诊 → 加急判定 → 并行取证 → 人工审批 → 循环润色 → 回复 → 归档回复 → 收口，另有一条驳回支路）：节点是带分类色标题栏的卡片，模型 / 表达式 / 分支 / 超时等高频参数直接写在卡片上，改完即时落进 DSL；端口按类别着色，连线是曲线。工具条提供「模板 / 搜索节点 / 整理布局 / 复制 / 粘贴 / DSL」，完整配置抽屉与 JSON 视图保留为高级模式。

![画布](docs/images/canvas.png)

模型不再是让人盲填 id 的裸文本框：点卡片上的「模型」会打开常见模型清单（混元 Hy4 / DeepSeek / 智谱 GLM / Kimi / 通义千问 / MiniMax / 豆包 / 硅基流动 / Step / OpenAI / 本地 Ollama），选一个即弹出端点、模型名与 API Key 的表单，「测试并连接」由服务端真实校验并通过后自动写回节点。清单里的「进程默认端点」走启动脚本注入的 `AF_LLM_*`；清单外的服务走「自定义 OpenAI 兼容端点」，原「连接器」页与完整配置抽屉全部保留。

![模型清单](docs/images/model-picker.png)

![模型连接向导](docs/images/model-connect.png)

知识库页：建库后粘贴文本或选择 `.txt` / `.md` 文件导入，导入即按段落切片并可立即试检索；命中片段带文档标题、片段序号与相关度。也可一键生成引用该知识库的「检索 → 作答」工作流，不必手抄 UUID 进 DSL。画布里的「检索」节点在配置抽屉勾选知识库，命中原文写进 `output.text`，下游 llm 提示词用 `{{ nodes.xxx.output.text }}` 引用即可。

审批中心：`human_gate` 挂起后进入待办收件箱，表格给出提示词、执行短号、节点、超时剩余时间与通过/驳回；审批意见统一填在上方，裁决回执会注明实际走到的分支。

![审批中心](docs/images/approvals.png)

执行详情：默认用**同一张画布**呈现执行态——取执行当时冻结的不可变版本 DSL，节点按状态着色
（强调=挂起/进行、中性=完成、危险=失败、灰=跳过），进行中的节点有呼吸边框；并行分支与循环轮次的
状态聚合到根层卡片，卡片上直接给出输出摘要或失败原因。点节点看输入 / 输出原文，右栏是
token / steps / traceId 与最终产出。需要逐个看子节点（如 `gather#kb#kb_hit`、`polish#0#polish_sign`）
时切到「节点列表」标签，原来的完整列表一栏不少；SSE 实时态对两个视图同时生效。

![执行详情](docs/images/execution.png)

## 快速开始（Windows）

前置：Docker Desktop、JDK 17、Maven 3.9+、Node ≥22、Python ≥3.11。

```powershell
# 1) 安装 Python 依赖（一次）
cd engine-api; python -m venv .venv; .venv/Scripts/pip install -e ".[dev]"; cd ..
# 2) 安装前端依赖（一次）
cd frontend; npm install; cd ..
# 3) 一键启动（基础设施 + 迁移 + 种子 + 4 个 Java 服务 + 引擎 + 前端）
./scripts/start-dev.ps1
```

打开 <http://127.0.0.1:5174> ，账号（密码统一 `agentflow123`）：

> 前端固定监听 `127.0.0.1:5174`（IPv4），避开常被本机其他项目（如 DocQA）占用的 5173；端口与协议在 `frontend/vite.config.ts` 中以 `strictPort` 固定，若 5174 被占用会直接报错而非静默顺延。

| 账号 | 角色 | 空间 |
|---|---|---|
| admin@agentflow.dev | OWNER + 平台 ADMIN | Alpha / Beta |
| editor@agentflow.dev | EDITOR | Space Alpha |
| viewer@agentflow.dev | VIEWER（触发执行会 403） | Space Alpha |
| beta@agentflow.dev | EDITOR | Space Beta（访问 Alpha 资源一律 404） |

WSL2：把第 3 步换成 `bash scripts/start-dev.sh`（先 `./scripts/stop-dev.ps1` 停掉 Windows 侧）。

## 第一次使用：5 步跑通内置示例

不用自己画流程，种子数据里已有两条发布好的示例。按下面点一遍就能看到完整链路（条件 → 并行 → 人工审批 → 循环）：

1. **登录**：打开 `http://127.0.0.1:5174`，用 `admin@agentflow.dev` / `agentflow123` 登录，落在「工作流」列表。
2. **打开画布**：点「示例：客户工单分诊」的名称，或该行的「画布」。右上角是「冻结版本 / 冻结并发布 / 运行」。「运行」按画布当前草稿生成版本快照执行，自建流程改完即可运行；只有要让正式版本对外生效时才需要「冻结并发布」。内置示例已发布 v4，模型与文档检索都走真实链路，两种方式结果一致。
3. **运行**：点右上角「运行」，弹窗里的 `initialVariables` 是一份 JSON，**按声明类型填**（`urgent` 必须是 `true`/`false`，不能是 `"true"` 或空串）：

   ```json
   {
     "ticket": "登录页验证码收不到，客户很急",
     "urgent": true
   }
   ```

   点「触发执行」，页面会自动跳到该次执行的详情页。
4. **审批**：流程跑到「加急审批」会停下，状态变 `SUSPENDED`。点顶部「审批」，上方输入框填审批意见（可选），在待办行点「通过」。审批人规则是表达式 `user.platformAdmin == true`，所以只有平台管理员（admin）能看到这条待办。
5. **看结果**：回到「执行」列表点开那次执行，或点待办里的执行短号。刷新后状态为 `SUCCEEDED`；左栏节点树里能看到并行分支的 `gather#kb#kb_hit`、循环两轮的 `polish#0` / `polish#1`、被跳过的 `notify`。想看驳回支路：再运行一次，在审批中心点「驳回」。

执行详情页分三栏：**左**是节点树（点任意一行选中），**中**是该节点的输入 / 输出原文，失败时还会就地给出错误码与原因，**右**是 token / steps / traceId / 错误码与最终产出。页面通过 SSE 实时刷新；未结束的执行右上角有「取消执行」。

常见卡点：

| 现象 | 原因与处理 |
|---|---|
| `EXEC-INPUT-001`：`variable 'urgent' expects type 'boolean'` | 运行弹窗里 `urgent` 留成了空串。填 `true` 或 `false`，不要加引号。 |
| `EXEC-INPUT-001`：`missing required variable 'ticket'` | 删掉了必填变量。把 `ticket` 补回 JSON。 |
| 运行按钮点了没反应 / 报校验错误 | 「运行」会先保存并校验当前草稿，节点配置有红字时先按提示修好；草稿本身可以直接运行，无需先发布。 |
| 运行报 `LLM-NOT_CONFIGURED-001` | 引擎没有拿到 `AF_LLM_*`。在启动脚本之前注入 `AF_LLM_BASE_URL` / `AF_LLM_API_KEY` / `AF_LLM_MODEL`，或在「连接器」页建一条模型连接并在画布上点「模型」接入。 |
| 触发执行 403 | 用的是 `viewer@agentflow.dev`，该角色不能触发；换 admin 或 editor。 |
| 审批中心是空的 | 用 `editor@agentflow.dev` 登录看不到 admin 的待办；换 admin。 |

## 接入真实模型（OpenAI 兼容）

**推荐：在画布上一步接入。** 点 llm 节点卡片上的「模型」→ 从清单里选一个（例如「混元 Hy4」）→ 弹出的表单已按该服务预填 Base URL 与模型名，只需填 API Key → 点「测试并连接」。服务端解密注入凭据后真实请求一次 `GET /models`，同时验证端点可达、Key 正确、响应符合 OpenAI 兼容格式；通过后自动创建一条模型连接，把 `connectionId` 与 `model` 写回节点，节点立即可执行。

验证不通过会就地给出可操作的原因（改 URL / 换 Key / 检查沙箱放行），并删掉刚建的临时连接，不会在列表里留下连不上的记录；上游没有 `/models` 时可在「高级选项」勾选「仍按预填模型名保存」。端点与模型名都可编辑，服务商控制台才是最终依据。

**接入后怎么确认真的生效**：引擎触发只认版本快照，所以「运行」会先保存画布当前草稿，复用或冻结一份同 checksum 的版本快照并显式带 `versionId` 触发——跑的就是刚改过的那张图，不会执行旧的已发布版本；已发布版本与灰度配置都不受影响，「冻结并发布」才会改动正式版本。内置示例、模板与新建节点默认走真实端点：模型由 `AF_LLM_*` 或模型连接提供，检索读知识库文档。若导入了旧版 DSL（llm 节点仍是已移除的 `provider: mock`），画布顶部会出现提示条，可一键把整层节点切到现有连接；不切的话执行会在该节点明确报错，不会产出示例文本。

![连接失败时的反馈](docs/images/model-connect-error.png)

清单外的服务点「自定义 OpenAI 兼容端点」自行填写端点、模型名与 Key，走同一套校验。「管理已有连接」直达「连接器」页：**高级模式**（连接器页与配置抽屉里的「模型来源 / provider / model」）字段一个没少，仍可手动精调、拉取模型清单、换密钥。

1. 「连接器」→ 类型选「模型接入（OpenAI 兼容）」，填名称、Base URL（如 `https://api.deepseek.com/v1`）、API Key。允许域名留空会取 Base URL 的域名。
2. 「模型」一栏可以直接填自己用的模型名（逗号分隔，如 `deepseek-chat, deepseek-reasoner`）；留空则创建时自动拉一次 `/models`。手填与拉取结果会取并集，上游不支持 `/models` 时靠手填照样能用。
3. 画布选中 llm 节点，「模型来源」选这条连接，「model」下拉列出该连接的模型，也允许直接填清单外的名字；新建 llm 节点会自动挂上第一条模型连接。执行详情里 `output.model` 是**实际下发**的模型名。

也可以不用连接、走进程级单端点（适合本地快速试）：在**启动脚本之前**设置环境变量（脚本不会覆盖它们，子进程会继承）。

   ```powershell
   $env:AF_LLM_BASE_URL = 'https://api.deepseek.com/v1'   # 或 Qwen/Ollama/OpenAI 等兼容端点
   $env:AF_LLM_API_KEY  = '<你的密钥>'
   $env:AF_LLM_MODEL    = 'deepseek-chat'
   .\scripts\start-dev.ps1
   ```

此时「模型来源」留「进程默认」，model 下拉来自 `GET /api/v1/llm/models`。两种来源都允许手填模型名；端点不通或上游不支持 `/models` 时退回手填并说明原因，不会把配置卡死。

约束与排错：

- 经连接调用时，端点与密钥**只在 connector-service 解密**并注入请求头；引擎与画布都只有连接 id。走进程级配置时同理，端点与密钥只来自环境变量，DSL 无法指定 URL。两条路径都不把密钥写进日志、错误体、事件载荷。
- 上游 `401/403` → `LLM-AUTH-001`（永久）；`429/5xx/超时` → 瞬时错误，由引擎 `RetryPolicy` 重试；`400/404/422` → `LLM-REQUEST-001`（多为模型名不对）。未配置环境变量 → `LLM-NOT_CONFIGURED-001`。
- 若上游不支持 `response_format`，设 `AF_LLM_JSON_RESPONSE_FORMAT=false`。
- 选推理型模型（`deepseek-flash` / `deepseek-v4-pro`）时，`maxTokens` 要留足思维链空间；否则思维链耗尽预算、正文为空，节点报 `LLM-RESPONSE-001`。示例、模板与新建节点默认用非推理的 `deepseek-chat`。
- 模型输入框下面会带能力标签（推理型 / 长上下文 / 支持 JSON / 图像输入）与上游给的上下文长度、输出上限。标签优先取上游 `/models` 的字段；上游只回 id 时按模型名推断并标「推断」，不硬编码某家某代的能力。选到推理型而 `maxTokens` 偏小时，界面给一个「设为 4096」的一键建议。
- 真实模型是**外部非确定性副作用**，崩溃恢复重放可能重复计费（at-least-once）。生产环境 `AF_PRODUCTION=true` 时强制要求 https 端点。
- `outputMode=json` 的节点：provider 会自动加 system 提示并使用 `response_format`，返回值解析成 `parsed`。

## 文档检索（知识库）

`retrieval` 节点检索的是**你自己导入的文档**，不是固定示例语料。数据模型见 `db/mysql/migration/V009__knowledge.sql`：知识库 → 文档 → 切片三层，全部带 `tenant_id`，软删文档/知识库时同步物理清除切片，检索 SQL 因此不需要 JOIN 软删状态。

1. 顶栏「知识库」→ 新建知识库 → 粘贴正文或选择 `.txt` / `.md` 文件 → 「导入文档」。单文档上限 200000 字符、500 个切片；导入时按 ~420 字符窗口在句末标点处断开并保留 60 字符重叠。
2. 「检索自测」输入问题，服务端用本地 BM25 对切片打分（中文单字 + 相邻二元组，ASCII 整词小写），返回带出处的原文片段。打分纯 JDK 实现，不引入向量库 / embedding 服务，文档内容不会外送第三方，离线可跑。
3. 画布添加「检索」节点 → 配置抽屉勾选知识库 → 填 `queryTemplate`（如 `{{ vars.question }}`）、`topK`、`minScore`。命中原文写进 `output.text`，`output.results` / `output.citations` 给出片段、文档 id 与相关度；下游 llm 的 `promptTemplate` 用 `{{ nodes.retrieve.output.text }}` 引用。也可以点「用此知识库创建问答工作流」直接生成整套骨架。

信任边界：引擎只持 `tenantId` 与内部令牌，经网关 `/internal/knowledge/search` 调控制面；服务端按租户逐条复核每个 `knowledgeBaseIds`，越权 id 静默忽略，既不返回数据也不报错暴露存在性。用户侧 `/api/v1/knowledge/search` 用同样的复核逻辑，便于在画布配置前先验证召回效果。

局限：这是**词法检索**（BM25），不是语义向量召回；没有 rerank、没有 PDF/Office 解析、没有增量索引重建。导入纯文本即可用，需要语义召回时再接外部 embedding 服务。

## 调试与触发

### 单节点试跑

画布任意节点卡片上的「试跑」（配置抽屉里也有「试跑此节点」）会把**目标节点 + 它的全部上游**裁成一张小图交给引擎，用同一个调度器、同一批执行器、同一份预算与错误分类真的跑一遍：模型真的被调用、检索真的查库、连接器真的出网，然后把这个节点的输入、输出、token、耗时和整条上游链路的状态回给你。

| 行为 | 说明 |
|---|---|
| 执行范围 | 只跑目标节点及其上游；目标节点之后的一律不执行 |
| 执行记录 | **不产生**。试跑是草稿的调试动作，不进执行列表、不产生版本，只留一条 `NODE_PREVIEWED` 审计 |
| 入参 | 与「运行」同一个表单，按 DSL 变量声明填写，必填项缺了不给点 |
| 超时 | 单次试跑最长 120 秒，超时以 `PREVIEW-TIMEOUT-001` 结束并取消 |
| 拒绝的结构 | 上游含 `loop` / `parallel` / `human_gate` 时返回 422 `PREVIEW-STRUCTURE-001`——裁剪会改变迭代、分支聚合与挂起续跑的语义，这时请用「运行」跑完整流程 |

### 运行入参：表单而不是手写 JSON

「运行」弹窗按 `variables` 的 `type` / `required` / `default` 渲染控件：布尔给勾选框、数字给数字框、字符串给文本框、`object` / `array` 给 JSON 编辑框（解析失败就地提示，不写回）；右上角可切回「JSON 原文」整块编辑。必填项没填时「触发执行」是禁用的，不会先落一条注定失败的执行记录——引擎侧的 `EXEC-INPUT-001` 仍然兜底，前端只是不让它白跑一趟。

### 工作流默认模型

工具条「默认模型」写入 DSL 的 `defaults.llm`（`connectionId` + `model`），只用于**新建** llm 节点的继承。勾选「同时应用到现有 N 个 llm 节点」会把当前值批量写进已有节点，不勾则一个都不动——执行时始终以节点自身 `config` 为准，改默认值不会让线上跑着的流程偷偷换模型。

### Webhook 与定时触发

画布工具条「触发」进入该工作流的触发页（`/workflows/:id/triggers`）。触发跑的是**已发布版本**，与画布草稿无关；没发布就先「冻结并发布」，否则触发会以 409 结束。

```bash
# Webhook：令牌即凭据，签名可选但建议带
curl -X POST 'http://127.0.0.1:8080/api/v1/hooks/<token>' \
  -H 'Content-Type: application/json' \
  -H "X-AF-Signature: sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | cut -d' ' -f2)" \
  -H "X-AF-Timestamp: $(date +%s)" \
  -H 'X-AF-Idempotency-Key: order-2024-0001' \
  -d '{"question":"退款怎么申请"}'
```

| 项 | 语义 |
|---|---|
| `token` | 24 字节 Base64URL，写在路径里；轮换后旧链接立即失效 |
| `X-AF-Signature` | `sha256=HMAC_SHA256(secret, 原始请求体)`；带上才校验，比较用常量时间 |
| `X-AF-Timestamp` | 秒级时间戳，与服务器偏差超过 300 秒直接拒（防重放） |
| `X-AF-Idempotency-Key` | 同一 key 重复投递只执行一次，第二次回 `idempotentReplay: true` |
| 限流 | 每个触发入口 60 次/分钟（Redis 固定窗口），超限 `AGT-RATE_LIMIT-001` |
| 停用 | 触发页关掉「启用」后入口返回 409，不再执行 |
| `POST /api/v1/hooks/<token>/probe` | 入口自检：只回类型、启用状态与所属工作流，不触发执行、不回密钥 |

定时触发用 cron（5 段 `分 时 日 月 周`，也接受 6 段写法并归一到秒位），最小精度到分钟，时区默认 `Asia/Shanghai`；调度器每 30 秒扫一轮，以 `last_fired_at` 为基准判断是否到点，**重启不补跑**。触发配置里可以写固定的入参，webhook 请求体里的同名字段会覆盖它。

## 演示脚本（P0 验收路径）

1. 登录 `editor@agentflow.dev` → 新建工作流 `demo`。
2. 画布：start → llm(draft) → end；或打开「DSL」粘贴 `docs/architecture.md` §6.4 的示例（LLM→检索→LLM）→ 应用。
3. Ctrl+S 保存（右上角状态变「已保存」）→「冻结并发布」。
4. 「运行」→ 变量 `{"question": "我的手机 13812345678 无法开机"}` → 触发。
5. 执行详情：节点树实时变亮（SSE），点节点看输入/输出原文——手机号已脱敏为 `[PHONE]`；右栏 token/steps/traceId。
6. 回到执行列表刷新：记录仍在（持久化）。
7. 登录 `beta@agentflow.dev`，用第 5 步 URL 里的 executionId 直接访问 → 404。
8. 登录 `orgadmin@agentflow.dev`（只在 Demo Org 上是 ADMIN）：进入 Space Alpha/Beta 均可读写——三级继承；跨到无关空间仍 404。
9. 「连接器」页（ADMIN）：新建 HTTP 连接器 → 填 `baseUrl`/`allowedHosts` → 授权密钥（只存密文）→ 在工作流 tool 节点引用其 id。指向内网/环回会被沙箱拒绝（`CONN-SANDBOX-001`）。

## 演示脚本（P3 验收路径）

1. 用 `editor@agentflow.dev` 建一个含 `human_gate` 的工作流：`start → transform(prep) → human_gate → (approved) ok / (rejected) no → end`，
   gate 的 `approvers` 填 `{"type":"ROLE","role":"ADMIN"}`，`promptTemplate` 写成 `确认要发布吗：{{ vars.mid }}`，
   `onTimeout` 填 `{"action":"REJECT"}`。冻结并发布 → 触发。
2. 执行详情里执行状态为 **SUSPENDED**，`gate` 节点也是 SUSPENDED（不是 RUNNING/FAILED）。
3. 用 `admin@agentflow.dev` 打开「审批中心」：看到待办（提示词、发起人、倒计时，审批人快照已冻结）→
   填评论→「通过」→ 执行续跑并走 `approved` 分支；「驳回」走 `rejected` 分支，另一分支显式 `NodeSkipped`。
4. 不裁决也能收敛：`onTimeout.action=REJECT` 时，超时后引擎按 `REJECT` 自动裁决（`resolvedBy=system:timeout`）。
5. 「评测」页：建数据集 → 加两个用例（一个能对上、一个对不上）→ 启动评测 → 结果 1 通过 1 失败；
   同一数据集对两个版本各跑一次 → 「A/B」表格给出 `winner` 与双方通过率/耗时/token。

停止：`./scripts/stop-dev.ps1`（`-Infra` 连容器一起停）。

重跑不必先手动停：`start-dev` 在构建前会先执行 `stop-dev` 并清掉残留的半成品 `*.jar.original`。
Windows 上运行中的 jar 是独占打开的，不先停就会在 `spring-boot:repackage` 改名时失败。
`stop-dev` 只停本仓库的进程（按仓库路径判定），不会影响同机其他项目的同名服务。

## 演示脚本（内置示例：审批 + 并行 + 循环）

种子数据里有两条已发布示例，`wf-demo-triage`（「示例：客户工单分诊」）在一条链路上跑齐了条件、并行、审批、循环四种结构：

1. 用 `admin@agentflow.dev` 打开「示例：客户工单分诊」→「运行」，变量填
   `{"ticket": "登录页验证码收不到，客户很急", "urgent": true}` → 触发。
2. 前四步直接跑完（分诊 llm → 加急判定 condition → 并行取证 parallel），到「加急审批」停下，
   执行状态 **SUSPENDED**。审批人规则是表达式 `user.platformAdmin == true`：平台管理员命中，
   普通 EDITOR 不命中（用 `editor@agentflow.dev` 打开审批中心看不到这条待办）。
3. 审批中心点「通过」→ 引擎续跑 `approved` 支：循环两轮润色 → 撰写回复 → 收口，`rejected` 支
   显式 `NodeSkipped`。执行详情里的节点键是 `gather#kb#kb_hit`、`polish#1#polish_sign` 这种带层级的
   形式——前缀即子图名与循环轮次。
4. 验证驳回：再触发一次，审批中心点「驳回」→ 走 `notify`（转人工复核）→ 收口。

## 健康检查

每个服务都暴露探活端点，可直接作为容器 liveness / readiness 探针：

| 服务 | 端点 | 反映的依赖 |
|---|---|---|
| agent-gateway :8080 | `GET /health` | Redis（限流、幂等） |
| workflow-service :8084 | `GET /health` | MySQL + Redis |
| connector-service :8085 | `GET /health` | MySQL + Redis |
| audit-service :8086 | `GET /health` | MySQL |
| engine-api :8000 | `GET /internal/health` | 进程存活 |
| engine-api :8000 | `GET /internal/metrics` | Prometheus 文本（需内部令牌） |

返回 200 为 `UP`，503 为 `DEGRADED`（至少一个依赖不可用，不应继续接流量）。这些端点不需要凭据——它们在 `/api/v1/**` 之外，不经过网关的认证、限流与熔断过滤器，可直接配成 docker-compose healthcheck 或 k8s 的 liveness / readiness probe。

生产守护开启时不返回依赖明细，只给 `unavailable`，避免把内部拓扑暴露给未认证调用方。

## 工程门禁

```powershell
./scripts/verify-all.ps1      # 本地三端全量：mvn verify / ruff+pytest / tsc+eslint+vitest+build
pytest                        # 全栈验收（仓库根目录，需先 scripts/start-dev.* 起服务）
```

CI（`.github/workflows/`）：`java-ci`（测试+Checkstyle+ArchUnit）、`python-ci`（Ruff+Pytest+testcontainers PG）、`frontend-ci`（typecheck+lint+vitest+build）、`contract-drift`（引擎 OpenAPI 生成物 vs 入库基线）、`e2e`（起真基础设施与服务，跑 `e2e/` 全栈验收，失败时上传各服务日志）。

提交规范、迁移与依赖升级流程见 [`CONTRIBUTING.md`](CONTRIBUTING.md)；版本变更见 [`CHANGELOG.md`](CHANGELOG.md)。

引擎侧新增或改动路由、请求/响应模型后，基线必须同步刷新，否则 `contract-drift` 会红：

```powershell
engine-api/.venv/Scripts/python.exe scripts/dump-openapi.py
git add docs/openapi/engine-api.json
```

## 安全

凭据加密、出网沙箱、多租户隔离等控制的位置与已知限制见 [`SECURITY.md`](SECURITY.md)。漏洞请走私下渠道上报，不要开公开 Issue。

## 环境变量

| 变量 | 默认（本地） | 说明 |
|---|---|---|
| `AF_JWT_SECRET` | dev-only-… | JWT HS256 密钥（≥32 字节），三端共享 |
| `AF_INTERNAL_TOKEN` | dev-internal-token | 服务间内部令牌（/engine/** 与引擎入站） |
| `AF_MYSQL_HOST/PORT/USER/PASSWORD` | 127.0.0.1:3306 root/root | 业务库 |
| `AF_AUDIT_MYSQL_USER` | af_audit | 审计写入账号（仅 SELECT/INSERT audit_event） |
| `AF_AUDIT_SINK` | auto | 审计出口：`auto`（`AF_MQ_ENABLED=true` 走 MQ，否则直写）/ `mq` / `direct` |
| `AF_AUDIT_RELAY_INTERVAL_MS` / `AF_AUDIT_DEGRADE_AFTER_ATTEMPTS` | 2000 / 3 | outbox 重投扫描间隔、连续失败多少次后降级直写（两者都不可达才记 `audit LOST`） |
| `AF_PG_DSN` | postgresql://agentflow:agentflow@127.0.0.1:5433/agentflow_events | 事件库 |
| `AF_REDIS_HOST/PORT` / `AF_REDIS_URL` | 127.0.0.1:6379 | 缓存/租约/预算/事件频道 |
| `AF_MQ_ENABLED` / `AF_ROCKETMQ_NAMESRV` | true（本地，compose 已带 RocketMQ） / 127.0.0.1:9876 | 审计 MQ；false 时审计改走 `direct:audit_event` 直写库（outbox 仍在，只是出口不同） |
| `AF_ROCKETMQ_GATE_TOPIC` | af-gate-decision | 裁决广播 topic（控制面发布，引擎消费；`start-dev` 与 CI 会预建） |
| `AF_MQ_CONSUME` / `AF_MQ_GATE_TOPIC` / `AF_MQ_GATE_GROUP` | true / af-gate-decision / af-engine-gate-consumer | 引擎是否消费 MQ 裁决、消费的 topic、消费组名 |
| `AF_DEFAULT_TOKEN_BUDGET` / `AF_DEFAULT_MAX_STEPS` / `AF_DEFAULT_DEADLINE_MS` | 50000 / 50 / 120000 | 租户政策上限（触发时钳制） |
| `AF_AUTHORIZED_CONNECTORS` | 空 | 额外授权的 connectorId（逗号分隔）；真实连接器由 connector-service 目录按空间自动授权 |
| `AF_CONNECTOR_SERVICE` | http://127.0.0.1:8085 | gateway → connector-service 路由目标 |
| `AF_CRED_MASTER_KEY` | 本地 dev 值（base64 32B） | 凭据主密钥；生产必须经 KMS/环境注入 |
| `AF_CRED_MASTER_KEY_PREVIOUS` | 空 | 轮换窗口内的旧主密钥（base64，逗号分隔），用于解密历史密文；全量重加密后清空 |
| `AF_PRODUCTION` | false | 生产守护开关。满足任一条件即进入守护：`AF_PRODUCTION=true`、`-Dagentflow.production=true`、`AF_ENV=production/staging`、`SPRING_PROFILES_ACTIVE` 含 `prod`。守护期内若仍用 dev 默认密钥/弱口令、未开 DB TLS 或未设 Cookie Secure，启动即 fail-fast；`AF_ENV=dev` 可显式退出守护 |
| `AF_MYSQL_USE_TLS` / `AF_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL` | false / true | 数据库 TLS；生产守护要求前者为 true，并应显式关闭后者 |
| `AF_SANDBOX_ALLOW_HTTP` / `AF_SANDBOX_ALLOW_LOOPBACK` | false / false | 沙箱是否允许明文 http / 环回地址（仅本地调试开启） |
| `AF_MONTHLY_CALL_LIMIT` / `AF_MONTHLY_TOKEN_LIMIT` | 10000 / 50000 | 成本中心月度初始限额（子租户沿祖先链取最小） |
| `AF_DEMO_ACCOUNTS` | true | 登录页是否下发演示账号提示；生产守护开启时恒不下发 |
| `AF_CHECKPOINT_INTERVAL` | 10 | 每 N 事件一次 CheckpointCreated |
| `AF_GATE_SWEEP_INTERVAL_S` | 15 | human_gate 超时裁决扫描间隔 |
| `AF_LLM_BASE_URL` | 空 | 真实模型端点（OpenAI 兼容，如 `https://api.deepseek.com/v1`）；空则节点报 `LLM-NOT_CONFIGURED-001` |
| `AF_LLM_API_KEY` | 空 | 上游密钥；只在 engine-api 进程内使用，绝不写入日志/事件/仓库 |
| `AF_LLM_MODEL` | gpt-4o-mini | 默认模型名；`start-dev` 由 `DEEPSEEK_API_KEY` 派生为 `deepseek-chat`（非推理模式，正文不会被思维链吃光） |
| `AF_LLM_TIMEOUT_S` / `AF_LLM_MAX_RESPONSE_CHARS` | 60 / 100000 | 单次调用超时、响应文本上限 |
| `AF_LLM_JSON_RESPONSE_FORMAT` | true | 若上游不支持 `response_format` 则置 false |
| `AF_LLM_SEND_SEED` | false | 是否向上游传递确定性 seed（仅部分厂商支持） |
| `AF_LLM_ALLOW_INSECURE_HTTP` | false | 生产守护例外：允许明文 http 端点（仅本地调试） |
| `AF_GATE_RELAY_INTERVAL_MS` | 5000 | 裁决 outbox 重投间隔（引擎不可达时补偿） |
| `AF_EVAL_POLL_INTERVAL_MS` | 3000 | 评测/A-B 结果收口轮询间隔 |
| `AF_RATE_LIMIT_TRIGGER_MAX` / `AF_RATE_LIMIT_LOGIN_MAX` | 20 / 10（start-dev 设 300） | 网关固定窗口限流（次/60s/用户｜IP） |
| `AF_LEASE_TTL_MS` / `AF_LEASE_RENEW_MS` | 30000 / 10000 | 执行租约 |
| `AF_GATEWAY_BASE` | http://127.0.0.1:8080 | workflow-service → gateway |
| `AF_ENGINE_SERVICE` | http://127.0.0.1:8000 | gateway /engine 路由目标 |

仓库内无任何真实密钥；以上默认值仅供本地开发。

## 已知限制

1. 审计走 outbox：事件与业务写同库同事务落 `audit_outbox`，relay 按 5s×attempts（上限 60s）退避重投，**不存在“放弃投递”**；`AF_MQ_ENABLED=false` 或 relay 连续失败达 `AF_AUDIT_DEGRADE_AFTER_ATTEMPTS`（默认 3）次时，出口切到 `direct:audit_event` 直写库，审计照样留痕。积压水位见 `GET /api/v1/admin/audit-outbox`（平台 ADMIN），已发送行保留 7 天后清理。只有在 MQ 与业务库同时不可达时才会丢，且以 `audit LOST` 记 ERROR。
2. 预算预占的崩溃泄漏窗口已由 checkpoint/恢复对账收敛（`BudgetLedger.reconcile`）；Redis 全部丢失时会从事件流重建。
3. 沙箱做「域名白名单 + 解析期私网拦截」，未做 DNS 钉扎（rebinding 由生产 egress 代理兜底）。
4. 引擎内置 RocketMQ 消费端（`engine-api/app/messaging/`，按 5.x remoting 协议用标准库自行实现，不依赖需要 librocketmq 的 C++ 客户端）：`AF_MQ_ENABLED` 与 `AF_MQ_CONSUME` 同为真时，与 HTTP 回调**并行**消费 `af-gate-decision`，重复裁决由引擎侧幂等吸收；HTTP 仍是主通道，`gate_resolve_outbox` 负责引擎不可达时的重投补偿。消费端只做短轮询（不发心跳、不挂起长轮询），状态见 `GET /internal/mq/status`。
5. Java 侧 OpenAPI 生成物未入库（contract-drift 覆盖引擎侧）。
6. `human_gate` 的 `EXPRESSION` 审批人只认受限子集：属性 `user.id` / `user.role` / `user.rank` / `user.platformAdmin` / `space.id`，比较 `== != < <= > >=`、`in` / `not in`、`and` / `or` / `not`，字面量支持字符串、整数、布尔、null 与列表；**不支持函数调用与下标**。三处同文法——发布期在引擎侧以 `ast` 白名单静态校验、运行期由控制面逐候选求值、画布编辑时即时体检；表达式为空或非法一律 fail-closed（无人可批，记 ERROR）。子图（loop/parallel/subworkflow）内不允许 `human_gate`。
7. 评测与 A/B 用轮询收口（默认 3s），不是事件驱动；用例数上限 50/次。
8. `loop.body` 与 `parallel.branches` 是子图，画布上有独立子图画布：面包屑逐层进出，层内可增删连线，子图内不能放 `start`、也不要求 `end`（出口由上层节点收口），画布最多下钻 4 层；JSON 编辑保留为「高级」入口，与表单实时双向同步。校验能定位到 `loop_1.body#body_end` 这样的层内 key。子图内可用 `vars.<itemVar>` / `vars.<indexVar>`，无需声明；不允许出现 `human_gate`（挂起后无法原地续跑）。
9. `retrieval` 检索为本地词法 BM25（中文单字 + 二元组），不是语义向量召回；无 rerank、无 PDF/Office 解析、无增量索引重建。不引入 embedding 服务，文档内容不出服务端。单次检索扫描切片上限 20000，`topK` 上限 50，单文档上限 200000 字符 / 500 切片；超出导入会在请求期被拒绝，不会静默截断。
10. 默认模型用非推理的 `deepseek-chat`。DeepSeek 的 `deepseek-flash` / `deepseek-v4-pro` 是推理模型，`max_tokens` 同时约束思维链与正文；给到 1024 时思维链可能吃光预算、正文为空，节点以 `LLM-RESPONSE-001` 失败并提示调大 `maxTokens`。这是上游行为，不会退回示例文本。
11. 定时触发由 workflow-service 内的单实例调度器扫表（默认 30s 一轮），多副本部署会重复扫描同一批行——目前靠 `last_fired_at` 的乐观更新与幂等键 `sched-{triggerId}-{分钟}` 收口，尚未做主备选举。触发粒度到分钟，不支持秒级；停机期间的到点不会补跑。Webhook 限流是单实例固定窗口（60 次/分钟），多副本时需要换成 Redis 原子计数器。
12. 试跑不落执行记录、不计入成本中心与评测：它跑的是真实副作用（模型调用会真的计费），但没有 `workflow_execution` 行，token 只出现在试跑面板与 `NODE_PREVIEWED` 审计里。上游含 `loop` / `parallel` / `human_gate` 的节点不给试跑。
13. 模型能力标签不是权威数据：上游 `/models` 给出字段才标得更准，只回 id 时按模型名推断并显式标「推断」。标签只影响界面提示与 `maxTokens` 建议，不改变实际下发的请求。

## 目录

```
common/ agent-gateway/ workflow-service/ audit-service/   # Java 控制面
engine-api/                                              # Python 执行引擎（数据面）
frontend/                                                # Vue3 + Vue Flow
db/mysql|postgres/                                       # 迁移 + 授权 + 事件库
e2e/                                                     # API 级验收（打 gateway）
ops/ scripts/ docs/                                      # 基础设施 / 脚本 / 架构与契约
```
