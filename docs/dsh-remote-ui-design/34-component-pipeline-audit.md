# 34 · 组件管线审计与合并（2026-11-14）

> 用户口径：「看看还缺哪些消息类型，哪些没覆盖就新增消息类型、视图类型和组件管线。
> 我 app 还设计了内置组件、动态组件、工具组件 ui，你要调查好，把那些你自定义的组件管线能接的都接上。
> 不要造轮子，重复的 dsh 组件管线删了接到现有的组件上来，可以扩展功能；
> 有些事件是 dsh 独有的就单个依次创建原生组件，实在不好创建的用动态组件。」

## 1. 现有分层（先摸清，再动手）

| 层 | 实现 | 数量 |
|---|---|---|
| 消息类型 | `ChatMessage.MessageType`：USER / AI / SYSTEM / THINKING / TOOL_CALL / TOOL_RESULT / AGENT_SUMMARY / ERROR / **REMOTE_CARD** | 9 |
| 视图类型 | `ChatAdapter.VIEW_TYPE_*`：USER / AI / SYSTEM / THINKING / TOOL_CALL / ERROR / CONTEXT_INJECTION / SYSTEM_PROMPT / **REMOTE_CARD** | 9 |
| 内置组件 | `ComponentRegistry` 注册的 `ChatComponent` | 39 |
| 动态组件 | `DynamicCardView`（`getType()="*dynamic*"`，通配兜底）：标题 + content/items/actions，其余字段收进「查看原始 JSON」折叠区 | 1（兜底） |
| 工具组件 UI | `ToolCallCardView`（`tool_call`）+ `ToolActivityRowView` / `ToolGroupPartRenderer`（工具组折叠行） | 2 |
| 别名归一 | `ComponentTypes.resolve`：`alert`→`alert_card`、`steps`→`steps_card`、`timeline`→`steps`、`markdown`→`markdown_card`… | — |

**结论一：事件不需要新增消息/视图类型。** 消息类型表达的是"这条是用户/AI/系统/工具/远程卡"，
DSH 的事件（job、command、todo、subagent、审批…）都是"AI 回合内部的卡片"，
正确落点是**组件管线**（`ComponentData` + `ComponentRegistry`），不是新 MessageType。
唯一例外是 `REMOTE_CARD` —— 它本来就是给"远程 view 片段"准备的，我们暂未用到。

## 2. 合并：DSH 专用渲染 → 现有组件

| 原 DSH 卡 | 现在的落点 | 做法 |
|---|---|---|
| `dsh_notice`（生命周期提示：目标/压缩/重试/子代理/钩子/插话/模型切换…） | **`alert_card`** | `DshCards` 里 30 处 `"dsh_notice"` → `"alert_card"`（level 字段本来就兼容）；`AlertCardView` **扩展**：`jump=last` 滚到末尾气泡、`jump=subagent`+`childId` 滚到子代理轨迹、长按看全文（复用 `DshCardViews.showNoticeDialog`） |
| `dsh_command`（终端命令卡） | **`code_card`** | `command/run`、`command/done` 两处映射改 `code_card`；`CodeCardView` **扩展**：接受 `command` 作为 `code` 的别名（语言默认 shell），并把 `status`（running/success/failed）并入头部标签 |
| `todo/write` | `todo_card` | 本来就是现有组件（items 用 `text`/`done`，字段已对齐） |
| `deliverables/presented` | `file_list` | 现有组件 |
| `tool-workflow/*` | `steps`（= `steps_card`） | 现有组件（别名归一） |
| `developer/message`、`system/message`、`team/*` | `markdown_doc` / `alert_card` | 现有组件 |
| 工具调用 | `tool_call` | 现有组件（`DshProjection` 生成，和本地工具同一条渲染管线） |

删掉的重复实现：`DshCardViews.DshNoticeCard`、`DshCardViews.DshCommandCard`（含注册表登记与文档注释）。
`ComponentRegistry` 从 39 → 37 个组件。

## 3. 保留的 DSH 原生组件（确实独有）

| 组件 | 为什么不能接现有组件 |
|---|---|
| `dsh_job`（`DshJobCard`） | 现有 `job` 是**主动轮询的作业卡**（`job_id`+`title`+`cmd`，2s 轮询日志、带进度圈），语义是"App 自己跑的作业"。DSH 的 job 是宿主侧的**被动状态灯**（label + running/done/failed，key 按 jobId 原地更新），硬接会导致它去轮询一个不存在的作业 |
| `subagent_card`（`DshSubagentCard`） | 子代理的**实时轨迹**（label + 最近 6 步工具/结果，`deck_only` 投到甲板卡槽），现有 `steps_card` 是静态步骤列表，没有 childId/轨迹累积语义 |

这两个属于用户说的"dsh 独有 → 单个依次创建原生组件"。

## 4. 动态组件的位置

