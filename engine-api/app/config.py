"""运行时配置。全部经环境变量注入（前缀 AF_），仓库内无任何真实密钥。"""

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


@lru_cache
def get_settings() -> Settings:
    return Settings()
