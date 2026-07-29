# Agent 架构设计文档

> 版本: 2.2 | 更新日期: 2026-07-29

## 一、架构概述

答题宝的 AI Agent 系统采用**多引擎 + 多服务路由 + 动态工具**架构，支持本地推理与云端 API 无缝切换。

### 1.1 完整架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                        Agent UI Layer                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ AIChatActivity   │  │ DeepThinkingView │  │ AIChatAdapter  │  │
│  └────────┬─────────┘  └────────┬─────────┘  └────────────────┘  │
└───────────┼─────────────────────┼────────────────────────────────┘
            │                     │
┌───────────▼─────────────────────▼────────────────────────────────┐
│                     Agent Entry Layer                             │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                    ServiceRouter                            │  │
│  │   路由决策核心：选择本地推理 / Ollama / OpenAI / 千问...     │  │
│  └────────────────────────────┬───────────────────────────────┘  │
│                               │                                   │
│  ┌──────────────────┐  ┌──────▼──────┐  ┌──────────────────┐    │
│  │UnifiedAgentEngine│  │AIAgentEngine│  │SmartIntentRecognizer│  │
│  │ (统一引擎入口)    │  │(核心引擎)    │  │  (意图识别)       │    │
│  └────────┬─────────┘  └──────┬──────┘  └────────┬─────────┘    │
└───────────┼───────────────────┼──────────────────┼──────────────┘
            │                   │                  │
┌───────────▼───────────────────▼──────────────────▼──────────────┐
│                    Engine Subsystems                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │TaskDecomposition │  │DeepThinkingEngine│  │CreativeWriting │  │
│  │   Manager        │  │  (深度思考引擎)   │  │  Engine        │  │
│  └────────┬────────┘  └────────┬─────────┘  └────────┬───────┘  │
│           │                    │                       │          │
│  ┌────────▼────────┐  ┌────────▼─────────┐  ┌────────▼──────┐   │
│  │  ChatModeManager│  │ AgentChatHandler │  │InferenceCore  │   │
│  └────────┬────────┘  └────────┬─────────┘  └────────┬──────┘   │
└───────────┼────────────────────┼─────────────────────┼──────────┘
            │                    │                     │
┌───────────▼────────────────────▼─────────────────────▼──────────┐
│                    Tool & Skill Layer                            │
│  ┌────────────────────────────────┐  ┌────────────────────────┐ │
│  │     AIToolManager (27 tools)   │  │   Skill System         │ │
│  │  ┌───────────┬──────────────┐  │  │  ┌─────────────────┐   │ │
│  │  │FileTool    │NetworkTool   │  │  │  │ SkillManager    │   │ │
│  │  │SearchTool  │LocationTool  │  │  │  │ SkillLoader     │   │ │
│  │  │WeatherTool │TranslateTool │  │  │  │ DynamicSkill    │   │ │
│  │  │DBTool      │SystemTool    │  │  │  └─────────────────┘   │ │
│  │  └───────────┴──────────────┘  │  │                        │ │
│  └────────────────────────────────┘  └────────────────────────┘ │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                    Model & Inference Layer                       │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │ MultiModelManager│  │  ModelRegistry   │  │OnlineModelMgr │  │
│  └────────┬─────────┘  └────────┬─────────┘  └───────────────┘  │
│           │                     │                                │
│  ┌────────▼─────────────────────▼────────────────────────────┐   │
│  │                    AIInferenceCore                         │   │
│  │  ┌────────────────────┐  ┌────────────────────────────┐   │   │
│  │  │ AgentInferenceJNI  │  │  Retrofit (Cloud APIs)     │   │   │
│  │  │ (llama.cpp 本地推理)│  │  (OpenAI/Ollama/千问...)    │   │   │
│  │  └────────────────────┘  └────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────────┐
│                    GPU Acceleration Layer                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
│  │GPUAccelerationMgr│  │ GpuAdaptiveTuner │  │ Capability    │  │
│  └────────┬─────────┘  └────────┬─────────┘  │ Detector      │  │
│           │                     │             └───────────────┘  │
│  ┌────────▼─────────┐  ┌────────▼─────────┐                     │
│  │  OpenCL Backend  │  │  Vulkan Backend  │                     │
│  └──────────────────┘  └──────────────────┘                     │
└──────────────────────────────────────────────────────────────────┘
```

## 二、核心引擎组件

### 2.1 UnifiedAgentEngine（统一引擎）

**入口类**: `com.oilquiz.app.ai.agent.UnifiedAgentEngine`

作为 Agent 系统的统一调度入口，负责：
- 接收用户请求并分发给合适的子引擎
- 管理 Agent 生命周期
- 协调任务分解与执行
- 维护对话上下文

### 2.2 AIAgentEngine（核心推理引擎）

**入口类**: `com.oilquiz.app.ai.agent.AIAgentEngine`

核心 Agent 逻辑引擎，负责：
- 推理请求的预处理和后处理
- 与 `AIInferenceCore` 和 `ServiceRouter` 协作
- 管理回调链与流式输出

### 2.3 TaskDecompositionManager（任务分解）

**入口类**: `com.oilquiz.app.ai.agent.TaskDecompositionManager`

将复杂用户请求分解为可执行的子任务：
- 解析用户意图并规划执行步骤
- 分配工具调用
- 协调并行执行

### 2.4 DeepThinkingEngine（深度思考）

**入口类**: `com.oilquiz.app.ai.chat.DeepThinkingEngine`

实现链式思维推理（Chain-of-Thought），适用于复杂分析和推理场景：
- 多轮内部推演
- 逐步验证结论
- 生成结构化分析报告

### 2.5 CreativeWritingEngine（创意写作）

**入口类**: `com.oilquiz.app.ai.agent.CreativeWritingEngine`

专注于创意内容生成：
- 文案撰写
- 创意构思
- 风格化输出

## 三、意图识别与路由

### 3.1 SmartIntentRecognizer

**类名**: `com.oilquiz.app.ai.agent.SmartIntentRecognizer`

分析用户输入，识别意图类型：
- `QUIZ` — 答题相关
- `QUESTION` — 题目分析
- `LEARNING` — 学习辅助
- `TRANSLATION` — 翻译请求
- `CREATIVE` — 创意写作
- `GENERAL` — 通用对话
- `SYSTEM` — 系统操作

### 3.2 ServiceRouter（服务路由）

**类名**: `com.oilquiz.app.ai.agent.ServiceRouter`

根据意图、设备能力和用户偏好，选择最优 AI 服务后端：

```
意图识别结果 → ServiceRouter 决策矩阵
                        ↓