`DynamicCardView` 作为**兜底**：`ComponentRegistry.render` 里未注册类型 → 走它；
已注册但 `canRender=false` 时也回退到它。DSH 以后新增事件类型时：
1. 若形状能套现有组件 → 在 `DshCards` 里一行映射即可；
2. 套不上但字段简单（title/content/items）→ 直接发 `dynamic`，无需改 App；
3. 只有需要专门交互（如 `dsh_job` 的状态灯、`subagent_card` 的轨迹）才新增原生组件。

## 5. 内置工具专用卡 / 原生控件（第二轮审计）

用户提醒："我还有内置工具专用卡也可以用，还有原生控件组件"。把 36 个 `*CardView` 按语义过了一遍：

| 类别 | 组件（type） |
|---|---|
| **工具专用卡** | `tool_call`（工具执行）、`file_card`（单文件）、`file_list`（目录/产物）、`diff`（变更对比）、`code_card`（代码/命令）、`json_viewer`（JSON）、`markdown_doc`（整篇文档） |
| **数据/结构卡** | `table_card`、`chart`（柱/折/面/饼/散点/雷达）、`metric_card`、`progress_card`、`info_card`（键值）、`steps_card`、`todo_card`、`list_card`、`grid_card` |
| **媒体卡** | `image_grid`、`video`、`audio`、`playlist` |
| **场景/工具卡** | `quiz_card`（答题宝核心：可作答+判题）、`weather_card`、`agenda`（日程）、`location`、`link_card`、`file_card`、`app_card`、`contact_card` |
| **原生控件渲染** | `layout`（Layout 树 → 聊天流卡片）、`LayoutPrimitiveCardView`（`NativeLayoutRenderer` 单控件桥接）、`html`（WebView 富内容） |
| **状态/提示** | `alert_card`、`note_card`、`agent_status`（气泡内状态徽章，可 spinning）、`feedback_bar`、`job`（主动轮询作业卡） |
| **DSH 原生** | `dsh_job`、`subagent_card` |

第二轮据此又接了两处：

| DSH 事件 | 落点 | 说明 |
|---|---|---|
| `tool/ptc-dispatch`、`tool/ptc-dispatch-start` | **`tool_call`** | PTC 本质就是一次工具调用，复用内置工具卡（原来发的是提示卡） |
| `goal/*`、`team/*` 等正文 | **`alert_card`** | 修了一个真 bug：`alert_card` 只读 `content`，而这些映射写的是 `text` → 正文整段丢失。现在 `content` 空时回退 `text`（`canRender` 也认 `text`） |

仍然"接不上"的（有明确理由）：

| 想接的 | 为什么不行 |
|---|---|
| `job`（主动轮询作业卡） | 需要 App 自己的作业目录（`job_id`+轮询日志），DSH job 是宿主侧被动状态灯 |
| `diff` | `workspace/changes` 事件只有 `{turn}`，没有逐文件 diff 载荷；`deliverables/presented` 也只给路径 |
| `image_grid` | `image/offload` 只有附件引用，插件没有取图端点 |
| `steps_card` | 可用，但 `tool-workflow/*` 已用它；子代理轨迹需要 `childId` 累积语义，故保留 `subagent_card` |

## 6. 验证


- 单测：`DshEventCoverageTest` 6/6（把 63 种事件逐个 reduce，`unknownEventKinds()==0`，
  并断言插话卡落在 `alert_card`）；`DshHistoryImporterTest` 7/7；
- APK：860MB，`sha256 8b1e9fca…`，21:32 装机成功；
- 组件类型集合（DSH 侧）：`alert_card`、`code_card`、`todo_card`、`file_list`、`steps`、
  `tool_call`、`layout`、`markdown_doc`、`dsh_job`、`subagent_card` —— 除最后两个全部是现有组件。
## 7. 第三轮：改原生组件（2026-11-14，用户问"可以改原生组件吗"）

**可以，而且优先改原生组件、不新增。** 本轮把最后一对重复的 dsh_job 也接进了内置 job：

- JobCardView **扩展静态模式**：job_id 为空但有 status 时，不轮询、直接画
  「状态点 + 任务名 + 运行中/完成/失败」；canRender 同时接受两种用法；
  原来那种带 job_id 的主动轮询作业卡行为**完全不变**；
- DshCards 的 job/registered、job/settled 改发 job（props：	itle/job_label/status）；
- 删除 DshCardViews.DshJobCard 与注册项；DshCardViews 现在只剩 DshSubagentCard；
- ComponentTypes 目录里删掉 dsh_command/dsh_job/dsh_notice 三条 schema，
  补上 code_card（模型要终端/提示/任务卡时用通用类型，同样能拿到原生渲染）。

改原生组件的三条规矩（这轮都遵守）：

1. **只加可选字段，不动老字段语义**（job_id 仍是轮询模式；content 仍优先于 	ext）；
2. **老生产者的渲染结果不变**（job 的轮询分支零改动；lert 只在正文为空时回退）；
3. **每改一个都跑单测 + 装机验证**（DshEventCoverageTest 6/6，APK 装机成功）。

至此 DSH 专用原生组件只剩 **subagent_card** 一个（子代理实时轨迹，childId 累积语义，
现有 steps_card 是静态列表，接不上）。