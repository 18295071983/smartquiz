# llama.cpp 更新调研（2026-10-01 → 2026-10-08）

> 调研日期：2026-10-09
> 我们 vendored：`2c36e81`（2026-10-01，来源 gitcode 镜像）
> 官方最新：`a11f57b` / release `b11510`（2026-10-08）
> 目的：判断是否值得升级、有哪些与本项目相关的改动

## 结论摘要

**不建议为"新特性"升级。** 本区间内与本项目（Android + Adreno OpenCL + 工具调用）直接相关的改动很少（OpenCL 仅 3 个提交），且我们最关心的 grammar / 工具调用问题**在上游早已修复（2026-03）**，不需要靠升级解决 —— 真正原因是我们的采样链绕过了 `common_sampler`（已通过方案 E 修正）。

**唯一值得单独 cherry-pick 的**：`2207c8e` opencl 越界读修复（我们正是 Adreno 840 + OpenCL）。

---

## 一、与 Adreno / OpenCL 相关（共 3 个提交）

| 提交 | 日期 | 内容 | 相关性 |
|---|---|---|---|
| `2207c8e` | 10-06 | **opencl: fix OOB read in adreno xmem GEMM (#30041)** | **高** —— Adreno xmem GEMM 越界读。作者来自 Qualcomm（`lih@qti.qualcomm.com`）。我们启用 Adreno xmem F16xF32 GEMM（日志可见 `Adreno xmem F16xF32 GEMM enabled`），此修复直接适用 |
| `a8c9a4e` | 10-02 | opencl: use sigmoid f16 for bf16 (#29787) | 低 —— bf16 精度修正，我们用 Q4_K_M/Q4_0，影响有限 |
| `b92761a` | 10-03 | ggml-openvino 更新至 2026.4.1（含 MoE 融合等大量优化） | 无 —— 我们不用 OpenVINO |

## 二、与采样 / grammar / 工具调用相关

| 提交 | 日期 | 内容 | 相关性 |
|---|---|---|---|
| `d0b490f` | 10-07 | **sampling: use greedy selection for eligible temperature-zero chains (#29797)** | **中** —— 使 temp=0 的链（含 **grammar / reasoning-budget 路径**）改用 greedy 选择。与我们 `ModelSampling` 的 temp 档位、以及方案 E 引入的 grammar+rbudget 链有关 |
| `1fb7ef3` | 10-02 | spec: 为 simple draft 与 MTP 加入概率采样（含 grammar 约束下的拒绝采样、掩码后重归一化） | 低 —— 投机解码，移动端未启用 |
| `9bf55f4` | 10-03 | chat: 让 Ling 3.0 parser 支持 json_schema | 无 —— 非本项目模型 |
| `71ad059` | 10-08 | CUDA: 改进 top-k 算法选择（radix select） | 无 —— CUDA 专用 |
| `24e4183` | 10-08 | ggml-cuda: GDN state 每 warp 4 列 | 无 |

## 三、需要注意的其他改动

- `b92761a`（OpenVINO）体积庞大但与本项目无关。
- 搜到的 CUDA 改动（top-k、GDN）均不适用（我们是 Adreno OpenCL）。
- 未在本轮检索中发现针对 **Android / Hexagon / QNN** 的新后端改动。

## 四、升级评估

### 升级的代价（实测过一轮）
本项目 `native-lib.cpp` 大量使用 `common/` 内部 API：
- `common_chat_templates_init/apply`、`common_chat_params`、`common_chat_parser_params`
- `common_peg_arena` / `common_peg_parser`
- `common_grammar_trigger`、`common_params_sampling`、`common_sampler_*`（方案 E 新引入）
- `chatTemplateForKvReuse()` 自己修补 Qwen 模板（依赖模板文本特征串）

跨 7 天 + 上游这些 API 仍在演进（例如 `common_grammar` 的 type 语义、grammar_prefill 的适用范围在 #29066 被收窄），**升级需要重新验证模板修补、grammar 挂载、采样参数映射**，回归面不小。

### 建议
1. **不整体升级**。当前修复已完成且双模型真机验证通过，`llama.cpp` 零改动是很有利的状态。
2. **单独 cherry-pick `2207c8e`**（opencl 越界读修复）—— 单文件、风险低、针对我们的 GPU。可用 `git cherry-pick 2207c8e` 或手工应用 diff。
3. 若将来确需升级，**先做 API 兼容性盘点**再动手。

## 五、待确认

- `2207c8e` 的具体 diff 尚未核对（下次可直接取 `https://github.com/ggml-org/llama.cpp/commit/2207c8e.patch`）。
- 需确认该修复是否已在我们的 `2c36e81` 中（按日期判断应**未**包含，10-06 > 10-01）。
