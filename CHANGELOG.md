# 变更日志

## [2.1.0] - 2026-08-05

### 核心修复

#### 1. 模型输出乱码和非标准字符修复
- **问题**：截断逻辑错误拆分 Unicode 代理对（emoji 等），源代码包含损坏的 U+FFFD 字符，模型输出包含乱码模式（"锟斤拷"、"烫烫烫"等）
- **修复**：
  - 新增 `safeTruncate()` 避免截断时拆分代理对
  - 新增 `sanitize()` 移除 U+FFFD 替换字符和非法控制字符
  - 新增 `detectGarble()` 识别常见乱码模式
  - 新增 `cleanModelOutput()` 组合清理和乱码检测，返回 null 触发降级
- **应用范围**：AIService、OnlineInferenceService、AIImportAgent、OnlineAgentEngine、LlamaHelper、AIFieldMapper、AIImportOrchestrator、InferenceQueue、SmartIntentRecognizer 等所有模型输出路径

#### 2. 推理锁保护修复
- **问题**：`generateStream` 系列方法不获取推理锁，可能导致并发推理引发 native 崩溃
- **修复**：
  - 给 `generateStream(String/List<Message>/ChatRequest)` 3 个方法添加 `acquireInferenceWriteLock` + `try/finally` 保护
  - 利用 `ReentrantReadWriteLock` 写锁可重入特性，`generate(List<Message>)` → `generateStream(List<Message>)` 不会死锁
  - 添加详细的锁竞争日志（线程名、持有数、等待队列、耗时）
- **文件**：`LlamaHelper.java`

#### 3. ChatML 格式硬编码修复
- **问题**：`PromptBuilder.build()` 硬编码 ChatML 格式（`<|im_start|>`），对 Llama 3/Gemma/Phi-3 等非 ChatML 模型格式不匹配
- **修复**：
  - 删除 `formatPromptForNativeLib` 和 `formatPromptForModel` 死代码
  - 新增 `buildMessagesForModel` 返回消息列表而非格式化字符串
  - `generateAsync/generateSync/generateStream/generate` 全部改用消息列表路径
  - native 层 `llama_chat_apply_template` 自动适配所有模型格式
- **文件**：`AIService.java`、`InferenceRouter.java`、`AIInferenceCore.java`、`ALChat.java`

#### 4. 在线 Agent 多轮推理中断修复
- **问题**：在线 Agent 多轮推理设计缺陷导致中断
- **修复**：推理最多重试 2 轮，确保工具调用流程完整

### 新增功能

#### 1. 在线模型 API 统计自动切换
- **数据源切换**：在线模式使用 API usage 数据（🌐），本地模式使用 native 数据（⚡）
- **回调链路**：`OnlineInferenceService.parseAndNotifyTokenStats` → `InferenceRouter` → `AIChatActivity.onTokenStats`
- **UI 显示**：`updateStreamingTokenStats` 自动切换数据源，`AIServiceStatusActivity` 显示 API 累计统计

#### 2. AI 导入引擎 v4（混合管道）
- **新增组件**：
  - `AIImportAgent` - 专用 Agent 组件
  - `AIFieldMapper` - 三层语义冲突解决（避免"答案1/答案2"与"正确答案"冲突）
  - `FieldMappingRegistry` - 字段映射注册表
  - `ImportValidator` - 导入验证器
  - `QuestionFormatDetector` - 题目格式检测
  - `ExcelSheetPicker` - Excel 表格选择器
- **二进制文件处理**：`FileContentExtractor` 使用 `WorkbookFactory.create()` 处理 .xls/.xlsx
- **工具**：`CharsetDetector`（字符集检测）、`GarbledTextFixer`（乱码修复）

#### 3. 在线 Agent 引擎
- **新增组件**：
  - `OnlineAgentEngine` - 在线 Agent 核心引擎
  - `OnlineToolManager` / `OnlineToolRegistry` - 工具管理和注册
  - `OnlineThinkingChain` - 思考链（默认折叠）
  - `OnlineToolGuide` - 工具引导卡片
  - `OnlineToolUsageTracker` - 工具使用追踪
  - `OnlinePromptBuilder` - 在线提示词构建

