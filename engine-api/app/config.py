"""运行时配置。全部经环境变量注入（前缀 AF_），仓库内无任何真实密钥。"""

import os
from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AF_", env_file=".env", extra="ignore", populate_by_name=True
    )

    pg_dsn: str = "postgresql://agentflow:agentflow@127.0.0.1:5433/agentflow_events"
    redis_url: str = "redis://127.0.0.1:6379/0"
    # 出站回调控制面用的共享令牌（gateway / workflow-service / connector-service 认它）。
    internal_token: str = "dev-internal-token" # noqa: S105 # 本地默认值；部署必须经 AF_INTERNAL_TOKEN 覆盖
    # 入站 /internal/** 专用凭据。网关转发时会**剥离客户端的 X-Internal-Token**
    # 并注入这个值；客户端即使持有控制面令牌也无法直连引擎的全局事件/执行接口。
    # 未显式配置时回退 AF_INTERNAL_TOKEN，保证本地 dev 与现有部署零改动；生产模式
    # 由 _guard_production 强制要求显式配置且与控制面令牌不同。
    engine_token: str = Field(
        default="dev-internal-token",
        validation_alias=AliasChoices("AF_ENGINE_INTERNAL_TOKEN", "AF_INTERNAL_TOKEN"),
    )
    gateway_base_url: str = "http://127.0.0.1:8080" # 引擎回调控制面的唯一入口（§5.2）
    pg_migrations_path: str = "../db/postgres/migration"

    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # 阶段门：只有已实现执行器的节点类型才放行（DSL 校验阶段拒绝未实现的）
    # P0: start/end/llm/tool；P1 逐步放开 condition/transform/loop/parallel/subworkflow；
    # retrieval 在文档知识库落地后放开（V009 + KnowledgeService + RetrievalExecutor）。
    supported_node_types: frozenset[str] = frozenset(
        {
            "start",
            "end",
            "llm",
            "retrieval",
            "tool",
            "condition",
            "transform",
            "loop",
            "parallel",
            "subworkflow",
            "human_gate",
            "mcp",
        }
    )

    gate_sweep_interval_s: int = 15 # human_gate 超时扫描间隔

    # 裁决 MQ 通道（引擎主动拉取 af-gate-decision）。AF_MQ_ENABLED 与 Java 侧同名，
    # 一个开关同时管生产与消费；mq_consume 是子开关，用于「只发不收」的灰度场景。
    mq_enabled: bool = False
    mq_consume: bool = True
    mq_namesrv: str = "127.0.0.1:9876"
    # 容器部署时把 namesrv 广播的 broker host 重写为内网服务名（如 rocketmq-broker）。
    # 空值表示不重写，非容器（宿主机直连 127.0.0.1:10911 端口映射）行为不变。
    mq_broker_host: str = ""

    # 语义检索（retrieval 节点 vector/hybrid 模式）：未配置时向量模式明确报
    # RETRIEVAL-VECTOR-NOT_CONFIGURED-001，lexical 默认行为逐字节不变。
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v3"
    embedding_dimensions: int = 1024
    embedding_timeout_ms: int = 10000
    # 单次请求懒索引的切片上限（超出明确拒绝，不静默截断）
    retrieval_index_cap: int = 2000
    # rerank 精排（retrieval 节点 rerank=true 时启用）：OpenAI 兼容 /rerank 端点。
    # 未配置时 rerank=true 明确报 RERANK-NOT_CONFIGURED-001，不静默回退到融合次序。
    rerank_base_url: str = ""
    rerank_api_key: str = ""
    rerank_model: str = "bge-reranker-v2-m3"
    rerank_timeout_ms: int = 5000
    # rerank 前召回的候选放大因子：先取 top_k * factor 个候选再精排截断，
    # 让 rerank 能从更大范围捞回被向量/词法排序误杀的块。
    rerank_candidate_factor: int = 3
    mq_gate_topic: str = "af-gate-decision"
    mq_gate_group: str = "af-engine-gate-consumer"
    mq_poll_timeout_ms: int = 3_000
    mq_pull_batch: int = 32
    checkpoint_interval: int = 10 # 每 N 个事件写一次 CheckpointCreated
    lease_ttl_ms: int = 30_000 # 执行租约 TTL
    lease_renew_ms: int = 10_000 # 续约间隔
    # Redis TCP 半开防护：socket 级超时防止单次调用永久阻塞；
    # 关停 drain 上限防止某个在途任务卡死整个 lifespan 收尾。
    redis_socket_timeout_s: float = 2.0
    redis_socket_connect_timeout_s: float = 2.0
    shutdown_drain_timeout_s: float = 15.0
    health_probe_timeout_s: float = 1.0 # readiness 单次依赖探测超时
    event_payload_max_bytes: int = 256 * 1024 # 溢出转 node_io
    default_max_parallelism: int = 4
    event_tail_max_limit: int = 500

    # 真实 LLM（OpenAI 兼容 /chat/completions）。默认全部关闭：未配置时项目仍完全离线可跑。
    # 端点/密钥只从这里注入，绝不来自 DSL 或用户入参（SSRF / 凭据注入防线）。
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "gpt-4o-mini" # 默认模型名；真实调用需注入 AF_LLM_BASE_URL / AF_LLM_API_KEY
    llm_timeout_s: float = 60.0
    llm_max_response_chars: int = 100_000
    llm_json_response_format: bool = True # 上游不支持 response_format 时置 false
    llm_send_seed: bool = False # 部分厂商支持 seed，用于重放确定性；不支持时须关闭
    llm_allow_insecure_http: bool = False # 生产默认禁止明文 http 外送密钥
    llm_stream: bool = False # LLM 节点逐 token 流式（OutputDelta 展示事件）；opt-in，默认关闭

    # LangFuse 追踪导出：复用 DocQA 自托管实例（compose profile langfuse），按 project 隔离。
    # 默认关闭：不安装任何 OTel 组件，执行管道与关闭前逐字节一致。
    langfuse_tracing_enabled: bool = False
    langfuse_otlp_endpoint: str = "http://127.0.0.1:13000/api/public/otel/v1/traces"
    langfuse_public_key: str = ""
    langfuse_secret_key: str = ""
    # 非回环 endpoint 必须显式 opt-in：pk/sk 组成的 Basic 凭据默认只发往本机
    langfuse_allow_private: bool = False


