# 推理库与推理引擎设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述推理能力的两层架构：**推理引擎（Java 调度层）** 与 **推理库（底层 JNI/llama.cpp 实现层）**。全部依据真实源码。

## 一、两层架构总览

```
┌──────────────────────────── 推理引擎（Java 调度层）─────────────────────────┐
│  AIInferenceCore            统一推理引擎入口（本地/在线自动路由、并发锁）        │
│    ↓                                                                        │
│  InferenceRouter            推理路由（本地/在线/回退、模型切换）               │
│    ↓                                                                        │
│  AIService（本地）           OnlineModelManager / OnlineInferenceService     │
└──────────────────────────────────────┬─────────────────────────────────────┘
                                       ↓ JNI
┌──────────────────────────── 推理库（底层实现层）──────────────────────────────┐
│  LlamaHelper（JNI 封装，~72 native 方法）                                    │
│    ↓                                                                            │
│  native-lib.cpp（llama.cpp 实现）                                              │
│  ├─ 模型加载/推理（generate / generateStream）                                │
│  ├─ 聊天上下文（common_chat_templates_apply）                                  │
│  ├─ 工具调用（<tool_call> FC） + 思考链解析                                    │
│  ├─ AgentKvCache（KV 增量缓存）                                                │
│  └─ 加速后端（NPU/OpenCL/Vulkan + flash attention）                                       │
└──────────────────────────────────────────────────────────────────────────────┘
```

**关键区分**：
- **推理引擎** = 决定"用什么模型/走本地还是在线/并发调度"（Java 决策层）。
- **推理库** = 真正执行"模型计算/token 生成"（JNI + llama.cpp 实现层）。

## 二、推理引擎（Java 调度层）

### 2.1 AIInferenceCore（统一入口）

**类**: `com.oilquiz.app.ai.refactor.AIInferenceCore`（单例）

统一推理引擎入口，负责本地/在线**自动路由**与**并发锁**。

| 方法 | 说明 |
|------|------|
| `generateAsync(prompt, config)` | 异步生成（CompletableFuture） |
| `generateSync(prompt, config)` | 同步生成 |
| `generateStream(prompt, config, callback)` | 流式生成 |
| `isLocalAvailable()` / `isOnlineAvailable()` | 可用性检查 |
| `getCurrentInferenceType()` | 当前推理类型（LOCAL/ONLINE） |
| `shutdown()` | 关闭 |

**核心逻辑**（自动路由）：
```java
InferenceType type = getRouter().getCurrentInferenceType();
if (type == LOCAL && !ensureModelReady()) {
    if (manager.hasActiveOnlineModel()) type = ONLINE;  // 本地回退到在线
    else throw ...;
}
if (type == ONLINE) getRouter().generate(prompt, config).join();
else aiService.generateSync(...);  // 本地
```

**并发**：`inferenceLock`（`synchronized(inferenceLock)`）+ 2 线程池（`AI-Inference-Worker`，daemon）。

### 2.2 InferenceConfig（推理配置）

```java
public static class InferenceConfig {
    public String systemPrompt = "请用中文回答。";
    public int maxTokens = 8192;
    public float temperature = 0.7f;
    public float topP = 0.9f;
    public int topK = 40;
    public List<ChatMessage> history;
}
```

### 2.3 InferenceRouter（推理路由）

**类**: `com.oilquiz.app.ai.inference.InferenceRouter`（单例）

负责本地/在线路由、模型切换、回退。

| 方法 | 说明 |
|------|------|
| `getCurrentInferenceType()` | 当前类型（LOCAL/ONLINE） |
| `isUsingLocalModel()` / `isUsingOnlineModel()` | 是否本地/在线 |
| `generate / generateSync / generateStream` | 生成（分发到本地或在线） |
| `switchModel(modelId)` | 切换模型 |
| `setActiveOnlineModel(modelId)` | 激活在线模型 |
| `stopInference()` | 停止 |

**InferenceType**：
```java
public enum InferenceType {
    LOCAL("本地模型"),
    ONLINE("在线模型");
}
```

### 2.4 StreamCallback 回调

```java
public interface StreamCallback {
    void onStart();
    void onToken(String token);
    void onThinkingToken(String token);
    void onThinkingEnd();
    void onComplete(String fullText);
    void onError(String error);
    void onTokenStats(int promptTokens, int completionTokens);
    void onProgress(int progress);
}
```

## 三、推理库（底层实现层）

