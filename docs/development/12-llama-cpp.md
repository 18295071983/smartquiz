# llama.cpp 功能设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述 llama.cpp 本地推理引擎的功能设计。全部依据真实源码 `src/main/cpp/native-lib.cpp`（约 347KB）与 JNI 层 `com.oilquiz.app.ai.jni.LlamaHelper`。

## 一、架构位置

```
Java 层
├── AIService / AIInferenceCore       推理服务
├── LlamaHelper                       JNI 封装（native 方法）
└── AgentLoopEngine                   Agent 单循环
        ↓ JNI
C++ 层 (native-lib.cpp)
├── llama.cpp 核心（模型加载/推理）
├── 聊天上下文（llama_chat_apply_template）
├── AgentKvCache（KV 增量缓存）
└── GPU 后端（OpenCL/Vulkan）
```

## 二、JNI 接口清单（按功能分类）

`native-lib.cpp` 导出约 70 个 JNI 方法（`Java_com_oilquiz_app_ai_jni_LlamaHelper_*`），按功能分组：

### 2.1 模型生命周期

| JNI 方法 | 功能 |
|----------|------|
| `nativeInitModel(modelPath, nCtx, nThreads)` | 初始化模型 |
| `nativeRelease()` | 释放模型 |
| `nativeIsModelInitialized()` | 是否已初始化 |
| `nativeGetModelInfo()` | 模型信息 |
| `nativeOptimizeForPerformance()` / `nativeOptimizeForMemory()` | 优化模式 |

### 2.2 生成（一次性 + 流式）

| JNI 方法 | 功能 |
|----------|------|
| `nativeGenerate(prompt, maxTokens, ...)` | 一次性生成 |
| `nativeGenerateStream(prompt, maxTokens, ...)` | 流式生成 |
| `nativeGenerateStreamBytes(...)` | 流式字节生成 |
| `nativeGenerateStreamFromMessages(roles[], texts[], ...)` | 消息列表流式生成（自动 chat template） |
| `nativeGenerateBatch(...)` | 批量生成 |
| `nativeStopGeneration()` | 停止生成 |
| `nativeAppendMessagesAndGenerate(...)` | 追加消息生成 |

### 2.3 工具调用（Function Calling）

| JNI 方法 | 功能 |
|----------|------|
| `nativeGenerateWithTools(...)` | 带 tools JSON 生成（`common_chat_templates_apply`） |
| `nativeChatAddAssistantToolCall(...)` | 追加助理工具调用 |
| `nativeChatAddToolResult(...)` | 追加工具结果 |
| `nativeChatAddAssistant(...)` | 追加助理消息 |
| `nativeChatJson(...)` | chat JSON 协议 |

### 2.4 聊天上下文

| JNI 方法 | 功能 |
|----------|------|
| `nativeChatCreate(...)` | 创建聊天上下文 |
| `nativeChatSend(message, maxTokens, ...)` | 聊天发送 |
| `nativeChatStop()` / `nativeChatClear()` / `nativeChatDestroy()` | 停止/清空/销毁 |
| `nativeChatGetInfo()` | 上下文信息 |
| `nativeChatUpdatePrompts(...)` | 更新提示词 |
| `nativeClearHistory()` | 清空历史 |

### 2.5 KV 缓存 / 上下文管理

| JNI 方法 | 功能 |
|----------|------|
| `nativeSetKvCacheType(type)` | KV 量化（0=Q8_0 / 1=F16） |
| `nativeGetContextSize()` / `nativeGetContextUsedTokens()` / `nativeGetContextRemainingTokens()` | 上下文用量 |
| `nativeHasEnoughContextSpace(...)` | 上下文空间检查 |
| `nativeClearContextForInference(...)` | 清上下文 |
| `nativeGetModelNctx()` | 模型上下文容量 |
| `nativeCountTokens(...)` | token 计数 |

### 2.6 GPU / 硬件

| JNI 方法 | 功能 |
|----------|------|
| `nativeGetGPULayers()` / `nativeSetGPULayers(layers)` | GPU 层数 |
| `nativeGetThreadCount()` / `nativeSetThreadCount(threads)` | 线程数 |
| `nativeGetBatchSize()` / `nativeSetBatchSize(batch)` | 批大小 |
| `nativeGetMemoryPoolSize()` / `nativeSetMemoryPoolSize(mb)` | 内存池 |
| `nativeGetDeviceCount()` / `nativeGetDeviceInfo()` | 设备信息 |
| `nativeGetFreeDeviceMemory()` / `nativeGetTotalDeviceMemory()` / `nativeGetGpuMaxMemAllocSize()` | GPU 内存 |
| `nativeIsOpenCLLoaded()` / `nativeSetOpenCLLoaded()` | OpenCL |
| `nativeIsGPUWorking()` / `nativeGetOpenCLInfo()` / `nativeDetectGPUInfo()` | GPU 状态 |
| `nativeGetModelArchitecture()` | 模型架构 |

### 2.7 统计 / 安全

