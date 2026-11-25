"""AgentFlow 执行引擎（Python 数据面）。"""

from importlib.metadata import PackageNotFoundError
from importlib.metadata import version as _package_version

try:
    # 以 pyproject.toml 的版本为准；OpenAPI 的 info.version 也取它，
    # 否则会一直写着 FastAPI 的默认版本（0.1.0），与发布版本脱节。
    __version__ = _package_version("agentflow-engine")
except PackageNotFoundError: # 源码直跑、未 pip install 时兜底
    __version__ = "0.0.0"

__all__ = ["__version__"]
