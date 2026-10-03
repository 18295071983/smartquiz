# 硬件与性能设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述硬件加速（GPU）、上下文管理、性能优化项。

## 一、GPU 加速（`ai.gpu`）

### 1.1 核心组件

| 类 | 职责 |
|-----|------|
| `GpuAdaptiveTuner` | 运行时自适应调优 |
| `GpuCapabilityDetector` | GPU 能力评估（OpenCL/Vulkan） |
| `GpuConfig` | GPU 配置（backend/layers） |
| `GpuDatabase` | 设备基准数据 |
| `GpuInfo` / `GpuProfile` | GPU 信息/档案 |
| `MemoryMonitor` | 内存监控 |

### 1.2 后端支持

| 后端 | 状态 | 说明 |
|------|------|------|
| OpenCL | **ON** | 跨平台，兼容性好（Adreno 830 实测 663 t/s prefill） |
| Vulkan | **ON** | 双后端（2026-10 启用），设备支持即可切换 |

**当前设备**（Snapdragon 8 Elite Gen 2 / Adreno 840v2）：
- Adreno 专用 Kernel: ON
- GPU 全量 **36 层**（`gpu_layers_manual=` 预置会被忽略，以 auto=36 为准）

## 二、上下文管理

### 2.1 上下文容量（AIConfig.OptimizationMode）

| 模式 | id | contextSize |
|------|-----|------------|
| TURBO | 0 | 4096 |
| BALANCED | 1 | 12288（默认） |
| PERFORMANCE | 2 | 16384 |
| ULTIMATE | 3 | 16384 |

上限受 `ResourceConfig.MAX_CONTEXT_SIZE`（16384）与设备内存钳制。

### 2.2 上下文生命周期

- 12K 上下文下 Agent 单循环保留 ~8-10 轮完整历史，12 轮为平衡点。
- 超出后旧历史被裁剪，因此 `MAX_ITERATIONS_BASE=12` 为平衡点。

## 三、性能优化项（已实现）

| 优化 | 说明 | 位置 |
|------|------|------|
| **KV 增量缓存** | AgentKvCache 三级策略（INCREMENTAL/PARTIAL/FULL）；记账=prompt+生成输出；`llama_memory_seq_rm` 前缀截断 | `src/main/cpp/agent_kv_cache.*` |
| **batch warmup** | 1-token + 256-token 预热 | `native-lib.cpp` |
| **flash attention** | `GGML_OPENCL_FA_C8=1`（NSG2 变体，A8X 适配） | `native-lib.cpp` |
| **生成超时放宽** | 120s → 300s（Java watchdog 20s） | `native-lib.cpp` / Java |
| **输出限制放宽** | `FINAL_RESPONSE_MAX_TOKENS=4000` | `AgentLoopEngine` |
| **上下文容量** | 优化模式驱动（12K 默认） | `AIConfig` |
| **KV 记账修复** | 记账=prompt+生成输出（修复"只记 prompt→seqMax 校验必败"） | `native-lib.cpp` |

## 四、计时与统计

- 首 token 计时：`[PERF] First token` 日志。
- Token 统计：`ai.stats.TokenStatsManager` + 状态栏 `tv_token_stats` 显示。
- 生成速率：`streamingUpdateManager` 计算 token/s。

## 五、资源钳制

`com.oilquiz.app.ai.optimization.ResourceConfig` 根据设备 RAM 决定：
- 12GB+ 设备 → `MAX_CONTEXT_SIZE=16384`
- 内存钳制确保推理不 OOM（batch 回退 256 因 OOM）。

## 相关文档

- [AI 服务与推理设计](04-ai-service-inference.md)
- [项目架构总览](01-project-overview.md)
- [模块清单](08-module-inventory.md)
