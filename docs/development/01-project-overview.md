# 项目架构总览

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件是设计文档体系的入口，描述项目整体技术栈与模块划分。所有内容依据 git 分支 `feature/agent-local` 的真实源码编写。

## 一、项目定位

**答题宝** 是一个 Android 学习助手应用，核心能力是：
1. **AI 对话助手**：本地（llama.cpp GGUF）+ 在线（OpenAI/千问等）双引擎聊天。
2. **本地 AI Agent**：支持函数调用（Function Calling）的原生 Agent 单循环，可驱动 ~30 个本地工具。
3. **题库管理**：Excel/CSV 导入、题库结构化管理、错题复习、练习与测验。
4. **多模型管理**：本地模型下载/导入/切换，在线模型 API 配置，优化模式（上下文容量）管理。
5. **多模态**：图片、附件、OCR、语音输入/合成。

## 二、技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java（Android） |
| UI | AndroidX + Material3 + RecyclerView + ViewBinding/FindViewById |
| 注入 | Dagger Hilt |
| 异步 | LiveData / ViewModel / ExecutorService / CompletableFuture |
| 本地 LLM | llama.cpp (JNI) + GGUF 模型 |
| 加速 | NPU(Hexagon/HTP，默认) + OpenCL + Vulkan 四后端、batch warmup、flash attention |
| 数据库 | Room（题库/用量）+ SQLite |
| 网络 | Retrofit/OkHttp（在线 API） |
| JSON | org.json / Gson |
| 语音 | SpeechManager（ASR/TTS）+ MediaRecorder |

## 三、顶层模块（`com.oilquiz.app.*`）

```
com.oilquiz.app
├── MainActivity        应用入口（LAUNCHER）
├── ui                  界面层（activity / view / adapter / fragment）
├── ai                  AI 能力层（核心，见下）
├── model              数据模型
├── database           Room 数据库
├── repository         仓库层
├── manager            业务管理器
├── viewmodel          ViewModel
├── di                 Hilt 依赖注入模块
├── resource           资源（AppResourceManager/Permission 等）
├── util / toolkit     工具集
├── infra              基础设施（AppLogger 等）
├── webview            WebView 支持
└── weather            天气
```

## 四、AI 能力层（`com.oilquiz.app.ai`）

`ai` 包是本应用的核心，按职责划分为多个子模块：

```
com.oilquiz.app.ai
├── agent       Agent 引擎（单循环/工具/意图/在线）
│   ├── software   AgentSoftwareLayer + AgentLoopEngine（本地 MiMo 单循环）
│   ├── online     OnlineAgentEngine（在线 function calling）
│   └── ui         Agent 增强
├── chat        AI 对话（协调器/ViewModel/模块/渲染）
│   ├── coordination  AIChatCoordinator
│   ├── viewmodel     AIChatViewModel（状态机 LiveData）
│   ├── input         输入/附件管理
│   ├── status        服务状态栏
│   ├── history       历史抽屉
│   ├── lifecycle     生成生命周期
│   ├── streaming     流式 Token 管道
│   ├── component     AI 卡片组件（提示/代码/表格/图表等）
│   ├── render        渲染（Markdown/Math/Mermaid/HTML）
│   └── recovery      原生层恢复
├── service    AI 服务（AIService/AgentService/等）
├── inference  推理路由（InferenceRouter）
├── tool       工具系统（AIToolManager + ~30 个 AITool）
├── model      模型管理（ModelManager/ModelRegistry/OnlineModelManager）
├── jni        JNI（LlamaHelper 等）
├── inference  推理
├── gpu        GPU 加速（OpenCL/Vulkan/调优）
├── speech     语音（ASR/TTS）
├── config     配置（AppConfigManager 等）
├── refactor   推理核心（AIInferenceCore/AIConfig/CacheManager）
├── stats      统计（TokenStatsManager）
├── usage      用量/计费
├── importing  数据导入
├── repair     题目修复引擎
└── python     Python 工具
```

## 五、核心数据流

### 5.1 AI 对话链路

```
用户输入 → AIChatActivity（装配薄壳）
    ↓
AgentChatHandler.startAgentLoop()
    ↓ 双路由
├─ 本地: AgentSoftwareLayer → AgentLoopEngine（原生 FC JSON）
└─ 在线: AgentRouter → OnlineAgentEngine
    ↓
AIChatViewModel / StreamingTokenPipeline（流式）
    ↓
ChatAdapter（Markdown/组件渲染）→ 用户可见回复
```

### 5.2 工具链路（本地 Agent）

```
模型生成 <tool_call>{name,arguments}</tool_call>
    ↓
AgentLoopEngine 解析 → tool_registry 匹配工具（动态按需注入）
    ↓
参数校验 → 权限检查 → BaseAITool.execute()
    ↓
AIToolResult 回填 → 下一轮循环
```

## 六、关键设计原则

1. **Activity 是薄壳**：`AIChatActivity` 只做装配（`initModules()`），业务逻辑全部委托给独立模块类。
2. **Agent 单循环**：不再做"意图→分解→思考→整合"的多轮 LLM 编排（会破坏 KV cache 导致崩溃），改为"一次生成→解析工具调用→执行→追加→继续"。
3. **本地/在线双引擎**：本地走 llama.cpp 原生 FC，在线走 API 原生 function calling，统一由 AgentChatHandler 路由。
4. **动态工具注入**：初始只注入核心工具，模型用到哪个注入哪个，长尾走 tool_registry。
5. **KV 增量缓存**：`AgentKvCache` 独立 C++ 文件，三级策略，避免重复计算前缀。

## 七、相关设计文档

- [AI Agent 架构设计](02-ai-agent-architecture.md)
- [AI 对话界面设计](03-ai-chat-ui.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
- [工具系统设计](05-tool-system.md)
- [数据层设计](06-data-layer.md)
- [硬件与性能](07-hardware-performance.md)
- [模块清单](08-module-inventory.md)
- [开发规范](09-development-guide.md)
