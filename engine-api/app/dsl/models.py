"""DAG DSL 的 Pydantic v2 模型。与 docs/architecture.md §6.2 JSON Schema 一一对应。

JSON 侧统一 camelCase（alias_generator=to_camel）；规范化与 checksum 用
`model_dump(by_alias=True)` 后的 canonical JSON。
"""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

NodeKey = Annotated[str, Field(pattern=r"^[a-z][a-zA-Z0-9_-]{0,63}$")]


class CamelModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid", alias_generator=to_camel)

    def canonical_dump(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, mode="json", exclude_none=True)


# ---------- 通用 ----------


class VariableSpec(CamelModel):
    type: Literal["string", "number", "integer", "boolean", "object", "array"]
    required: bool = False
    default: Any = None
    pii: bool = False
    description: str | None = None


class RetryPolicy(CamelModel):
    max_attempts: int = Field(3, ge=1, le=5)
    backoff_ms: int = Field(1000, ge=100)
    backoff_multiplier: float = Field(2.0, ge=1.0)


class LlmDefaults(CamelModel):
    """工作流级默认模型：新建 llm 节点时继承。

    执行时仍以节点自己的 config 为准——这里不参与解析，避免「改了默认模型，
    线上跑的还是老模型」这种看不见的行为变化。
    """

    connection_id: str | None = None
    model: str | None = None


class Defaults(CamelModel):
    timeout_ms: int = Field(30_000, ge=100, le=600_000)
    max_parallelism: int = Field(4, ge=1, le=32)
    retry: RetryPolicy = Field(default_factory=RetryPolicy)
    llm: LlmDefaults | None = None


class Edge(CamelModel):
    from_: NodeKey = Field(alias="from")
    to: NodeKey
    branch: str | None = None


class Position(CamelModel):
    x: float = 0
    y: float = 0


# ---------- 节点 config ----------


class StartConfig(CamelModel):
    pass


class EndConfig(CamelModel):
    result_mapping: dict[str, str] = Field(min_length=1)


