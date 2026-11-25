#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跨进程全链 e2e 脚本骨架 — AgentFlow。

目标链路（真实环境才有意义）：
  前端 -> agent-gateway(路径白名单 + 服务端注入内部凭据) ->
  workflow-service submitExecution -> engine-api(PG 事件流，执行真相唯一权威) ->
  EventProjector 单向幂等投影 -> MySQL workflow_execution 终态 -> 前端查询一致。

要机检的不变量（方案 4.1）：
  终态只能由 projector 从事件流单向写入；乱序/重放事件不得把 CANCELLED 改回 SUCCEEDED。
  提交只有一条通道（outbox 与业务写入同事务）；引擎「接受但未派发」必须返回明确错误，202 != 成功。
  定时任务统一 LeaderElector，单实例假设不能只写在文档里。
  关停顺序：停止接收 -> drain 在途 -> 停后台 -> 关连接池。
   preview 打标并从恢复扫描排除，超时/重启不续跑、不重复计费。

【脚手架定位 / 诚实边界】
  * 这是可执行骨架，不是已跑通的 e2e。本机没有真实生产环境、真实业务数据、
    真实凭据，未启动网关/控制面/引擎/MQ，因此**没有产生任何 e2e 记录**。
  * 默认 dry-run：只打印阶段与断言，不发起任何网络请求、不碰数据库。
  * 仅 --execute 且提供真实端点/令牌才发请求；URL 为占位常量，需按
    docs/openapi / agent-gateway 实际路由校准（见 TODO）。
  * 本文件为新增脚手架；**不碰 AgentFlow 任何业务文件**，不改现有源码/脚本/配置。

运行：
  python drill_e2e_crossprocess.py # dry-run（默认，安全）
  python drill_e2e_crossprocess.py --execute \
      --gateway-url http://127.0.0.1:8080 --engine-url http://127.0.0.1:8000 \
      --internal-token <token> --tenant <uuid> --report out.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

# TODO(校准)：按真实 OpenAPI / EnginePathPolicy 白名单替换
PATH_SUBMIT = "/api/workflows/{definition_id}/executions" # 控制面提交
PATH_EXECUTION = "/api/executions/{execution_id}" # 控制面投影查询
PATH_ENGINE_READY = "/internal/health" # 引擎 liveness/readiness
PATH_GATEWAY_PROBE = "/engine/internal/executions" # 应被白名单 404

STAGES = [
    "preflight", # 网关 / 控制面 / 引擎可达，内部凭据注入校验
    "gateway_whitelist", # 未授权 /engine/** 路径返回 404，客户端令牌被剥离
    "submit_execution", # 提交，断言返回体语义（202 != 成功）
    "engine_event_stream", # 事件流写入、单调 last_seq
    "projection_terminal", # MySQL 投影终态与事件流一致
    "terminal_immutable", # 乱序/重放事件不改回非终态
    "preview_no_resume", # preview 超时/重启不续跑
    "report",
]

ASSERTIONS = [
    "终态不可逆：重放 SUCCEEDED/FAILED 事件不覆盖已有终态",
    "提交单通道：outbox 行与业务写入同事务；引擎未派发返回 409/5xx 而非 202",
    "投影器/Reaper/relay 走 LeaderElector，仅单实例生效",
    "关停先 drain 在途 _tasks 再关 store/redis（含真实在跑任务）",
    " /engine/** 白名单外 404；客户端 X-Internal-Token 被剥离改服务端注入",
    " preview 打标、finally 强制终态、恢复扫描不续跑",
    " readiness 探 PG+Redis，依赖 down 返回 503",
]


@dataclass
class Result:
    execute: bool
    checks: list[dict] = field(default_factory=list)
    started: float = field(default_factory=time.time)

    def add(self, stage: str, ok: bool, detail: str) -> None:
        self.checks.append({"stage": stage, "ok": ok, "detail": detail})
        print(f" [{'PASS' if ok else 'FAIL'}] {stage}: {detail}")

    def dump(self, path: str | None) -> dict:
        passed = sum(1 for c in self.checks if c["ok"])
        report = {
            "kind": "drill-crossprocess-e2e",
            "project": "AgentFlow",
            "executed": self.execute,
            "note": "骨架自检；本机未接真实服务，未产生真实 e2e 记录" if not self.execute
                    else "真实执行；端点/凭据由调用方提供",
            "passed": passed,
            "total": len(self.checks),
            "checks": self.checks,
            "elapsed_s": round(time.time() - self.started, 3),
        }
        if path:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(report, fh, ensure_ascii=False, indent=2)
        return report


