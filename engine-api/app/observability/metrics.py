"""Prometheus 指标（§13.3）。"""

from __future__ import annotations

from prometheus_client import Counter, Histogram

EXECUTION_TOTAL = Counter("af_execution_total", "Executions by terminal status", ["status"])
NODE_FAILED_TOTAL = Counter("af_node_failed_total", "Node failures", ["node_type", "error_code"])
EVENT_APPEND_LATENCY = Histogram(
    "af_event_append_latency_ms",
    "Event append latency",
    buckets=(1, 5, 10, 25, 50, 100, 250, 500, 1000),
)
REPLAY_TOTAL = Counter("af_replay_total", "State rebuilds from event log")
TOKEN_USED_TOTAL = Counter("af_token_used_total", "Token consumption", ["tenant_id"])
LEASE_CONTENTION_TOTAL = Counter("af_lease_contention_total", "Lease acquisition conflicts")
BUDGET_STOP_TOTAL = Counter("af_budget_stop_total", "Executions stopped by budget hard-stop")
