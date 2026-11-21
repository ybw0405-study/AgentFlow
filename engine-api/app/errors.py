"""引擎错误体系。code 与 architecture.md §8 错误码目录对齐。"""

from __future__ import annotations


class EngineError(Exception):
    """所有可预期引擎错误的基类。"""

    def __init__(self, code: str, message: str, *, transient: bool = False) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.transient = transient  # 瞬时错误才可重试（§8.4）


class PermanentNodeError(EngineError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, transient=False)


class TransientNodeError(EngineError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, transient=True)


class BudgetExhaustedError(PermanentNodeError):
    def __init__(self, message: str = "token budget exhausted") -> None:
        super().__init__("EXEC-BUDGET_EXHAUSTED", message)


class LoopLimitExceededError(PermanentNodeError):
    def __init__(self, message: str = "loop limit exceeded") -> None:
        super().__init__("EXEC-LOOP_LIMIT_EXCEEDED", message)


class ExpressionError(PermanentNodeError):
    def __init__(self, message: str) -> None:
        super().__init__("EXPR-EVAL-001", message)


class PiiBlockedError(PermanentNodeError):
    def __init__(self, message: str = "PII detected in model-bound content") -> None:
        super().__init__("PII-BLOCKED-001", message)


class ExecutionCancelledError(EngineError):
    def __init__(self, message: str = "execution cancelled") -> None:
        super().__init__("EXEC-CANCELLED", message)


class DeadlineExceededError(EngineError):
    def __init__(self, message: str = "execution deadline exceeded") -> None:
        super().__init__("EXEC-DEADLINE_EXCEEDED", message)


class StateTransitionError(EngineError):
    def __init__(self, message: str) -> None:
        super().__init__("WF-STATE-001", message)


class EventConcurrencyError(EngineError):
    def __init__(self, message: str = "event append conflict after retries") -> None:
        super().__init__("EVT-CONCURRENCY-001", message)


class EventTerminalError(EngineError):
    """终态不可逆（AF-9）：已 TERMINAL 的执行拒绝再追加非终态事件。"""

    def __init__(self, message: str = "execution already terminal") -> None:
        super().__init__("EVT-TERMINAL-001", message)


class GateSuspendedSignal(Exception):
    """human_gate 挂起信号。

    刻意不继承 EngineError：错误分类与重试逻辑完全不碰它。NodeRunner 显式捕获后
    落 NodeSuspended + GateSuspended 事件，再向上抛给调度器；调度器据此结束本轮
    执行（不落终态、不写 ExecutionFailed），租约释放后由裁决回调续跑。
    """

    def __init__(self, payload: dict[str, object]) -> None:
        super().__init__("human gate suspended")
        self.payload = payload
