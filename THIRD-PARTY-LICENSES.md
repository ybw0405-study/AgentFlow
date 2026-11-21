# Third-Party Licenses — AgentFlow

本仓库自身采用 MIT License（见 LICENSE）；见根目录 `NOTICE` 与 `LICENSE`。

下表由 `ops/gen-third-party-licenses.py` 从本机
`.venv/Lib/site-packages/*.dist-info/METADATA` 实际读取汇总
（`License-Expression` / License Classifier / `License` 字段），按许可证类别分组。
**权威且带版本与哈希的清单**是 `.github/workflows/sbom.yml` 产出的可下载 SBOM artifact；
若此处与发行包内 `LICENSE` / `METADATA` 不一致，以发行包内文本为准。
本文件不构成法律意见。

共识别 48 个第三方发行包，12 类许可证声明。

## Apache Software License（3）

| 组件 | 版本 |
| --- | --- |
| `bcrypt` | `5.0.0` |
| `requests` | `2.34.2` |
| `sortedcontainers` | `2.4.0` |

## Apache-2.0（5）

| 组件 | 版本 |
| --- | --- |
| `asyncpg` | `0.32.0` |
| `docker` | `7.2.0` |
| `opentelemetry-api` | `1.45.2` |
| `pytest-asyncio` | `1.5.0` |
| `testcontainers` | `4.15.0` |

## Apache-2.0 AND BSD-2-Clause（1）

| 组件 | 版本 |
| --- | --- |
| `prometheus-client` | `0.26.0` |

## Apache-2.0 OR BSD-2-Clause（1）

| 组件 | 版本 |
| --- | --- |
| `packaging` | `26.3` |

## BSD License（3）

| 组件 | 版本 |
| --- | --- |
| `colorama` | `0.4.6` |
| `httpx` | `0.28.1` |
| `jinja2` | `3.1.6` |

## BSD-2-Clause（2）

| 组件 | 版本 |
| --- | --- |
| `pygments` | `2.21.0` |
| `wrapt` | `2.5.2` |

## BSD-3-Clause（9）

| 组件 | 版本 |
| --- | --- |
| `click` | `8.5.1` |
| `fakeredis` | `2.40.0` |
| `httpcore` | `1.0.9` |
| `idna` | `3.20` |
| `markupsafe` | `3.0.4` |
| `python-dotenv` | `1.2.4` |
| `starlette` | `1.10.0` |
| `uvicorn` | `0.54.0` |
| `websockets` | `17.4` |

## MIT（16）

| 组件 | 版本 |
| --- | --- |
| `annotated-doc` | `0.0.6` |
| `annotated-types` | `0.8.0` |
| `anyio` | `4.16.0` |
| `charset-normalizer` | `3.5.2` |
| `fastapi` | `0.163.1` |
| `httptools` | `0.9.0` |
| `iniconfig` | `2.3.1` |
| `pip` | `26.2.1` |
| `pydantic` | `2.14.3` |
| `pydantic-core` | `2.50.4` |
| `pydantic-settings` | `2.16.3` |
| `pytest` | `9.2.0` |
| `redis` | `8.1.0` |
| `ruff` | `0.17.5` |
| `typing-inspection` | `0.4.5` |
| `urllib3` | `2.8.0` |

## MIT License（5）

| 组件 | 版本 |
| --- | --- |
| `h11` | `0.16.0` |
| `pluggy` | `1.7.0` |
| `pyyaml` | `6.0.3` |
| `simpleeval` | `1.0.8` |
| `watchfiles` | `1.3.0` |

## Mozilla Public License 2.0 (MPL 2.0)（1）

| 组件 | 版本 |
| --- | --- |
| `certifi` | `2026.10.23` |

## PSF-2.0（1）

| 组件 | 版本 |
| --- | --- |
| `typing-extensions` | `4.16.0` |

## Python Software Foundation License（1）

| 组件 | 版本 |
| --- | --- |
| `pywin32` | `312` |
