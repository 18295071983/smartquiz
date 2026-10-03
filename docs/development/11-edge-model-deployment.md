# 端侧大模型部署设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述端侧（手机端）大模型的完整部署链路：模型获取、加载、GPU 加速、上下文配置、KV 缓存、热切换、资源钳制。全部依据真实源码（`native-lib.cpp` / `ai.model` / `ai.service` / `ai.gpu` / `ai.refactor`）。

## 一、部署链路总览

```
模型获取（下载/导入/传输）
    ↓
模型管理（存储/校验/列表）
    ↓
模型加载（分块加载 + JNI）
    ↓
推理配置（上下文/线程/GPU/量化）
    ↓
GPU 加速（OpenCL/Adreno）
    ↓
KV 缓存（增量缓存优化）
    ↓
运行时（热切换/内存钳制/状态机）
```

## 二、模型获取

### 2.1 ModelDownloadManager

**类**: `com.oilquiz.app.ai.model.ModelDownloadManager`（单例）

从远程/预设下载模型，支持国内镜像：

| 方法 | 说明 |
|------|------|
| `download(request, callback)` | 下载模型 |
| `downloadPresetModel(modelId, presetInfo, callback)` | 下载预设模型 |
| `downloadFromCustomUrl(modelId, url, callback)` | 自定义 URL 下载 |
| `setUseDomesticMirror(enabled)` / `convertToDomesticMirror(url)` | 国内镜像 |
| `setCurrentMirrorSource(source)` | 镜像源 |
| `setDownloadMethod(method)` | 下载方式 |

### 2.2 本地导入

- `ModelManager.loadModelFromLocalPath(path)` — 从本地路径加载
- `ModelManager.loadModelFromUri(uri, progressCallback)` — 从 content:// URI 加载
- `ModelManager.importModelFromLocalPath(path, callback)` — 导入模型
- `ModelManager.autoImportFromDirectory(dir)` — 从目录自动导入
- `ModelManager.scanExternalStorageForModels()` — 扫描外部存储

### 2.3 模型传输

`ModelTransferManager` 支持局域网模型传输（`setModelSaveDirectory` / 服务启动）。

## 三、模型管理

**类**: `com.oilquiz.app.ai.model.ModelManager`

| 方法 | 说明 |
|------|------|
| `getModelPath(modelName)` | 模型路径 |
| `isModelAvailable(modelName)` | 是否可用 |
| `getModelSize(modelName)` | 模型大小 |
| `deleteModel(modelName)` | 删除模型 |
| `setModelSaveDirectory(dir)` | 自定义保存目录 |
| `listAvailableModels()` | 模型列表 |
| `getModelDir()` | 模型目录 |

相关：
- `ModelRegistry` — 模型注册/发现
- `ModelCacheManager` — 模型缓存
- `ModelMemoryManager` — 模型内存管理
- `ModelStateCache` — 模型状态缓存

## 四、模型加载

### 4.1 分块加载（ModelChunkLoader）

**类**: `com.oilquiz.app.ai.model.ModelChunkLoader`

对大模型分块加载，避免一次性占用过多内存，支持加载进度回调：

```java
void loadModel(String modelPath, int contextSize, int nThreads, ChunkCallback callback);
void cancel();
boolean isLoading();
void release();
```

### 4.2 JNI 加载入口

- `AIService.loadModelWithChunking(modelPath, contextSize, nThreads, chunkCallback)` — 分块加载
- `AIService.initializeAsync(modelName, callback)` — 异步初始化
- `AIService.loadModelWithChunking` 通过 `LlamaHelper`（JNI）加载 GGUF

底层 llama.cpp 初始化（`native-lib.cpp`）：
- `batchSize`（默认 32，warmup 时用 256）
- `threadCount`（线程数，钳制不小于 `MIN_THREADS=1`）
- `kvCacheType`（KV 量化：0=Q8_0 省一半内存 / 1=F16 默认高精度）

## 五、推理配置（AIConfig.OptimizationMode）

**类**: `com.oilquiz.app.ai.refactor.AIConfig` — 通过 OptimizationMode 管理上下文/线程/GPU。

| 模式 | id | contextSize | batchSize | memoryPoolMB | maxThreads | gpuEnabled |
|------|-----|------------|-----------|--------------|-----------|-----------|
| TURBO | 0 | 4096 | 64 | 1024 | 2 | false |
| BALANCED | 1 | 12288 | 128 | 2048 | 3 | true |
| PERFORMANCE | 2 | 16384 | 256 | 2560 | 4 | true |
| ULTIMATE | 3 | 16384 | 512 | 3072 | 4 | true |