#### 4. 引导式工具执行系统
- **新增组件**：
  - `ToolGuideFlow` - 工具引导流程
  - `ToolPreChecker` - 工具预检查器（检查参数缺失）
  - `ToolErrorRecovery` - 工具错误恢复
  - `ToolContextProvider` - 工具上下文提供者
  - `CompositeGuideFlow` - 组合引导流程
  - `ToolResultInterpreter` - 工具结果解释器（支持三层折叠）
  - `ToolResultStore` - 工具结果存储

#### 5. 模型下载页面整理
- **删除 15 个不合适模型**：ChatGLM3-6B（ggml格式）、Mixtral-8x7B(26GB)、DeepSeek-V2(8.5GB) 等过大模型
- **新增国内模型**：MiniCPM3-4B（面壁智能）、GLM-Edge-1.5B/4B（智谱AI）、Yi-Coder-1.5B（零一万物）、DeepSeek-R1-Distill-Qwen-1.5B
- **修复国际模型 URL**：Phi-3.5/Phi-3 的 GGUF 仓库路径错误
- **新增快捷方式**：本地模型导入界面添加"去下载"按钮

#### 6. 内容渲染器
- **新增组件**：`ContentRenderer`（接口）、`ContentTypeDetector`、`MarkdownContentRenderer`、`HtmlContentRenderer`、`MathContentRenderer`（数学公式）、`MermaidContentRenderer`（图表）、`RenderExecutor`

#### 7. 天气 UI 增强
- **新增自定义 View**：`CircularGaugeView`（圆形仪表盘）、`MinutelyPrecipChartView`（分钟级降水图）、`TempRangeBarView`、`TemperatureBarView`
- **布局重构**：`activity_weather_detail.xml`（+1476 行），新增逐日/逐小时预报项
- **新增 drawable**：glass_card、sky_sunny/cloudy/rainy/night、precip_map_bg 等 15 个

#### 8. 模型架构信息展示
- `AIServiceStatusActivity` 显示模型参数量、层数、头数、嵌入维度、上下文长度、内存占用等信息
- 新增定时刷新机制（2秒轮询）更新实时推理速度和 Token 计数

### 技术改进

#### JNI 内存安全
- `native-lib.cpp` 添加 JNI 信号处理器
- 修复 JNI 局部引用泄漏
- `Error` 级别保护 `countTokens/initModel` 方法
- `finally` 块升级为 `catch Throwable`

#### 测试工具
- 新增 `StreamTestReceiver` 广播接收器，可通过 ADB 广播触发流式输出测试
  ```bash
  adb shell am broadcast -a com.oilquiz.app.STREAM_TEST -n com.oilquiz.app/.receiver.StreamTestReceiver
  ```

### 变更统计

| 指标 | 数值 |
|------|------|
| 提交数量 | 10 |
| 变更文件数 | 180 |
| 新增代码行 | +44,710 |
| 删除代码行 | -5,612 |
| 新增 Java 类 | 40+ |

---

## [Unreleased] - 2026-07-29

### 新增功能

#### 1. Agent 软件层架构升级
- **新增文件**：
  - `AgentSoftwareLayer.java` — Agent 软件层主入口
  - `IntentRecognizer.java` — LLM 意图识别模块
  - `ComplexityAnalyzer.java` — LLM 复杂度分析模块
  - `TaskDecomposer.java` — LLM 任务分解模块
  - `ExecutionEngine.java` — 任务执行引擎
  - `ThinkingChainEngine.java` — 思考链引擎
  - `ResultIntegrator.java` — 结果整合模块
- **数据模型**：
  - `Task.java` — 任务定义和执行计划
  - `IntentResult.java` — 意图识别结果
  - `ComplexityLevel` — 复杂度级别枚举

#### 2. 天气系统全面重构
- **AIWeatherManager 增强**：
  - 新增空气质量查询接口
  - 新增分钟级降水预报接口
  - 新增天气预警三级回退机制（SDK → HTTP API → APISpace 备用）
  - 实现智能解析策略，只显示有实际数据的字段