_WEAK_INTERNAL_TOKENS = {"dev-internal-token", ""}
_DEV_MARKERS = {"dev", "local", "test", "development"}


def _is_true(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def production_enabled() -> bool:
    """生产守护判定，与 Java 侧 ProductionGuard 保持一致的四路信号。

    显式声明 dev/local/test 时一律不守护（便于本地以生产档位调试其余行为）。
    """
    env_name = os.getenv("AF_ENV", "").strip().lower()
    if env_name in _DEV_MARKERS:
        return False
    if _is_true(os.getenv("AF_PRODUCTION", "false")):
        return True
    if env_name in {"production", "prod", "staging"}:
        return True
    profiles = os.getenv("SPRING_PROFILES_ACTIVE", "")
    return any(p.strip().lower().startswith("prod") for p in profiles.split(","))


def _guard_production(settings: "Settings") -> None:
    """生产模式拒绝仓库内的 dev 默认令牌与外送密钥的明文通道。"""
    if not production_enabled():
        return
    token = settings.internal_token
    if token in _WEAK_INTERNAL_TOKENS or token.startswith("dev-"):
        raise RuntimeError(
            "FAIL-FAST: AF_INTERNAL_TOKEN still uses a development default. "
            "Provide a strong value in production."
        )
    # 入站凭据必须与控制面凭据分离，否则「剥离客户端令牌」形同虚设 ——
    # 拿到控制面令牌的人依然可以直连引擎 /internal/**。
    engine_token = settings.engine_token
    if (
        engine_token == token
        or engine_token in _WEAK_INTERNAL_TOKENS
        or engine_token.startswith("dev-")
    ):
        raise RuntimeError(
            "FAIL-FAST: AF_ENGINE_INTERNAL_TOKEN must be set to a strong value distinct from "
            "AF_INTERNAL_TOKEN in production "
            "(engine ingress credentials are server-injected)."
        )
    if settings.llm_api_key:
        insecure = not settings.llm_base_url.startswith("https://")
        if insecure and not settings.llm_allow_insecure_http:
            raise RuntimeError(
                "FAIL-FAST: AF_LLM_BASE_URL must use https when a real model key is configured. "
                "Set AF_LLM_ALLOW_INSECURE_HTTP=true only for local debugging."
            )
        if not settings.llm_model or settings.llm_model.startswith("mock"):
            raise RuntimeError(
                "FAIL-FAST: AF_LLM_MODEL must be a real model name when a real model key is set."
            )


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    _guard_production(settings)
    return settings