### 3.1 LlamaHelper（JNI 封装）

**类**: `com.oilquiz.app.ai.jni.LlamaHelper`

llama.cpp 的 JNI 封装，声明 **72 个 native 方法**（详见 [llama.cpp 功能设计](12-llama-cpp.md)）。

核心方法：
- `nativeInitModel(modelPath, nCtx, nThreads)` — 初始化
- `nativeGenerateStream*(...)` — 流式生成
- `nativeGenerateWithTools(...)` — 工具调用
- `nativeChatSend(...)` — 聊天上下文
- `nativeSetKvCacheType(...)` / `nativeSetGPULayers(...)` — 配置

### 3.2 native-lib.cpp（llama.cpp 实现）

**文件**: `src/main/cpp/native-lib.cpp`（约 347KB）

实现层，包含：
- llama.cpp 模型加载/推理。
- 聊天模板适配（`common_chat_templates_apply`）。
- 工具调用解析（`<tool_call>` 原生 FC）。
- 思考链解析（`stripThinkTags` / `findThinkingEndMarker`）。
- `AgentKvCache` 增量缓存（`agent_kv_cache.h`）。
- GPU 加速（`GGML_OPENCL_FA_C8=1` flash attention）。
- 崩溃恢复（`sigsetjmp`/`siglongjmp` + 信号处理）。
- UTF-8 安全（`sanitizeUtf8` / `splitUtf8Complete`）。

### 3.3 JNI 回调协议

`LlamaHelper.TokenCallback`（推理库 vs 引擎的桥接）：
```java
public interface TokenCallback {
    void onToken(String token);
    void onComplete(String fullText);
    void onError(String errorMsg);
}
```

推理是**同步阻塞调用**，回调在调用线程触发（`nativeGenerateStream`）。

## 四、本地/在线推理实现分工

| 模型 | 引擎层 | 库层 |
|------|--------|------|
| **本地** | `AIInferenceCore` → `AIService.generate*` | `LlamaHelper` → llama.cpp（`nativeGenerateStream`/`nativeChatSend`） |
| **在线** | `AIInferenceCore` → `InferenceRouter.generate*` | `OnlineInferenceService`（Retrofit/OkHttp）→ 云端 API |

## 五、推理调用链（流式）

```
调用方（AgentChatHandler / AIChatViewModel / 对话）
    ↓
AIInferenceCore.generateStream(prompt, config, callback)
    ↓ 自动路由（LOCAL/ONLINE）
InferenceRouter.generateStream(...)
    ↓
├─ LOCAL: AIService.generateStream → LlamaHelper.nativeGenerateStream
│           → native-lib.cpp（llama.cpp 生成）→ onToken → callback
└─ ONLINE: OnlineInferenceService → 云端 → 流式 → callback
```

## 六、相关组件

| 组件 | 类 | 职责 |
|------|-----|------|
| 上下文管理 | `ai.refactor.ContextWindowManager` / `UnifiedContextManager` / `SessionManager` | 上下文保护 |
| 缓存 | `ai.refactor.CacheManager` | 推理缓存 |
| 队列 | `ai.inference.InferenceQueue` | 推理队列 |
| 桥接 | `ai.bridge.ModelExecutionBridge` | 模型执行桥接 |
| 转换 | `ai.jni.TypeConverter` / `ChatRequest` | 类型转换/请求 |

## 2026-09/10 更新

- **llama.cpp 升级（10/01）**：合并官方最新 master（处理探针提交/GBK 修补），重新全量编译。
- **四后端 + NPU 默认（10/09）**：CMake 启用 GGML_OPENCL + GGML_VULKAN + GGML_HEXAGON；
  默认 `hexagon`，`auto` 按 `Hexagon > OpenCL > Vulkan` 解析出**单个**设备；
  AI 服务界面/设备信息页可切换（不再硬编码）。多设备曾导致模型加载在
  `ggml_backend_dev_get_props` 崩溃。
- **Vulkan 编译适配**：Adreno 840 需用与厂商驱动一致的兼容 SPIR-V 工具链编译（替换过时 glslc）；coopmat/bfloat16/dot 等高级特性受驱动限制走 F16 基础路径兜底。

## 相关文档

- [llama.cpp 功能设计](12-llama-cpp.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
- [端侧大模型部署设计](11-edge-model-deployment.md)
- [AI Agent 架构设计](02-ai-agent-architecture.md)
