"""引擎侧消息通道（当前只用于裁决：RocketMQ → 续跑）。"""

from .gate_consumer import GateDecisionConsumer

__all__ = ["GateDecisionConsumer"]
