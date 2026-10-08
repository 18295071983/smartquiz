# 答题宝 (SmartQuiz) 系统架构设计

> 版本: 2.4 | 更新日期: 2026-08-28 | 对应代码版本: v2.0（versionName，DB v24）

## 一、项目概述

答题宝是一款基于 Android 的智能学习平台，集成了**本地大语言模型推理引擎**、**本地/在线混合 Agent 智能代理系统**、**多模态输入（OCR/语音/图片）**、**多格式文件处理**和**WebView 混合界面**。

- **包名**: `com.oilquiz.app`（versionName 2.0，versionCode 2）
- **代码规模**: ~720 个 Java 文件，83 个 Activity（app 源码当前**无 .kt 文件**，纯 Java 实现；Compose/Kotlin 依赖为早期预留）
- **最低 SDK**: API 31 (Android 12)
- **目标 SDK**: API 34 (Android 14)
- **构建工具**: Gradle 8.13 + AGP 8.4.0 + JDK 17 + Chaquopy 16.1.0（Python 工具链）

## 二、总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                       UI 层 (Presentation)                    │
│  ┌──────────────────────┬──────────────────────────────────┐  │
│  │  Jetpack Compose UI  │  传统 XML Layout + ViewBinding   │  │
│  │  (Material 3)        │  (83 Activity)                   │  │
│  └──────────────────────┴──────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                    ViewModel 层 (State Management)            │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  13个 ViewModel  |  LiveData / StateFlow             │     │
│  └──────────────────────────────────────────────────────┘     │
├──────────────────────────────────────────────────────────────┤
│                     Domain 层 (Business Logic)                │
│  ┌──────────────────┬──────────────────┬──────────────────┐  │
│  │  Manager (12)    │  AI Agent 引擎   │  AI 工具 (58)    │  │
│  └──────────────────┴──────────────────┴──────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                       Data 层 (Data Access)                   │
│  ┌──────────────────┬──────────────────┬──────────────────┐  │
│  │  Room DB (v24)   │  Retrofit/OkHttp │  DataStore       │  │
│  └──────────────────┴──────────────────┴──────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                    Native 层 (C++ JNI)                        │
│  ┌──────────────────────────────────────────────┐             │
│  │  llama.cpp → llama-bridge.cpp → JNI Bridge   │             │
│  │  GPU: Vulkan（唯一后端，OpenCL 已关闭）        │             │
│  └──────────────────────────────────────────────┘             │
└──────────────────────────────────────────────────────────────┘
```

### 架构特点

- **MVVM 架构**: ViewModel + LiveData 驱动 UI 更新
- **依赖注入**: Hilt (Dagger 2.48) 管理对象创建与生命周期
- **混合 UI**: Compose (Material 3) + 传统 XML Layout
- **Repository 模式**: 数据访问通过 DAO 层直接访问
- **JNI 桥接**: C++ AI 推理引擎通过 JNI 与 Java 层通信（统一 JSON 协议 v1.11）
- **本地/在线混合**: Agent 引擎按模型可用性路由本地 Function Calling 或在线 OpenAI 兼容协议，在线不可用可本地降级
- **Python 工具链**: Chaquopy 内嵌 Python（pandas/numpy/bs4/jieba 等），供 AI 工具动态调用与原生布局渲染

## 三、模块划分

```
com.oilquiz.app/
├── ai/                 # AI 智能模块（核心）
│   ├── agent/          # Agent 引擎
│   │   ├── function/   # 本地原生 Function Calling 单循环架构
│   │   ├── online/     # 在线 Agent（OnlineAgentEngine/ToolManager/PromptBuilder/
│   │   │               #   AgentWorkspace/AgentMemoryStore/ThinkingChain 等）
│   │   ├── software/   # 软件层（engine/model/recognizer/result）
│   │   └── ui/         # Agent UI 呈现
│   ├── bridge/         # 本地推理 JNI 桥接
│   ├── callback/       # 回调处理 (2 files)
│   ├── chat/           # 聊天与对话管理 (20+ 子包)
│   │   ├── cache/ component/ coordination/ event/ history/ input/
│   │   ├── lifecycle/ live/ mode/ parser/ processor/ recovery/
│   │   ├── render/ status/ streaming/ ui/ viewmodel/
│   │   └── weather/    # 天气联动（横幅/实时刷新 observer）
│   ├── config/         # 配置验证
│   ├── db/             # 聊天数据库
│   ├── engine/         # 推理引擎
│   ├── export/         # AI 导出
│   ├── feature/        # AI 功能（题目生成/解析/翻译等）
│   ├── gpu/            # GPU 加速 (12 files)
│   ├── importing/      # AI 导入流水线（v4 混合）
│   ├── inference/      # 推理队列
│   ├── intent/         # 意图识别
│   ├── jni/            # JNI 桥接
│   ├── model/          # 模型管理 (20+ files)
│   ├── monitor/        # 性能监控
│   ├── optimization/   # 优化检测
│   ├── performance/    # 性能仪表盘
│   ├── python/         # Python 工具链（PythonToolManager/NativeLayoutRenderer）
│   ├── refactor/       # 推理核心重构
│   ├── repair/         # 推理修复
│   ├── service/        # AI 服务
│   ├── skill/          # 技能系统
│   ├── speech/         # 语音（asr/core/tts，SpeechManager 门面 + TTS 引擎策略）
│   ├── stats/          # 统计管理
│   ├── tool/           # AI 工具系统 (58 files)
│   │   ├── annotation/ # 注解定义
│   │   └── openai/     # OpenAI Tool Calling 协议适配
│   ├── usage/          # AI 用法配置与统计
│   └── util/           # AI 工具类
│
├── adapter/            # 适配器层
├── database/           # Room 数据库层 (v24)
├── di/                 # Hilt 依赖注入
├── infra/              # 基础设施
├── manager/            # 业务管理器 (12 files)
├── model/              # 数据模型
├── receiver/           # 广播接收器
├── repository/         # 数据仓库层
├── resource/           # 资源管理
├── toolkit/            # 应用工具集
├── ui/                 # UI 层
│   ├── accessibility/  # 无障碍辅助
│   ├── activity/       # 活动 (83 Activity)
│   ├── adapter/        # 适配器
│   ├── agent/ animation/ base/ dialog/ export/ widget/
├── util/               # 工具类（已分类）
│   ├── export/         # 导出（含 format/template）
│   ├── fileparser/     # 文件解析
│   ├── preview/        # 文件预览
│   ├── quiz/           # 答题工具
│   └── render/         # 文件渲染
├── viewmodel/          # ViewModel (13 files)
├── weather/            # 天气服务（model/util，QWeather SDK v5.2.2）
├── webview/            # WebView 组件（js/security）
└── MyApplication.java  # Application 类
```

## 四、核心子系统

### 4.1 AI Agent 子系统

```
用户输入 → InputValidator → ServiceRouter
                               ↓
                      ┌────────┴────────┐
                      ↓                 ↓
               AgentChatHandler    ModelManager
                      ↓                 ↓
               ChatModeManager    LlamaHelper (JNI)
                      ↓                 ↓
               AgentToolsManager   AIInferenceCore
                      ↓
         ┌───────────┼───────────┐
         ↓           ↓           ↓
     DatabaseTool  FileReaderTool  LayoutEditorTool  ... (58 tool files)
