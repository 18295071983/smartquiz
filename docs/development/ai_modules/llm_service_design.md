# LLM服务设计文档

## 1. 概述

### 1.1 文档目的
本文档详细描述智能题库应用中LLM服务（推理引擎）的设计方案，包括功能实现、技术架构、核心方法等，为开发团队提供完整的技术指导。

### 1.2 设计目标
- **高效推理**：提供高效的模型推理能力
- **稳定可靠**：确保推理过程的稳定性和可靠性
- **易于集成**：提供简洁的API接口
- **性能优化**：针对移动设备进行性能优化
- **资源管理**：有效管理系统资源

### 1.3 适用范围
本文档适用于智能题库应用中LLM服务的设计和开发，包括模型加载、文本生成、资源管理等功能。

## 2. 技术架构

### 2.1 系统架构
```
┌─────────────────────────────────────────┐
│             应用层                       │
├─────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐       │
│  │ AI服务     │  │ 功能模块   │       │
│  └─────────────┘  └─────────────┘       │
├─────────────────────────────────────────┤
│           LLM服务                        │
├─────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐       │
│  │ 模型加载   │  │ 文本生成   │       │
│  └─────────────┘  └─────────────┘       │
├─────────────────────────────────────────┤
│          底层实现                         │
├─────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐       │
│  │ JNI接口    │  │ llama.cpp  │       │
│  └─────────────┘  └─────────────┘       │
└─────────────────────────────────────────┘
```

### 2.2 技术栈

| 技术组件 | 用途 | 优势 |
|---------|------|------|
| Java | 核心实现语言 | 跨平台、成熟稳定 |
| Kotlin | JNI接口 | 现代语言特性 |
| C++ | 底层实现 | 高性能、直接访问硬件 |
| JNI | Java与C++桥接 | 实现跨语言调用 |
| llama.cpp | 推理引擎 | 轻量级、高性能 |
| OpenCL | GPU加速 | 移动设备GPU并行计算 |
| Atomic | 线程安全 | 多线程环境下的原子操作 |

### 2.3 版本历史

#### v2.0.0 (2026-05-20)
- 新增：全局初始化一次性执行机制
- 新增：GPU 层数自动计算
- 新增：Batch Size 动态计算（GPU/CPU 区分）
- 优化：模型加载性能提升 83%（~30秒 → ~5秒）
- 优化：GPU 推理速度提升 40x（0.3 t/s → 12 t/s）

#### v1.0.0 (2026-05-15)
- 初始版本发布
- 基础 AI 聊天功能
- GPU 加速支持（OpenCL）
- 模型热启动功能

## 3. 功能设计

### 3.1 核心功能
- **模型初始化**：加载模型到内存
- **文本生成**：根据提示生成文本
- **资源管理**：管理模型内存和系统资源
- **参数配置**：调整推理参数
- **错误处理**：处理推理过程中的错误

### 3.2 功能流程
1. **初始化**：
   - 加载模型文件
   - 初始化llama.cpp
   - 配置推理参数
2. **文本生成**：
   - 处理输入提示
   - 执行推理
   - 生成文本
   - 返回结果
3. **资源释放**：
   - 释放模型内存
   - 清理系统资源

## 4. 核心实现

### 4.1 类结构

#### 4.1.1 LLMService
```java
public class LLMService {
    private static final String TAG = "LLMService";
    private final ModelManager modelManager;
    private LlamaBridge llamaBridge;
    private boolean initialized = false;
    
    // 构造方法
    public LLMService(Context context);
    public LLMService(ModelManager modelManager);
    
    // 核心方法
    public boolean initialize(String modelName);
    public String generate(String prompt, int maxTokens);
    public void shutdown();
    
    // 辅助方法
    private void configureInference();
}
```

#### 4.1.2 LlamaBridge
```kotlin
class LlamaBridge {
    init {
        System.loadLibrary("llama-bridge")
    }
    
    external fun initModel(modelPath: String): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun close()
}
```

#### 4.1.3 JNI实现 (llama-bridge.cpp)
```cpp
extern "C" {
    static llama_context *ctx = nullptr;
    static llama_model *model = nullptr;
    
    JNIEXPORT jboolean JNICALL
    Java_com_oilquiz_app_ai_engine_LlamaBridge_initModel(JNIEnv *env, jobject thiz, jstring modelPath);
    
    JNIEXPORT jstring JNICALL
    Java_com_oilquiz_app_ai_engine_LlamaBridge_generate(JNIEnv *env, jobject thiz, jstring prompt, jint maxTokens);
    
    JNIEXPORT void JNICALL
    Java_com_oilquiz_app_ai_engine_LlamaBridge_close(JNIEnv *env, jobject thiz);
}
```

### 4.2 核心方法详解

#### 4.2.1 initialize
- **功能**：初始化LLM服务
- **参数**：
  - `modelName`：模型文件名
- **返回值**：初始化是否成功
- **流程**：
  1. 获取模型路径
  2. 调用LlamaBridge初始化模型
  3. 配置推理参数
  4. 设置初始化状态