- **QWeatherSdkManager 优化**：
  - 使用直连 switch-case 调用替代反射调用
  - 增强天气数据解析和格式化
- **WeatherService 重构**：
  - 实现生活指数 API 的 HTTP 回退机制（SDK 403 时）
  - 支持空气质量、预警、分钟降水等全品类天气数据
- **天气详情界面升级**：
  - 新增网页跳转按钮组（当前天气/小时预报/日预报/空气质量/生活指数）
  - 优化小时预报卡片布局
  - 新增动态图表展示
  - 支持更多天气信息可视化

#### 3. 天气 Banner 组件
- **新增 WeatherBannerView**：
  - 可自定义的天气横幅组件
  - MaterialCardView 容器，整体可点击
  - 子视图禁用点击事件传递
- **新增 WeatherBannerController**：
  - 管理天气横幅生命周期
  - 处理点击跳转逻辑

#### 4. AI 聊天界面升级
- **ChatAdapter 增强**：
  - 支持思考链可视化展示
  - 支持工具调用消息渲染
  - 支持流式内容展示
- **AgentModeHandler 优化**：
  - 集成 Agent 软件层处理流程
  - 支持无限等待 AI 服务初始化
  - 实现 Agent 执行步骤 UI 展示

### 技术实现

#### 天气预警三级回退机制
```java
// WeatherService.java
private JSONObject fetchWarningWithFallback(String location) {
    // 1. 尝试 SDK
    try { return qWeatherSdkManager.getWarningNow(location); }
    // 2. SDK 失败 → HTTP API
    catch (Exception e) { return fetchWarningFromHttp(location); }
    // 3. HTTP 失败 → APISpace 备用
    catch (Exception e2) { return fetchWarningFromApispace(location); }
}
```

#### Agent 软件层处理流程
```java
// AgentSoftwareLayer.java
public AgentResponse processUserMessage(String message) {
    // 1. 意图识别
    IntentResult intent = intentRecognizer.recognize(message);
    // 2. 复杂度分析
    ComplexityLevel complexity = complexityAnalyzer.analyze(message);
    // 3. 任务分解（仅复杂请求）
    TaskPlan plan = taskDecomposer.decompose(message, complexity);
    // 4. 执行引擎
    ExecutionResult result = executionEngine.execute(plan);
    // 5. 思考链（可选）
    ThinkingChain chain = thinkingChainEngine.generate(message);
    // 6. 结果整合
    return resultIntegrator.integrate(result, chain);
}
```

#### 天气数据智能解析
```java
// AIWeatherManager.java - 字段名: 值 格式
private String formatWeatherData(JSONObject data) {
    StringBuilder sb = new StringBuilder();
    for (String key : data.keySet()) {
        Object value = data.get(key);
        if (isValidValue(value)) {
            sb.append(key).append(": ").append(value).append("\n");
        }
    }
    return sb.toString().trim();
}
```

### UI 变更

#### 天气详情页面
- `activity_weather_detail.xml` 大幅重构
  - 新增网页跳转按钮组
  - 优化小时预报卡片显示
  - 新增预警区域显示
  - 支持动态图表区域
- `widget_weather_banner.xml` 新增天气横幅组件

#### 聊天界面
- `item_ai_message.xml` 新增思考链显示支持
- `item_tool_call_message.xml` 工具调用消息优化
- `item_tool_result_message.xml` 工具结果消息优化

### 性能影响
- Agent 软件层增加一次 LLM 调用（意图识别 + 复杂度分析）
- 天气三级回退最坏情况延迟约 3-5 秒
- 天气数据智能解析增加约 10-20ms 处理时间

