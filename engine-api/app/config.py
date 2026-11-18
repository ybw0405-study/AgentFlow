"""运行时配置。全部经环境变量注入（前缀 AF_），仓库内无任何真实密钥。"""

import os
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AF_", env_file=".env", extra="ignore")

    pg_dsn: str = "postgresql://agentflow:agentflow@127.0.0.1:5433/agentflow_events"
    redis_url: str = "redis://127.0.0.1:6379/0"
    internal_token: str = "dev-internal-token"  # noqa: S105  # 本地默认值；部署必须经 AF_INTERNAL_TOKEN 覆盖
    gateway_base_url: str = "http://127.0.0.1:8080"  # 引擎回调控制面的唯一入口（§5.2）
    pg_migrations_path: str = "../db/postgres/migration"

    host: str = "0.0.0.0"
    port: int = 8000
    log_level: str = "INFO"

    # 阶段门：只有已实现执行器的节点类型才放行（DSL 校验阶段拒绝未实现的）
    # P0: start/end/llm/tool；P1 逐步放开 condition/transform/loop/parallel/subworkflow
    supported_node_types: frozenset[str] = frozenset(
        {
            "start",
            "end",
            "llm",
            "tool",
            "condition",
            "transform",
            "loop",
            "parallel",
            "subworkflow",
            "human_gate",
        }
    )

    gate_sweep_interval_s: int = 15  # human_gate 超时扫描间隔
    checkpoint_interval: int = 10  # 每 N 个事件写一次 CheckpointCreated
    lease_ttl_ms: int = 30_000  # 执行租约 TTL
    lease_renew_ms: int = 10_000  # 续约间隔
    event_payload_max_bytes: int = 256 * 1024  # 溢出转 node_io（D5）
    default_max_parallelism: int = 4
    event_tail_max_limit: int = 500

    # 真实 LLM（OpenAI 兼容 /chat/completions）。默认全部关闭：未配置时项目仍完全离线可跑。
    # 端点/密钥只从这里注入，绝不来自 DSL 或用户入参（SSRF / 凭据注入防线）。
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = "gpt-4o-mini"  # mock-* 占位模型名回落到此值
    llm_timeout_s: float = 60.0
    llm_max_response_chars: int = 100_000
    llm_json_response_format: bool = True  # 上游不支持 response_format 时置 false
    llm_send_seed: bool = False  # 部分厂商支持 seed，用于重放确定性；不支持时须关闭
    llm_allow_insecure_http: bool = False  # 生产默认禁止明文 http 外送密钥


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
