# 工具执行路径并行口径清单（2026-10-23）

盘点方法：以 `AIToolResult` 的两条通道为轴（`getResult()` 发模型、`getAdditionalInfo()` 发卡片），
穷举所有会真正执行工具的入口，逐项比较超时 / 重试 / 缓存 / 截断 / 校验 / 成败判定。
**每条结论标注核实状态**：`✓` = 我读到 file:line 实现；`△` = 子代理报告、我未逐行复核。

## 一、执行入口清单

| 入口 | 位置 | 超时 | 重试 | 缓存 | 结果截断 | 校验/别名/依赖 |
|---|---|---|---|---|---|---|
| A 在线 Agent | `ai/agent/online/OnlineToolManager.java` | 基线 30s、交互 120s、知识库导入 180s，读工具自声明 ✓ | `MAX_RETRY=2` ✓；非只读超时**不重试** ✓ | 只读幂等才缓存 ✓；键 `toolName+arguments` ✓；TTL 5min ✓ | **16K** + "...(已截断)" ✓ | 仅 parseArguments；别名靠底座 |
| B 对话页 native FC | `ai/service/AgentService.java`（由 `ai/bridge/ModelExecutionBridge.java:1277,1482` 驱动 C++ 解析出的 tool_calls） | 基线 30s、`get_result` 120s，读工具自声明 ✓ | 2 次 ✓；有副作用超时**不重试** ✓（本轮改） | 副作用判据同 A ✓（本轮改）；键 `call.name+arguments`；TTL 300s（与 A 数值相同） | **400 字符**（天气 1500）✓ | `resolveToolName` + `validateParams` + 依赖预调用 ✓ |
| C 本地软件 Agent | `ai/agent/software/engine/AgentLoopEngine.java` | **15s**，**不读** `executionTimeoutMs` ✓ | `MAX_RETRIES=1`，同参重跑，仅排除 `network_search`，**不区分副作用** ✓ | 无 ✓ | **6000** ✓ | 只解析参数 JSON；别名走自己的 fuzzy ✓ |
| D 导入用 v2 小路径 | `ai/importing/v2/ImportToolManager.java` | 无 ✓ | 熔断：单会话最多 2 次调用 ✓ | 无 | 无 | 3 个工具自有实现，不经 `AIToolManager` ✓ |

裸调用点（**绕过以上全部策略**）：`UiInteractor.java:91,278`、`AgentToolView.java:250`、
`DynamicToolExecutor.java:645`（call_tool 嵌套执行**现已带超时**，见第二节第 4 条）、
`infra/DebugLayoutInjectActivity`、`MediaGenActivity`、`AIImportActivity`、
`ChatSpeechInputController`、`DynamicToolManagerTool:463`、`AIChatActivity` 多处 `new Thread` ✓。
这些点仍没有缓存/重试/截断策略，只有嵌套执行补上了超时。

死副本：`ai/agent/ToolExecutionOrchestrator.java` —— 全仓库零引用（我 grep 复核），
其类注释自证"抽取了一半未接线" ✓。按既定规矩保留不动，等拍板。

## 二、本轮已收口的四条

1. **超时判定**：`ToolTimeouts.effectiveMs(tool, args, base)`，A/B 共用，硬上限 660s 只剩一份。
2. **副作用判据**：`ToolSideEffects`（只读/幂等白名单，白名单而非黑名单）。B 路径原来是
   "除 `ui_component` 外全部缓存 300s + 任何超时一律重试 2 次" → 同参数二次调用被静默跳过、
   副作用被执行两遍，都属"没跑说跑了 / 跑了说没跑"。
3. **`status` 词表与失败内容**：`ToolStatusVocabulary` 统一发给模型的标签；B 路径的失败分支
   原来只打印 `error/error_message/message` 就 return，`linux_shell` 非零退出（只有
   `output`/`exit_code`/`hint`）在这条路径上变成一句 `[工具执行失败]` 后面空无一物。
   现在成功失败都全量 dump、按 `error→stderr→output→exit_code→hint` 排序，
   且**认不出的 status 不再回落成"成功"**（旧代码 `isSuccess = true` 默认值会说谎）。
