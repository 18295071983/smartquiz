# AI Agent 架构设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述本地 AI Agent 的真实架构：**MiMo 风格单循环引擎** + **本地/在线双路由**。

## 一、设计核心

Agent 采用 **单循环引擎** 而非多 LLM 编排：

```
旧架构（已废弃）：意图识别 → 任务分解 → 思考链 → 执行 → 结果整合（多次 LLM 调用）
真实架构：        一次 generate → 解析工具调用 → 执行工具 → 追加结果 → 继续... → 最终回复
```

**为什么废弃多 LLM 编排**：多次调用 `LlamaHelper.generate()` 并反复 `chatDestroy()` 会破坏 chat context 的 KV cache，导致后续 `chatSend` 解码时 SIGSEGV 崩溃。单循环在**同一上下文链**上连续 generate，KV cache 增量保留。

## 二、Agent 入口与双路由

### 2.1 AgentChatHandler

**类**: `com.oilquiz.app.ai.chat.AgentChatHandler`

统一入口，负责意图判断 + 本地/在线路由分派 + 回调桥接。

```java
// 本地/在线双路由（真实逻辑）
boolean useLocalAgent = aiConfig.isLocalAgentEnabled()
        && !engine.isOnlineModelActive();   // engine = AgentRouter

if (useLocalAgent) {
    softwareLayer.processMessage(message, enableThinking, history);  // 本地单循环
} else {
    engine.execute(message, maxTokens, enableThinking);              // 在线引擎
}
```

### 2.2 双引擎

| 引擎 | 类 | 协议 | 适用 |
|------|-----|------|------|
| **本地** | `ai.agent.software.AgentSoftwareLayer` + `engine.AgentLoopEngine` | 原生 Function Calling JSON（llama.cpp `common_chat_templates_apply`） | 本地 GGUF |
| **在线** | `ai.agent.AgentRouter` → `ai.agent.online.OnlineAgentEngine` | 原生 function calling + reasoning_content | 在线 API |

## 三、本地单循环引擎

### 3.1 AgentSoftwareLayer

**类**: `ai.agent.software.AgentSoftwareLayer`

软件层入口，管理单线程 executor 与状态（`isProcessing`）。

```java
public class AgentSoftwareLayer {
    private final AIService aiService;
    private final AgentLoopEngine loopEngine;   // 单循环引擎
    private final ExecutorService executor;      // 单线程
    private final AtomicBoolean isProcessing;
}
```

### 3.2 AgentLoopEngine

**类**: `ai.agent.software.engine.AgentLoopEngine`

原生 Function Calling 单循环引擎核心。

#### 关键参数（真实常量）

| 常量 | 值 | 说明 |
|------|-----|------|
| `MAX_ITERATIONS_BASE` | 12 | Agent 总轮次上限（8K 上下文≈8-10 轮完整保留，12 为平衡点） |
| `MAX_TOOL_ROUNDS` | 6 | 工具调用轮次上限 |
| `TOTAL_TIME_BUDGET_MS` | 180000 | 总时间预算（180s） |
| `FINAL_RESPONSE_MAX_TOKENS` | 4000 | 最终回复 token 上限 |
| `MAX_TOOLS_PER_RUN` | 5 | 每轮最多注入工具数 |
| `MAX_TOOL_DESC_CHARS` | 150 | 工具描述截断长度 |
| `MAX_TOOL_RESULT_LENGTH` | 1200 | 工具结果截断长度 |

#### 循环流程

```
每轮:
  generate() → 解析 <tool_call>{"name","arguments"}</tool_call>
    ├─ 有工具调用 → 执行工具 → 追加结果 → 进入下一轮
    └─ 无工具调用 → generateStream() 流式输出最终回复 → 结束
```

`<tool_call>` 解析兼容多形态:
- 单条 `{"name","arguments"}`
- `{"tool_calls":[...]}` 数组（一轮并行多个工具）
- `arguments` 为 JSON 字符串 / `parameters` 别名 / `function.name`（Qwen-Agent fncall 约定）

## 四、动态工具注入

### 4.1 注入策略

```
初始工具集 = 关键词命中 + tool_registry + control_lookup（检索逃生口）
    ↓
activeTools（LinkedHashSet）随模型调用增长
    ↓
模型用到哪个工具 → 注入哪个
```