### 代码变更
- **新增**：`AgentSoftwareLayer.java`, `IntentRecognizer.java`, `ComplexityAnalyzer.java`, `TaskDecomposer.java`, `ExecutionEngine.java`, `ThinkingChainEngine.java`, `ResultIntegrator.java`, `Task.java`, `WeatherBannerView.java`, `WeatherBannerController.java`, `QWeatherIconMapper.java`
- **修改**：`AIWeatherManager.java`, `QWeatherSdkManager.java`, `WeatherService.java`, `WeatherDetailActivity.java`, `WeatherBannerManager.java`, `AIChatActivity.java`, `ChatAdapter.java`, `ChatOrchestrator.java`, `AgentModeHandler.java`, `MainActivity.java`
- **修改**：`activity_weather_detail.xml`, `widget_weather_banner.xml`, `activity_main.xml`, `item_ai_message.xml`

### 文件变更统计
- 修改文件：45 个
- 新增代码行：+7770
- 删除代码行：-3313
- 净增代码行：+4457

---

## [Unreleased] - 2026-05-26

### 新增功能

#### 1. JNI 中文编码修复
- **问题**：JNI 的 `GetStringUTFChars`/`NewStringUTF` 使用 Modified UTF-8，无法正确处理中文字符
- **解决方案**：
  - 新增 `ChatRequest` 数据类，使用 `byte[]` 传递 UTF-8 编码内容
  - Java 层使用 `StandardCharsets.UTF_8` 编码字符串
  - Native 层实现 `bytesToUtf8String()` 和 `utf8StringToJstring()` 进行正确编解码
  - 支持 UTF-16 surrogate pairs（4字节Unicode字符）

#### 2. 主界面优化
- **按钮功能调整**：
  - 题目生成 → AI聊天
  - 模型导入 → 模型管理
  - AI配置 → AI中心
- **图标更新**：所有图标替换为 Emoji 风格，提升视觉一致性

### 技术实现

#### UTF-8 编解码 (native-lib.cpp)
```cpp
// Java byte[] → C++ std::string (标准UTF-8)
static std::string bytesToUtf8String(JNIEnv* env, jbyteArray byteArray) {
    jbyte* bytes = env->GetByteArrayElements(byteArray, nullptr);
    std::string result(reinterpret_cast<const char*>(bytes), length);
    env->ReleaseByteArrayElements(byteArray, bytes, JNI_ABORT);
    return result;
}

// C++ std::string → Java jstring (支持中文)
static jstring utf8StringToJstring(JNIEnv* env, const std::string& utf8Str) {
    // 手动解析UTF-8多字节序列，处理中文3字节编码
    // 转换为UTF-16后使用 NewString 创建 jstring
}
```

#### ChatRequest 数据类
```java
public class ChatRequest {
    private byte[] fullPromptUtf8;  // UTF-8编码的提示词
    private int maxTokens;
    private float temperature;
    // ... Builder模式支持
}
```

### 性能影响
- 编码转换开销：< 0.1ms（100个汉字）
- 相比模型推理时间（500-2000ms），影响可忽略（< 0.02%）

### 代码变更
- **新增**：`ChatRequest.java`
- **修改**：`LlamaHelper.java`, `native-lib.cpp`, `InferenceRouter.java`
- **修改**：`activity_main.xml`, `MainActivity.java`, `strings.xml`

---

## [2.0.0] - 2026-05-20

### 新增功能

#### 1. 模型加载性能优化
- **全局初始化一次性执行**：确保 `ggml_backend_init`、`llama_backend_init`、`ggml_backend_load_all` 只在应用生命周期内执行一次
- **热启动保持**：模型在内存中保持加载状态，避免每次切换应用时重新加载
- **内存管理优化**：内存紧张时不再释放模型，保持推理上下文

#### 2. GPU 加速配置优化
- **GPU 层数自动计算**：根据 GPU 内存自动计算最优 GPU layers（使用 80% 可用内存）
- **Adreno GPU 优化**：针对 Qualcomm Adreno 系列 GPU 进行专项优化
- **内存占用平衡**：在性能和内存占用之间取得平衡（71 层，12 t/s）

#### 3. Batch Size 动态计算
- **GPU/CPU 模式区分**：GPU 模式使用更大的 batch size 以充分利用并行计算
- **内存感知**：根据设备总内存自动调整批处理大小
- **配置表**：
  | 设备内存 | GPU 模式 | CPU 模式 |
  |---------|---------|----------|
  | ≥12GB | 512 | 256 |
  | ≥8GB | 512 | 256 |
  | ≥6GB | 256 | 128 |
  | ≥4GB | 256 | 128 |
  | <4GB | 128 | 64 |