4. **嵌套 `call_tool` 超时**：`DynamicToolExecutor.runNestedWithTimeout` 按
   `ToolTimeouts.effectiveMs` 取预算并压到 60s 上限——这条路径以前完全没有超时。
   两个 ThreadLocal 必须显式带进工作线程，否则"挪个线程"就等于弄丢两道保护：
   `CALL_DEPTH`（不传就每层重置成 0，A→B→A 递归失去 `MAX_CALL_DEPTH` 上限）与卡片抑制
   （`ToolResultCardBinder.SUPPRESS_COUNT` 同为 ThreadLocal，不传就会给子调用挂卡）。
   线程池线程会被复用，退出时是 `remove()` 而不是只设置。
   顺带修一条静默说谎：旧实现在子调用 `isSuccess()==false` 时因为 `getResult()` 为 null
   而回落到字符串"工具执行完成"，嵌套失败被报成完成。

## 三、未收口的差异（按建议顺序）

1. **截断预算四个数并存**：A=16K、B=400（天气 1500）、C=6000、
   `TOOL_RESULT_MAX_LENGTH=Integer.MAX_VALUE` + 空壳 `smartTruncate()`（都是死代码）✓。
   同一个 `file_reader` 结果，A 给 16K、B 给 400 字符。需要拍板"模型该看到多少"，
   不是我能自行统一的（涉及 token 预算）。
2. **UI 侧还有三份 status 词表**：`chat/ui/ToolRowStyle.java:25-40`、
   `chat/component/ToolCallCardView.java:44,72-84` + `chat/ActivityTimeline.java:302-307`、
   `chat/parts/ToolCallPartRenderer.java:110-135`（归一层）✓。
   `chat/context/ChatContextBuilder.java:129-130` 只让 `success`/`failed` 两种卡片进上下文 ✓。
   统一要动渲染层，风险与本轮不同级，建议单独一轮。
3. **字段名多键兜底各写一套**：`ToolResultInterpreter.strDeep` 一份实现约 150 处调用，
   另有 6 份 `firstNonEmpty`（`DynamicToolResultRenderer`、`ToolResultCardBinder`、
   `DynamicCardView`、`NativeLayoutRenderer`、`NetworkSearchTool`、`ComponentDisplay`）△。
   同一概念的键名集合互不相同（文件路径、error、url、count 至少四例）。
   这解释了两件事：为什么卡片"有时直接吃原始 JSON"，以及为什么新工具加字段要到处补键名。
4. **二次摘要阈值 10 份左右**：`chat/util/ToolResultText` 2000/8 行、
   `ChatContextBuilder` 6000/2500/1200/300/512、`ToolCallCardView` 50、
   `DynamicCardView` 4000、`ToolResultCardBinder` 调用处传 60/120/160/200/400 字面量、
   `ActivityTimeline` 与 `ChatAdapter` 各有一份 48 ✓/△。
5. **裸调用点的其余策略**：`DynamicToolExecutor` 的超时已补（第二节第 4 条），但
   `UiInteractor`、`AgentToolView`、`MediaGenActivity`、`AIImportActivity`、
   `DebugLayoutInjectActivity`、`DynamicToolManagerTool:463` 这些直接
   `tool.execute(...)` 的点仍然没有超时/缓存/截断，也不申报 `executionTimeoutMs`。
   它们多数是 UI 触发或调试入口（不在模型轮次里），要不要统一取决于你是否希望
   "任何工具调用都受同一套约束"——这条需要你拍板，我不自行扩面。

## 四、我明确没做的

- 没改 A/B/C 任何一条路径的重试次数与基线超时数值（只统一了判定方式）。
- 没动 UI 词表、没动字段名兜底集合、没动截断预算 —— 都需要你先定口径。
- 没删 `ToolExecutionOrchestrator`、`smartTruncate`、`TOOL_RESULT_MAX_LENGTH` 这三处死残留。
- 全程未真机验证；`AgentService` 抬头与内容顺序变化影响 native FC 那条路径的模型输入，
  值得设备上跑一次带失败命令的对话确认。