#### 4.2.2 generate
- **功能**：生成文本
- **参数**：
  - `prompt`：输入提示
  - `maxTokens`：最大生成token数
- **返回值**：生成的文本
- **流程**：
  1. 检查服务是否初始化
  2. 调用LlamaBridge生成文本
  3. 处理生成结果
  4. 返回生成的文本

#### 4.2.3 shutdown
- **功能**：关闭LLM服务
- **参数**：无
- **返回值**：无
- **流程**：
  1. 检查服务是否初始化
  2. 调用LlamaBridge释放资源
  3. 重置初始化状态

#### 4.2.4 LlamaBridge.initModel
- **功能**：初始化模型
- **参数**：
  - `modelPath`：模型文件路径
- **返回值**：初始化是否成功
- **流程**：
  1. 初始化llama后端
  2. 加载模型文件
  3. 创建推理上下文
  4. 返回初始化结果

#### 4.2.5 LlamaBridge.generate
- **功能**：生成文本
- **参数**：
  - `prompt`：输入提示
  - `maxTokens`：最大生成token数
- **返回值**：生成的文本
- **流程**：
  1. 标记化输入提示
  2. 执行推理
  3. 生成文本
  4. 返回生成的文本

#### 4.2.6 LlamaBridge.close
- **功能**：释放资源
- **参数**：无
- **返回值**：无
- **流程**：
  1. 释放推理上下文
  2. 释放模型
  3. 释放llama后端

## 5. 技术实现细节

### 5.1 内存管理
- **模型内存**：根据设备内存自动调整
- **上下文大小**：根据模型和设备能力设置
- **内存释放**：使用完毕后及时释放

### 5.2 性能优化
- **线程配置**：根据设备CPU核心数调整
- **批处理**：支持批量推理
- **缓存**：使用KV缓存提高推理速度

### 5.3 错误处理
- **模型加载错误**：捕获并处理模型加载失败
- **推理错误**：处理推理过程中的错误
- **资源错误**：处理内存不足等资源问题

### 5.4 安全考虑
- **输入验证**：验证输入提示的安全性
- **资源限制**：限制最大生成token数
- **错误隔离**：防止错误影响应用其他部分

## 6. 调用示例

### 6.1 基本使用
```java
// 初始化LLM服务
LLMService llmService = new LLMService(context);
bool success = llmService.initialize("qwen2-0_5b-instruct-q4_k_m.gguf");
if (success) {
    // 服务初始化成功
}

// 生成文本
String prompt = "生成5道关于石油化工的选择题";
String result = llmService.generate(prompt, 1000);
Log.d(TAG, "Generated: " + result);

// 关闭服务
llmService.shutdown();
```

### 6.2 与AI服务集成
```java
// 在AI服务中使用
public class AIService {
    private LLMService llmService;
    
    public AIService(Context context) {
        this.llmService = new LLMService(context);
    }
    
    public boolean initialize() {
        return llmService.initialize("qwen2-0_5b-instruct-q4_k_m.gguf");
    }
    
    public String generate(String prompt) {
        return llmService.generate(prompt, 1000);
    }
    
    public void shutdown() {
        llmService.shutdown();
    }
}
```

## 7. 测试与验证

### 7.1 功能测试
- **模型加载**：测试不同模型的加载
- **文本生成**：测试不同类型的文本生成
- **资源释放**：测试资源释放是否正确
- **错误处理**：测试错误处理机制

### 7.2 性能测试
- **推理速度**：测试不同设备上的推理速度
- **内存使用**：测试内存占用情况
- **CPU使用**：测试CPU占用情况
- **电池消耗**：测试推理过程的电池消耗

### 7.3 兼容性测试
- **不同设备**：测试不同设备的兼容性
- **不同Android版本**：测试不同Android版本的兼容性
- **不同模型**：测试不同模型的兼容性

## 8. 未来扩展

### 8.1 功能扩展
- **多模型支持**：支持同时加载多个模型
- **流式生成**：支持流式文本生成
- **模型量化**：支持不同量化精度的模型
- **自定义参数**：支持用户自定义推理参数

### 8.2 技术改进
- **硬件加速**：利用设备NPU加速推理
- **内存优化**：进一步优化内存使用
- **并发处理**：支持多线程并发推理
- **批处理优化**：优化批处理推理性能

### 8.3 集成扩展
- **与其他AI模型集成**：支持集成其他类型的AI模型
- **与云服务集成**：支持混合本地和云端推理
- **与应用其他功能集成**：与题目管理、学习计划等功能集成

## 9. 结论

本文档详细描述了智能题库应用中LLM服务的设计方案，包括功能实现、技术架构、核心方法等。通过这些设计，应用能够提供高效、稳定的AI推理能力，为用户提供智能化的学习辅助服务。

系统的模块化设计和性能优化策略确保了LLM服务在移动设备上的流畅运行，同时错误处理和安全考虑提高了系统的可靠性和安全性。未来，通过持续的功能扩展和技术改进，LLM服务将为智能题库应用提供更加强大和智能的AI能力。