def http_json(method: str, url: str, body: dict | None, headers: dict, timeout: float) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else {"raw": raw})
    except urllib.error.HTTPError as exc:
        return exc.code, {"error": exc.read().decode("utf-8", "replace")}
    except Exception as exc: # noqa: BLE001
        return -1, {"error": repr(exc)}


def run(args: argparse.Namespace) -> int:
    r = Result(execute=args.execute)
    print("=" * 72)
    print("跨进程全链 e2e 骨架 (AgentFlow) | execute =", args.execute)
    if not args.execute:
        print("DRY-RUN：仅列出阶段与断言，不发起任何请求。加 --execute 才会连真实服务。")
    print("=" * 72)
    print("阶段:", " -> ".join(STAGES))
    print("断言:")
    for a in ASSERTIONS:
        print(" -", a)

    if not args.execute:
        for s in STAGES:
            r.add(s, True, "dry-run：阶段已登记，未执行")
        rep = r.dump(args.report)
        print(f"\n骨架自检完成：{rep['passed']}/{rep['total']}（均为 dry-run 登记，非真实 e2e 结果）")
        return 0

    hdr = {"Content-Type": "application/json", "X-Internal-Token": args.internal_token}
    code, _ = http_json("GET", args.engine_url + PATH_ENGINE_READY, None, hdr, args.timeout)
    r.add("preflight", code == 200, f"引擎 readiness HTTP {code}")

    # 白名单外路径 + 客户端自带令牌应被网关拒绝/剥离
    code, _ = http_json("GET", args.gateway_url + PATH_GATEWAY_PROBE, None,
                        dict(hdr, **{"X-Internal-Token": "client-supplied"}), args.timeout)
    r.add("gateway_whitelist", code in (403, 404), f"未授权 /engine/** 路径 HTTP {code}（期望 403/404）")

    submit = PATH_SUBMIT.format(definition_id=args.definition_id)
    code, body = http_json("POST", args.gateway_url + submit,
                           {"tenantId": args.tenant, "preview": False}, hdr, args.timeout)
    eid = str(body.get("executionId", "")) if isinstance(body, dict) else ""
    # I2：接受但未派发不能是 202 且必须带明确状态/说明
    r.add("submit_execution", code in (200, 201, 202) and eid != "",
          f"HTTP {code}, executionId={eid!r}（TODO: 校验 status/note，202 不算成功）")

    # TODO(engine_event_stream / projection_terminal / terminal_immutable / preview_no_resume)：
    # 真实实现需：查 PG 事件流 last_seq 单调、查 MySQL workflow_execution 终态、
    # 重放一条迟到事件断言终态不变、注入 preview 超时后重启断言不续跑。
    # 骨架不内嵌 PG/MySQL 客户端，标记为待接线（按 FAIL 计，接线后重跑）。
    for stage, detail in [
        ("engine_event_stream", "TODO: 接真实 PG 事件流，断言 last_seq 单调"),
        ("projection_terminal", "TODO: 接真实 MySQL 投影，断言终态与事件流一致"),
        ("terminal_immutable", "TODO: 重放迟到事件，断言终态不被改回非终态"),
        ("preview_no_resume", "TODO: 注入 preview + 重启，断言恢复扫描不续跑"),
    ]:
        r.add(stage, False, detail)

    rep = r.dump(args.report)
    print(f"\n真实执行骨架结果：{rep['passed']}/{rep['total']}（TODO 项按 FAIL 计，接线后重跑）")
    return 0 if rep["passed"] == rep["total"] else 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="AgentFlow 跨进程全链 e2e 骨架（默认 dry-run）")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--gateway-url", default="http://127.0.0.1:8080")
    p.add_argument("--engine-url", default="http://127.0.0.1:8000")
    p.add_argument("--internal-token", default="")
    p.add_argument("--tenant", default="11111111-1111-1111-1111-111111111111")
    p.add_argument("--definition-id", default="00000000-0000-0000-0000-000000000001")
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--report", default="")
    return run(p.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
