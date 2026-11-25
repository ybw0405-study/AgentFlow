#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""性能压测脚本骨架 — 重点：AgentFlow 月度额度跨月窗口。

背景（方案）：`BudgetLedgerMapper.java` 用 `DATE_FORMAT(NOW(3),'%Y-%m')`
算账期（MySQL 侧、UTC 月），`ExecutionService.java` 用
`YearMonth.now(ZoneId.of("Asia/Shanghai"))`（CST 月），compose 未设
`--default-time-zone`。跨月边界（UTC 与 CST 差 8h）会出现：
  Java 查 CST 月、SQL 写 UTC 月 -> 月度额度硬约束查空账本失效、用量少算、
  账本被劈成两个月。修复方向：period 由注入的 PeriodClock 统一计算后作为
  参数传入 SQL，任何 SQL 不得用 NOW() 计算业务账期（不变量 I5）。

压测策略（**不等待真实跨月**）：用可控时钟注入把服务「冻结」到跨月边界前后，
并发扣减额度，断言账期一致性与额度不超发：
  用例 M1：2026-02-28 23:30 CST（= 2026-02-28 15:30 UTC，同一 UTC 月内）
  用例 M2：2026-03-01 00:30 CST（= 2026-02-28 16:30 UTC，CST 已换月、UTC 未换月）
  用例 M3：2026-03-01 08:30 CST（= 2026-03-01 00:30 UTC，两侧都换月）
  在 M2 这个「窗口」里，Java 认为 3 月、SQL 认为 2 月 —— 正是 bug 触发点。

观测 / 断言：
  * 单个 (tenant, period) 账本恰好 1 行，不劈月；
  * Java period 与 SQL 写入 period 完全一致；
  * 并发扣减总量不超过月度额度（无超发、无少算）；
  * 跨月窗口内重复请求不双计、不重复计费；
  * p99 延迟与错误率符合 SLO。

【脚手架定位 / 诚实边界】
  * 这是可执行骨架，**本机未做真实压测**：没有真实 MySQL / 服务 / 可控时钟，
    没有产生任何压测报告与实测数值。
  * 默认 dry-run：只打印跨月用例矩阵与断言，不发请求、不连库。
  * --execute 需调用方提供真实端点、DB DSN 与「时钟注入」开关；骨架不内嵌
    MySQL 客户端，账本查询为 TODO 占位（按 FAIL 计，接线后重跑）。

运行：
  python drill_loadtest_monthly_quota.py # dry-run（默认）
  python drill_loadtest_monthly_quota.py --execute \
      --base-url http://127.0.0.1:8080 --dsn mysql://... --quota 1000 \
      --concurrency 50 --report out.json
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

# (用例名, 冻结时刻 CST, 期望 Java period, 期望 SQL period, 说明)
CASES = [
    ("M1_before", "2026-02-28T23:30:00+08:00", "2026-02", "2026-02", "CST 未换月，UTC 亦未换月"),
    ("M2_window", "2026-03-01T00:30:00+08:00", "2026-03", "2026-03", "CST 已换月 / UTC 仍 2 月（劈月触发点）"),
    ("M3_after", "2026-03-01T08:30:00+08:00", "2026-03", "2026-03", "两侧均换月"),
]

ASSERTIONS = [
    "A1 单个 (tenant, period) 账本恰好 1 行（不劈月）",
    "A2 Java PeriodClock 计算的 period == SQL 写入的 period",
    "A3 并发扣减总量 <= 月度额度（无超发）",
    "A4 用量不少算（请求数 == 账本 used 增量）",
    "A5 跨月窗口内幂等键生效，无重复计费",
    "A6 p99 延迟 / 错误率符合 SLO",
]


@dataclass
class LoadResult:
    execute: bool
    rows: list[dict] = field(default_factory=list)

    def dump(self, path: str | None) -> dict:
        report = {
            "kind": "drill-loadtest-monthly-quota",
            "project": "AgentFlow",
            "executed": self.execute,
            "note": "骨架自检；本机未做真实压测，无实测数值" if not self.execute
                    else "真实执行；数值由调用方环境产生",
            "assertions": ASSERTIONS,
            "rows": self.rows,
        }
        if path:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(report, fh, ensure_ascii=False, indent=2)
        return report


def one_request(url: str, body: dict, headers: dict, timeout: float) -> tuple[bool, float]:
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 method="POST", headers=headers)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return (200 <= resp.status < 300), (time.perf_counter() - t0) * 1000
    except Exception: # noqa: BLE001
        return False, (time.perf_counter() - t0) * 1000


def run(args: argparse.Namespace) -> int:
    r = LoadResult(execute=args.execute)
    print("=" * 72)
    print("月度额度跨月窗口压测骨架 (AgentFlow) | execute =", args.execute)
    print("跨月用例矩阵：")
    for name, clock, jp, sp, note in CASES:
        print(f" {name}: clock={clock} java_period={jp} sql_period={sp} # {note}")
    print("断言:")
    for a in ASSERTIONS:
        print(" -", a)
    print("=" * 72)
    if not args.execute:
        for name, clock, jp, sp, _ in CASES:
            r.rows.append({"case": name, "clock": clock, "dry_run": True,
                           "would_assert": f"java_period == sql_period == {jp} 且 used <= {args.quota}"})
            print(f" [dry-run] {name}: 登记跨月用例，未发请求、未连库")
        r.dump(args.report)
        print("\n骨架自检完成；本机未做真实压测，无实测数值。加 --execute 才会真实打流。")
        return 0

    headers = {"Content-Type": "application/json", "X-Internal-Token": args.internal_token}
    path = "/api/executions/charge" # TODO: 按真实额度扣减端点校准
    for name, clock, jp, sp, _ in CASES:
        print(f"\n---- 用例 {name} @ {clock} ----")
        body = {"tenantId": args.tenant, "amount": 1, "clockOverride": clock, "idempotencyKey": f"b4-{name}"}
        lat: list[float] = []
        errors = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futs = [pool.submit(one_request, args.base_url + path, dict(body, idempotencyKey=f"b4-{name}-{i}"),
                                headers, args.timeout) for i in range(args.concurrency)]
            for f in concurrent.futures.as_completed(futs):
                ok, ms = f.result()
                lat.append(ms)
                if not ok:
                    errors += 1
        # TODO: 真实实现需查 MySQL budget_ledger：
        # SELECT period, count(*), sum(used) FROM budget_ledger WHERE tenant_id=%s GROUP BY period
        # 断言恰好 1 行、period == jp、sum(used) <= quota、无重复计费。
        row = {
            "case": name, "clock": clock, "expected_java_period": jp, "expected_sql_period": sp,
            "requests": len(lat), "error_rate": round(errors / (len(lat) or 1), 4),
            "ledger_rows": None, "period_consistent": None, "quota_ok": None,
            "note": "TODO: 接真实 MySQL 账本查询；骨架不内嵌 DB 客户端",
        }
        r.rows.append(row)
        print(" ", json.dumps(row, ensure_ascii=False))
    r.dump(args.report)
    print("\n真实执行骨架结果：账本断言为 TODO（未接线），需接 MySQL 后重跑。")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="AgentFlow 月度额度跨月窗口压测骨架（默认 dry-run）")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--base-url", default="http://127.0.0.1:8080")
    p.add_argument("--dsn", default="")
    p.add_argument("--internal-token", default="")
    p.add_argument("--tenant", default="11111111-1111-1111-1111-111111111111")
    p.add_argument("--quota", type=int, default=1000)
    p.add_argument("--concurrency", type=int, default=50)
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--report", default="")
    return run(p.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
