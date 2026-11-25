"""裁决消息消费端：MQ → 引擎续跑。

定位：**与控制面 HTTP 回调并行的第二条通道**。HTTP 是快路径（用户点完立刻生效），
MQ 是冗余路径——引擎被网络策略隔离、控制面重启中、relay 还在退避时，消息仍留在
broker 里，引擎自己拉得到。

幂等：裁决在引擎侧幂等，所以这条通道对「HTTP 已经处理过」是安全的。消费结果分三类：
`applied`（本通道真的推进了状态机）、`duplicate`（节点已裁决）、`stale`（执行已收口，
消息迟到）。后两类都是常态而非故障——HTTP 是快路径，MQ 大多时候只是在重复劳动。

线程模型：拉取是阻塞 IO，放独立线程；处理协程回到 FastAPI 的事件循环上执行，
避免在 IO 线程里碰 asyncio 对象。
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
import uuid
from datetime import UTC, datetime
from typing import Any

from .client import PullConsumer, RemotingError

log = logging.getLogger("agentflow.mq")

_VALID_DECISIONS = {"APPROVED", "REJECTED", "TIMEOUT"}

# 迟到的裁决：执行已收口 / 该节点已不在挂起态。丢掉即可，不是故障。
_STALE_STATUSES = {"NOT_SUSPENDED"}
# 消息本身对不上：执行不存在、decision 非法。属于不该出现的消息。
_INVALID_STATUSES = {"NOT_FOUND", "INVALID_DECISION"}


def _is_route_missing(exc: BaseException) -> bool:
    """broker 还没为这个 topic 建路由（autoCreateTopicEnable 下要等首条消息）。"""
    text = str(exc)
    return "route lookup failed" in text or "no broker route" in text or "code=17" in text


class GateDecisionConsumer:
    """消费 `af-gate-decision`：`{executionId, nodeKey, decision, resolvedBy, comment}`。"""

    def __init__(
        self,
        dispatcher: Any,
        *,
        namesrv: str,
        topic: str,
        group: str,
        poll_timeout_ms: int = 3_000,
        batch: int = 32,
        broker_host: str | None = None,
        reconnect_delay_s: float = 2.0,
        idle_interval_s: float = 0.2,
        busy_retries: int = 3,
        busy_retry_delay_s: float = 0.3,
    ) -> None:
        self._dispatcher = dispatcher
        self.namesrv = namesrv
        self.topic = topic
        self.group = group
        self.reconnect_delay_s = reconnect_delay_s
        self.idle_interval_s = idle_interval_s
        self._busy_retries = max(0, busy_retries)
        self._busy_retry_delay_s = max(0.0, busy_retry_delay_s)
        self._consumer = PullConsumer(
            namesrv,
            topic,
            group,
            poll_timeout_ms=poll_timeout_ms,
            batch=batch,
            broker_host=broker_host,
        )
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._connected = False
        self._stats: dict[str, Any] = {
            "consumed": 0,
            "applied": 0,
            "duplicate": 0,
            "stale": 0,
            "invalid": 0,
            "failed": 0,
            "lastError": None,
            "lastConsumedAt": None,
        }

    # ---------- 生命周期 ----------

    def start(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._thread = threading.Thread(target=self._run, name="gate-mq-consumer", daemon=True)
        self._thread.start()
        log.info(
            "gate mq consumer started topic=%s group=%s namesrv=%s",
            self.topic,
            self.group,
            self.namesrv,
        )

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=10)
        self._consumer.close()
        log.info("gate mq consumer stopped")

    def status(self) -> dict[str, Any]:
        return {
            "enabled": True,
            "namesrv": self.namesrv,
            "topic": self.topic,
            "group": self.group,
            "connected": self._connected,
            "offsets": self._consumer.offsets() if self._connected else {},
            **self._stats,
        }

    # ---------- 拉取线程 ----------

    def _run(self) -> None:
        # 路由缺失可能持续很久（首条消息发出前 topic 不存在）：固定间隔重试会刷屏，
        # 指数退避到 30s 封顶；一旦 poll 成功立即复位，路由恢复后马上回到快节奏。
        route_delay = self.reconnect_delay_s
        while not self._stop.is_set():
            try:
                if not self._connected:
                    self._consumer.connect()
                    self._connected = True
                    log.info("gate mq consumer connected offsets=%s", self._consumer.offsets())
                messages = self._consumer.poll()
                route_delay = self.reconnect_delay_s
            except (RemotingError, OSError, ValueError) as exc:
                self._connected = False
                self._stats["lastError"] = str(exc)
                if _is_route_missing(exc):
                    delay = route_delay
                    route_delay = min(route_delay * 2, 30.0)
                    log.info(
                        "gate mq topic %s not routed yet, retrying in %.0fs", self.topic, delay
                    )
                else:
                    delay = self.reconnect_delay_s
                    log.warning("gate mq poll failed, will reconnect: %s", exc)
                self._consumer.close()
                self._stop.wait(delay)
                continue
            for message in messages:
                self._dispatch(message)
            if not messages:
                # 短轮询：空转时让出 CPU（一次审批一条消息，200ms 的延迟可忽略）
                self._stop.wait(self.idle_interval_s)

    def _dispatch(self, message: dict[str, Any]) -> None:
        payload = self._decode(message)
        if payload is None:
            return
        self._stats["consumed"] += 1
        self._stats["lastConsumedAt"] = datetime.now(UTC).isoformat()
        if self._loop is None:
            return
        try:
            future = asyncio.run_coroutine_threadsafe(self._handle(payload), self._loop)
            future.result(timeout=30)
        except Exception as exc: # 消费线程不能因单条消息崩掉
            self._stats["failed"] += 1
            self._stats["lastError"] = str(exc)
            log.warning("gate mq message handling failed keys=%s: %s", message.get("keys"), exc)

    def _decode(self, message: dict[str, Any]) -> dict[str, Any] | None:
        try:
            payload = json.loads(message.get("body") or b"{}")
        except ValueError:
            self._stats["invalid"] += 1
            self._stats["lastError"] = "payload is not json"
            log.warning("gate mq message is not json, skipped")
            return None
        if not isinstance(payload, dict):
            self._stats["invalid"] += 1
            return None
        decision = str(payload.get("decision", "")).upper()
        if (
            not payload.get("executionId")
            or not payload.get("nodeKey")
            or decision not in _VALID_DECISIONS
        ):
            self._stats["invalid"] += 1
            self._stats["lastError"] = "payload missing fields"
            log.warning("gate mq message missing fields, skipped: %s", payload)
            return None
        payload["decision"] = decision
        return payload

    # ---------- 处理 ----------

    async def _handle(self, payload: dict[str, Any]) -> None:
        execution_id = uuid.UUID(str(payload["executionId"]))
        # BUSY = 租约被别处占着（通常正是 HTTP 快路径在续跑）。等一拍重试，
        # 那一侧收口后本消息会落到 ALREADY_RESOLVED，正好记成 duplicate。
        for attempt in range(self._busy_retries + 1):
            result = await self._dispatcher.resolve_gate(
                execution_id,
                str(payload["nodeKey"]),
                str(payload["decision"]),
                str(payload.get("resolvedBy") or ""),
                payload.get("comment"),
            )
            if str(result.get("status") or "") != "BUSY" or attempt == self._busy_retries:
                break
            await asyncio.sleep(self._busy_retry_delay_s)

        status = str(result.get("status") or "")
        if status == "RESOLVED":
            self._stats["applied"] += 1
            log.info(
                "gate resolved from mq execution=%s node=%s decision=%s",
                payload["executionId"],
                payload["nodeKey"],
                payload["decision"],
            )
        elif status == "ALREADY_RESOLVED":
            self._stats["duplicate"] += 1
        elif status == "ALREADY_TERMINAL":
            # 执行已经收口（HTTP 抢先续跑、或超时扫描先收口）：这条消息是迟到的，
            # 丢掉即可。**不算失败**——MQ 通道的常态就是「HTTP 已经处理过」。
            self._stats["stale"] += 1
        elif status in _STALE_STATUSES:
            self._stats["stale"] += 1
            log.info(
                "gate mq message is stale (%s) for %s/%s",
                status,
                payload["executionId"],
                payload["nodeKey"],
            )
        elif status in _INVALID_STATUSES:
            self._stats["invalid"] += 1
            log.warning(
                "gate mq message is not acceptable (%s) for %s/%s",
                status,
                payload["executionId"],
                payload["nodeKey"],
            )
        else:
            # BUSY 重试耗尽 / 引擎侧抛错：记失败并留下状态，便于排查
            self._stats["failed"] += 1
            self._stats["lastError"] = f"resolve_gate returned {status or 'UNKNOWN'}"
            log.warning(
                "gate mq resolve returned %s for %s/%s",
                status,
                payload["executionId"],
                payload["nodeKey"],
            )
