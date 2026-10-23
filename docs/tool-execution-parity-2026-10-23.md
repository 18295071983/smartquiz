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

## 二、本轮已收口的五条

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
5. **工具结果字段别名表**：`ToolResultFields`（PATH / URL / ARTIFACT / FILE_TYPE / SIZE /
   MESSAGE / ERROR / SUMMARY / COUNT_TOTAL）。迁移前实测的漂移：文件路径三套、摘要三套、
   大小两套、URL 两套、消息两套。已改问表的有 23 处（`ToolResultInterpreter` 22 +
   `DynamicToolResultRenderer` 的产物归类 1），**仍有 79 处 `strDeep` 是就地键清单**
   （多为各工具专属字段与"种类计数"，本来就不该合并）。
   守卫测试 `ToolResultFieldsTest` 把"迁移前各处实际用过的键集合"抄成快照逐个要求覆盖
   （谁精简表项就红），并钉住三条边界：`message` 不进 ERROR、种类计数不并进通用 count、
   `source` 不进 URL（它是来源站点名，当链接渲染会错）。
   自我更正：原盘点把"count 六套"也算漂移，实测多数是**不同的量**
   （rows/words/pages/lines/sources/matches 各有别名），并成一张表反而会显示错数字。
6. **上一条自己引入的回归（同日修掉）**：合并时没区分"键名本身就是这个概念"与
   "含义随工具而变"，把 `output` 当路径别名并进了 `PATH` 与 `ARTIFACT`。
   `output` 在 shell / python / 动态工具里是**标准输出正文**，于是
   `systemResourceTemplate` 把整段 stdout 又打一行"📄 文件："，
   `DynamicToolResultRenderer` 更把 `{name, output:"已保存3条"}` 归成产物文件、
   生成一张 path 指向一段文本的 file_card（真机现象：卡片外观正常、每条链接点开都失败）。
   `target`（落盘目标/跳转地址/ssh 主机）、`name`（工具名/应用名/系列名/文件名）、
   `result`（整段载荷/报告正文）同病。
   修法在**口径**不在卡片，且**集中的单位是"读取处"不是"概念"**：
   每个读取处一张按站点命名的表，逐字照抄 `git show 3d5687b8^` 的原清单（含顺序），
   23 处改为 `SEARCH_COUNT` / `PATH_WITH_NAME` / `FILE_GENERATOR_PATH` /
   `SYSTEM_RESOURCE_FILE` / `SYSTEM_RESOURCE_URL` 等；`ToolResultFields` 删除 `union`
   与全部概念并表（合并正是引入事故的动作）。中间一版"只摘坏键、其余仍问概念表"
   也不行——`file,filePath,path` 被并成 `filePath,path,…,file`、
   `size,bytes_written,file_size` 被并成 `size,file_size,bytes_written`，
   first-hit 语义就写在顺序里，顺序变了就是行为变了。
   唯一保留的放宽是 `DYNAMIC_ARTIFACT`（多认 `file_path,link`，历史三键相对顺序不变）。
   守卫：`ToolResultFieldsTest` 与历史原清单做**数组等值**比对（集合包含抓不到顺序回归），
   外加"内容键只许出现在点名允许它的那一处"正反向断言；
   `ToolResultUiTemplateTest`（4）与 `DynamicToolResultRendererTest` 回归（2）覆盖
   stdout 不当路径、写入类 `output` 仍当路径、文本不生成产物卡。
   **教训**：合并手抄键清单时，"并集覆盖所有历史写法"不是正确性标准——
   全局表会同时把每个读取处的接受面放大到全部工具的键空间；
   而 first-hit 顺序是行为的一部分，必须逐处还原。

## 三、未收口的差异（按建议顺序）

1. **截断预算四个数并存**：A=16K、B=400（天气 1500）、C=6000、
   `TOOL_RESULT_MAX_LENGTH=Integer.MAX_VALUE` + 空壳 `smartTruncate()`（都是死代码）✓。
   同一个 `file_reader` 结果，A 给 16K、B 给 400 字符。需要拍板"模型该看到多少"，
   不是我能自行统一的（涉及 token 预算）。
2. **工具卡状态：等 parts 落地后一起改（已拍定，勿重复盘）**。我原样写"UI 侧三份词表"是错的，
   核完生产侧后是**两个概念共用一个字段名**：
   · `tool_call` 状态只有三个词，唯一写入方是 `AIChatActivity:7114`（`running`）与
     `:7129`（`success`/`failed`）；
   · `done/current/todo` 属于**步骤项**（`StepsCardView:30,152`、`AgentStatusChipCardView:68,139`），
     `PENDING` 属于**执行块**（`ActivityTimeline:33` 的 `Status`，它还接 `BlockStatus`）。
     `ActivityTimeline.parseStatus` 词表最宽，是因为它同时在给这两类归一，不是"第三份工具状态表"。
   真正的问题不是用词不同，是**未知词的默认值四处不一致**：`ToolRowStyle` 与
   `ToolCallCardView` 落"⏳ 执行中"、`ActivityTimeline` 落 `PENDING`、
   `ChatContextBuilder:129-130` 直接**不让该卡片进上下文**。今天不炸（生产侧只有三词），
   炸在加第四个终态时（`timeout`/`cancelled`/provider 的 `incomplete`）——
   用户看到永久转圈，模型那边这次工具结果根本不进上下文，双面静默失灵。
   修法（拍定）：① 抽纯函数 `ToolCallStatus.parse(String)` → `RUNNING/SUCCESS/FAILED/UNKNOWN`，
   **UNKNOWN 显式化**（中性"已结束（状态未知）"，不回落成"执行中"），且 `ChatContextBuilder`
   放行 UNKNOWN；三个消费者各改几行。② 步骤/块那套词从 `ActivityTimeline` 提名成 `StepStatus`，
   别让下一个消费者再手抄 `done/current/todo`。③ **不要**并 `ResponsesStreamParser.Result.status`
   （`completed/incomplete/failed`，response 级概念）——同词不同概念。
   待核清的一点：`ToolCallPartRenderer.normalizeStatus` 注释称"快照兜底路径写的是 `done`"，
   我 grep 不到任何写 `done` 进 `tool_call` props 的路径，该注释可能已过期；
   别把它当"必须兼容 done"的依据抄到下一处，改之前先复核。
3. **字段名多键兜底已收成别名表**：见本节第 5 条；剩余的读取处（另三份 `firstNonEmpty`、
   `DynamicCardView`、`NativeLayoutRenderer`、`NetworkSearchTool`）仍带自己的键清单，
   逐个迁移的风险与收益不同级，见第三节第 3 条。
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
  其中**卡状态那一条已拍定：等 parts 任务落地后一起改**（第三节第 2 条含结论与修法，勿重复盘）。
- 没删 `ToolExecutionOrchestrator`、`smartTruncate`、`TOOL_RESULT_MAX_LENGTH` 这三处死残留。
- 全程未真机验证；`AgentService` 抬头与内容顺序变化影响 native FC 那条路径的模型输入，
  值得设备上跑一次带失败命令的对话确认。