┌───────────────────────┼──────────────────────────┐
│  离线场景             │  在线场景                 │
│  → 本地 llama.cpp     │  → OpenAI / 千问 / ...    │
│  → 模型可本地运行     │  → API 密钥验证           │
│  → GPU 加速可用       │  → 服务可用性检查         │
└───────────────────────┴──────────────────────────┘
```

## 四、工具系统（AIToolManager）

### 4.1 工具分类

| 工具 | 类名 | 功能 |
|------|------|------|
| **FileReaderTool** | 文件读取 | 读取本地文件内容 |
| **FileAnalyzerTool** | 文件分析 | 分析文件结构和元数据 |
| **FileGeneratorTool** | 文件生成 | 动态生成文档/报告 |
| **FileExporter** | 文件导出 | 导出到各种格式 |
| **FileParser** | 文件解析 | 解析 Excel/CSV/JSON 等 |
| **NetworkSearchTool** | 网络搜索 | 联网搜索信息 |
| **WebPageReaderTool** | 网页阅读 | 读取并解析网页内容 |
| **SmartResearchTool** | 智能研究 | 多源信息整合研究 |
| **DatabaseTool** | 数据库操作 | 查询本地题库 |
| **LocationTool** | 位置服务 | 获取位置信息 |
| **TranslationTool** | 翻译 | 内建多语言翻译 |
| **AIWeatherManager** | 天气 | 天气查询服务 |
| **AppOperationTool** | 应用操作 | 控制应用功能 |
| **SystemResourceTool** | 系统资源 | 获取系统信息 |
| **PermissionManagerTool** | 权限管理 | 权限检查与申请 |

### 4.2 工具执行流程

```
Agent 请求工具 → AIToolManager 匹配工具
                        ↓
                权限检查 (PermissionManagerTool)
                        ↓
                参数验证 (BaseAITool.validate)
                        ↓
                工具执行 (BaseAITool.execute)
                        ↓
                结果格式化 (AIToolResult)
                        ↓
                回传 Agent 引擎
```

## 五、Agent 软件层（v2.2 新增）

### 5.1 架构概述

Agent 软件层是在现有 Agent 引擎之上的更高抽象层，提供**意图识别 → 复杂度分析 → 任务分解 → 执行 → 思考链 → 结果整合**的完整处理流程。

```
用户消息
    ↓
