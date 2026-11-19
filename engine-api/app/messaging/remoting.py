"""RocketMQ 4.x remoting 协议的**最小**实现（只覆盖消费所需的三条命令）。

为什么不引官方客户端：`rocketmq` / `rocketmq-client-python` 都是 C++ 客户端的 ctypes
绑定，需要本机存在 librocketmq 动态库；Windows 开发机与精简 CI 镜像都装不上，
结果就是「消费端只在某些机器上存在」——比没有更糟。这里只用标准库实现拉取路径：

- GET_ROUTEINFO_BY_TOPIC(105)：拿 broker 地址与读队列数；
- PULL_MESSAGE(11)：按队列偏移长轮询拉一批消息；
- HEART_BEAT(34)：让 broker 知道这个消费组还活着。

协议本身很简单：`totalLen(4) + headerLen(4，高字节是序列化类型) + headerJson + body`，
消息体是定长字段 + 变长 body/topic/properties。编解码全部是纯函数，便于单测
（`tests/unit/test_rocketmq_codec.py` 用构造出来的字节流验证）。
"""

from __future__ import annotations

import json
import struct
from typing import Any

# 序列化类型：0 = JSON
SERIALIZE_JSON = 0

# 请求码（只实现用到的）
CODE_PULL_MESSAGE = 11
CODE_HEART_BEAT = 34
CODE_GET_ROUTEINFO_BY_TOPIC = 105

# 响应码
CODE_SUCCESS = 0
CODE_PULL_NOT_FOUND = 19
CODE_PULL_RETRY_IMMEDIATELY = 20
CODE_PULL_OFFSET_MOVED = 21

# 消息魔数：V1 是默认存储格式（V2 只在特定特性下出现，遇到直接报错而不是错位解析）
MAGIC_CODE_V1 = -626843481

# V1 消息头定长部分：body 从第 88 字节开始
_V1_BODY_OFFSET = 88


def encode_frame(
    code: int, opaque: int, ext_fields: dict[str, Any] | None = None, body: bytes | None = None
) -> bytes:
    """组一个 JSON 序列化的请求帧。"""
    header = {
        "code": code,
        "language": "PYTHON",
        "version": 401,
        "opaque": opaque,
        "flag": 0,
        "remark": None,
        "extFields": ext_fields or {},
    }
    raw_header = json.dumps(header, separators=(",", ":")).encode()
    payload = body or b""
    total = 4 + len(raw_header) + len(payload)
    header_len = (SERIALIZE_JSON << 24) | len(raw_header)
    return struct.pack(">ii", total, header_len) + raw_header + payload


def decode_frame(data: bytes) -> tuple[dict[str, Any], bytes]:
    """拆一个响应帧，返回 (header, body)。"""
    if len(data) < 8:
        raise ValueError("frame too short")
    total, header_len = struct.unpack(">ii", data[:8])
    header_size = header_len & 0xFFFFFF
    expected = 8 + header_size + max(0, total - 4 - header_size)
    if len(data) < expected:
        raise ValueError(f"incomplete frame: need {expected}, got {len(data)}")
    header = json.loads(data[8 : 8 + header_size].decode() or "{}")
    body = data[8 + header_size : 8 + header_size + (total - 4 - header_size)]
    return header, body


def command_code(header: dict[str, Any]) -> int:
    return int(header.get("code", -1))


def command_opaque(header: dict[str, Any]) -> int:
    return int(header.get("opaque", -1))


def ext_field(header: dict[str, Any], name: str) -> str | None:
    fields = header.get("extFields") or {}
    value = fields.get(name)
    return None if value is None else str(value)


def parse_route(body: bytes) -> tuple[str | None, list[int]]:
    """从路由响应里取 (master 地址, 可读队列 id 列表)。"""
    if not body:
        return None, []
    route = json.loads(body.decode())
    master: str | None = None
    for broker in route.get("brokerDatas") or []:
        addrs = broker.get("brokerAddrs") or {}
        master = addrs.get("0")
        if master:
            break
    queue_ids: list[int] = []
    for queue in route.get("queueDatas") or []:
        if master and queue.get("brokerName") != _broker_name_of(route, master):
            continue
        queue_ids.extend(range(int(queue.get("readQueueNums", 0))))
    if not queue_ids:
        for queue in route.get("queueDatas") or []:
            queue_ids.extend(range(int(queue.get("readQueueNums", 0))))
    return master, queue_ids


def _broker_name_of(route: dict[str, Any], addr: str) -> str | None:
    for broker in route.get("brokerDatas") or []:
        if (broker.get("brokerAddrs") or {}).get("0") == addr:
            return broker.get("brokerName")
    return None


def parse_messages(body: bytes) -> list[dict[str, Any]]:
    """拆拉取响应里的消息列表。

    返回 `{topic, body, properties, queueOffset, queueId, keys}`；properties 已解析成 dict。
    """
    messages: list[dict[str, Any]] = []
    pos = 0
    while pos + 4 <= len(body):
        total = struct.unpack(">i", body[pos : pos + 4])[0]
        if total <= 0 or pos + total > len(body):
            break
        messages.append(_parse_one(body[pos : pos + total]))
        pos += total
    return messages


def _parse_one(raw: bytes) -> dict[str, Any]:
    magic = struct.unpack(">i", raw[4:8])[0]
    if magic != MAGIC_CODE_V1:
        raise ValueError(f"unsupported message magic {magic}")
    queue_id = struct.unpack(">i", raw[12:16])[0]
    queue_offset = struct.unpack(">q", raw[20:28])[0]
    body_len = struct.unpack(">i", raw[84:88])[0]
    body = raw[_V1_BODY_OFFSET : _V1_BODY_OFFSET + body_len]
    pos = _V1_BODY_OFFSET + body_len
    topic_len = raw[pos]
    pos += 1
    topic = raw[pos : pos + topic_len].decode("utf-8", "replace")
    pos += topic_len
    props_len = struct.unpack(">h", raw[pos : pos + 2])[0]
    pos += 2
    properties = parse_properties(raw[pos : pos + props_len].decode("utf-8", "replace"))
    return {
        "topic": topic,
        "body": body,
        "properties": properties,
        "queueId": queue_id,
        "queueOffset": queue_offset,
        "keys": properties.get("KEYS"),
    }


def parse_properties(raw: str) -> dict[str, str]:
    """RocketMQ 属性串：`k\\x01v\\x02k\\x01v`。"""
    out: dict[str, str] = {}
    if not raw:
        return out
    for pair in raw.split("\x02"):
        if "\x01" not in pair:
            continue
        key, value = pair.split("\x01", 1)
        out[key] = value
    return out