```

### 4.2 AI 服务层

支持多 AI 后端服务路由：

| 服务 | 类 | 说明 |
|------|-----|------|
| 本地推理 | `LlamaHelper` | llama.cpp JNI 推理 |
| OpenAI | `ServiceRouter` | GPT 系列 |
| Ollama | `ServiceRouter` | 本地 Ollama 服务 |
| 千问 | `ServiceRouter` | 阿里通义千问 |
| Anthropic | `ServiceRouter` | Claude 系列 |
| Gemini | `ServiceRouter` | Google Gemini |
| 文心一言 | `ServiceRouter` | 百度文心 |

### 4.2.1 多模态服务（新增 v2.3）

支持多模态大语言模型（如 Qwen2.5-VL）的本地推理：

```
用户消息 + 图片
  │
  ├── 模型支持多模态（supportsVision）→ LlamaHelper.generateWithImage()
  │    ├── 历史消息评估 → KV cache
  │    ├── 图像编码 → mtmd
  │    └── 流式生成 → 结合图文上下文
  │
  └── 模型不支持多模态 → AttachmentPreParser.parseImage()
       └── OCR 识别 → 文字描述注入 prompt
```

| 组件 | 类 | 说明 |
|------|-----|------|
| 模型管理 | `MultiModelManager` | mmproj 生命周期管理 |
| 模型信息 | `ModelInfo` | supportsVision / mmprojPath |
| JNI 接口 | `LlamaHelper` | nativeLoadMultimodal / nativeGenerateWithImage |
| 底层库 | `mtmd` | 图像编码（clip + 特征提取） |

### 4.3 GPU 加速子系统

```
GpuInfo → GpuAdaptiveTuner
   ↓            ↓
VulkanInfo   GpuDatabase
   ↓            ↓