AgentSoftwareLayer.processUserMessage()
    ↓
┌─────────────────────────────────────────────────┐
│ 1. IntentRecognizer    — 意图识别               │
│ 2. ComplexityAnalyzer  — 复杂度分析             │
│ 3. TaskDecomposer      — 任务分解（仅复杂请求）  │
│ 4. ExecutionEngine     — 执行引擎               │
│ 5. ThinkingChainEngine — 思考链生成             │
│ 6. ResultIntegrator    — 结果整合               │
└─────────────────────────────────────────────────┘
    ↓
AgentResponse（最终响应）
```

### 5.2 核心组件

| 组件 | 类名 | 功能 |
|------|------|------|
| 软件层入口 | `AgentSoftwareLayer` | 统一调度 Agent 处理流程 |
| 意图识别 | `IntentRecognizer` | LLM 驱动的意图分类 |
| 复杂度分析 | `ComplexityAnalyzer` | 评估请求复杂度等级 |
| 任务分解 | `TaskDecomposer` | 将复杂请求分解为子任务 |
| 执行引擎 | `ExecutionEngine` | 执行任务列表 |
| 思考链引擎 | `ThinkingChainEngine` | 生成推理过程展示 |
| 结果整合 | `ResultIntegrator` | 整合执行结果和思考链 |

### 5.3 数据模型

```java
// 意图结果
public class IntentResult {
    IntentType type;      // QUIZ/QUESTION/TRANSLATION/WEATHER/GENERAL
    float confidence;     // 识别置信度
    String rawMessage;    // 原始消息
}

// 复杂度级别
public enum ComplexityLevel {
    SIMPLE,   // 单轮问答，直接回答
    MEDIUM,   // 多步推理，可能需要工具
    COMPLEX   // 复杂任务，需要完整规划和执行
}

// 任务定义
public class Task {
    String id;
    String description;
    String toolName;      // 使用的工具名称
    JSONObject parameters; // 工具参数
    TaskStatus status;     // PENDING/RUNNING/COMPLETED/FAILED
    String result;        // 执行结果
}
```

### 5.4 处理流程详解

#### 简单请求（SIMPLE）
```
用户: "今天北京天气怎么样？"
    → IntentRecognizer → WEATHER
    → ComplexityAnalyzer → SIMPLE
    → 直接调用 AIWeatherManager
    → 返回结果
```

#### 复杂请求（COMPLEX）
```
用户: "帮我分析最近的天气趋势并给出穿衣建议"
    → IntentRecognizer → QUESTION
    → ComplexityAnalyzer → COMPLEX
    → TaskDecomposer → [获取天气, 分析趋势, 生成建议]
    → ExecutionEngine → 依次执行各任务
    → ThinkingChainEngine → 生成推理步骤
    → ResultIntegrator → 整合最终响应
```

### 5.5 与现有引擎的关系

Agent 软件层封装了现有的 `UnifiedAgentEngine` 和 `AIAgentEngine`，向上提供更简洁的 API：

```
AgentSoftwareLayer
    ↓ (调用)
UnifiedAgentEngine / AIAgentEngine
    ↓ (调用)
ServiceRouter → LlamaHelper / Retrofit
    ↓ (调用)
AIToolManager → 各工具实现
```

## 六、Skill 技能系统

动态加载和执行可扩展的技能模块：

```
SkillManager → 技能注册表
     ↓
SkillLoader → 磁盘/网络加载技能定义
     ↓
DynamicSkillExecutor → 运行时执行
```

- **SkillManager**: 维护技能注册表，管理生命周期
- **SkillLoader**: 从 assets/网络加载技能定义
- **DynamicSkillExecutor**: 在沙箱中执行技能代码

## 七、Chat 子系统

### 7.1 核心组件

| 组件 | 类名 | 功能 |
|------|------|------|
| 聊天处理 | `AgentChatHandler` | AI 对话的完整处理管线 |
| 聊天模式 | `ChatModeManager` | 切换对话模式（问答/分析/创作） |
| 思考过程 | `DeepThinkingEngine` | 深度思考与推理过程可视化 |
| 消息管理 | `AgentMessageManager` | 消息的创建、存储、迭代 |

### 7.2 ChatModeManager 模式

- **问答模式**: 快速问答，低延迟
- **分析模式**: 深度分析，多步推理
- **创作模式**: 创意写作，风格化输出
- **思考模式**: 链式推理，过程可见

## 八、模型管理

### 8.1 MultiModelManager

支持多个模型并行管理：
- 本地模型 (GGUF 格式，通过 llama.cpp)
- 离线模型包管理
- 模型热切换

### 8.2 ModelRegistry

模型注册和发现：
- 扫描设备上的可用模型
- 模型元数据管理
- 版本检测

### 8.3 OnlineModelManager

云端模型 API 管理：
- API Key 存储 (APIKeyManager)
- 服务可用性检测
- 流量统计与计费

## 九、GPU 加速系统

### 9.1 核心组件

```
DeviceCapabilityDetector → 设备硬件检测
         ↓
