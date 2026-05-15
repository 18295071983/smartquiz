# 答题宝 (SmartQuiz) 系统架构设计

> 版本: 2.0 | 更新日期: 2026-05-16 | 对应代码版本: v2.0

## 一、项目概述

答题宝是一款基于 Android 的智能学习平台，集成了**本地大语言模型推理引擎**、**Agent 智能代理系统**、**多格式文件处理**和**WebView 混合界面**。

- **包名**: `com.oilquiz.app`
- **最低 SDK**: API 31 (Android 12)
- **目标 SDK**: API 34 (Android 14)
- **构建工具**: Gradle 8.13 + AGP 8.4.0 + JDK 17

## 二、总体架构

```
┌──────────────────────────────────────────────────────────────┐
│                       UI 层 (Presentation)                    │
│  ┌──────────────────────┬──────────────────────────────────┐  │
│  │  Jetpack Compose UI  │  传统 XML Layout + ViewBinding   │  │
│  │  (Material 3)        │  (60+ Activity)                  │  │
│  └──────────────────────┴──────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                    ViewModel 层 (State Management)            │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  13个 ViewModel  |  LiveData / StateFlow             │     │
│  └──────────────────────────────────────────────────────┘     │
├──────────────────────────────────────────────────────────────┤
│                     Domain 层 (Business Logic)                │
│  ┌──────────────────┬──────────────────┬──────────────────┐  │
│  │  Repository (12) │  Manager (9)     │  Resource (10)   │  │
│  └──────────────────┴──────────────────┴──────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                       Data 层 (Data Access)                   │
│  ┌──────────────────┬──────────────────┬──────────────────┐  │
│  │  Room DB (v20)   │  Retrofit/OkHttp │  DataStore       │  │
│  └──────────────────┴──────────────────┴──────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│                    Native 层 (C++ JNI)                        │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  llama.cpp → agent_inference_jni → JNI Bridge        │     │
│  │  GPU: OpenCL / Vulkan                                │     │
│  └──────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

### 架构特点

- **MVVM 架构**: ViewModel + LiveData 驱动 UI 更新
- **依赖注入**: Hilt (Dagger) 管理对象创建与生命周期
- **混合 UI**: Compose (Material 3) + 传统 XML Layout
- **Repository 模式**: 数据访问通过 Repository 层抽象
- **JNI 桥接**: C++ AI 推理引擎通过 JNI 与 Java 层通信

## 三、模块划分

```
com.oilquiz.app/
├── ai/                 # AI 智能模块（核心）
│   ├── agent/          # Agent 引擎 (10 files)
│   ├── chat/           # 聊天与对话管理 (6 files)
│   ├── config/         # 设备检测与推理配置 (4 files)
│   ├── feature/        # AI 功能 (4 files)
│   ├── gpu/            # GPU 加速 (13 files)
│   ├── inference/      # 推理队列 (1 file)
│   ├── intent/         # 意图识别 (1 file)
│   ├── jni/            # JNI 桥接 (3 files)
│   ├── model/          # 模型管理 (13 files)
│   ├── refactor/       # 推理核心重构 (4 files)
│   ├── performance/    # 性能监控 (2 files)
│   ├── service/        # AI 服务 (6 files)
│   ├── skill/          # 技能系统 (3 files)
│   ├── tool/           # AI 工具系统 (27 files)
│   ├── callback/       # 回调处理 (1 file)
│   ├── db/             # 聊天数据库 (2 files)
│   ├── monitor/        # 性能监控 (1 file)
│   ├── optimization/   # 设备检测 (1 file)
│   └── util/           # AI 工具类 (5 files)
│
├── database/           # Room 数据库层 (15 files)
├── di/                 # Hilt 依赖注入 (1 file)
├── infra/              # 基础设施 (6 files)
├── manager/            # 业务管理器 (9 files)
├── model/              # 数据模型 (17 files)
├── repository/         # 数据仓库 (12 files)
├── resource/           # 资源管理 (10 files)
├── toolkit/            # 应用工具集 (1 file)
├── ui/                 # UI 层 (80+ files)
│   ├── activity/       # 活动 (60+)
│   ├── adapter/        # 适配器 (12)
│   ├── base/           # 基类 (2)
│   ├── widget/         # 天气小组件 (3)
│   └── export/         # 导出流程 (3)
├── util/               # 工具类 (40+ files)
│   ├── export/         # 导出 (template + format)
│   ├── preview/        # 文件预览 (14)
│   ├── render/         # 文件渲染 (14)
│   └── fileparser/     # 文件解析 (2)
├── viewmodel/          # ViewModel (13 files)
├── weather/            # 天气服务 (2 files)
└── webview/            # WebView 管理 (15 files)
    ├── js/             # JS 桥接接口 (5)
    └── security/       # WebView 安全 (3)
