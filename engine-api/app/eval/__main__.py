"""CLI 入口（engine-api 目录下运行）：

python -m app.eval # 跑离线场景并打印报告
python -m app.eval --check-baseline # 与入库基线比对，漂移退出 1（CI 门禁）
python -m app.eval --update-baseline # 语义有意变更后刷新基线，随 PR 提交
python -m app.eval --live --report eval-live-report.json # nightly 真模型冒烟 + judge
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from .harness import (
    BASELINE_PATH,
    EvalRegressionError,
    diff_scenarios,
    load_baseline,
    run_all,
    write_baseline,
)
from .live import DEFAULT_THRESHOLD, run_live


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="app.eval", description="AgentFlow 工作流确定性回归 harness（默认离线）"
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-baseline", action="store_true", help="与入库基线比对，漂移退出 1")
    mode.add_argument("--update-baseline", action="store_true", help="把当前报告写为新基线")
    mode.add_argument(
        "--live", action="store_true", help="真模型 llm 节点冒烟 + LLM judge（需 AF_LLM_* 环境）"
    )
    parser.add_argument("--report", type=Path, default=None, help="报告 JSON 写入路径")
    parser.add_argument(
        "--threshold", type=float, default=DEFAULT_THRESHOLD, help="--live judge 均分门禁"
    )
    args = parser.parse_args(argv)

    if args.live:
        return asyncio.run(run_live(report_path=args.report, threshold=args.threshold))

    # 脚本化瞬时失败是场景预期行为，调度器的 ERROR 日志对门禁输出只是噪音
    logging.getLogger("app.engine.scheduler").setLevel(logging.CRITICAL)
    try:
        report = asyncio.run(run_all())
    except EvalRegressionError as exc:
        print(f"回归语义被破坏: {exc}", file=sys.stderr)
        return 1

    if args.report is not None:
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    if args.update_baseline:
        write_baseline(report)
        print(f"基线已更新：{len(report['scenarios'])} 个场景 -> {BASELINE_PATH}")
        return 0
    if args.check_baseline:
        baseline = load_baseline()
        drifted = diff_scenarios(report, baseline)
        version_mismatch = report["version"] != baseline.get("version")
        if drifted or version_mismatch:
            print("基线漂移：", file=sys.stderr)
            if version_mismatch:
                print(
                    f" - version: 基线 {baseline.get('version')} != 当前 {report['version']}",
                    file=sys.stderr,
                )
            for name in drifted:
                print(f" - {name}", file=sys.stderr)
                expected = baseline.get("scenarios", {}).get(name)
                actual = report["scenarios"].get(name)
                print(
                    " 基线 " + json.dumps(expected, ensure_ascii=False, sort_keys=True),
                    file=sys.stderr,
                )
                print(
                    " 当前 " + json.dumps(actual, ensure_ascii=False, sort_keys=True),
                    file=sys.stderr,
                )
            return 1
        print(f"基线校验通过：{len(report['scenarios'])} 个场景一致")
        return 0

    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
