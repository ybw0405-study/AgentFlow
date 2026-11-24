"""CI 用的 OpenAI 兼容端点替身。

产品侧不允许离线 provider（DSL 里写 `provider: mock` 会直接校验失败），所以 CI 里
没有任何可用的模型端点，凡是有 llm 节点的验收都会停在 `LLM-NOT-CONFIGURED-001`。
这个进程只解决「CI 拿不到真实密钥」这一件事：它在 127.0.0.1 上冒充一个
OpenAI 兼容服务，由 workflow 启动脚本通过 `AF_LLM_*` 注入给引擎。

它不是产品代码的一部分，也不改变执行路径：引擎仍然走真实的 HTTP 调用、
真实的超时与错误分类、真实的 token 计量（usage 由本进程给出），只是对端是本地进程
而不是厂商。要求真实语义的用例（检索质量、推理模型行为）仍由 `AF_E2E_REAL_LLM=1`
控制，在这里会 skip。

用法：python e2e/support/fake_openai.py --port 8099 [--model deepseek-chat]
"""

from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

# 固定回复：确定性让断言可以比较，长度足够让 token 计数非零。
_REPLY = (
    "这是本地替身端点返回的确定性回答：已按提示词要点组织内容，"
    "覆盖问题本身、可执行步骤与风险提示三部分。"
)
_JSON_REPLY = '{"answer": "本地替身端点返回的确定性结构化回答", "confidence": 0.9}'


class Handler(BaseHTTPRequestHandler):
    """只实现引擎真正会打的两个路由。"""

    server_version = "FakeOpenAI/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        # 静默：CI 日志里只关心引擎与服务，不需要每条代理请求。
        pass

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 约定
        path = self.path.split("?", 1)[0].rstrip("/")
        if path.endswith("/models"):
            self._send(200, {"object": "list", "data": [{"id": self.server.model_name}]})
            return
        if path.endswith("/health"):
            self._send(200, {"status": "ok"})
            return
        self._send(404, {"error": {"message": f"no route {path}"}})

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 约定
        path = self.path.split("?", 1)[0].rstrip("/")
        if path.endswith("/embeddings"):
            try:
                length = int(self.headers.get("Content-Length") or 0)
                request = json.loads(self.rfile.read(length) or b"{}")
            except (ValueError, json.JSONDecodeError):
                self._send(400, {"error": {"message": "malformed json"}})
                return
            texts = request.get("input") or []
            if isinstance(texts, str):
                texts = [texts]
            self._send(
                200,
                {
                    "object": "list",
                    "model": request.get("model") or self.server.model_name,
                    "data": [
                        {"object": "embedding", "index": i, "embedding": _fake_embedding(t)}
                        for i, t in enumerate(texts)
                    ],
                    "usage": {"prompt_tokens": sum(len(t) for t in texts), "total_tokens": sum(len(t) for t in texts)},
                },
            )
            return
        if not path.endswith("/chat/completions"):
            self._send(404, {"error": {"message": f"no route {path}"}})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            request = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._send(400, {"error": {"message": "malformed json"}})
            return

        # 引擎在 outputMode=json 时要求精确 JSON；其余按纯文本回。提示词长度参与
        # prompt_tokens，让「按模型/按租户」的用量报表有可分辨的数字。
        wants_json = _wants_json(request)
        content = _JSON_REPLY if wants_json else _REPLY
        if request.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            prompt_chars = sum(len(str(m.get("content", ""))) for m in request.get("messages", []))
            prompt_tokens = max(1, prompt_chars // 2)
            completion = max(1, len(content) // 2)
            usage = {"prompt_tokens": prompt_tokens, "completion_tokens": completion,
                     "total_tokens": prompt_tokens + completion}
            frames = [
                {"choices": [{"index": 0, "delta": {"content": content}}]},
                {"choices": [], "usage": usage},
            ]
            for frame in frames:
                self.wfile.write(("data: " + json.dumps(frame, ensure_ascii=False) + "\n\n").encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
            return
        prompt_chars = sum(len(str(m.get("content", ""))) for m in request.get("messages", []))
        prompt_tokens = max(1, prompt_chars // 2)
        completion_tokens = max(1, len(content) // 2)
        self._send(
            200,
            {
                "id": "chatcmpl-fake",
                "object": "chat.completion",
                "model": request.get("model") or self.server.model_name,
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": content},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "total_tokens": prompt_tokens + completion_tokens,
                },
            },
        )


def _wants_json(request: dict[str, Any]) -> bool:
    if isinstance(request.get("response_format"), dict):
        fmt = request["response_format"]
        return isinstance(fmt, dict) and fmt.get("type") in {"json_object", "json_schema"}
    for message in request.get("messages", []):
        if "valid JSON object only" in str(message.get("content", "")):
            return True
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--model", default="deepseek-chat")
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.model_name = args.model  # type: ignore[attr-defined]
    print(f"fake openai-compatible endpoint on http://127.0.0.1:{args.port}/v1", flush=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        server.shutdown()


def _fake_embedding(text: str) -> list[float]:
    """确定性词袋哈希向量：CJK 单字 + ASCII 词落桶计数后 L2 归一（1024 维）。"""
    import math
    import re as _re
    import zlib

    vec = [0.0] * 1024
    tokens = _re.findall(r"[一-鿿]|[a-z0-9]+", str(text).lower())
    for tok in tokens:
        vec[zlib.crc32(tok.encode("utf-8")) % 1024] += 1.0
    norm = math.sqrt(sum(x * x for x in vec)) or 1.0
    return [x / norm for x in vec]


if __name__ == "__main__":
    main()

