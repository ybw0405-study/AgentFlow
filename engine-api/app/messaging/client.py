"""极简 pull consumer：一个后台线程，按队列轮询 broker。

只做「拉一批 → 交出去 → 推进偏移」，不做重平衡、不做本地偏移持久化：

- 单实例消费（裁决通道本来就是单实例语义：多实例会让同一 execution 被并发续跑）；
- 不持久化偏移：重启后从队列**最大偏移**开始，历史消息不重放。这对裁决通道是正确取舍——
  需要重放的是控制面 outbox 的职责（按 execution 状态重投），MQ 在这里是并行快通道。
"""

from __future__ import annotations

import socket
import struct
import time
from typing import Any

from . import remoting

# 拉取用到的额外命令：查队列最大偏移（初始化偏移用）
CODE_GET_MAX_OFFSET = 30

# 只用 SUBSCRIPTION 位。SUSPEND（长轮询）在 5.x broker 上会把请求挂到
# brokerSuspendMaxTimeMillis（15s）而不是客户端给的值，socket 超时必然踩中；
# 短轮询 + 200ms 间隔对「一次审批一条消息」的体量完全够用，也更好排查。
_PULL_FLAG = 4


class RemotingError(RuntimeError):
    """协议层或网络层错误；调用方据此重连。"""


def rewrite_broker_host(addr: str, host: str | None) -> str:
    """把 broker 地址里的 host 换成 `host`，端口保持不变。

    RocketMQ broker 向 namesrv 注册的是 `brokerIP1:listenPort`。本地宿主机部署时
    brokerIP1=127.0.0.1，宿主端口映射可达；但在 all-in-container 模式下，engine 是
    独立容器，拿到的 `127.0.0.1:10911` 指向它自己，永远连不上。此时用容器网络内的
    服务名（如 `rocketmq-broker`）覆盖 host，端口 10911 不变——broker 与 engine 在
    同一 compose 网络，服务名可解析，且不需要把 broker 端口暴露到局域网。

    `host` 为空/None 时逐字节原样返回，保证非容器部署的行为不变。
    """
    if not host or not addr:
        return addr
    _, sep, port = addr.partition(":")
    if not sep:
        return addr
    return f"{host}:{port}"


class RemotingConnection:
    """一条到 namesrv / broker 的 TCP 连接（一问一答，同步）。"""

    def __init__(self, addr: str, timeout: float = 5.0) -> None:
        host, _, port = addr.partition(":")
        self.addr = addr
        self._opaque = 0
        try:
            self._sock = socket.create_connection((host, int(port or 10911)), timeout=timeout)
        except OSError as exc:
            raise RemotingError(f"connect {addr} failed: {exc}") from exc
        self._sock.settimeout(timeout)

    def request(
        self, code: int, ext: dict[str, Any] | None = None, body: bytes | None = None
    ) -> tuple[dict[str, Any], bytes]:
        self._opaque += 1
        opaque = self._opaque
        try:
            self._sock.sendall(remoting.encode_frame(code, opaque, ext, body))
            head = self._recv_exact(8)
            total, header_len = struct.unpack(">ii", head)
            header_size = header_len & 0xFFFFFF
            rest = self._recv_exact(header_size + max(0, total - 4 - header_size))
        except OSError as exc:
            raise RemotingError(f"{self.addr} request failed: {exc}") from exc
        header, payload = remoting.decode_frame(head + rest)
        return header, payload

    def _recv_exact(self, size: int) -> bytes:
        buf = bytearray()
        while len(buf) < size:
            chunk = self._sock.recv(size - len(buf))
            if not chunk:
                raise RemotingError(f"{self.addr} closed connection")
            buf.extend(chunk)
        return bytes(buf)

    def close(self) -> None:
        try:
            self._sock.close()
        except OSError:
            pass