- `maxTokens`：默认 16384（AIConfig.getMaxTokens）。
- 上限受 `ResourceConfig.MAX_CONTEXT_SIZE`（16384）与设备 RAM 钳制。

## 六、GPU 加速

### 6.1 硬件层

**类**: `com.oilquiz.app.ai.gpu.*`

| 类 | 职责 |
|-----|------|
| `GpuConfig` | GPU 配置（backend/layers） |
| `GpuCapabilityDetector` | GPU 能力评估（OpenCL/Vulkan） |
| `GpuAdaptiveTuner` | 运行时自适应调优 |
| `GpuDatabase` | 设备基准数据 |
| `MemoryMonitor` | GPU 内存监控 |

### 6.2 后端（当前设备 Snapdragon 8 Elite Gen 2）

| 后端 | 状态 |
|------|------|
| OpenCL | **ON** |
| Vulkan | **ON**（2026-10 起双后端） |
| Adreno 专用 Kernel | ON |
| GPU 层数 | **全量 36 层**（`gpu_layers_manual=` 预置被忽略，以 auto=36 为准） |

### 6.3 Flash Attention

```cpp
// native-lib.cpp
setenv("GGML_OPENCL_FA_C8", "1", 0);  // NSG2 变体（128 WG，适配 Adreno）
// 条件：GGML_OPENCL_FA_C8=1 且 n_kv >= 2048
```

当 KV 缓存达到 2048（当前 12K 上下文已达标）时启用 flash attention cluster-parallel，显著提速。

## 七、KV 缓存

### 7.1 Agent KV 增量缓存（AgentKvCache）

**文件**: `src/main/cpp/agent_kv_cache.h/.cpp`

为 Agent 多轮迭代提供的独立 KV 增量缓存，避免每次都重新计算前缀。

- **策略**: `INCREMENTAL` / `PARTIAL` / `FULL` 三级。
- **记账**: prompt + 生成输出。
- **前缀截断**: `llama_memory_seq_rm`。

### 7.2 KV 量化

`kvCacheType`：
- `0` = Q8_0（省一半内存）
- `1` = F16（默认，精度更高）

## 八、运行时管理与热切换

### 8.1 热切换（ModelHotSwitcher）

**类**: `com.oilquiz.app.ai.model.ModelHotSwitcher`

```java
boolean hotSwitch(String targetModelId, SwitchCallback callback);  // 热切换
boolean preloadModel(String modelId, PreloadCallback callback);    // 预加载
```

### 8.2 内存钳制（ResourceConfig）

`com.oilquiz.app.ai.optimization.ResourceConfig` 根据设备 RAM 决定：
- 12GB+ 设备 → `MAX_CONTEXT_SIZE = 16384`
- batch warmup 时 256-token，OOM 回退。

### 8.3 服务状态机

`AIService` 状态：`UNINITIALIZED → INITIALIZING → LOADING → READY → INFERRING → (ERROR/UNLOADED)`

## 九、推荐部署参数（当前设备 12GB）

| 参数 | 建议值 | 说明 |
|------|--------|------|
| 优化模式 | BALANCED | 默认（contextSize=12288） |
| GPU 层数 | 36 | 全量 |
| Flash Attn | ON | `GGML_OPENCL_FA_C8=1` |
| KV 量化 | F16 | 精度优先（内存够） |
| batch warmup | 1 + 256 | 预热 |
| 生成超时 | 300s | C++ / watchdog 20s |
| 本地模型 | Qwen3-4B-Q4_K_M.gguf | 当前测试模型 |

## 十、部署验证（adb）

```bash
# 安装 APK
adb install -r build/app/outputs/apk/debug/答题宝-debug-2.0.apk

# 启动应用（AIChatActivity exported=false，经 App LAUNCHER 进入）
adb shell monkey -p com.oilquiz.app -c android.intent.category.LAUNCHER 1

# 观察 GPU 层数与推理
adb logcat -s "native-lib"
```

## 相关文档

- [AI 服务与推理设计](04-ai-service-inference.md)
- [硬件与性能](07-hardware-performance.md)
- [模块清单](08-module-inventory.md)
- [AI Agent 架构设计](02-ai-agent-architecture.md)
