# AI 服务与推理设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述 AI 推理服务（本地 + 在线）、模型管理、JNI 封装。

## 一、服务层概览

```
com.oilquiz.app.ai.service
├── AIService            本地推理主入口（单例）
├── InferenceRouter      本地/在线推理路由（单例）
├── OnlineInferenceService  在线推理服务
├── AgentService         Agent 服务
├── OnlineOCRService     在线 OCR
├── ModelPreloadService  模型预加载
└── AICrashHandler       AI 崩溃处理
```

## 二、AIService（本地推理主入口）

**类**: `com.oilquiz.app.ai.service.AIService`

单例（`AIService.getInstance(Context)`），负责本地 llama.cpp 推理的全生命周期。

### 2.1 初始化与模型加载

| 方法 | 说明 |
|------|------|
| `initializeAsync(callback)` | 异步初始化 |
| `reloadModelAsync(callback)` / `reloadCurrentModelSafe()` | 重载模型 |
| `switchModelSafe(modelName)` / `hotSwitchModel(...)` | 热切换模型 |
| `loadModelWithChunking(...)` | 分块加载 |
| `preloadModel(...)` | 预加载 |

### 2.2 推理

| 方法 | 说明 |
|------|------|
| `generate(prompt, maxTokens, GenerateCallback)` | 一次性生成 |
| `generateStream(prompt, history, maxTokens, GenerateStreamCallback)` | **流式生成**（核心） |
| `chatSend(message, maxTokens, enableThinking, TokenCallback)` | 聊天上下文发送（本地 Agent 用） |
| `stopGeneration()` | 停止 |
| `clearHistory()` | 清空上下文 |

`GenerateStreamCallback` 接口：

```java
interface GenerateStreamCallback {
    void onToken(String token);
    void onSuccess(String fullText);
    void onError(Exception error);
}
```

### 2.3 状态观察

`AIService` 实现状态机（`AIServiceState.ServiceStage`）：

```
UNINITIALIZED → INITIALIZING → LOADING → READY → INFERRING → (ERROR/UNLOADED)
```

通过 `registerDetailedStatusObserver(DetailedStatusObserver)` 订阅状态变化（`onStateChanged`/`onError`/`onInitialized`）。

## 三、InferenceRouter（本地/在线路由）

**类**: `com.oilquiz.app.ai.inference.InferenceRouter`

统一推理路由，根据配置选择本地或在线。

| 方法 | 说明 |
|------|------|
| `generateStream(message, config, StreamCallback)` | 推理（自动路由） |
| `isUsingOnlineModel()` | 是否在用在线模型 |
| `getCurrentModelName()` | 当前模型名 |

## 四、本地推理核心（`ai.refactor`）

| 类 | 职责 |
|-----|------|
| `AIInferenceCore` | 推理核心（JNI 封装，`InferenceConfig`） |
| `LlamaHelper` | llama.cpp 接口（Native Thread、TokenCallback） |
| `AIConfig` | 优化模式/上下文配置 |
| `CacheManager` | 上下文缓存管理 |

### JNI 层

`com.oilquiz.app.ai.jni` 下的 `LlamaHelper` 等通过 JNI 调用本地 llama.cpp。`AgentKvCache`（`src/main/cpp/agent_kv_cache.h/.cpp`）提供 KV 增量缓存。

## 五、模型管理（`ai.model`）

| 类 | 职责 |
|-----|------|
| `ModelManager` | 本地模型管理（下载/导入/列表） |
| `ModelRegistry` | 模型注册与发现 |
| `ModelCacheManager` | 模型缓存 |
| `ModelHotSwitcher` | 模型热切换 |
| `OnlineModelManager` | 在线模型管理（API Key/服务检测/计费） |
| `MultiModelManager` | 多模型并行管理 |

## 六、硬件加速（`ai.gpu`）

| 类 | 职责 |
|-----|------|
| `GpuAdaptiveTuner` | GPU 运行时调优 |
| `GpuCapabilityDetector` | 加速能力评估（OpenCL/Vulkan/NPU 枚举） |
| `GpuConfig` | GPU 配置 |
| `GpuDatabase` | 设备基准数据 |
| `MemoryMonitor` | 内存监控 |

当前设备（Snapdragon 8 Elite Gen 2）：
- 四后端：**Hexagon(NPU) 默认** > OpenCL > Vulkan > CPU；AI 服务界面可切换，不再硬编码。
- 本机实测 prefill：NPU ~1500 tok/s，OpenCL ~205 tok/s（见 `14-cmake-build.md`）。
- Adreno 专用 Kernel: ON
- 加速层数上限 `MAX_GPU_LAYERS = 64`（native 与 `ResourceConfig` 同为 64）；
  实际按模型层数与内存预算算出，真机 2B 模型为 **43/43 层全量卸载到 HTP0**。

## 七、优化项（已实现）

| 优化 | 说明 |
|------|------|
| **KV 增量缓存** | AgentKvCache 三级策略（INCREMENTAL/PARTIAL/FULL），记账=prompt+生成输出 |
| **batch warmup** | 1-token + 256-token 预热 |
| **flash attention** | `GGML_OPENCL_FA_C8=1`（NSG2 变体） |
| **生成超时放宽** | 120s → 300s（Java watchdog 20s） |
| **输出限制放宽** | `FINAL_RESPONSE_MAX_TOKENS=4000` |
| **上下文容量** | 由 OptimizationMode 决定（12K 默认） |

## 相关文档

- [项目架构总览](01-project-overview.md)
- [AI Agent 架构设计](02-ai-agent-architecture.md)
- [AI 对话界面设计](03-ai-chat-ui.md)
- [工具系统设计](05-tool-system.md)
