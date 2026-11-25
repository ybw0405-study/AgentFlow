"""RocketMQ remoting 编解码：用构造出来的字节流验证，不依赖 broker。"""

from __future__ import annotations

import json
import struct

import pytest

from app.messaging import remoting
from app.messaging.gate_consumer import GateDecisionConsumer


def _v1_message(topic: str, payload: bytes, props: str) -> bytes:
    """按 V1 存储格式拼一条消息（字段顺序与 broker 写盘一致）。"""
    topic_b = topic.encode()
    props_b = props.encode()
    buf = bytearray()
    buf += struct.pack(">i", 0) # totalSize，最后回填
    buf += struct.pack(">i", remoting.MAGIC_CODE_V1)
    buf += struct.pack(">i", 0) # bodyCRC
    buf += struct.pack(">i", 3) # queueId
    buf += struct.pack(">i", 0) # flag
    buf += struct.pack(">q", 7) # queueOffset
    buf += struct.pack(">q", 1024) # physicOffset
    buf += struct.pack(">i", 0) # sysFlag
    buf += struct.pack(">q", 0) # bornTimestamp
    buf += b"\x00" * 8 # bornHost
    buf += struct.pack(">q", 0) # storeTimestamp
    buf += b"\x00" * 8 # storeHost
    buf += struct.pack(">i", 0) # reconsumeTimes
    buf += struct.pack(">q", 0) # preparedTransactionOffset
    buf += struct.pack(">i", len(payload))
    assert len(buf) == 88, "V1 定长头必须是 88 字节"
    buf += payload
    buf += struct.pack(">b", len(topic_b)) + topic_b
    buf += struct.pack(">h", len(props_b)) + props_b
    buf[0:4] = struct.pack(">i", len(buf))
    return bytes(buf)


def test_frame_roundtrip():
    frame = remoting.encode_frame(
        remoting.CODE_PULL_MESSAGE, 7, {"topic": "af-gate-decision"}, b"hello"
    )
    header, body = remoting.decode_frame(frame)
    assert remoting.command_code(header) == remoting.CODE_PULL_MESSAGE
    assert remoting.command_opaque(header) == 7
    assert remoting.ext_field(header, "topic") == "af-gate-decision"
    assert body == b"hello"


def test_decode_frame_rejects_partial_data():
    frame = remoting.encode_frame(remoting.CODE_HEART_BEAT, 1, {}, b"")
    with pytest.raises(ValueError):
        remoting.decode_frame(frame[:-4])


def test_parse_messages_handles_multiple_and_properties():
    body = _v1_message(
        "af-gate-decision", b'{"nodeKey":"approve"}', "KEYS\x01exec-1:approve\x02TAGS\x01gate\x02"
    )
    body += _v1_message("af-gate-decision", b'{"nodeKey":"approve2"}', "")
    messages = remoting.parse_messages(body)
    assert len(messages) == 2
    first = messages[0]
    assert first["topic"] == "af-gate-decision"
    assert first["body"] == b'{"nodeKey":"approve"}'
    assert first["queueOffset"] == 7
    assert first["queueId"] == 3
    assert first["properties"]["TAGS"] == "gate"
    assert first["keys"] == "exec-1:approve"
    assert messages[1]["properties"] == {}


def test_parse_messages_rejects_unknown_magic():
    raw = bytearray(_v1_message("t", b"{}", ""))
    raw[4:8] = struct.pack(">i", -626843479) # V2：不猜格式，直接报错
    with pytest.raises(ValueError):
        remoting.parse_messages(bytes(raw))


def test_parse_properties_ignores_garbage_pairs():
    assert remoting.parse_properties("A\x01 1\x02broken\x02B\x012") == {"A": " 1", "B": "2"}


def test_parse_route_picks_master_and_read_queues():
    route = {
        "brokerDatas": [
            {"brokerName": "broker-a", "brokerAddrs": {"0": "127.0.0.1:10911"}},
        ],
        "queueDatas": [
            {"brokerName": "broker-a", "readQueueNums": 4, "writeQueueNums": 4},
        ],
    }
    addr, queues = remoting.parse_route(json.dumps(route).encode())
    assert addr == "127.0.0.1:10911"
    assert queues == [0, 1, 2, 3]


def _consumer() -> GateDecisionConsumer:
    # 只测纯解析逻辑，不连 broker：dispatcher 用占位对象
    return GateDecisionConsumer(
        object(), namesrv="127.0.0.1:9876", topic="af-gate-decision", group="g"
    )


def test_gate_consumer_rejects_non_json():
    consumer = _consumer()
    assert consumer._decode({"body": b"not json"}) is None
    assert consumer.status()["invalid"] == 1


def test_gate_consumer_requires_execution_node_and_decision():
    consumer = _consumer()
    body = json.dumps({"executionId": "e-1", "nodeKey": "approve"}).encode()
    assert consumer._decode({"body": body}) is None
    assert consumer.status()["invalid"] == 1


def test_gate_consumer_normalizes_decision_case():
    consumer = _consumer()
    body = json.dumps({"executionId": "e-1", "nodeKey": "approve", "decision": "approved"}).encode()
    payload = consumer._decode({"body": body})
    assert payload is not None
    assert payload["decision"] == "APPROVED"


# ---- broker host 重写：容器部署修 127.0.0.1 回环地址，非容器行为不变 ----


def test_rewrite_broker_host_replaces_host_keeps_port():
    from app.messaging.client import rewrite_broker_host

    assert rewrite_broker_host("127.0.0.1:10911", "rocketmq-broker") == "rocketmq-broker:10911"


def test_rewrite_broker_host_empty_host_is_noop():
    from app.messaging.client import rewrite_broker_host

    # 非容器部署不设覆盖：namesrv 广播的地址逐字节不变
    assert rewrite_broker_host("127.0.0.1:10911", None) == "127.0.0.1:10911"
    assert rewrite_broker_host("127.0.0.1:10911", "") == "127.0.0.1:10911"


def test_rewrite_broker_host_without_port_is_noop():
    from app.messaging.client import rewrite_broker_host

    # 无端口的地址不猜端口，原样返回（RemotingConnection 会用默认 10911）
    assert rewrite_broker_host("127.0.0.1", "rocketmq-broker") == "127.0.0.1"