GpuProfile  BenchmarkResult
```

构建仅启用 **Vulkan 后端**（GGML_VULKAN=ON，OpenCL 已关闭），自动检测设备能力并调优。

### 4.4 AI 工具系统

共 **58 个工具类**（ai/tool/ 目录），覆盖：

| 类别 | 工具（示例） |
|------|------|
| **文件操作** | FileReaderTool, FileAnalyzerTool, FileGeneratorTool, AIFileExporter, AIFileParser, ExcelTool, WorkspaceTool |
| **数据库** | DatabaseTool（题目/错题/笔记增删改查） |
| **网络** | NetworkSearchTool（Metaso 联网搜索）, SmartResearchTool, WebPageReaderTool |
| **系统** | AppOperationTool, AppToolkitAITool, SystemResourceTool, SystemConnectTool, TimeDateTool, CalculatorTool, PermissionManagerTool, GetModelsProfileTool, UpdateModelsProfileTool |
| **UI 组件** | SystemUIComponentTool（原生 UI 卡片）, UIComponentPluginTool/PluginManager/TypeRegistry（插件化组件）, LayoutEditorTool（动态布局画布）, ControlLookupTool（控件查询）, FloatingWindowController, VideoToPlayerTool |
| **多模态** | OCRRecognizeTool, ImageGenTool, DashscopeMediaTool, SpeechSynthesisTool, VoiceInputTool |
| **动态插件** | DynamicAITool, DynamicToolManagerTool, DynamicToolExecutor, ToolRegistryTool |
| **其他** | LocationTool, MemoryTool（Agent 记忆）, ToolDependencyChecker, AIWeatherManager, AIEntertainmentManager |

### 4.5 天气服务系统

#### 三级回退机制
```
天气预警请求
    ↓
┌─────────────────────────────────────────┐
│ 1. QWeatherSdkManager (SDK)             │
│    → 和风天气 SDK 直接调用              │
└──────────────┬──────────────────────────┘
               ↓ 失败
┌─────────────────────────────────────────┐
│ 2. WeatherService (HTTP API)            │
│    → 直接调用和风天气 REST API          │
└──────────────┬──────────────────────────┘
               ↓ 失败
┌─────────────────────────────────────────┐
│ 3. APISpace 备用 API                    │
│    → 国家预警中心数据                   │
└─────────────────────────────────────────┘
```

#### 天气服务组件

| 组件 | 类名 | 功能 |
|------|------|------|
| 天气管理器 | `AIWeatherManager` | 统一天气查询入口 |
| SDK 管理 | `QWeatherSdkManager` | 和风天气 SDK 封装 |
| 天气服务 | `WeatherService` | HTTP API 回退调用 |
| 位置工具 | `LocationTool` | 获取用户位置信息 |
| 图标映射 | `QWeatherIconMapper` | 天气图标映射 |
| 横幅组件 | `WeatherBannerView` | 天气横幅 UI 组件 |
| 横幅管理 | `WeatherBannerController` | 横幅控制器 |

#### 支持的天气数据类型
- 实时天气、24小时预报、3天预报
- 空气质量、生活指数（16类）
- 天气预警、分钟级降水
- 日出日落、天文数据

### 4.6 Skill 技能系统

动态加载和执行技能：
- `SkillManager` — 技能注册与管理
- `SkillLoader` — 技能加载器

### 4.7 WebView 混合界面

```
WebViewActivity → bridge.js
        ↓                ↓
   pages/ai-chat.html  api.js
   pages/question.html utils.js
   pages/quiz.html
```

WebView 与原生通过 JavaScript Bridge 通信，支持：
- 数据库操作
- 文件读写
- 工具调用

### 4.8 文件处理子系统

#### 导入支持格式
| 格式 | 解析引擎 |
|------|---------|
| Excel (.xls/.xlsx) | Apache POI |
| CSV | 自实现 |
| JSON | Gson |
| Markdown | Markwon |
| PDF | iText7 |
| Word | Apache POI |

#### 导出支持格式
| 格式 | 导出引擎 |
|------|---------|
| Excel | ExcelExporter |
| CSV | CSVExporter |
| HTML | HTMLExporter |
| JSON | JSONExporter |
| PDF | PDFExporter |
| Word | WordExporter |
| 长图 | 模板导出 (24种模板) |

### 4.9 语音子系统（2026-08-12 落地）

```
SpeechManager（门面）
   ├── ASR（在线识别，OpenAI 兼容 /audio/transcriptions）
   └── TTS（策略）
        ├── OpenAiTtsEngine（/audio/speech）
        ├── DashScopeTtsEngine（DashScope 原生）
        └── SystemTtsEngine（系统 TTS 兜底）