- **关键词命中**：按用户输入关键词路由到对应工具。
- **tool_registry**：全工具池检索逃生口（长尾工具不常驻 prompt）。
- **control_lookup**：低频 UI 控件参数检索。
- 每轮最多 `MAX_TOOLS_PER_RUN`（5）个工具，描述截断至 `MAX_TOOL_DESC_CHARS`（150 字符）。

## 五、意图识别（SmartIntentRecognizer）

**类**: `ai.agent.SmartIntentRecognizer`

- 用于判断"是否走 Agent"以及工具意图初步分类。
- **重要**：本地 Agent 模式下 LLM 意图识别被关闭（`llmRecognitionEnabled=false`）——因为 `recognizeByLLM` 调用 `LlamaHelper.generate` 会破坏 chat context 的 KV cache 导致崩溃。改为纯规则/关键词判断 + 模型自主 FC 决定是否调用工具。

| 意图 | 说明 | 是否工具 |
|------|------|---------|
| WEATHER / SEARCH / DATABASE / CALCULATOR / OCR / FILE / WEB / TIME | 工具类 | 是 |
| TRANSLATE / CREATIVE / LEARNING / ANALYSIS | 内容生成 | 否 |

## 六、KV 增量缓存（AgentKvCache）

**文件**: `src/main/cpp/agent_kv_cache.h/.cpp`（独立 C++ 模块）

- 三级策略：`INCREMENTAL` / `PARTIAL` / `FULL`。
- **记账** = prompt + 生成输出（已修复"只记 prompt → seqMax 校验必败"的问题）。
- 使用 `llama_memory_seq_rm` 做部分前缀截断。
- 让 Agent 多轮迭代时保留已有 KV，只增量计算新增部分 → 显著提速。

## 七、上下文容量管理

由 `AIConfig.OptimizationMode` 决定（`ai.refactor.AIConfig`）：

| 模式 | id | contextSize | 说明 |
|------|-----|------------|------|
| TURBO | 0 | 4096 | 极速，低内存 |
| BALANCED | 1 | 12288 | 均衡（默认） |
| PERFORMANCE | 2 | 16384 | 性能 |
| ULTIMATE | 3 | 16384 | 极限 |

上限受 `ResourceConfig.MAX_CONTEXT_SIZE`（16384）与设备内存钳制。

## 八、错误/恢复

- 工具失败 → 替代工具映射（`getAlternativeTool`）。
- 原生层崩溃 → `NativeRecoveryHandler` 自动恢复（`autoRecoverNativeState`）。
- 生成超时 → Java watchdog（20s 无心跳判定超时）+ C++ 300s 生成超时。

## 九、真实调用链

```
AgentChatHandler.startAgentLoop(message, ...)
    ↓
├─ useLocalAgent
│   └─ AgentSoftwareLayer.processMessage()
│       └─ AgentLoopEngine（单循环，原生 FC）
│           ├─ generate() → <tool_call> 解析
│           ├─ tool_registry → AIToolManager.execute()
│           ├─ 追加结果 → continue
│           └─ generateStream() → 最终回复
└─ else（在线）
    └─ AgentRouter → OnlineAgentEngine.execute()
```

## 2026-09/10 更新

- **在线引擎对齐 dsh 架构（9/23）**：工具实时注册、动态工具结构化、统计统一（缓存 usage 全字段直读），与 dsh 的 ACP 通道对齐。
- **动态工具注入**：工具集扩充至 30+，新增 linux_shell（内置 Linux 工具箱/命令路由）、ssh_exec（JSch 远程 SSH）、media_toolkit（本地媒体工具箱）、remote_dsh（手机远程控制电脑，dsh 桥接）。
- **本地推理加速（9/24）**：KV 前缀稳定、最小 prompt、核心工具速查常驻（file_reader/workspace）；新增详细 token 日志 + 思考 token UI 显示（Agent 调试）。
- **Agent 工具执行**：shell_command 移除护栏（action=shell_mode 显式开关），命令路由 内置→系统→busybox→toybox 可调。

## 相关文档

- [项目架构总览](01-project-overview.md)
- [AI 对话界面设计](03-ai-chat-ui.md)
- [工具系统设计](05-tool-system.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