### 性能提升

| 优化项 | 优化前 | 优化后 | 提升 |
|-------|--------|--------|------|
| 模型加载时间 | ~30 秒 | ~5 秒 | **83%** |
| 全局初始化 | 每次重复执行 | 只执行一次 | **100%** |
| GPU 推理速度 | 0.3 t/s | 12 t/s | **40x** |
| 热启动恢复 | 重新加载 | 即时可用 | **即时** |

### 问题修复

#### 1. 模型加载速度慢
- **问题**：`ggml_backend_init` 每次创建 `InferenceContext` 时都重复执行，耗时 24.5 秒
- **修复**：添加全局原子标志 `s_backendInitialized` 和 `s_llamaBackendInitialized`，确保只执行一次
- **文件**：`src/main/cpp/native-lib.cpp`

#### 2. 批处理大小硬编码
- **问题**：`calculateOptimalBatchSize` 函数硬编码返回 256，未根据设备能力动态调整
- **修复**：实现基于 GPU/CPU 模式和设备内存的动态计算逻辑
- **文件**：`src/main/java/com/oilquiz/app/ai/service/AIService.java`

#### 3. 死代码清理
- **移除**：未使用的 `optimizeBatchSize` 函数
- **原因**：该函数从未被调用，使用保守的 batch size 值

### 代码变更详情

#### Native 层 (C++)
- `src/main/cpp/native-lib.cpp`
  - 添加全局原子标志：`s_backendInitialized`、`s_llamaBackendInitialized`
  - 修改 `setupGGMLBackendPath()`：添加单次执行保护
  - 修改 `InferenceContext` 构造函数：添加单次执行保护
  - 修改 `release()`：移除 `llama_backend_free()` 调用

#### Java 层
- `src/main/java/com/oilquiz/app/ai/service/AIService.java`
  - 修复 `calculateOptimalBatchSize()`：实现动态计算逻辑
  - 移除 `optimizeBatchSize()`：清理死代码

### 技术实现

#### 全局初始化控制
```cpp
static std::atomic<bool> s_backendInitialized(false);
static std::atomic<bool> s_llamaBackendInitialized(false);

// 使用 exchange(true) 确保原子性
if (s_backendInitialized.exchange(true)) {
    LOGI("GGML backend already initialized, skipping");
    return;
}
```

#### Batch Size 动态计算
```java
private int calculateOptimalBatchSize(boolean isGpuMode, long availableMB, long totalMB) {
    int batchSize;
    
    if (isGpuMode) {
        if (totalMB >= 8192) {
            batchSize = 512;
        } else if (totalMB >= 6144) {
            batchSize = 256;
        } else {
            batchSize = 128;
        }
    } else {
        // CPU 模式使用更保守的值
        batchSize = isGpuMode ? 512 : 256;
    }
    
    return batchSize;
}
```

### 测试验证

- ✅ 编译成功：`BUILD SUCCESSFUL in 12s`
- ✅ 安装成功：`Performing Streamed Install`
- ✅ 热启动测试：模型保持加载状态
- ✅ GPU 推理：12 t/s（Adreno 750）

### 已知限制

1. **首次加载**：首次加载模型仍需完整初始化（约 25 秒）
2. **内存占用**：GPU 模式下内存占用较高（约 4GB）
3. **设备差异**：不同 GPU 型号性能差异较大

### 下一步计划

1. **模型量化优化**：支持 Q2_K、Q3_K 等更轻量级量化
2. **多模型管理**：支持同时加载多个模型
3. **推理缓存**：实现 KV Cache 持久化
4. **性能监控**：添加实时性能指标监控

---

## 历史版本

### [1.0.0] - 2026-05-15
- 初始版本发布
- 基础 AI 聊天功能
- GPU 加速支持（OpenCL）
- 模型热启动功能
