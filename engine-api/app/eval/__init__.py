"""工作流确定性回归 harness（PR6）。

固定 DSL + 固定 executionId/seed，在内存事件库 + fakeredis + 单测同款离线夹具上
跑一组回归场景，把剔除耗时/时间戳后的执行报告与入库基线比对。默认全程离线：
不触网、不调真实模型。`--live` 才走真实 OpenAI 兼容端点（nightly 专用）。

开发期工具：从 engine-api 源码目录以 `python -m app.eval` 运行，依赖 dev extras
（fakeredis/pytest）与 tests/ 夹具，不随服务进程加载。
"""
