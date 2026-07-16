# 答题宝 (SmartQuiz) AI 功能设计文档

> **版本: 2.0 | 更新日期: 2026-05-16**

---

## 目录

1. [概述](#1-概述)
2. [AI 服务架构](#2-ai-服务架构)
3. [AI 功能模块](#3-ai-功能模块)
4. [本地 LLM 推理引擎](#4-本地-llm-推理引擎)
5. [多模型管理](#5-多模型管理)
6. [AI 配置系统](#6-ai-配置系统)
7. [JNI 接口设计](#7-jni-接口设计)
8. [UI 集成](#8-ui-集成)

---

## 1. 概述

答题宝 (SmartQuiz) 的 AI 系统采用 **多服务混合架构**，融合了本地推理与云端 API 两种能力路径。系统核心目标是在保障用户隐私与离线可用性的前提下，提供高质量的 AI 辅助学习体验。

### 1.1 设计理念

- **隐私优先**：敏感数据优先使用本地推理，不上传至云端
- **弹性降级**：本地模型不可用时自动切换至云端 API，反之亦然
- **按需加载**：模型文件按需下载，减少存储占用
- **统一抽象**：上层业务不感知底层推理引擎差异

### 1.2 技术栈总览

| 层级 | 技术选型 |
|------|----------|
| 本地推理引擎 | llama.cpp (JNI 绑定) |
| 模型格式 | GGUF |
| GPU 加速 | OpenCL / Vulkan |
| 云端 API | OpenAI / 千问 (Qwen) / Anthropic Claude / Gemini / 文心一言 / Ollama |
| 文字识别 | Google ML Kit OCR |
| 开发语言 | Kotlin (上层) + C++ (JNI 层) |

---

## 2. AI 服务架构

### 2.1 总体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         UI 层 (Activity / Fragment)                  │
│   AIChatActivity  │  QuestionAnalysisFragment  │  TranslateDialog   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     业务服务层 (Service Layer)                       │
│  AIChatService │ QuestionAnalyzer │ QuestionGenerator │ Translator  │
│              LearningAssistantService  │  OCRService               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    服务路由层 (ServiceRouter)                        │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────────────┐  │
│  │ 路由决策引擎  │  │ 负载均衡策略  │  │ 故障转移 & 熔断            │  │
│  └─────────────┘  └──────────────┘  └───────────────────────────┘  │
└──────────────┬──────────────────────────────┬──────────────────────┘
               │                              │
               ▼                              ▼
┌──────────────────────────┐   ┌──────────────────────────────────────┐
│   本地推理引擎 (Local)     │   │        云端 API 引擎 (Cloud)          │
│                          │   │                                      │
│  ┌────────────────────┐  │   │  ┌────────┐ ┌────────┐ ┌─────────┐  │
│  │  AgentInferenceJNI │  │   │  │ OpenAI │ │  千问   │ │Anthropic│  │
│  │  (llama.cpp 绑定)   │  │   │  └────────┘ └────────┘ └─────────┘  │
│  └────────┬───────────┘  │   │  ┌────────┐ ┌────────┐ ┌─────────┐  │
│           │              │   │  │ Gemini │ │ 文心一言│ │ Ollama  │  │
│  ┌────────┴───────────┐  │   │  └────────┘ └────────┘ └─────────┘  │
│  │  GGUF Model Loader │  │   │                                      │
│  └────────┬───────────┘  │   │  ┌────────────────────────────────┐  │
│           │              │   │  │     统一 HTTP Client 层         │  │
│  ┌────────┴───────────┐  │   │  │  (OkHttp + 流式响应解析)       │  │
│  │  GPU Delegate      │  │   │  └────────────────────────────────┘  │
│  │  (OpenCL / Vulkan) │  │   │                                      │
│  └────────────────────┘  │   └──────────────────────────────────────┘
└──────────────────────────┘
```

### 2.2 ServiceRouter 路由决策流程

```
                     ┌──────────────┐
                     │  用户发起请求  │
                     └──────┬───────┘
                            │
                            ▼
                   ┌────────────────┐
                   │  检查网络状态    │
                   └───────┬────────┘
                           │
              ┌────────────┼────────────┐
              │ 有网络      │            │ 无网络
              ▼            │            ▼
    ┌─────────────────┐    │    ┌─────────────────┐
    │  检查用户偏好设置  │    │    │  强制本地推理     │
    └────────┬────────┘    │    └────────┬────────┘
             │             │             │
             ▼             │             │
    ┌─────────────────┐    │             │
    │  本地模型可用?    │    │             │
    └───┬─────────┬───┘    │             │
        │ 是      │ 否      │             │
        ▼         │         │             │
  ┌──────────┐    │         │             │
  │ 本地推理  │    │         │             │
  └──────────┘    │         │             │
                  ▼         │             │
         ┌──────────────┐   │             │
         │  选择云端服务  │   │             │
         └──────┬───────┘   │             │
                │           │             │
         ┌──────┴──────┐    │             │
         │ API Key 有效? │   │             │
         └──┬───────┬──┘    │             │
            │ 是    │ 否    │             │
            ▼       │       │             │
    ┌────────────┐  │       │             │
    │ 云端 API   │  │       │             │
    │ 推理调用    │  │       │             │
    └────────────┘  │       │             │
                    ▼       ▼             ▼
              ┌────────────────────────────┐
              │     返回错误 / 降级提示      │
              └────────────────────────────┘
```

### 2.3 核心接口定义

```kotlin
interface IAIService {
    suspend fun chat(
        messages: List<Message>,
        config: AIConfig,
        onToken: (String) -> Unit
    ): Result<String>

    suspend fun chatStream(
        messages: List<Message>,
        config: AIConfig,
        callbacks: StreamCallbacks
    )
}

interface StreamCallbacks {
    fun onToken(token: String)
    fun onThinking(thinkingContent: String)
    fun onComplete(fullResponse: String)
    fun onError(error: AIException)
}
```

---

## 3. AI 功能模块

### 3.1 功能全景

```
┌─────────────────────────────────────────────────────────────────┐
│                     答题宝 AI 功能矩阵                            │
├───────────────┬───────────────┬───────────────┬─────────────────┤
│   AI 聊天     │  题目分析      │  题目生成      │  学习助手        │
│  (AIChat)    │ (QAnalysis)   │ (QGeneration) │ (LAssistant)    │
├───────────────┼───────────────┼───────────────┼─────────────────┤
│   翻译        │   OCR 识别    │               │                 │
│ (Translate)  │   (OCR)      │               │                 │
└───────────────┴───────────────┴───────────────┴─────────────────┘
```

### 3.2 AI 聊天 (AI Chat)

**功能描述**：提供自然语言对话能力，支持多轮上下文记忆。

**核心能力**：

| 子功能 | 说明 |
|--------|------|
| 自然对话 | 支持任意话题的自由对话，维护历史上下文 |
| 深度思考模式 | 启用 Chain-of-Thought 推理，展示模型的思考过程 |
| 创意写作 | 根据提示生成文章、故事、诗歌等创意内容 |

**处理流程**：

```
用户输入 → 上下文拼接(历史消息 + System Prompt) → ServiceRouter 路由
    → 推理引擎(本地/云端) → 流式 Token 输出 → UI 逐字渲染
```

**深度思考模式流程**：

```
用户输入
    │
    ▼
┌──────────────────────────────────────────┐
│  System Prompt 注入思考指令                │
│  "请逐步思考，先给出推理过程，再给出最终答案"   │
└──────────────────┬───────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────┐
│  Token 分类解析                           │
│  ┌─────────────┐  ┌────────────────────┐ │
│  │ 思考标记检测  │  │  <thinking> 标签    │ │
│  │ 关键词匹配   │  │  结构化解析         │ │
│  └─────────────┘  └────────────────────┘ │
└──────────────────┬───────────────────────┘
                   │
                   ▼
         ┌────────────────────┐
         │ 思考过程 (折叠显示)  │
         │ 最终答案 (正常显示)  │
         └────────────────────┘
```

### 3.3 题目分析 (Question Analysis)

**功能描述**：对用户提交的题目进行智能分析，评估难度等级、识别知识点、提供解题思路。

**分析维度**：

```
┌─────────────────────────────────────────┐
│              题目分析结果                 │
├─────────────┬───────────────────────────┤
│ 难度等级     │ ⭐ ~ ⭐⭐⭐⭐⭐ (1~5 级)      │
│ 知识点标签   │ ["函数", "三角函数", ...]   │
│ 考察能力     │ 计算能力 / 理解能力 / 应用能力│
│ 解题思路     │ 分步骤的推理路径            │
│ 易错点       │ 常见错误分析               │
│ 同类题推荐   │ 相似题目 ID 列表           │
└─────────────┴───────────────────────────┘
```

**处理流水线**：

```
原始题目数据 → 文本预处理(去噪/格式化) → Prompt 构造
    → 推理引擎 → JSON 结构化解析 → 分析结果对象 → UI 渲染
```

### 3.4 题目生成 (Question Generation)

**功能描述**：根据用户提供的素材（知识点、文本材料、图片 OCR 结果）自动生成练习题。

**生成策略**：

| 策略 | 说明 |
|------|------|
| 知识点驱动 | 根据选定的知识点标签生成对应题目 |
| 材料驱动 | 分析输入文本/文档，提取关键信息后出题 |
| 难度自适应 | 根据用户历史答题表现，自动调整题目难度 |
| 题型多样化 | 支持选择题、填空题、判断题、简答题 |

**生成流水线**：

```
输入素材(知识点/文本/图片)
    │
    ▼
┌──────────────────────────────────────────────┐
│  素材预处理                                    │
│  - 文本: 分词 / 关键信息提取                    │
│  - 图片: OCR → 文本                            │
│  - 知识点: 检索知识图谱                         │
└──────────────────┬───────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────┐
│  Prompt 模板引擎                              │
│  选择题型模板 + 注入素材 + 难度参数 + 格式约束   │
└──────────────────┬───────────────────────────┘
                   │
                   ▼
          推理引擎 → JSON 结构化输出
                   │
                   ▼
┌──────────────────────────────────────────────┐
│  质量校验                                      │
│  - 答案一致性检查  - 格式完整性验证              │
│  - 去重检测       - 难度匹配度评估              │
└──────────────────┬───────────────────────────┘
                   │
                   ▼
              生成的题目入库
```

### 3.5 学习助手 (Learning Assistant)

**功能描述**：基于用户的学习历史和答题数据，提供个性化学习建议和进度分析。

```
┌─────────────────────────────────────────────────────────┐
│                    学习助手功能架构                        │
├─────────────────┬─────────────────┬─────────────────────┤
│  学习进度分析    │  知识薄弱点诊断   │  学习计划推荐        │
│                 │                 │                     │
│  - 正确率趋势    │  - 错题聚类分析  │  - 每日练习量建议    │
│  - 知识点覆盖    │  - 薄弱知识点    │  - 重点突破方向      │
│  - 学习时长统计  │  - 难度适应性    │  - 复习周期提醒      │
├─────────────────┼─────────────────┼─────────────────────┤
│  学习报告生成    │  智能答疑       │  激励反馈            │
│                 │                 │                     │
│  - 周报/月报    │  - 错题讲解     │  - 成就徽章          │
│  - PDF 导出     │  - 概念解释     │  - 学习连续天数       │
│  - 图表可视化   │  - 延伸问答     │  - 进步曲线          │
└─────────────────┴─────────────────┴─────────────────────┘
```

### 3.6 翻译 (Translation)

**功能描述**：支持多语言互译，特别针对题目和学科内容的翻译优化。

**支持语言**：中文、英文、日文、韩文、法文、德文、西班牙文、俄文等。

```
输入文本 → 语种检测 → 翻译 Prompt 构造 → 推理引擎 → 译文输出
                              │
                     ┌────────┴────────┐
                     │  学科术语库匹配   │
                     │  (数学/物理/化学) │
                     └─────────────────┘
```

**特性**：
- 学科专业术语精确翻译
- 保留原文格式（公式、符号）
- 批量翻译支持

### 3.7 OCR 文字识别

**功能描述**：基于 Google ML Kit 实现图片文字识别，作为 AI 管线的输入端。

```
┌──────────┐    ┌─────────────┐    ┌──────────────────┐
│ 相机/相册  │ →  │ 图像预处理    │ →  │ ML Kit Text      │
│ 获取图片   │    │ (裁剪/增强)   │    │ Recognition      │
└──────────┘    └─────────────┘    └────────┬─────────┘
                                            │
                              ┌─────────────┴─────────────┐
                              ▼                           ▼
                    ┌─────────────────┐         ┌─────────────────┐
                    │ 题目分析 Pipeline │         │ 翻译 Pipeline    │
                    └─────────────────┘         └─────────────────┘
```

---

## 4. 本地 LLM 推理引擎

### 4.1 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                    本地推理引擎架构                               │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   Kotlin 层                              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │   │
│  │  │ LocalLLM     │  │ InferenceQueue│  │ MemoryMonitor │  │   │
│  │  │ Service      │  │ Manager       │  │               │  │   │
│  │  └──────┬───────┘  └──────┬────────┘  └───────┬───────┘  │   │
│  └─────────┼─────────────────┼───────────────────┼──────────┘   │
│            │                 │                   │              │
│  ══════════╪═════════════════╪═══════════════════╪═══════════   │
│            │    JNI 边界      │                   │              │
│  ══════════╪═════════════════╪═══════════════════╪═══════════   │
│            │                 │                   │              │
│  ┌─────────┴─────────────────┴───────────────────┴──────────┐   │
│  │                    C++ JNI 层                             │   │
│  │  ┌──────────────────────────────────────────────────────┐│   │
│  │  │           agent_inference_jni.cpp                     ││   │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ││   │
│  │  │  │ 模型加载  │ │ Token 生成│ │ GPU 调度  │ │ 内存管理  │ ││   │
│  │  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ ││   │
│  │  └──────────────────────────────────────────────────────┘│   │
│  └─────────────────────────┬────────────────────────────────┘   │
│                            │                                    │
│  ┌─────────────────────────┴────────────────────────────────┐   │
│  │                  llama.cpp 核心库                          │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐ │   │
│  │  │ ggml     │ │ llama    │ │ sampler  │ │ ggml-backend │ │   │
│  │  │ 张量计算  │ │ 模型推理  │ │ 采样器    │ │ 后端抽象     │ │   │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────┬───────┘ │   │
│  └────────────────────────────────────────────────┼─────────┘   │
│                                                    │            │
│  ┌─────────────────────────────────────────────────┴─────────┐  │
│  │                GPU 加速后端                                │  │
│  │  ┌──────────────────────┐  ┌────────────────────────────┐ │  │
│  │  │   OpenCL Backend     │  │    Vulkan Backend          │ │  │
│  │  │   (通用 GPU 加速)     │  │    (高性能 GPU 加速)        │ │  │
│  │  └──────────────────────┘  └────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 AgentInferenceJNI 核心能力

```kotlin
object AgentInferenceJNI {
    init { System.loadLibrary("agent_inference_jni") }

    // 生命周期管理
    external fun init(configJson: String): Boolean
    external fun loadModel(modelPath: String, paramsJson: String): Boolean
    external fun release()

    // 推理接口
    external fun generate(
        prompt: String,
        callback: StreamingCallback
    ): Boolean

    external fun stopGenerate()

    // 状态查询
    external fun isModelLoaded(): Boolean
    external fun getModelInfo(): String
    external fun getMemoryUsage(): Long

    // GPU 相关
    external fun getAvailableBackends(): String
    external fun setBackend(backendName: String): Boolean
}
```

### 4.3 推理队列管理

为了解决多请求并发和资源争用问题，系统实现了推理队列：

```
┌─────────────────────────────────────────────────────┐
│               InferenceQueueManager                  │
│                                                     │
│  请求1 ──┐                                          │
│  请求2 ──┤  ┌──────────────┐    ┌────────────────┐  │
│  请求3 ──┼─→│ PriorityQueue │ →  │ 推理引擎(单例)  │  │
│  请求n ──┘  └──────────────┘    └────────────────┘  │
│                                                     │
│  特性:                                               │
│  - FIFO 基础 + 优先级插队                            │
│  - 可取消队列中的请求                                 │
│  - 超时自动清理                                     │
└─────────────────────────────────────────────────────┘
```

### 4.4 内存优化策略

| 策略 | 实现 |
|------|------|
| 模型懒加载 | 首次推理请求时才加载模型到内存 |
| LRU 缓存 | 最近使用的模型常驻，不常用模型卸载 |
| 内存水位线 | 超过阈值时自动卸载非活跃模型 |
| 量化支持 | 支持 Q4_K_M / Q5_K_M / Q8_0 等多种量化格式 |
| 上下文窗口管理 | 动态调整 KV Cache 大小，适配不同内存设备 |
| 共享内存优化 | 多个 Session 间共享模型权重 |

### 4.5 GPU 加速策略

```
设备能力检测
    │
    ├── 支持 Vulkan 1.1+ → 优先使用 Vulkan Backend
    ├── 支持 OpenCL 2.0+ → 降级使用 OpenCL Backend
    └── 无 GPU 支持      → CPU 推理 (ARM NEON 优化)
```

**GPU 加速效果**：

| 场景 | CPU (Q4_K_M) | GPU (Vulkan) | 加速比 |
|------|-------------|--------------|--------|
| 7B 模型 | ~8 tok/s | ~25 tok/s | ~3x |
| 3B 模型 | ~18 tok/s | ~50 tok/s | ~2.8x |
| 1.5B 模型 | ~30 tok/s | ~70 tok/s | ~2.3x |

---

## 5. 多模型管理

### 5.1 模型管理体系

```
┌─────────────────────────────────────────────────────────────────┐
│                      多模型管理体系                               │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                  ModelRegistry                            │ │
│  │  ┌─────────────────┐  ┌─────────────────┐                 │ │
│  │  │  已注册模型列表    │  │  模型能力描述     │                 │ │
│  │  │  (本地 + 云端)   │  │  (元数据 + 标签)  │                 │ │
│  │  └─────────────────┘  └─────────────────┘                 │ │
│  └──────────────────────────┬────────────────────────────────┘ │
│                             │                                  │
│              ┌──────────────┼──────────────┐                   │
│              │              │              │                   │
│              ▼              ▼              ▼                   │
│  ┌─────────────────┐ ┌────────────┐ ┌──────────────────┐      │
│  │MultiModelManager│ │OnlineModel │ │LocalModelManager │      │
│  │   (统一调度)     │ │ Manager    │ │                  │      │
│  │                 │ │            │ │ - 模型下载        │      │
│  │ - 运行时切换     │ │ - API 配置  │ │ - 模型校验        │      │
│  │ - 能力匹配      │ │ - 余额查询  │ │ - 模型卸载        │      │
│  │ - 故障转移      │ │ - 服务状态  │ │ - 存储管理        │      │
│  └─────────────────┘ └────────────┘ └──────────────────┘      │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 本地模型管理

**模型格式**：统一使用 GGUF 格式，存放在应用私有目录下的 `models/` 文件夹。

```
/models
├── qwen2.5-1.5b-instruct-q4_k_m.gguf     (1.2 GB)
├── qwen2.5-3b-instruct-q4_k_m.gguf       (2.4 GB)
├── llama3.2-3b-instruct-q4_k_m.gguf      (2.3 GB)
└── model_registry.json
```

**model_registry.json 结构**：

```json
{
  "models": [
    {
      "id": "qwen2.5-1.5b-instruct",
      "name": "通义千问 2.5 1.5B",
      "file": "qwen2.5-1.5b-instruct-q4_k_m.gguf",
      "size_bytes": 1288490188,
      "quantization": "Q4_K_M",
      "capabilities": ["chat", "qa", "translate"],
      "context_length": 32768,
      "language": ["zh", "en"],
      "version": "2.0",
      "checksum": "sha256:..."
    }
  ]
}
```

**下载流程**：

```
用户选择模型 → 检查存储空间 → 检查网络状态
    ↓
断点续传下载 (OkHttp + Range 请求)
    ↓
SHA256 完整性校验
    ↓
解压/验证 GGUF 格式
    ↓
注册到 ModelRegistry → 可用
```

### 5.3 云端模型管理

**支持的云端服务及模型**：

| 服务商 | 模型示例 | 特性 |
|--------|---------|------|
| OpenAI | GPT-4o, GPT-4o-mini | 综合能力最强 |
| 千问 (Qwen) | qwen-max, qwen-plus | 中文优化 |
| Anthropic | Claude 3.5 Sonnet | 长上下文、代码 |
| Google | Gemini 2.0 Flash | 多模态 |
| 百度 | 文心一言 4.0 | 中文生态 |
| Ollama | 自定义部署 | 私有化部署 |

**API Key 管理**：

```
┌────────────────────────────────────┐
│         API Key 安全存储            │
│                                    │
│  Android Keystore                  │
│      │                             │
│      ▼                             │
│  EncryptedSharedPreferences        │
│      │                             │
│      ▼                             │
│  AES-256-GCM 加密存储              │
│  (每厂商独立 Key，运行时解密)        │
└────────────────────────────────────┘
```

### 5.4 运行时模型切换

```
┌──────────────────────────────────────────────┐
│            模型切换流程                        │
│                                              │
│  用户选择新模型                                │
│       │                                      │
│       ▼                                      │
│  ┌─────────────────────┐                     │
│  │ MultiModelManager   │                     │
│  │ .switchModel(id)    │                     │
│  └──────────┬──────────┘                     │
│             │                                │
│    ┌────────┴────────┐                       │
│    │ 本地模型?         │                      │
│    └───┬─────────┬───┘                       │
│        │ 是       │ 否 (云端)                 │
│        ▼          ▼                          │
│  ┌──────────┐ ┌──────────┐                   │
│  │ 卸载当前   │ │ 验证 API │                   │
│  │ 本地模型   │ │ Key      │                   │
│  │ 加载新模型 │ └──────────┘                   │
│  └──────────┘                                │
│        │          │                          │
│        └────┬─────┘                          │
│             ▼                                │
│  ┌─────────────────────┐                     │
│  │ 更新 ServiceRouter  │                     │
│  │ 路由表              │                     │
│  └─────────────────────┘                     │
│             │                                │
│             ▼                                │
│  ┌─────────────────────┐                     │
│  │ 通知 UI 层刷新状态   │                     │
│  │ (当前模型名、能力等)  │                     │
│  └─────────────────────┘                     │
└──────────────────────────────────────────────┘
```

---

## 6. AI 配置系统

### 6.1 推理参数配置

```kotlin
data class AIConfig(
    val temperature: Float = 0.7f,      // 0.0 ~ 2.0
    val topP: Float = 0.9f,             // 0.0 ~ 1.0
    val topK: Int = 40,                 // 1 ~ 100
    val maxTokens: Int = 2048,          // 最大生成长度
    val repetitionPenalty: Float = 1.1f, // 重复惩罚
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val systemPrompt: String = "",
    val stopSequences: List<String> = emptyList()
)
```

**不同场景推荐配置**：

| 场景 | temperature | top_p | max_tokens |
|------|------------|-------|------------|
| 创意写作 | 0.9 ~ 1.2 | 0.95 | 4096 |
| 题目生成 | 0.7 ~ 0.9 | 0.9 | 2048 |
| 题目分析 | 0.2 ~ 0.4 | 0.8 | 1024 |
| 翻译 | 0.1 ~ 0.3 | 0.7 | 2048 |
| 深度思考 | 0.5 ~ 0.7 | 0.9 | 8192 |
| 学习建议 | 0.6 ~ 0.8 | 0.85 | 2048 |

### 6.2 设备能力检测

```kotlin
object DeviceCapabilityDetector {
    data class DeviceInfo(
        val totalRamMB: Long,
        val availableRamMB: Long,
        val cpuCores: Int,
        val cpuArch: String,          // arm64-v8a / armeabi-v7a / x86_64
        val gpuVendor: String,        // Qualcomm / Mali / PowerVR / null
        val vulkanSupported: Boolean,
        val openclSupported: Boolean,
        val recommendedModelSize: ModelSize  // SMALL / MEDIUM / LARGE
    )

    enum class ModelSize {
        SMALL,   // 1.5B 及以下 (RAM < 4GB)
        MEDIUM,  // 3B (RAM 4~8GB)
        LARGE    // 7B 及以上 (RAM > 8GB)
    }

    fun detect(): DeviceInfo
}
```

**设备分级策略**：

```
┌──────────────┬──────────────┬─────────────────────┬──────────────┐
│ 设备等级      │ RAM          │ 推荐模型              │ GPU 后端      │
├──────────────┼──────────────┼─────────────────────┼──────────────┤
│ 低端设备      │ 2~4 GB       │ 1.5B Q4_K_M          │ CPU 仅       │
│ 中端设备      │ 4~8 GB       │ 3B Q4_K_M            │ OpenCL       │
│ 高端设备      │ 8~12 GB      │ 7B Q4_K_M / 3B Q8_0  │ Vulkan       │
│ 旗舰设备      │ 12 GB+       │ 7B Q8_0 / 14B Q4_K_M │ Vulkan       │
└──────────────┴──────────────┴─────────────────────┴──────────────┘
```

### 6.3 性能优化配置

```kotlin
data class PerformanceConfig(
    val threadCount: Int = getOptimalThreadCount(),
    val batchSize: Int = 512,
    val ubatchSize: Int = 512,
    val contextSize: Int = 2048,
    val flashAttention: Boolean = true,
    val mmap: Boolean = true,          // 内存映射加载
    val mlock: Boolean = false         // 锁定内存页
)
```

**线程数自适应**：

```kotlin
fun getOptimalThreadCount(): Int {
    val cores = Runtime.getRuntime().availableProcessors()
    return when {
        cores <= 4 -> cores              // 小核心数，全用
        cores <= 8 -> cores - 1          // 保留一个核给 UI
        else -> min(cores - 2, 12)       // 最多使用 12 个线程
    }
}
```

---

## 7. JNI 接口设计

### 7.1 agent_inference_jni.cpp 模块划分

```
agent_inference_jni.cpp
├── 生命周期管理
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_init()
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_loadModel()
│   └── Java_com_quiz_ai_inference_AgentInferenceJNI_release()
│
├── 推理核心
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_generate()
│   └── Java_com_quiz_ai_inference_AgentInferenceJNI_stopGenerate()
│
├── 状态查询
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_isModelLoaded()
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_getModelInfo()
│   └── Java_com_quiz_ai_inference_AgentInferenceJNI_getMemoryUsage()
│
├── GPU 后端管理
│   ├── Java_com_quiz_ai_inference_AgentInferenceJNI_getAvailableBackends()
│   └── Java_com_quiz_ai_inference_AgentInferenceJNI_setBackend()
│
└── 内部辅助
    ├── jstring_to_utf8() / utf8_to_jstring()
    ├── parse_params_json()
    ├── build_llama_context_params()
    └── handle_jni_exception()
```

### 7.2 流式 Token 输出机制

```
Java 层 (Kotlin)                          C++ 层 (JNI)
─────────────────                         ────────────
                                             │
AgentInferenceJNI.generate()                 │
    │                                        │
    ├─ 传入 StreamingCallback 对象 ──────────→│
    │                                        │
    │                                   ┌────▼─────────────────────┐
    │                                   │  llama_context *ctx      │
    │                                   │  while (has_next_token) {│
    │    ┌──────────────────────────┐   │    llama_decode(ctx,     │
    │    │ callback.onToken(token)  │◄──│      batch);             │
    │    │ callback.onThinking(t)   │   │    token = llama_sample( │
    │    └──────────────────────────┘   │      ctx, sampler);      │
    │                                   │    env->CallVoidMethod(   │
    │    ┌──────────────────────────┐   │      callback,           │
    │    │ callback.onComplete(res) │◄──│      onToken, token);    │
    │    │ OR                       │   │  }                       │
    │    │ callback.onError(e)      │   │  env->CallVoidMethod(    │
    │    └──────────────────────────┘   │    callback,             │
    │                                   │    onComplete, result);  │
    │                                   └──────────────────────────┘
```

**回调接口定义**：

```kotlin
interface StreamingCallback {
    fun onToken(token: String)
    fun onThinking(thinkingContent: String)
    fun onComplete(fullText: String)
    fun onError(errorCode: Int, errorMessage: String)
}
```

### 7.3 错误处理与崩溃恢复

```
┌───────────────────────────────────────────────────────────┐
│                    C++ 层异常处理                          │
│                                                           │
│  try {                                                    │
│      // 推理逻辑                                           │
│      llama_decode(ctx, batch);                            │
│  } catch (const std::exception &e) {                      │
│      // C++ 异常捕获                                       │
│      env->ThrowNew(exceptionClass, e.what());             │
│  } catch (...) {                                          │
│      // 未知异常                                           │
│      env->ThrowNew(exceptionClass, "Unknown error");      │
│  }                                                        │
│                                                           │
│  ── JNI 边界 ──                                           │
│                                                           │
│  Kotlin 层 catch (e: AIException) {                       │
│      when (e.errorCode) {                                 │
│          OUT_OF_MEMORY → 卸载模型, 释放内存                │
│          MODEL_LOAD_FAILED → 校验文件完整性, 重新下载       │
│          INFERENCE_TIMEOUT → 重试 (最多3次)               │
│          GPU_INIT_FAILED → 降级到 CPU 推理                 │
│          else → 提示用户, 记录日志                         │
│      }                                                    │
│  }                                                        │
└───────────────────────────────────────────────────────────┘
```

**错误码定义**：

```kotlin
object AIErrorCodes {
    const val SUCCESS = 0
    const val OUT_OF_MEMORY = 1001
    const val MODEL_LOAD_FAILED = 1002
    const val MODEL_NOT_FOUND = 1003
    const val INFERENCE_TIMEOUT = 1004
    const val GPU_INIT_FAILED = 2001
    const val GPU_OOM = 2002
    const val CONTEXT_OVERFLOW = 3001
    const val JNI_BRIDGE_ERROR = 9001
}
```

---

## 8. UI 集成

### 8.1 AIChatActivity 架构

```
┌─────────────────────────────────────────────────────────────┐
│  AIChatActivity                                              │
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Toolbar                                                 ││
│  │  [返回]  当前模型: 千问2.5-3B ▼  [更多 ⋮]                ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                                                         ││
│  │  ┌──────────────────────────────────┐                    ││
│  │  │  🤖 AI: 你好！有什么可以帮助你？   │                    ││
│  │  └──────────────────────────────────┘                    ││
│  │                                                         ││
│  │              ┌──────────────────────────┐                ││
│  │              │ 😊 用户: 帮我分析这道题    │                ││
│  │              └──────────────────────────┘                ││
│  │                                                         ││
│  │  ┌──────────────────────────────────┐                    ││
│  │  │ 💭 思考: 分析题目类型为函数题...    │  ← 可折叠        ││
│  │  │ 📝 回答: 这道题考察的是三角函数...  │                    ││
│  │  │ ████████░░░░░░░░  (生成中...)     │                    ││
│  │  └──────────────────────────────────┘                    ││
│  │                                                         ││
│  │  RecyclerView (消息列表)                                  ││
│  └─────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ [🎤] [📎 附件] [___________________输入框_______________] │││
│  │ [📷] [深度思考 ○]  [模型: ▾]  [发送 →]                   │││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 8.2 流式文本显示

```kotlin
class StreamingTextView : AppCompatTextView {

    private var displayText = SpannableStringBuilder()
    private var cursorVisible = true

    fun appendToken(token: String) {
        displayText.append(token)
        text = displayText
        // 自动滚动到底部
        (parent as? RecyclerView)?.scrollToPosition(adapter.itemCount - 1)
    }

    fun appendThinking(thinking: String) {
        val thinkingSpan = SpannableStringBuilder(thinking)
        thinkingSpan.setSpan(
            ForegroundColorSpan(Color.GRAY),
            0, thinking.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        thinkingSpan.setSpan(
            StyleSpan(Typeface.ITALIC),
            0, thinking.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        displayText.append("\n💭 ")
        displayText.append(thinkingSpan)
        text = displayText
    }

    fun showGeneratingIndicator() {
        // 闪烁光标动画
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                cursorVisible = (it.animatedValue as Float) > 0.5f
                invalidate()
            }
            start()
        }
    }

    fun hideGeneratingIndicator() {
        // 移除光标动画
    }
}
```

### 8.3 思考过程可视化

```
普通模式:
┌──────────────────────────────────┐
│  直接输出答案内容                  │
│  无中间思考过程                   │
└──────────────────────────────────┘

深度思考模式:
┌──────────────────────────────────┐
│  💭 思考过程               [展开▼] │
│  ┌────────────────────────────┐  │
│  │ 1. 首先分析题目类型...      │  │  ← 折叠态: 显示前2行
│  │ 2. 确定使用公式...         │  │
│  │ 3. 检查边界条件...  ╴╴╴╴╴ │  │
│  └────────────────────────────┘  │
│  ─────────────────────────────── │
│  📝 最终答案                      │
│  根据以上分析，正确答案是...       │
│                                  │
│  ░░░░░░░░░░░░░░ (正在生成...)     │
└──────────────────────────────────┘

完成态 (协作模式):
┌──────────────────────────────────┐
│  💭 思考过程               [收起▲] │
│  ┌────────────────────────────┐  │
│  │ 1. 首先分析题目类型...      │  │
│  │ 2. 确定使用公式...         │  │
│  │ 3. 检查边界条件...         │  │
│  │ 4. 验证结果的合理性...     │  │
│  │ 5. 得出结论...            │  │
│  └────────────────────────────┘  │
│  ─────────────────────────────── │
│  📝 最终答案                      │
│  根据以上分析，正确答案是...       │
└──────────────────────────────────┘
```

**思考内容解析流程**：

```
原始 Token 流
    │
    ▼
┌──────────────────────────────────────┐
│  <thinking> 标签检测器               │
│  ┌────────────────────────────────┐  │
│  │ 状态机:                        │  │
│  │  NORMAL → 遇到<thinking> →     │  │
│  │  THINKING → 遇到</thinking> →  │  │
│  │  ANSWER                        │  │
│  └────────────────────────────────┘  │
└──────────────┬───────────────────────┘
               │
    ┌──────────┴──────────┐
    ▼                     ▼
 思考内容 Token         答案 Token
    │                     │
    ▼                     ▼
 ThinkingView 显示    答案 View 显示
 (折叠/展开)          (正常渲染)
```

### 8.4 模型/服务商选择 UI

```
┌──────────────────────────────────────────────┐
│          选择 AI 模型                    [✕]  │
├──────────────────────────────────────────────┤
│                                              │
│  📱 本地模型                                  │
│  ┌──────────────────────────────────────┐    │
│  │ ○ 千问 2.5 1.5B (1.2 GB)    [已下载] │    │
│  │ ● 千问 2.5 3B (2.4 GB)      [已加载] │    │
│  │ ○ Llama 3.2 3B (2.3 GB)   [下载 ▾]  │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ☁️ 云端模型                                  │
│  ┌──────────────────────────────────────┐    │
│  │ ○ OpenAI GPT-4o             [已配置]  │    │
│  │ ○ 千问 qwen-max             [已配置]  │    │
│  │ ○ Claude 3.5 Sonnet       [未配置 ⚠]  │    │
│  │ ○ Gemini 2.0 Flash         [已配置]  │    │
│  │ ○ 文心一言 4.0              [已配置]  │    │
│  │ ○ Ollama (本地部署)        [已配置]  │    │
│  └──────────────────────────────────────┘    │
│                                              │
│  ┌──────────────────────────────────────┐    │
│  │            [确认选择]                  │    │
│  └──────────────────────────────────────┘    │
└──────────────────────────────────────────────┘
```

### 8.5 消息数据模型

```kotlin
sealed class ChatMessage {
    data class UserMessage(
        val id: String,
        val content: String,
        val attachments: List<Attachment> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class AIMessage(
        val id: String,
        val content: String,
        val thinkingContent: String? = null,
        val status: MessageStatus = MessageStatus.GENERATING,
        val modelName: String,
        val modelProvider: ProviderType,
        val tokenCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class SystemMessage(
        val id: String,
        val content: String,
        val type: SystemMessageType
    ) : ChatMessage()
}

enum class MessageStatus {
    THINKING,     // 思考中
    GENERATING,   // 生成中
    COMPLETED,    // 完成
    ERROR,        // 错误
    STOPPED       // 用户手动停止
}

enum class ProviderType {
    LOCAL_LLAMA,
    OPENAI,
    QWEN,
    ANTHROPIC,
    GEMINI,
    WENXIN,
    OLLAMA
}
```

### 8.6 ViewModel 状态管理

```kotlin
class AIChatViewModel(
    private val aiService: IAIService,
    private val modelManager: MultiModelManager
) : ViewModel() {

    data class ChatUiState(
        val messages: List<ChatMessage> = emptyList(),
        val currentModel: ModelInfo? = null,
        val isGenerating: Boolean = false,
        val deepThinkingEnabled: Boolean = false,
        val availableModels: List<ModelInfo> = emptyList(),
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendMessage(content: String) { ... }
    fun stopGeneration() { ... }
    fun switchModel(modelId: String) { ... }
    fun clearHistory() { ... }
    fun toggleDeepThinking() { ... }
}
```

---

## 附录 A：目录结构参考

```
app/src/main/
├── java/com/quiz/ai/
│   ├── service/
│   │   ├── IAIService.kt
│   │   ├── ServiceRouter.kt
│   │   ├── AIChatService.kt
│   │   ├── QuestionAnalyzer.kt
│   │   ├── QuestionGenerator.kt
│   │   ├── LearningAssistantService.kt
│   │   ├── TranslatorService.kt
│   │   └── OCRService.kt
│   ├── inference/
│   │   ├── AgentInferenceJNI.kt
│   │   ├── LocalLLMService.kt
│   │   ├── InferenceQueueManager.kt
│   │   ├── StreamingCallback.kt
│   │   └── MemoryMonitor.kt
│   ├── model/
│   │   ├── ModelRegistry.kt
│   │   ├── ModelInfo.kt
│   │   ├── MultiModelManager.kt
│   │   ├── OnlineModelManager.kt
│   │   └── LocalModelManager.kt
│   ├── config/
│   │   ├── AIConfig.kt
│   │   ├── PerformanceConfig.kt
│   │   └── DeviceCapabilityDetector.kt
│   ├── cloud/
│   │   ├── OpenAIClient.kt
│   │   ├── QwenClient.kt
│   │   ├── AnthropicClient.kt
│   │   ├── GeminiClient.kt
│   │   ├── WenXinClient.kt
│   │   └── OllamaClient.kt
│   ├── chat/
│   │   ├── AIChatActivity.kt
│   │   ├── AIChatViewModel.kt
│   │   ├── ChatMessage.kt
│   │   ├── StreamingTextView.kt
│   │   └── ModelPickerDialog.kt
│   └── error/
│       ├── AIErrorCodes.kt
│       └── AIException.kt
├── cpp/
│   └── agent_inference_jni.cpp
└── jniLibs/
    ├── arm64-v8a/
    │   └── libagent_inference_jni.so
    └── armeabi-v7a/
        └── libagent_inference_jni.so
```

---

## 附录 B：术语表

| 术语 | 说明 |
|------|------|
| GGUF | GPT-Generated Unified Format，llama.cpp 使用的模型文件格式 |
| JNI | Java Native Interface，Java 与 C/C++ 之间的桥接接口 |
| KV Cache | Key-Value Cache，Transformer 推理中用于加速的缓存机制 |
| Token | 文本被模型分词后的最小处理单元 |
| Quantization | 量化，将模型参数从高精度（如 FP16）压缩到低精度（如 INT4） |
| Chain-of-Thought | 思维链，让模型展示逐步推理过程的技术 |
| 熔断 | 当服务连续失败时，自动切断请求以保护系统稳定 |
| System Prompt | 系统提示词，用于设定 AI 的角色和行为规则 |
| OpenCL | 开放计算语言，跨平台 GPU 加速框架 |
| Vulkan | 新一代高性能图形和计算 API |