```

## 四、核心子系统

### 4.1 AI Agent 子系统

```
用户输入 → SmartIntentRecognizer → ServiceRouter
                                        ↓
                   ┌────────────────────┼──────────────────────┐
                   ↓                    ↓                      ↓
          UnifiedAgentEngine    DeepThinkingEngine   CreativeWritingEngine
                   ↓                    ↓                      ↓
           TaskDecomposition    ChatModeManager     AIInferenceCore
                   ↓                    ↓                      ↓
           AgentToolsManager    AgentChatHandler   llama.cpp JNI
                   ↓
       ┌───────────┼───────────┐
       ↓           ↓           ↓
   FileTool    NetworkTool  DatabaseTool  ... (27 tools)
```

### 4.2 AI 服务层

支持多 AI 后端服务路由：

| 服务 | 类 | 说明 |
|------|-----|------|
| 本地推理 | `AgentInferenceJNI` | llama.cpp JNI 推理 |
| OpenAI | `ServiceRouter` | GPT 系列 |
| Ollama | `ServiceRouter` | 本地 Ollama 服务 |
| 千问 | `ServiceRouter` | 阿里通义千问 |
| Anthropic | `ServiceRouter` | Claude 系列 |
| Gemini | `ServiceRouter` | Google Gemini |
| 文心一言 | `ServiceRouter` | 百度文心 |

### 4.3 GPU 加速子系统

```
DeviceCapabilityDetector → GPUAccelerationManager
        ↓                         ↓
GpuCapabilityDetector      GpuAdaptiveTuner
        ↓                         ↓
   VulkanInfo               GpuDatabase
        ↓                         ↓
  GpuProfile              BenchmarkResult
```

支持 OpenCL 和 Vulkan 两种后端，自动检测设备能力并选择最优方案。

### 4.4 AI 工具系统

共 27 个工具，覆盖：

| 类别 | 工具 |
|------|------|
| **文件操作** | FileReaderTool, FileAnalyzerTool, FileGeneratorTool, FileExporter, FileParser |
| **网络** | NetworkSearchTool, WebPageReaderTool, SmartResearchTool |
| **系统** | AppOperationTool, SystemResourceTool, PermissionManagerTool, DatabaseTool |
| **位置** | LocationTool |
| **翻译** | TranslationTool |
| **天气** | AIWeatherManager |

### 4.5 Skill 技能系统

动态加载和执行技能：
- `SkillManager` — 技能注册与管理
- `SkillLoader` — 技能加载器
- `DynamicSkillExecutor` — 动态技能执行

### 4.6 WebView 混合界面

```
WebViewActivity → WebViewLoadManager
        ↓                ↓
RedirectWebViewClient  FileRedirectManager
        ↓
