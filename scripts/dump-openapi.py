"""导出 engine-api 的 OpenAPI 到 docs/openapi/engine-api.json（契约漂移基线）。

用法：engine-api/.venv python scripts/dump-openapi.py（仓库根执行）
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "engine-api"))

from app.main import create_app


def main() -> None:
    app = create_app()
    out = ROOT / "docs" / "openapi" / "engine-api.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    # 显式 LF：Windows 上 text mode 默认写 CRLF，而 .gitattributes 要求 LF 入库，
    # 两者混用会让「同一份代码生成字节一致」的保证失效（Java 侧已按同样方式修过）。
    with out.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(
            json.dumps(app.openapi(), ensure_ascii=False, indent=2, sort_keys=True)
            + "\n"
        )
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
