# 工具系统设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述 AI 工具系统：AIToolManager、AITool 接口、BaseAITool 抽象类，以及 37 个实现 `AITool` 接口的工具类（含抽象骨架与非对话型工具）。

## 一、工具系统架构

```
AgentLoopEngine（解析 <tool_call>）
    ↓
AIToolManager（匹配/执行/动态注入）
    ↓
AITool 接口 → BaseAITool 抽象类 → 各具体工具
```

## 二、核心接口

### 2.1 AIToolManager

**类**: `com.oilquiz.app.ai.tool.AIToolManager`

工具注册、匹配、执行、动态注入中心。

| 方法 | 说明 |
|------|------|
| `getTools()` | 获取全部工具列表 |
| `getToolsMap()` | 获取工具名→工具映射 |
| `getTool(String name)` | 按名取工具 |
| `executeTool(String toolName, Map<String,Object> params)` | 执行工具 |
| `getToolDescriptions()` | 获取工具描述（用于 FC prompt） |
| `registerDynamicTool(AITool tool)` | 注册动态工具 |

### 2.2 AITool 接口

```java
public interface AITool {
    String getName();
    String getDescription();                          // 工具描述
    AIToolResult execute(Map<String, Object> parameters);  // 执行
    Map<String, String> getParameterDescriptions();
    // 可选（default 方法，返回 null/0 表示走通用逻辑）
    default Map<String, Object> getOutputSchema() { return null; }
    default Map<String, Object> presentCall(Map<String, Object> args) { return null; }
    default Map<String, Object> presentResult(Map<String, Object> args, AIToolResult r) { return null; }
    /** 本工具单次执行需要的超时（毫秒）；0 = 用 OnlineToolManager 的默认值 */
    default long executionTimeoutMs(Map<String, Object> args) { return 0L; }
}
```

**关于超时（2026-09-27 新增，实测驱动）**：托管调用方 `OnlineToolManager` 对所有工具套了 30s 默认超时
（`ui_component.get_result`/`permission_manager`/`dashscope_media`/`knowledge_base.import_*` 等另有放宽）。
**耗时不可控的工具必须自己声明** `executionTimeoutMs`，调用方取"默认值与声明值的较大者"（硬上限 11 分钟），
否则会出现"工具自己以为还有时间、调用方已经把它掐了"。踩过的实例：`remote_dsh` 在电脑上真跑任务，
手机端传 `timeout=150` 仍在 30s 被杀，且超时后模型只拿到空结果 → 只能盲目重试。
超时失败时调用方会返回可诊断的错误（等了多久、任务可能仍在后台），不再返回空消息。

### 2.3 BaseAITool 抽象类

**类**: `com.oilquiz.app.ai.tool.BaseAITool` implements `AITool`

提供参数校验、权限检查、结果格式化等通用逻辑。

## 三、真实工具清单

### 3.1 数据/知识类

| 工具 | 类 | 功能 |
|------|-----|------|
| DatabaseTool | `ai.tool.DatabaseTool` | 查询本地题库 |
| NetworkSearchTool | `ai.tool.NetworkSearchTool` | 联网搜索 |
| WebPageReaderTool | `ai.tool.WebPageReaderTool` | 网页阅读 |
| SmartResearchTool | `ai.tool.SmartResearchTool` | 多源研究整合 |
| MemoryTool | `ai.tool.MemoryTool` | 记忆 |

### 3.2 文件类

| 工具 | 类 | 功能 |
|------|-----|------|
| FileReaderTool | `ai.tool.FileReaderTool` | 读取文件 |
| FileAnalyzerTool | `ai.tool.FileAnalyzerTool` | 分析文件 |
| FileGeneratorTool | `ai.tool.FileGeneratorTool` | 生成文件 |
| ExcelTool | `ai.tool.ExcelTool` | Excel 处理 |
| TextToolsTool | `ai.tool.TextToolsTool` | 文本处理 |

### 3.3 计算/工具类

| 工具 | 类 | 功能 |
|------|-----|------|
| CalculatorTool | `ai.tool.CalculatorTool` | 数学计算 |
| UnitConverterTool | `ai.tool.UnitConverterTool` | 单位换算 |
| TimeDateTool | `ai.tool.TimeDateTool` | 时间/日期 |
| LocationTool | `ai.tool.LocationTool` | 位置 |
| ControlLookupTool | `ai.tool.ControlLookupTool` | UI 控件参数检索 |
| ToolRegistryTool | `ai.tool.ToolRegistryTool` | 工具注册表 |

### 3.4 多模态类

| 工具 | 类 | 功能 |
|------|-----|------|
| OCRRecognizeTool | `ai.tool.OCRRecognizeTool` | 图片文字识别 |
| ImageGenTool | `ai.tool.ImageGenTool` | 图片生成 |
| DashscopeMediaTool | `ai.tool.DashscopeMediaTool` | DashScope 媒体 |
| SpeechSynthesisTool | `ai.tool.SpeechSynthesisTool` | 语音合成 |
| VoiceInputTool | `ai.tool.VoiceInputTool` | 语音输入 |
| VideoToPlayerTool | `ai.tool.VideoToPlayerTool` | 视频转播放 |

### 3.5 系统/权限类

| 工具 | 类 | 功能 |
|------|-----|------|
| PermissionManagerTool | `ai.tool.PermissionManagerTool` | 权限管理 |
| SystemConnectTool | `ai.tool.SystemConnectTool` | 系统连接 |
| SystemResourceTool | `ai.tool.SystemResourceTool` | 系统资源（open_app/shell_command/termux_exec/ssh_exec/read_setting 等，见下） |
| AppOperationTool | `ai.tool.AppOperationTool` | 应用操作 |
| WorkspaceTool | `ai.tool.WorkspaceTool` | 工作区 |
| GetModelsProfileTool / UpdateModelsProfileTool | `ai.tool.*` | 模型配置获取/更新 |

## 四、工具执行流程

```
Agent 引擎解析 <tool_call>{"name","arguments"}
    ↓
tool_registry 匹配工具（动态按需注入，每轮最多 MAX_TOOLS_PER_RUN=5）
    ↓
参数校验（BaseAITool.validate）
    ↓
权限检查（PermissionManagerTool）
    ↓
BaseAITool.execute(params)
    ↓
AIToolResult（回填 Agent 上下文）
```

## 五、动态注入策略

```
初始注入 = 关键词命中 + tool_registry + control_lookup
    ↓
activeTools（LinkedHashSet）随模型使用增长
    ↓
每轮 prompt 只注入核心工具（MAX_TOOLS_PER_RUN=5），工具描述截断至 150 字符
    ↓
长尾工具走 tool_registry 检索
```

## 六、工具参数说明（openai 协议）

`ai.tool.openai` 下定义了与 OpenAI Tool Calling 兼容的参数结构：
- `ToolDefinition` — 工具定义
- `ToolCall` — 工具调用
- `ParamDefinition` — 参数定义

参数 schema 通过 `ToolSchemaExtractor` 从工具注解（`@Tool`/`@Param`/`@Action`/`@Dependency`）提取。

## 相关文档

- [AI Agent 架构设计](02-ai-agent-architecture.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
- [模块清单](08-module-inventory.md)