JSDatabaseInterface / JSFileInterface / JSClipboardInterface / JSToolInterface
```

WebView 与原生通过 JavaScript Bridge 通信，支持：
- 数据库操作 (JSDatabaseInterface)
- 文件读写 (JSFileInterface)
- 剪贴板操作 (JSClipboardInterface)
- 工具调用 (JSToolInterface)

### 4.7 文件处理子系统

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
| Excel | ExcelExporter + Template |
| CSV | CSVExporter |
| HTML | HTMLExporter, EnhancedHTMLExporter |
| JSON | JSONExporter |
| Markdown | MarkdownExporter |
| PDF | PDFExporter |
| Word | WordExporter |
| 长图 | LongImageExporter |
| 模板导出 | TemplateBasedExporter (24种模板) |

#### 文件预览引擎
| 引擎 | 支持格式 |
|------|---------|
| TBS SDK | Word/Excel/PPT/PDF |
| Pdfium | PDF |
| LibreOfficeKit | Office 文档 |
| OnlyOffice | Office 文档 |
| Markwon | Markdown |
| ImageView | 图片 |
| TextView | 纯文本 |

## 五、数据流

### 5.1 题目导入流程

```
用户选择文件 → ImportActivity → FilePickerHelper
                                      ↓
                              FileContentExtractor
                                      ↓
                              ImportHistoryRepository
                                      ↓
                              QuestionRepository → Room DB
                                      ↓
                              ImportResultActivity
```

### 5.2 AI 对话流程

```
用户输入 → AIChatActivity → AgentChatHandler
                                ↓
                          SmartIntentRecognizer
                                ↓
                          ServiceRouter (选择服务)
                                ↓
                     ┌──────────┼──────────┐
                     ↓                     ↓
              AgentInferenceJNI      Retrofit (云端API)
              (本地 llama.cpp)            ↓
                     ↓              Stream Response
              InferenceCore              ↓
                     ↓            CallbackHandler
              CallbackHandler            ↓
                     ↓            ChatHistoryManager
              ChatHistoryManager
                     ↓
              ChatAdapter → RecyclerView
```

### 5.3 答题流程

```
StartQuizActivity → QuizViewModel
        ↓                ↓
  QuizSessionManager  QuestionRepository
        ↓                ↓
  QuizActivity      Room DB (questions)
        ↓
  ScoreRepository → ScoreViewModel
```

## 六、安全架构

| 层级 | 措施 |
|------|------|
| **网络安全** | HTTPS + network_security_config.xml |
| **数据加密** | SecurityCrypto (EncryptedSharedPreferences) |
| **WebView 安全** | SecurityWebViewClient + JS 接口权限控制 |
| **文件安全** | FileRedirectManager + FileRedirectRule 白名单 |
| **密钥管理** | APIKeyManager (加密存储) |
| **代码混淆** | ProGuard (proguard-rules.pro + proguard-poi-rules.pro) |
| **证书** | Bouncy Castle X.509 证书管理 |

## 七、多语言支持

| 语言 | 资源目录 |
|------|---------|
| 简体中文 (默认) | `values/` |
| English | `values-en/` |
| 繁體中文 | `values-zh-rTW/` |

运行时可通过 `LanguageManager` 动态切换语言。

## 八、主题系统

- 支持多套色系切换 (`ThemeColorManager`)
- 深色模式 (`values-night/`)
- 自定义主题 (`ThemeManager`)
- 24 色系可选 (`ThemeColorActivity`)

## 九、技术栈总览

| 类别 | 技术 |
|------|------|
| **UI** | Compose Material 3, XML Layout, ViewBinding, RecyclerView |
| **架构** | MVVM, Hilt DI, Repository Pattern |
| **数据库** | Room 2.5.2 (v20) |
| **网络** | Retrofit 2.9.0, OkHttp 4.12.0 |
| **AI** | llama.cpp JNI, TensorFlow Lite 2.14.0, ML Kit 16.0.0 |
| **文件** | Apache POI 5.2.3, iText7 7.2.3, Pdfium 1.9.0 |
| **图像** | Glide 4.16.0, Coil 2.6.0, Lottie 6.4.0 |
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

- [Agent 架构设计](AGENT_ARCHITECTURE.md)
- [AI 功能设计](development/ai_feature_design.md)
- [数据库结构设计](database/database_structure.md)
- [技术栈详情](development/tech_stack.md)
- [模块功能设计](development/module_function_design.md)
- [开发标准规范](development/development_standards.md)