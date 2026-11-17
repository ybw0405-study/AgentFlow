"""结构化日志：每行带 traceId（§13.3）。日志红线：禁 PII 原文/提示词全文/密钥/DSN。"""

from __future__ import annotations

import logging

from .trace import current_trace_id


class TraceIdLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = current_trace_id.get("") or "-"
        return True


def setup_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s %(name)s traceId=%(trace_id)s %(message)s")
    )
    handler.addFilter(TraceIdLogFilter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
