# 推理/生成状态文案（多套备用 + 按进度切换）设计记录

2026-10-25。用户反馈："思考中推理状态你可以设计多套备用文案，按照推理进度及生成进度切换可以吗。
防止用户等不及，你看看业界是怎么设计这套文案的"。

## 1. 业界怎么设计（调研结论）

| 来源 | 做法 | 对我们的启示 |
|---|---|---|
| [NN/g：Response Times: The 3 Important Limits](https://www.nngroup.com/articles/response-times-3-important-limits/) | 1s 内不必特别反馈；超过 1s 要让用户知道"在工作"；超过 10s 必须有持续进度反馈 + 明确的中断入口。工作量未知时，无法百分比就退化为"绝对工作量反馈"或通用忙碌指示（点/转圈/状态行） | 推理耗时不可预测 → 用"阶段 + 已耗时"做推进感；我们有停止按钮 = 中断入口；t/s 就是"绝对工作量反馈" |
| [Perplexity 设计研究](https://blakecrosley.com/guides/design/perplexity)（PhaseIndicator：`Searching the web…` → `Reading sources…` → `Writing answer…`） | "Show the process, not just results"：把等待拆成用户能理解的阶段，逐段换文案 | 我们同构：等待响应 → 思考（理解/梳理/推敲/验证/收敛）→ 生成（组织/撰写/补充/收尾） |
| [Ant Design X：生成过程确认规范](https://x.ant.design/docs/spec/confirm-generation-process) | 生成过程要"可感知、可中断、可确认"，随阶段给出不同状态提示 | 状态行常驻在输入框上方；停止按钮同排可用 |
| ChatGPT / Claude 的 thinking 模式（业界通行做法；如 [windmill PR 把 thinking 折叠成带 `thought for` 时长的状态行](https://github.com/windmill-labs/windmill/pull/10515)） | 生成中显示 Thinking/Working 状态；结束后折叠成 "Thought for Xs" | 我们的"已深度思考 X 秒"就是同一模式；活动态才挂动态文案，结束回完成态 |

结论：**不编假百分比**，用"阶段 + 阶段内耗时 + 实时速度"三类真实信号；
文案拆成多段（每段 ≤10 个汉字，保证一行放得下"文案 + 速度 + 余额"）；
只在当前阶段内推进，阶段切换重置；结束回到"已深度思考 X 秒"。

## 2. 我们的文案表

实现：`ai/chat/InferenceStatusPhrases#phrase(phase, phaseElapsedMs)`（纯函数、可单测）。
底部生成状态行与气泡内「深度思考」折叠头共用它，保证同屏同词。

### 等待响应（已发出，等首个 token；同时覆盖初始化/加载模型/预处理）

| 本阶段耗时 | 文案 |
|---|---|
| 0–3s | 等待模型响应… |
| 3–8s | 正在连接模型… |
| 8–20s | 响应较慢，仍在等待… |
| ≥20s | 仍在等待响应… |

### 思考中（reasoning tokens 流式阶段）

| 本阶段耗时 | 文案 |
|---|---|
| 0–4s | 正在理解问题… |
| 4–10s | 正在梳理思路… |
| 10–20s | 正在推敲关键点… |
| 20–35s | 正在验证推理… |
| 35–60s | 正在收敛结论… |
| ≥60s | 思考较久，仍在继续… |

### 生成中（正文 tokens 流式阶段）

| 本阶段耗时 | 文案 |
|---|---|
| 0–3s | 正在组织回答… |
| 3–10s | 正在撰写回答… |
| 10–25s | 正在补充细节… |
| ≥25s | 正在收尾… |

### 结束

- 底部状态行整行收起（回到空闲）；
- 气泡内思考块折叠头回到 **"已深度思考 X 秒"**；
- 多个思考块只有当前活跃块用动态文案（parts 路径由 `AssistantTurn#markLiveStreaming`
  只给 live 轮打 streaming；legacy 只对最后一组生效）。

## 3. 实现要点

- `ChatMessage.InferenceProgress#phaseStartTime`（transient）：当前阶段开始时刻，
  `markStreamingPhase` 阶段变化时重置；渲染侧按 `now - phaseStartTime` 选文案。
- 底部状态行：`AIChatActivity#statusPhraseTicker` 每 1.2s 推进一次；
  `markStreamingPhase` 立即写首句；结束停心跳并清空阶段词表。
- 思考块折叠头：`ReasoningPartRenderer#stateLabel` 与 `ChatAdapter#activeThinkingPhrase`
  （legacy 路径）用同一函数 + `phaseStartTime` 计算；文案变化时 Activity 用
  `PAYLOAD_STATUS_UPDATE` 重绑流式那一条，让折叠头跟着换（1.2s 节奏，不逐 token）。
- 速度仍由 `updateStreamingTokenStats` / `noteReasoningTokenForSpeed` 单独刷新，
  不参与文案选择（避免每个 token 改文案）。

## 4. 验证

- `InferenceStatusPhrasesTest` 5 条覆盖三个阶段的阈值、等待类阶段共用、结束态返回 null；
  `InferenceStatusLabelTest` 5 条保留（短标签回退/活跃判定）。
- 全量 `testDebugUnitTest --offline --no-build-cache` **959 / 0 skipped / 0 failures / 0 errors**；
  `assembleDebug --offline` 通过。按用户要求未做真机测试。

## 5. 工具侧「思考」文案（2026-10-25 追加）

用户："工具侧思考指纹是否可以加，文案啊"。工具执行阶段（工具卡/工具组）同样需要随耗时推进的文案；
但**文案必须进指纹**，否则视图会停在绑定那一刻的旧句子（TurnPartsHost 按 `Part.fingerprint()` 早退）。

### 文案表

| 工具已耗时 | 通用文案（GENERIC） |
|---|---|
| 0–3s | 正在调用工具… |
| 3–10s | 工具执行中… |
| 10–30s | 工具耗时较长，仍在执行… |
| ≥30s | 仍在等待工具结果… |

### 指纹与刷新链路

1. `ChatMessage.runningToolStartTime`（transient）：本轮第一个工具开始时刻；
2. `AIChatActivity` 1.2s 文案心跳里检测"还有工具在跑"：
   - parts：流式消息 `components` 里 `tool_call` 的 `props.status == running`；
   - legacy：最近 TOOL_CALL 消息的 `toolCallInfo.status == RUNNING`；
   首次检测到记起点并同步到流式消息，工具全部结束清 0；
3. `AssistantTurn.fromMessageForStreaming` 为 running 的工具组算 `Part.statusText`；
   **`statusText` 参与 `Part.fingerprint()`** → 文案推进时指纹变化 → 重绑工具卡；
4. `ToolGroupPartRenderer` 运行中显示 `⏳ + statusText`（无字段退回"N 个工具执行中"）；
   legacy `ChatAdapter` 工具卡同样用 `toolPhrase`；
5. 底部生成状态行：有工具在跑时优先显示工具侧文案、速度位暂时收起；工具结束回到推理/生成文案。

### 验证

`InferenceStatusPhrasesTest` 覆盖工具文案 4 档；全量 960 / 0 skipped / 0 failures / 0 errors。
## 6. 引擎侧活动总线 + 分类文案（2026-10-25 追加）

用户："你看看还有哪些指纹需要文案，一起弄上。多样化，比如正在生成文件，引擎侧是否可以提供基础设施"。

### 6.1 引擎侧基础设施

- `ai/chat/status/AgentActivityBus`：工具执行统一收口 `AIToolManager#executeTool` 外层包一层——
  开始 `started(toolName)` 发布、`finally finished(toolName)` 清理，异常/提前返回也不会脏；
  快照 = 分类 + 开始时刻 + 入队序号（并发工具取**最早开始**的那个，毫秒相同也稳定）。
- `ai/chat/status/AgentActivityKind`：工具名 → 活动分类的唯一口径（引擎/UI 共用）：
  `SEARCH / READ_FILE / GENERATE_FILE / GENERATE_IMAGE / GENERATE_CHART / RUN_CODE /
  DOWNLOAD / QUERY_DATA / GENERIC`。
  顺序有讲究：先判"下载"再判"读文件"（`download_file` 含 `load_file`），
  先判"读文件"再判"生成文件"（`pdf_read` 归读）；由单测钉住。
- UI 不再逐 tick 猜工具名：文案心跳读总线快照，按 `分类 + 已耗时` 选句；
  总线为空时退回 components/TOOL_CALL 状态扫描（旧路径兜底）。
- 每轮生成开始/结束 `AgentActivityBus.clear()` 兜底。

### 6.2 多样化文案（按分类）

| 分类 | 早期（<10s） | 较久（≥10s） |
|---|---|---|
| SEARCH | 正在联网搜索… | 搜索较久，仍在检索… |
| READ_FILE | 正在读取文件… | 文件读取较久，仍在继续… |
| **GENERATE_FILE** | **正在生成文件…** | **文件较大，仍在生成…** |
| GENERATE_IMAGE | 正在生成图片… | 图片生成较久，仍在继续… |
| GENERATE_CHART | 正在生成图表… | 图表绘制较久，仍在继续… |
| RUN_CODE | 正在运行代码… | 代码执行较久，仍在运行… |
| DOWNLOAD | 正在下载文件… | 下载较慢，仍在继续… |
| QUERY_DATA | 正在查询数据… | 查询较久，仍在进行… |
| GENERIC | 正在调用工具… → 工具执行中… → 工具耗时较长… → 仍在等待工具结果… | 同左（四段） |

### 6.3 指纹审计（"还有哪些指纹需要文案"）

| 指纹 | 是否承载状态文案 | 结论 |
|---|---|---|
| `Part.fingerprint()`（parts 增量引擎） | 是 | 已加入 `statusText`：覆盖 reasoning 折叠头、tool_group、**单个 tool_call**；文案推进 → 指纹变化 → 重绑 |
| `thinkingFingerprint`（legacy 思考行） | 否（header 每次 bind 都重写） | 不塞文案（塞了只会无意义重建）；由 1.2s payload 通知重绑刷新 |
| `toolCardFingerprint`（legacy 工具卡） | 否（header 每次 bind 都重写） | 同上；工具卡状态文案来自 `message.runningToolStartTime + runningToolKind` |
| `agentSummaryFp` | 否 | 汇总卡内容型指纹，状态文案不参与 |
| `componentSegmentsFingerprint` | 否 | 组件内容 digest；产物卡（文件/图片）完成才出现，运行中状态在工具卡/工具组 |
| `roundStructureFingerprint` | 否 | 结构指纹（正文段/产物/流式态），不承载文案 |

### 6.4 展示位置

- parts：`ToolGroupPartRenderer` 标题 = `⏳ + statusText`（多工具）；
  `ToolCallPartRenderer` 把 `statusText` 透传进 `tool_call` props，
  `ToolCallCardView` 运行中状态位显示 `⏳ 正在生成文件…`；
- legacy：`ChatAdapter` 工具卡标题用同一个 `toolPhrase(kind, elapsed)`；
- 底部生成状态行：工具运行期间优先显示分类文案、速度位收起；工具结束回到推理/生成文案。

### 6.5 验证

`AgentActivityKindTest` 5 条（分类、文案多样化、通用四段、短名还原、总线生命周期）；
全量 965 / 0 skipped / 0 failures / 0 errors；APK 07:16:36 已装机，未做真机测试。
## 7. 工具中文别名 + "正在干什么"追加（2026-10-25 追加）

用户："工具名是否可以有文案别名啊…一堆洋文有时候会感到困惑…还有是否可以追加，比如正在干什么。
引擎侧基础设施不够的话可以追加设计啊。还有就是没有在文案里的工具名，是否就是通用工具？"

### 7.1 别名（中文名）三层口径

1. **登记表**：`ToolMetaRegistry`（已有约 60 条：`python_execute → Python 执行`、
   `network_search → 网络搜索`、`excel_tool → Excel 处理` …）；
2. **分类兜底**：表里没有的工具 → `AgentActivityKind#displayLabel`
   （搜索工具 / 文件读取工具 / 文件生成工具 / 图片生成工具 / 图表生成工具 / 代码执行工具 /
   下载工具 / 数据查询工具 / 自定义工具）；**不再**输出 `Foo Bar` 这种英文；
3. **使用点**：`ToolCallCardView`（单工具卡名字）、`ActivityTimeline`（工具行标题）、
   `ChatMessage.ToolCallInfo.toolDisplayName` 都走同一个 `ToolMetaRegistry`，没有第二套口径。

### 7.2 "正在干什么"（目标追加）

- 引擎侧：`AIToolManager.executeTool` 把工具参数一并发布到 `AgentActivityBus`
  （`started(toolName, parameters)`）；快照新增 `displayName` + `target`；
- `target` 提取：优先取 `file_name/filename/path/file_path/output/title/name/query/q/url/keyword/text`，
  去目录只留文件名、去协议、最长 18 字（避免把整段 JSON 甩到状态行）；
- 文案组装：`InferenceStatusPhrases.toolPhrase(kind, elapsed, target)` →
  `正在生成文件 · 季度报告.docx`（有目标时去掉句尾省略号）；没有目标时保持
  `正在生成文件…`；
- 展示位置：底部生成状态行、工具组标题（`⏳ + statusText`）、单工具卡状态位
  （`⏳ 正在生成文件 · 季度报告.docx`）共用同一句；legacy 工具卡标题同口径。

### 7.3 未知工具 = 通用工具吗？

是：分类落到 `AgentActivityKind.GENERIC`，文案走通用四段
（`正在调用工具… → 工具执行中… → 工具耗时较长… → 仍在等待工具结果…`），
卡片名显示兜底中文标签（如"自定义工具"）。不会出现空文案，也不会露出内部下划线名/洋文。

### 7.4 验证

`ToolMetaRegistryTest`（未知工具中文兜底）、`AgentActivityKindTest`（快照 displayName/target、
文案追加）覆盖；全量 967 / 0 skipped / 0 failures / 0 errors；APK 07:21:26 已装机，未做真机测试。