class PullConsumer:
    """按队列轮询一个 topic。`poll()` 返回本轮拉到的消息（可能为空）。"""

    def __init__(
        self,
        namesrv: str,
        topic: str,
        group: str,
        *,
        poll_timeout_ms: int = 3_000,
        batch: int = 32,
        broker_host: str | None = None,
    ) -> None:
        self.namesrv = namesrv
        self.topic = topic
        self.group = group
        self.poll_timeout_s = max(poll_timeout_ms, 500) / 1000.0
        self.batch = max(batch, 1)
        self.broker_host = broker_host or None
        self._namesrv_conn: RemotingConnection | None = None
        self._broker_conn: RemotingConnection | None = None
        self._broker_addr: str | None = None
        self._queues: list[int] = []
        self._offsets: dict[int, int] = {}
        self._cursor = 0

    # ---------- 连接与拓扑 ----------

    def connect(self) -> None:
        # 每次重连都从干净状态开始：上一次可能停在「namesrv 通了、broker 没通」，
        # 复用半连接会一直报 broker connection is not established。
        self.close()
        try:
            self._namesrv_conn = RemotingConnection(self.namesrv)
            header, body = self._namesrv_conn.request(
                remoting.CODE_GET_ROUTEINFO_BY_TOPIC, {"topic": self.topic}
            )
            code = remoting.command_code(header)
            if code != remoting.CODE_SUCCESS:
                raise RemotingError(f"route lookup failed: code={code} {header.get('remark')}")
            addr, queues = remoting.parse_route(body)
            if not addr:
                raise RemotingError(f"no broker route for topic {self.topic}")
            # 容器部署下 namesrv 广播的 host 可能是回环地址；按需重写为内网服务名。
            addr = rewrite_broker_host(addr, self.broker_host)
            # 拉取是短轮询，正常情况下立刻返回；超时只用于兜住 broker 卡死
            self._broker_conn = RemotingConnection(addr, timeout=max(3.0, self.poll_timeout_s))
            self._broker_addr = addr
            self._queues = queues or [0]
            self._offsets = {}
            for queue_id in self._queues:
                self._offsets[queue_id] = self._max_offset(queue_id)
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        for conn in (self._broker_conn, self._namesrv_conn):
            if conn is not None:
                conn.close()
        self._broker_conn = None
        self._namesrv_conn = None
        self._broker_addr = None

    # ---------- 协议动作 ----------

    def _broker(self) -> RemotingConnection:
        if self._broker_conn is None:
            raise RemotingError("broker connection is not established")
        return self._broker_conn

    def _max_offset(self, queue_id: int) -> int:
        header, _ = self._broker().request(
            CODE_GET_MAX_OFFSET, {"topic": self.topic, "queueId": str(queue_id)}
        )
        value = remoting.ext_field(header, "offset")
        return int(value) if value is not None else 0

    def poll(self) -> list[dict[str, Any]]:
        """轮询一轮：按队列轮转，一次只拉一个队列（避免长轮询叠加成 N 倍超时）。"""
        if not self._queues or self._broker_conn is None:
            raise RemotingError("consumer is not connected")
        queue_id = self._queues[self._cursor % len(self._queues)]
        self._cursor += 1
        offset = self._offsets.get(queue_id, 0)
        header, body = self._broker().request(
            remoting.CODE_PULL_MESSAGE,
            {
                "consumerGroup": self.group,
                "topic": self.topic,
                "queueId": str(queue_id),
                "queueOffset": str(offset),
                "maxMsgNums": str(self.batch),
                "sysFlag": str(_PULL_FLAG),
                "commitOffset": "0",
                "suspendTimeoutMillis": "0",
                "subscription": "*",
                "subVersion": str(int(time.time() * 1000)),
                "expressionType": "TAG",
            },
        )
        code = remoting.command_code(header)
        next_offset = remoting.ext_field(header, "nextBeginOffset")
        if code == remoting.CODE_SUCCESS:
            if next_offset is not None:
                self._offsets[queue_id] = int(next_offset)
            return remoting.parse_messages(body)
        if code in (remoting.CODE_PULL_NOT_FOUND, remoting.CODE_PULL_RETRY_IMMEDIATELY):
            if next_offset is not None:
                self._offsets[queue_id] = int(next_offset)
            return []
        if code == remoting.CODE_PULL_OFFSET_MOVED:
            # 偏移越界（队列被截断/重建）：接受 broker 建议值，不重放历史
            if next_offset is not None:
                self._offsets[queue_id] = int(next_offset)
            return []
        raise RemotingError(f"pull failed: code={code} {header.get('remark')}")

    def offsets(self) -> dict[int, int]:
        return dict(self._offsets)