class LlmConfig(CamelModel):
    # 模型连接（tool_connector 中 type=LLM 的一条记录）的 id。给了它就走连接器托管的
    # 端点与凭据；为空时走进程级 AF_LLM_* 端点。两条路径都只调用真实模型。
    connection_id: str | None = None
    provider: Literal["openai-compatible"] = "openai-compatible"
    model: str = Field(min_length=1)
    prompt_template: str = Field(min_length=1)
    system_prompt: str | None = None
    temperature: float = Field(0.7, ge=0, le=2)
    max_tokens: int = Field(1024, ge=1, le=32_000)
    output_mode: Literal["text", "json"] = "text"
    output_schema: dict[str, Any] | None = None

    @field_validator("provider", mode="before")
    @classmethod
    def _reject_offline_provider(cls, value: Any) -> Any:
        """离线 provider 已从产品中移除，历史 DSL 直接给出可操作的错误。"""
        if isinstance(value, str) and value.strip().lower() == "mock":
            raise ValueError(
                "离线 mock 模型已移除：请为节点设置 connectionId，或改用进程端点 AF_LLM_*"
            )
        return value

    @field_validator("connection_id")
    @classmethod
    def _blank_connection_is_none(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        return stripped or None

    @model_validator(mode="after")
    def _json_mode_needs_schema(self) -> LlmConfig:
        if self.output_mode == "json" and not self.output_schema:
            raise ValueError("outputMode=json requires outputSchema")
        return self


class RetrievalConfig(CamelModel):
    knowledge_base_ids: list[str] = Field(min_length=1)
    query_template: str = Field(min_length=1)
    top_k: int = Field(5, ge=1, le=50)
    min_score: float = Field(0.0, ge=0, le=1)
    rerank: bool = False
    retrieval_mode: Literal["lexical", "vector", "hybrid"] = "lexical"


class ToolConfig(CamelModel):
    connector_id: str = Field(min_length=1)
    operation: str = Field(min_length=1)
    params_mapping: dict[str, str] = Field(default_factory=dict)
    idempotent: bool = True # False 时禁止重试


class McpConfig(CamelModel):
    """MCP 非工具能力：resource 读服务端资源，prompt 取提示模板。tools 仍走 tool 节点。"""

    connector_id: str = Field(min_length=1)
    capability: Literal["resource", "prompt"]
    uri: str | None = None
    prompt_name: str | None = None
    arguments_mapping: dict[str, str] = Field(default_factory=dict)
    idempotent: bool = True # False 时禁止重试

    @model_validator(mode="after")
    def _locator_matches_capability(self) -> McpConfig:
        if self.capability == "resource":
            if not self.uri or self.prompt_name:
                raise ValueError("resource capability requires uri and forbids promptName")
        elif not self.prompt_name or self.uri:
            raise ValueError("prompt capability requires promptName and forbids uri")
        return self


class ConditionCase(CamelModel):
    name: str = Field(min_length=1)
    when: str = Field(min_length=1)


class ConditionConfig(CamelModel):
    cases: list[ConditionCase] = Field(min_length=1)
    else_branch: str = "default"


class LoopConfig(CamelModel):
    items_expression: str = Field(min_length=1)
    item_var: str = "item"
    index_var: str = "index"
    body: SubGraph
    max_iterations: int = Field(100, ge=1, le=1000)
    concurrency: int = Field(1, ge=1, le=16)
    collect: LoopCollect | None = None


class LoopCollect(CamelModel):
    into: str = Field(min_length=1)
    item: str = Field(min_length=1)


class ParallelConfig(CamelModel):
    branches: dict[str, SubGraph] = Field(min_length=2)
    join: Literal["all", "first_success"] = "all"
    max_concurrency: int = Field(4, ge=1, le=16)


class TransformMapping(CamelModel):
    target: str = Field(pattern=r"^vars\.")
    template: str


class TransformConfig(CamelModel):
    mappings: list[TransformMapping] = Field(min_length=1)


class SubworkflowConfig(CamelModel):
    definition_id: str = Field(min_length=1)
    version_policy: Literal["LATEST_PUBLISHED", "FIXED"] = "LATEST_PUBLISHED"
    version_id: str | None = None
    input_mapping: dict[str, str] = Field(default_factory=dict)
    output_mapping: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _fixed_needs_version(self) -> SubworkflowConfig:
        if self.version_policy == "FIXED" and not self.version_id:
            raise ValueError("versionPolicy=FIXED requires versionId")
        return self


class ApproverRule(CamelModel):
    type: Literal["ROLE", "USERS", "EXPRESSION"]
    role: Literal["OWNER", "ADMIN", "EDITOR", "VIEWER"] | None = None
    user_ids: list[str] | None = None
    expression: str | None = None


class GateTimeout(CamelModel):
    action: Literal["REJECT", "DEFAULT_BRANCH"]
    default_branch: str | None = None


class HumanGateConfig(CamelModel):
    approvers: ApproverRule
    prompt_template: str = Field(min_length=1)
    timeout_ms: int = Field(86_400_000, ge=60_000, le=604_800_000)
    on_timeout: GateTimeout = Field(default_factory=lambda: GateTimeout(action="REJECT"))


# ---------- 节点（判别联合） ----------


class NodeBase(CamelModel):
    key: NodeKey
    name: str | None = None
    timeout_ms: int | None = Field(None, ge=100, le=600_000)
    retry: RetryPolicy | None = None
    position: Position | None = None


class StartNode(NodeBase):
    type: Literal["start"]
    config: StartConfig


class EndNode(NodeBase):
    type: Literal["end"]
    config: EndConfig


class LlmNode(NodeBase):
    type: Literal["llm"]
    config: LlmConfig


class RetrievalNode(NodeBase):
    type: Literal["retrieval"]
    config: RetrievalConfig


class ToolNode(NodeBase):
    type: Literal["tool"]
    config: ToolConfig


class McpNode(NodeBase):
    type: Literal["mcp"]
    config: McpConfig


class ConditionNode(NodeBase):
    type: Literal["condition"]
    config: ConditionConfig


class LoopNode(NodeBase):
    type: Literal["loop"]
    config: LoopConfig


class ParallelNode(NodeBase):
    type: Literal["parallel"]
    config: ParallelConfig


class TransformNode(NodeBase):
    type: Literal["transform"]
    config: TransformConfig


class SubworkflowNode(NodeBase):
    type: Literal["subworkflow"]
    config: SubworkflowConfig


class HumanGateNode(NodeBase):
    type: Literal["human_gate"]
    config: HumanGateConfig


AnyNode = Annotated[
    StartNode
    | EndNode
    | LlmNode
    | RetrievalNode
    | ToolNode
    | McpNode
    | ConditionNode
    | LoopNode
    | ParallelNode
    | TransformNode
    | SubworkflowNode
    | HumanGateNode,
    Field(discriminator="type"),
]


class SubGraph(CamelModel):
    nodes: list[AnyNode] = Field(min_length=1)
    edges: list[Edge] = Field(default_factory=list)


LoopConfig.model_rebuild()
ParallelConfig.model_rebuild()


class WorkflowDsl(CamelModel):
    dsl_version: Literal["1.0"]
    variables: dict[str, VariableSpec] = Field(default_factory=dict)
    defaults: Defaults = Field(default_factory=Defaults)
    nodes: list[AnyNode] = Field(min_length=2)
    edges: list[Edge] = Field(default_factory=list)