```

- `ai/speech/core|asr|tts` 三层结构；含 SSL 宽松回退与系统 TTS 并发/回调防护。
- 对 Agent 暴露 SpeechSynthesisTool / VoiceInputTool。

### 4.10 动态布局画布子系统（2026-08-26 ~ 08-28 落地）

AI 可通过工具直接编排原生 UI：

```
LLM 工具调用
   ├── SystemUIComponentTool（原生 UI 组件卡片，插件化注册）
   ├── LayoutEditorTool（layout_canvas 增删改查，JSON 容错 + add 参数兼容）
   │        → LayoutCanvasManager（chat/component）→ NativeLayoutRenderer（python）
   ├── ControlLookupTool（查询现有控件树）
   └── 控件树增强：嵌套注册类型 / 现场定义 / use 模板；连续输入多轮表单
```

详见 [02-ai-agent-architecture.md](../development/02-ai-agent-architecture.md)。

### 4.11 AI 对话渲染管线（2026-08-25 落地）

- 流式渲染 + is_partial 增量解析 + 误判防护
- Markdown 结构用内置 UI 组件渲染（代码卡/表格卡）+ Prism4j 语法高亮
- Mermaid 图形化与 KaTeX 数学公式：本地 js（assets/js）+ WebView 渲染

## 五、数据流

### 5.1 题目导入流程

```
用户选择文件 → ImportActivity → FileParserUtil
                                    ↓
                              QuestionDao → Room DB
                                    ↓
                              ImportResultActivity
```

### 5.2 AI 对话流程

```
用户输入 → AIChatActivity → AgentChatHandler
                               ↓
                         ServiceRouter (选择服务)
                               ↓
                    ┌──────────┼──────────┐
                    ↓                     ↓
             LlamaHelper (JNI)      Retrofit (云端API)
           (本地 llama.cpp)              ↓
                              Stream Response
                              ↓
                        ChatAdapter → RecyclerView
```

### 5.3 答题流程

```
QuizActivity → QuizViewModel
        ↓                ↓
  QuestionDao      ScoreDao
        ↓                ↓
  Room DB (question)   Room DB (score_history)
```

## 六、安全架构

| 层级 | 措施 |
|------|------|
| **网络安全** | HTTPS + network_security_config.xml |
| **数据加密** | SecurityCrypto (EncryptedSharedPreferences) |
| **WebView 安全** | Security + JS 接口权限控制 |
| **密钥管理** | APIKeyManager (加密存储) |
| **代码混淆** | ProGuard (proguard-rules.pro + proguard-poi-rules.pro) |

## 七、多语言支持

| 语言 | 资源目录 |
|------|---------|
| 简体中文 (默认) | `values/` |
| English | `values-en/` |
| 繁體中文 | `values-zh-rTW/` |

运行时可通过 `LanguageManager` 动态切换语言。

## 八、主题系统

- 支持多套色系切换 (`ThemeManager`)
- 深色模式 (`values-night/`)
- 自定义主题
- 24 色系可选 (`ThemeColorActivity`)

## 九、技术栈总览

| 类别 | 技术 |
|------|------|
| **UI** | Compose Material 3, XML Layout, ViewBinding, RecyclerView |
| **架构** | MVVM, Hilt DI 2.48 |
| **数据库** | Room 2.5.2 (v24) |
| **网络** | Retrofit 2.9.0, OkHttp 4.12.0 |
| **AI** | llama.cpp JNI (Vulkan), TensorFlow Lite 2.15.0, ML Kit 16.0.0 |
| **文件** | Apache POI 5.2.3, iText7 7.2.3, Pdfium 1.9.0 |
| **图像** | Glide 4.16.0, Coil 2.6.0, Lottie 6.4.0 |
| **Python** | Chaquopy 16.1.0（pandas/numpy/bs4/jieba/requests） |
| **富文本渲染** | Prism4j 2.0.0（代码高亮）、本地 Mermaid/KaTeX（assets/js，WebView 渲染） |
| **天气** | QWeather SDK 5.2.2 |
| **工具** | Guava 32.1.2, JGit 6.7.0, ZXing 4.3.0, Jsoup 1.17.2 |
| **安全** | SecurityCrypto, BouncyCastle 1.76 |
| **测试** | JUnit 4, Mockito 4.8.1, Robolectric 4.10.3 |

## 十、构建与部署

- **构建**: Gradle 8.13 + AGP 8.4.0, CMake 3.22.1 (可选)
- **输出**: `答题宝-debug-{version}.apk` / `答题宝-release-{version}.apk`
- **ABI**: arm64-v8a, x86_64
- **签名**: smartquiz.keystore (debug/release)
- **本地库编译**: MSYS2 + NDK 26.1.10909125 (可选)
- **最低安装**: API 31+

---

## 相关文档

- [Agent 架构设计](../development/02-ai-agent-architecture.md)
- [AI 服务与推理设计](../development/04-ai-service-inference.md)
- [数据库结构设计](../database/database_structure.md)
- [项目架构总览](../development/01-project-overview.md)
- [模块清单](../development/08-module-inventory.md)
- [开发规范](../development/09-development-guide.md)