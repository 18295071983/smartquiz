# 模块清单

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件列出真实源码（`com.oilquiz.app.*`）中的核心模块与类，作为代码索引。
> 全项目共 **721 个 Java 类**（ai 包 376 个，ui 包 107 个）。此数字随代码迭代浮动，以实际 `src/main/java` 为准。

## 一、AI 能力层（`ai` 包，376 类）

| 子包 | 类数 | 职责 |
|------|------|------|
| `ai.agent` | 53 | Agent 引擎（software/online/ui/thinking） |
| `ai.chat` | 76 | AI 对话（coordinator/viewmodel/input/status/history/lifecycle/streaming/component/render/recovery/parser） |
| `ai.tool` | 60 | 工具系统（AIToolManager + ~30 工具 + annotation/openai） |
| `ai.model` | 24 | 模型管理 |
| `ai.usage` | 30 | 用量/计费 |
| `ai.speech` | 23 | 语音（ASR/TTS） |
| `ai.importing` | 23 | 数据导入 |
| `ai.service` | 11 | AI 服务 |
| `ai.gpu` | 12 | GPU 加速 |
| `ai.python` | 12 | Python 工具 |
| `ai.util` | 11 | 工具集 |
| `ai.refactor` | 6 | 推理核心（AIInferenceCore/AIConfig/CacheManager） |
| `ai.feature` | 4 | 学习助理/题目分析/生成/翻译 |
| `ai.config` / `ai.jni` / `ai.inference` / `ai.optimization` / `ai.performance` / `ai.monitor` / `ai.stats` | 各 1-4 | 配置/JNI/推理/优化/性能/统计 |
| `ai.bridge` / `ai.callback` / `ai.engine` / `ai.export` / `ai.intent` / `ai.repair` | 各 1-3 | 桥接/回调/引擎/导出/意图/修复 |

### 1.1 核心类索引（按职责）

| 职责 | 类 |
|------|-----|
| Agent 入口 | `chat.AgentChatHandler` |
| 本地 Agent | `agent.software.AgentSoftwareLayer` / `agent.software.engine.AgentLoopEngine` |
| 在线 Agent | `agent.AgentRouter` / `agent.online.OnlineAgentEngine` |
| 对话协调 | `chat.coordination.AIChatCoordinator` |
| 对话状态机 | `chat.viewmodel.AIChatViewModel` |
| 工具管理 | `tool.AIToolManager` / `tool.AITool` / `tool.BaseAITool` |
| 消息渲染 | `chat.ChatAdapter` |
| 服务状态 | `chat.status.ServiceStatusManager` |
| 输入 | `chat.input.ChatInputManager` / `chat.input.AttachmentProcessor` |
| 历史 | `chat.history.ChatHistoryController` / `chat.history.ChatHistoryAdapter` |
| 生成生命周期 | `chat.lifecycle.GenerationLifecycleManager` |
| 流式管道 | `chat.streaming.StreamingTokenPipeline` |
| 原生恢复 | `chat.recovery.NativeRecoveryHandler` |
| 对话服务 | `service.AIService` / `chat.ChatMessage` |
| 推理 | `inference.InferenceRouter` / `refactor.AIInferenceCore` |
| 模型 | `model.ModelManager` / `model.ModelRegistry` / `model.OnlineModelManager` |
| 语音 | `speech.SpeechManager` / `speech.SpeechRecognitionService` / `speech.TTSService` |
| GPU | `gpu.GpuAdaptiveTuner` / `gpu.GpuCapabilityDetector` / `gpu.GpuConfig` |
| 上下文 | `refactor.AIConfig` / `optimization.ResourceConfig` |

## 二、界面层（`ui` 包，107 类）

| 子包 | 职责 |
|------|------|
| `ui.activity` | Activity（AIChatActivity 等） |
| `ui.adapter` | 适配器（AttachmentAdapter 等） |
| `ui.fragment` | Fragment |
| `ui.view` | 自定义 View |
| `ui.base` | BaseActivity |

## 三、基础设施层

| 包 | 职责 |
|-----|------|
| `com.oilquiz.app.model` | 数据模型 |
| `com.oilquiz.app.database` | Room 数据库 + DAO |
| `com.oilquiz.app.repository` | 仓库层 |
| `com.oilquiz.app.manager` | 业务管理器 |
| `com.oilquiz.app.viewmodel` | ViewModel |
| `com.oilquiz.app.di` | Hilt 注入 |
| `com.oilquiz.app.resource` | 资源管理 |
| `com.oilquiz.app.util` | 工具集 |
| `com.oilquiz.app.infra` | 基础设施（AppLogger 等） |
| `com.oilquiz.app.webview` | WebView 支持 |

## 四、工具注册表（30 个真实工具）

`DatabaseTool` / `NetworkSearchTool` / `WebPageReaderTool` / `SmartResearchTool` / `MemoryTool` / `FileReaderTool` / `FileAnalyzerTool` / `FileGeneratorTool` / `ExcelTool` / `TextToolsTool` / `CalculatorTool` / `UnitConverterTool` / `TimeDateTool` / `LocationTool` / `ControlLookupTool` / `ToolRegistryTool` / `OCRRecognizeTool` / `ImageGenTool` / `DashscopeMediaTool` / `SpeechSynthesisTool` / `VoiceInputTool` / `VideoToPlayerTool` / `PermissionManagerTool` / `SystemConnectTool` / `SystemResourceTool` / `AppOperationTool` / `WorkspaceTool` / `GetModelsProfileTool` / `UpdateModelsProfileTool` / `LayoutEditorTool`

## 2026-09/10 更新

- **新增模块**：
  - TermuxEnvInstaller / TermuxEnvSetupActivity（一键准备向导：Termux + Ubuntu 容器，内置 Termux/API/Boot APK）
  - EdgeToEdgeHelper（全局 edge-to-edge 适配，Application 生命周期统一接入）
  - LinuxShellTool（内置 Linux 工具箱 + 命令路由）
  - media_toolkit（内置 ffmpeg/ffprobe + Python android_media 接口）
  - RemoteDshTool（手机远程控制电脑，dsh 桥接）
  - StorageWriter（MediaStore 优先的统一存储写入）
  - SystemResourceTool.ssh_exec（JSch 远程 SSH）
- **已删除模块**：com.oilquiz.app.vnc（VNC/图形界面，10/01 整体删除）、内置 SSH 终端页面（10/02 删除）。

## 相关文档

- [项目架构总览](01-project-overview.md)
- [开发规范](09-development-guide.md)
