# 调研笔记

原 `docs/research/` 下的两份 llama.cpp 笔记（grammar 原理与不适用分析、2026-10-09 更新调研）
以及 ggml 设备能力查询崩溃的归档，已随 2026-10-09 的整理移除。

相关内容现分散在：

- **`CHANGELOG.md`** 的 `[2026-10-09]` 条目 —— 本轮全部改动的原因、定位过程与结论（含
  `ggml_backend_dev_get_props()` 崩溃这一未解决问题的排查记录）。
- **代码注释** —— `src/main/cpp/native-lib.cpp`（后端选择、auto 单设备、`get_props` 的
  安全边界）、`LlamaHelper.java`（`DEFAULT_BACKEND`、`backendLabel`、`getResolvedBackend`）、
  `DeviceInfoActivity.java`（设备枚举区块）等处均写明了「为什么这样做」。
- **`docs/development/12-llama-cpp.md`、`14-cmake-build.md`** —— 后端选型与构建参数的最新事实。
- 提交历史中可检索：`git log --grep=grammar`、`git log --grep=hexagon`、
  `git log --grep=get_props`。
