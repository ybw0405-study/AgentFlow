# DSL 规范、输出契约、错误码与可观测

> 本文件由 `docs/architecture.md` 按主题拆分归档，正文保持原文（含原章节编号）。
> 章节索引与 `§` 交叉引用对照见 [docs/README.md](../README.md)。

## 6. DSL 规范

### 6.1 设计原则

- 单文件 JSON，`dslVersion` 显式声明；画布坐标等 UI 元数据放 `position`，引擎忽略。
- 变量引用统一两种形态：模板字符串 `"{{ nodes.a.output.text }}"`（Jinja2 沙箱）与受限表达式 `vars.score > 0.5`（simpleeval 白名单）。
- 循环/并行的子图复用同一 `graph` 结构递归定义。
- 表达式可用名：`vars.*`、`nodes.<key>.output.*`、`nodes.<key>.status`、`loop.item/index`（循环体内）；白名单函数：`len upper lower contains startsWith endsWith int float str bool abs min max round json_get`。**禁属性下划线访问、禁 import、禁任意 eval。**

### 6.2 完整 JSON Schema（草案 v1.0）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://agentflow/schemas/workflow-dsl/1.0.json",
  "title": "AgentFlow Workflow DSL v1.0",
  "type": "object",
  "required": ["dslVersion", "nodes", "edges"],
  "additionalProperties": false,
  "properties": {
    "dslVersion": { "const": "1.0" },
    "variables": {
      "type": "object",
      "additionalProperties": {
        "type": "object",
        "required": ["type"],
        "additionalProperties": false,
        "properties": {
          "type": { "enum": ["string","number","integer","boolean","object","array"] },
          "required": { "type": "boolean", "default": false },
          "default": {},
          "pii": { "type": "boolean", "default": false },
          "description": { "type": "string" }
        }
      }
    },
    "defaults": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "timeoutMs": { "type": "integer", "minimum": 100, "maximum": 600000, "default": 30000 },
        "maxParallelism": { "type": "integer", "minimum": 1, "maximum": 32, "default": 4 },
        "retry": { "$ref": "#/$defs/retry" }
      }
    },
    "nodes": { "type": "array", "minItems": 2, "items": { "$ref": "#/$defs/node" } },
    "edges": { "type": "array", "items": { "$ref": "#/$defs/edge" } }
  },
  "$defs": {
    "nodeKey": { "type": "string", "pattern": "^[a-z][a-zA-Z0-9_-]{0,63}$" },
    "retry": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "maxAttempts": { "type": "integer", "minimum": 1, "maximum": 5, "default": 3 },
        "backoffMs": { "type": "integer", "minimum": 100, "default": 1000 },
        "backoffMultiplier": { "type": "number", "minimum": 1, "default": 2.0 }
      }
    },
    "graph": {
      "type": "object",
      "required": ["nodes", "edges"],
      "additionalProperties": false,
      "properties": {
        "nodes": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/node" } },
        "edges": { "type": "array", "items": { "$ref": "#/$defs/edge" } }
      }
    },
    "edge": {
      "type": "object",
      "required": ["from", "to"],
      "additionalProperties": false,
      "properties": {
        "from": { "$ref": "#/$defs/nodeKey" },
        "to": { "$ref": "#/$defs/nodeKey" },
        "branch": { "type": "string", "description": "condition/human_gate 出边的分支名" }
      }
    },
    "node": {
      "type": "object",
      "required": ["key", "type", "config"],
      "additionalProperties": false,
      "properties": {
        "key": { "$ref": "#/$defs/nodeKey" },
        "type": { "enum": ["start","end","llm","retrieval","tool","mcp","condition",
                            "loop","parallel","transform","subworkflow","human_gate"] },
        "name": { "type": "string" },
        "timeoutMs": { "type": "integer", "minimum": 100, "maximum": 600000 },
        "retry": { "$ref": "#/$defs/retry" },
        "position": { "type": "object",
                      "properties": { "x": { "type": "number" }, "y": { "type": "number" } } },
        "config": { "type": "object" }
      },
      "allOf": [
        { "if": { "properties": { "type": { "const": "start" } } },
          "then": { "properties": { "config": { "type": "object", "maxProperties": 0 } } } },
        { "if": { "properties": { "type": { "const": "end" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["resultMapping"], "additionalProperties": false,
            "properties": { "resultMapping": { "type": "object", "minProperties": 1,
              "additionalProperties": { "type": "string" } } } } } } },
        { "if": { "properties": { "type": { "const": "llm" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["model","promptTemplate"], "additionalProperties": false,
            "properties": {
              "provider": { "const": "openai-compatible", "default": "openai-compatible" },
              "connectionId": { "type": "string", "minLength": 1,
                "description": "tool_connector 中 type=LLM 的记录 id；端点与密钥只在 connector-service，DSL 不含 URL/Key" },
              "model": { "type": "string", "minLength": 1 },
              "promptTemplate": { "type": "string", "minLength": 1 },
              "systemPrompt": { "type": "string" },
              "temperature": { "type": "number", "minimum": 0, "maximum": 2, "default": 0.7 },
              "maxTokens": { "type": "integer", "minimum": 1, "maximum": 32000, "default": 1024 },
              "outputMode": { "enum": ["text","json"], "default": "text" },
              "outputSchema": { "type": "object",
                "description": "outputMode=json 时必填的 JSON Schema" }
            } } } } },
        { "if": { "properties": { "type": { "const": "retrieval" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["knowledgeBaseIds","queryTemplate"],
            "additionalProperties": false,
            "properties": {
              "knowledgeBaseIds": { "type": "array", "minItems": 1,
                "items": { "type": "string" } },
              "queryTemplate": { "type": "string", "minLength": 1 },
              "topK": { "type": "integer", "minimum": 1, "maximum": 50, "default": 5 },
              "minScore": { "type": "number", "minimum": 0, "maximum": 1, "default": 0 },
              "rerank": { "type": "boolean", "default": false }
            } } } } },
        { "if": { "properties": { "type": { "const": "tool" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["connectorId","operation"],
            "additionalProperties": false,
            "properties": {
              "connectorId": { "type": "string", "minLength": 1 },
              "operation": { "type": "string", "minLength": 1 },
              "paramsMapping": { "type": "object",
                "additionalProperties": { "type": "string" } },
              "idempotent": { "type": "boolean", "default": true,
                "description": "false 时禁止重试" }
            } } } } },
        { "if": { "properties": { "type": { "const": "mcp" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["connectorId","capability"],
            "additionalProperties": false,
            "properties": {
              "connectorId": { "type": "string", "minLength": 1 },
              "capability": { "enum": ["resource","prompt"] },
              "uri": { "type": "string", "description": "capability=resource 必填，模板字符串可用" },
              "promptName": { "type": "string", "description": "capability=prompt 必填" },
              "argumentsMapping": { "type": "object",
                "additionalProperties": { "type": "string" } },
              "idempotent": { "type": "boolean", "default": true,
                "description": "false 时禁止重试" }
            } } } } },
        { "if": { "properties": { "type": { "const": "condition" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["cases"], "additionalProperties": false,
            "properties": {
              "cases": { "type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["name","when"], "additionalProperties": false,
                "properties": { "name": { "type": "string" },
                                "when": { "type": "string" } } } },
              "elseBranch": { "type": "string", "default": "default" }
            } } } } },
        { "if": { "properties": { "type": { "const": "loop" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["itemsExpression","body"],
            "additionalProperties": false,
            "properties": {
              "itemsExpression": { "type": "string" },
              "itemVar": { "type": "string", "default": "item" },
              "indexVar": { "type": "string", "default": "index" },
              "body": { "$ref": "#/$defs/graph" },
              "maxIterations": { "type": "integer", "minimum": 1, "maximum": 1000, "default": 100 },
              "concurrency": { "type": "integer", "minimum": 1, "maximum": 16, "default": 1 },
              "collect": { "type": "object", "required": ["into","item"],
                "additionalProperties": false,
                "properties": { "into": { "type": "string" },
                                "item": { "type": "string" } } }
            } } } } },
        { "if": { "properties": { "type": { "const": "parallel" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["branches"], "additionalProperties": false,
            "properties": {
              "branches": { "type": "object", "minProperties": 2,
                "additionalProperties": { "$ref": "#/$defs/graph" } },
              "join": { "enum": ["all","first_success"], "default": "all" },
              "maxConcurrency": { "type": "integer", "minimum": 1, "maximum": 16, "default": 4 }
            } } } } },
        { "if": { "properties": { "type": { "const": "transform" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["mappings"], "additionalProperties": false,
            "properties": {
              "mappings": { "type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["target","template"],
                "additionalProperties": false,
                "properties": {
                  "target": { "type": "string", "pattern": "^vars\\." },
                  "template": { "type": "string" } } } } }
            } } } } },
        { "if": { "properties": { "type": { "const": "subworkflow" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["definitionId"], "additionalProperties": false,
            "properties": {
              "definitionId": { "type": "string", "minLength": 1 },
              "versionPolicy": { "enum": ["LATEST_PUBLISHED","FIXED"],
                "default": "LATEST_PUBLISHED" },
              "versionId": { "type": "string",
                "description": "versionPolicy=FIXED 时必填" },
              "inputMapping": { "type": "object",
                "additionalProperties": { "type": "string" } },
              "outputMapping": { "type": "object",
                "additionalProperties": { "type": "string" } }
            } } } } },
        { "if": { "properties": { "type": { "const": "human_gate" } } },
          "then": { "properties": { "config": {
            "type": "object", "required": ["approvers","promptTemplate"],
            "additionalProperties": false,
            "properties": {
              "approvers": { "type": "object", "required": ["type"],
                "additionalProperties": false,
                "properties": {
                  "type": { "enum": ["ROLE","USERS","EXPRESSION"] },
                  "role": { "enum": ["OWNER","ADMIN","EDITOR","VIEWER"] },
                  "userIds": { "type": "array", "items": { "type": "string" } },
                  "expression": { "type": "string" } } },
              "promptTemplate": { "type": "string", "minLength": 1 },
              "timeoutMs": { "type": "integer", "minimum": 60000,
                "maximum": 604800000, "default": 86400000 },
              "onTimeout": { "type": "object", "required": ["action"],
                "additionalProperties": false,
                "properties": {
                  "action": { "enum": ["REJECT","DEFAULT_BRANCH"] },
                  "defaultBranch": { "type": "string" } } }
            } } } } }
      ]
    }
  }
}
```

### 6.3 编译期校验规则（Schema 之外，422 + 节点路径）

| 规则 | 错误码 |
|---|---|
| JSON Schema 不通过 | `DSL-VALIDATION-001` |
| 重复 nodeKey | `DSL-VALIDATION-001`（指出冲突 key） |
| 边引用了不存在的节点 | `DSL-REF-001` |
| 图有环（含循环体内部） | `DSL-CYCLE-001`（列出环上 nodeKey） |
| 引用未声明变量 / 引用了非上游节点输出 | `DSL-REF-001` |
| 悬空节点（start 不可达） | `DSL-ORPHAN-001` |
| condition/human_gate 出边 branch 与 cases 不匹配 | `DSL-BRANCH-001` |
| 恰好 1 个 start、≥1 个 end；loop body 内禁止 start/end/subworkflow 指向自身定义 | `DSL-VALIDATION-001` |
| `outputMode=json` 但缺 `outputSchema`；`versionPolicy=FIXED` 但缺 `versionId` | `DSL-VALIDATION-001` |
| 循环体节点引用体外的兄弟节点（允许引用祖先与 vars） | `DSL-REF-001` |

### 6.4 示例（P0 演示工作流「LLM → 工具 → LLM」）

```json
{
  "dslVersion": "1.0",
  "variables": { "question": { "type": "string", "required": true, "pii": true } },
  "defaults": { "timeoutMs": 30000, "maxParallelism": 4,
                "retry": { "maxAttempts": 3, "backoffMs": 1000, "backoffMultiplier": 2 } },
  "nodes": [
    { "key": "start", "type": "start", "config": {} },
    { "key": "draft", "type": "llm", "config": {
        "provider": "openai-compatible", "model": "deepseek-chat",
        "promptTemplate": "请根据问题起草答案要点：{{ vars.question }}",
        "outputMode": "text", "maxTokens": 1024 } },
    { "key": "enrich", "type": "retrieval", "config": {
        "knowledgeBaseIds": ["kb-demo"],
        "queryTemplate": "{{ vars.question }}", "topK": 5, "minScore": 0 } },
    { "key": "final", "type": "llm", "config": {
        "provider": "openai-compatible", "model": "deepseek-chat",
        "promptTemplate": "结合要点与检索给出最终回答。\n要点：{{ nodes.draft.output.text }}\n检索：{{ nodes.enrich.output.result }}\n问题：{{ vars.question }}",
        "outputMode": "text", "maxTokens": 1200 } },
    { "key": "end", "type": "end", "config": {
        "resultMapping": { "answer": "{{ nodes.final.output.text }}" } } }
  ],
  "edges": [
    { "from": "start", "to": "draft" },
    { "from": "start", "to": "enrich" },
    { "from": "draft", "to": "final" },
    { "from": "enrich", "to": "final" },
    { "from": "final", "to": "end" }
  ]
}
```

---

## 7. 节点输出契约（重放与下游引用的依据）

| 类型 | output 结构 | 可重试 |
|---|---|---|
| start | `{ variables }`（校验+默认值合并后） | — |
| end | `{ result }` | — |
| llm | `{ text, parsed?, tokenUsage{prompt,completion,total}, model, latencyMs }` | 瞬时 |
| retrieval | `{ chunks[{id,score,snippet,citation}], query, tookMs }` | 瞬时 |
| tool | `{ result, callId, latencyMs }` 或 `{ error }` | 视 connector.idempotent |
| condition | `{ matchedBranch, evaluated[] }` | — |
| loop | `{ items[], completed, limitExceeded? }` | 瞬时 |
| parallel | `{ branches{ <name>: output }, join }` | 瞬时 |
| transform | `{ variables }`（写入的 vars 子集） | — |
| subworkflow | `{ childExecutionId, result, tokenUsed }` | 瞬时 |
| human_gate | `{ decision: approved/rejected, resolvedBy, comment? }` | — |

---

## 8. 错误码目录

| 码 | HTTP | 场景 |
|---|---|---|
| `AGT-AUTH-001` | 401 | 未认证/令牌失效（不区分账号是否存在） |
| `AGT-RATE_LIMIT-001` | 429 | 网关限流 |
| `AGT-UPSTREAM-001` | 502 | engine 不可用（超时/连接失败） |
| `AGT-UPSTREAM-003` | 503 | Kill Switch 开启 |
| `WF-NOT_FOUND-001` | 404 | 资源不存在或跨租户（不区分） |
| `WF-FORBIDDEN-001` | 403 | 角色不足（审计记录尝试） |
| `WF-IMMUTABLE-001` | 409 | 修改已冻结版本 |
| `WF-STATE-001` | 409 | 非法状态转移 |
| `WF-POLICY-CLAMPED` | — | 仅审计：policy 被下调 |
| `DSL-VALIDATION-001` / `DSL-CYCLE-001` / `DSL-REF-001` / `DSL-ORPHAN-001` / `DSL-BRANCH-001` | 422 | DSL 校验族 |
| `EXEC-LOOP_LIMIT_EXCEEDED` | 200（执行内） | 循环超限，执行 FAILED |
| `EXEC-BUDGET_EXHAUSTED` | 200（执行内） | 预算硬停止 |
| `EXEC-DEADLINE_EXCEEDED` | 200（执行内） | deadline 到达 → TIMED_OUT |
| `PII-BLOCKED-001` | 200（执行内） | PII 第二层校验命中，节点永久失败 |
| `EXPR-EVAL-001` | 200（执行内） | 表达式求值失败（永久） |
| `LLM-RATE_LIMIT` / `TOOL-TIMEOUT` 等 | — | 瞬时，触发重试 |
| `LLM-NOT_CONFIGURED-001` | 200（执行内） | 真实 provider 未配置 `AF_LLM_BASE_URL` / `AF_LLM_API_KEY`（永久） |
| `LLM-AUTH-001` | 200（执行内） | 上游 401/403，密钥无效（永久，不重试） |
| `LLM-REQUEST-001` | 200（执行内） | 上游 400/404/422，请求或模型名非法（永久） |
| `LLM-RATE_LIMIT-001` | 200（执行内） | 上游 429（瞬时，重试） |
| `LLM-TIMEOUT-001` | 200（执行内） | 上游超时（瞬时，重试） |
| `LLM-UPSTREAM-001` | 200（执行内） | 上游 5xx / 网络异常（瞬时，重试） |
| `LLM-RESPONSE-001` | 200（执行内） | 上游响应体非法、空内容或超限（永久） |

---

## 9. 可观测性

- **trace**：W3C `traceparent` 前端 → gateway → workflow-service → engine → Provider；所有日志行含 `traceId`、`executionId`、`tenantId`（MDC / structlog contextvars）。
- **追踪后端（可选）**：`AF_LANGFUSE_TRACING_ENABLED=true` 时引擎经第二个 OTLP HTTP exporter 把执行/节点 span 导出到**复用的 DocQA 自托管 LangFuse**（独立 project，pk/sk 隔离）；span 的 traceId 与透传 traceId 一致，只带 id/计数/model/outcome（脱敏同日志红线）。默认关闭，不安装 OTel 组件。详见 README「追踪导出（LangFuse）」。
- **指标**（Prometheus）：`af_execution_total{status}`、`af_execution_duration_seconds`（直方图）、`af_node_failed_total{nodeType,errorCode}`、`af_replay_total`、`af_lease_contention_total`、`af_budget_stop_total`。
- **日志红线**：禁止出现原始 PII、提示词全文、用户问题原文、连接器密钥、DSN（CI grep + Code Review 双保险）。PII 日志只记类别与数量。

---