GpuCapabilityDetector → GPU 能力评估 (OpenCL/Vulkan)
         ↓
GPUAccelerationManager → 加速策略决策
         ↓
GpuAdaptiveTuner → 运行时自适应调优
         ↓
GpuDatabase → 设备基准数据（已有设备的性能档案）
         ↓
MemoryMonitor → 实时内存监控
         ↓
BenchmarkResult → 性能基准结果
```

### 9.2 后端支持

| 后端 | 说明 |
|------|------|
| OpenCL | 跨平台 GPU 计算，兼容性好 |
| Vulkan | 低开销图形与计算 API，现代 Android |

系统自动检测最佳后端方案。

## 十、AI 服务层

### 10.1 多服务架构

```
┌──────────────────────────────────────┐
│           ServiceRouter              │
│         (路由决策 & 故障转移)         │
├──────────┬──────────┬───────────────┤
│  本地推理  │  本地服务  │   云端 API     │
├──────────┼──────────┼───────────────┤
│ llama.cpp│  Ollama  │ OpenAI        │
│  GGUF    │  (可选)   │ 千问/通义     │
│          │          │ Anthropic     │
│          │          │ Gemini        │
│          │          │ 文心一言       │
└──────────┴──────────┴───────────────┘
```

### 10.2 AI 服务组件

| 服务 | 类名 | 功能 |
|------|------|------|
| AI主服务 | `AIService.java` | AI 服务主入口 |
| Agent服务 | `AIAgentService.java` | Agent 后台服务 (Foreground) |
| 处理服务 | `AIProcessingService.java` | AI 处理管道 |
| 基础服务 | `AgentService.java` | Agent 基础服务 |
| 崩溃处理 | `AICrashHandlerService.java` | AI 崩溃恢复 |

## 十一、Feature 功能层

独立的 AI 功能模块：

| 模块 | 类名 | 功能 |
|------|------|------|
| 学习助手 | `LearningAssistant` | 智能学习建议与辅导 |
| 题目分析 | `QuestionAnalyzer` | 题目考点与难度分析 |
| 题目生成 | `QuestionGenerator` | AI 自动出题 |
| 翻译 | `Translator` | 多语言翻译 |

## 十二、配置与优化

### 12.1 AI 配置

- `AIConfig.java` — 推理参数配置（temperature, top_p, max_tokens 等）
- `InferenceQueue.java` — 推理请求队列管理
- `DeviceOptimizationManager.java` — 设备适配优化

### 12.2 缓存与会话

- `CacheManager.java` — 推理结果缓存
- `SessionManager.java` — 对话会话管理
- `ChatDatabaseHelper.java` — 聊天数据库辅助

### 12.3 性能监控

- `PerformanceDashboard.java` — 性能仪表盘
- `PerformanceMonitor.java` — 运行时监控
- `MemoryMonitor.java` — GPU 内存监控

## 十三、Agent 状态机

```
IDLE → ANALYZING → PLANNING → EXECUTING → VALIDATING → RESPONDING → COMPLETED
  ↑        │            │          │            │            │           │
  └────────┴────────────┴──────────┴────────────┴────────────┴───────────┘
                              ERROR (任意阶段)
```

### 状态说明

| 状态 | 描述 |
|------|------|
| IDLE | 空闲，等待用户输入 |
| ANALYZING | 意图识别中 |
| PLANNING | 任务规划中 |
| EXECUTING | 执行工具/推理 |
| VALIDATING | 结果验证 |
| RESPONDING | 生成回复 |
| COMPLETED | 完成，返回结果 |
| ERROR | 异常状态，触发恢复 |

---

## 相关文档

- [系统架构设计](system/system_architecture.md)
- [AI 功能设计](development/ai_feature_design.md)
- [技术栈详情](development/tech_stack.md)
- [AI模块设计](development/ai_modules/)