| JNI 方法 | 功能 |
|----------|------|
| `nativeGetInferenceSpeed()` / `nativeGetTokenCount()` | 推理速度/Token |
| `nativeGetLastError()` | 错误 |
| `nativeInstallSignalHandlers()` | 崩溃信号处理 |
| `nativeHandleMemoryPressure()` | 内存压力 |

## 三、核心功能模块

### 3.1 聊天模板适配

使用 llama.cpp `common_chat_templates_apply`，自动适配不同模型 chat 格式（Qwen Hermes / Llama3 / Mistral 等）。支持：
- 消息列表（roles + texts）→ `nativeGenerateStreamFromMessages`
- 工具调用 prompts → `nativeGenerateWithTools`（JSON 协议）

### 3.2 工具调用解析

- 支持 `<tool_call>{"name","arguments"}</tool_call>` 原生 FC 格式（Qwen）。
- 支持 `{"tool_calls":[...]}` 数组（一轮并行工具）。
- `chatJson` 协议用于 Agent。

### 3.3 思考链解析

```cpp
// 剥离 think/thought 标签及其内容
static std::string stripThinkTags(const std::string& input);
// 处理跨 token 拆分的结束标记（</think> 被拆成 "</" + "think>"）
static int findThinkingEndMarker(const std::string& buf, int& markerLen);
```

- 优先使用 C++ `onReasoning`/`onJson` reasoning 事件。
- 回退 `<think>`/`<thought>` 标签提取。

### 3.4 KV 缓存（AgentKvCache）

`native-lib.cpp` 集成 `agent_kv_cache.h`，提供：
- 三级策略：`INCREMENTAL` / `PARTIAL` / `FULL`。
- 记账 = prompt + 生成输出。
- `llama_memory_seq_rm` 前缀截断。
- 使 Agent 多轮迭代增量计算前缀，避免重复计算。

### 3.5 GPU 加速

```cpp
// OpenCL flash attention cluster-parallel (NSG2)
setenv("GGML_OPENCL_FA_C8", "1", 0);
// 条件：GGML_OPENCL_FA_C8=1 且 n_kv >= 2048
```

- OpenCL ON / Vulkan ON（双后端，2026-10 起；设备支持哪个用哪个，AI 服务界面可切换）。
- GPU 全量 36 层。
- batch warmup（1 + 256 token）。

### 3.6 崩溃恢复

```cpp
// 统一的 sigsetjmp 包裹宏（所有推理 JNI 方法）
// 崩溃恢复：siglongjmp 跳过错位的 RAII guard
static thread_local sigjmp_buf fatal_jmp_buf;
static void fatal_signal_handler(int sig);
static void install_fatal_signal_handlers();
```

捕获原生层崩溃/信号，转交给 Java 层 `NativeRecoveryHandler` 做断点恢复。

### 3.7 UTF-8 安全

```cpp
static std::string sanitizeUtf8(const std::string& input);
static std::string splitUtf8Complete(const std::string& input, std::string& completeOut);
static jstring safeNewStringUTF(JNIEnv* env, const std::string& str);
```

- 避免流式 token 拆分 UTF-8 字符导致乱码。
- 清理 tool_call 标签残留（避免 TTS 朗读残留标签）。

## 四、JNI 回调协议

`LlamaHelper.TokenCallback`：

```java
public interface TokenCallback {
    void onToken(String token);      // 流式 token
    void onComplete(String fullText); // 完成
    void onError(String errorMsg);   // 错误
}
```

推理使用 `sigsetjmp`/`siglongjmp` 包裹，回调在调用线程同步触发（`nativeGenerateStream` 是同步阻塞调用）。

## 五、关键参数（native 层）

| 参数 | 默认 | 说明 |
|------|------|------|
| batchSize | 32 | 批大小（warmup 用 256） |
| threadCount | 设备核 | 线程数（钳制 min=1） |
| kvCacheType | 1 (F16) | 0=Q8_0 省内存 / 1=F16 精度 |
| GPU layers | 36 | Adreno 全量 |
| nCtx | 12288 | 上下文（BALANCED） |
| GGML_OPENCL_FA_C8 | 1 | flash attention |

## 六、推理流程（流式）

```
nativeGenerateStreamFromMessages / nativeGenerateStream
    ↓
llama.cpp context（chat template 适配）
    ↓
生成 token → onToken 回调 → Java 流式更新
    ↓（若模型输出工具）
解析 <tool_call> → AgentLoopEngine 执行工具 → 追加 → 继续
    ↓
onComplete(fullText) / onError(error)
```

## 七、部署相关

- 模型文件：GGUF 格式，通过 `ModelManager`/`ModelDownloadManager` 管理。
- 加载：`ModelChunkLoader` 分块加载。
- 认证：`nativeInitModel(modelPath, nCtx, nThreads)`。

## 相关文档

- [端侧大模型部署设计](11-edge-model-deployment.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
- [AI Agent 架构设计](02-ai-agent-architecture.md)
- [硬件与性能](07-hardware-performance.md)
