"""导出 engine-api 的 OpenAPI 到 docs/openapi/engine-api.json（契约漂移基线）。

用法：engine-api/.venv python scripts/dump-openapi.py（仓库根执行）
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "engine-api"))

from app.main import create_app  # noqa: E402


def main() -> None:
    app = create_app()
    out = ROOT / "docs" / "openapi" / "engine-api.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(app.openapi(), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
