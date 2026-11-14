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
## 8. 第四轮：瞬时状态整体搬进插槽（2026-11-14）

用户口径：「那就转成原生组件放到插槽里面。收到更新事件后更新，完成后消失。」

DockSlotBus 的类注释本来就写着这个分工：

> 进时间线的是"发生过什么"（消息 + 卡片，落定即快照、不可原地更新）；
> 插槽放"现在是什么、我能做什么"（后台任务、目标、压缩中…），随状态实时改写、消失即恢复原状。

所以这一轮把**瞬时状态**从消息流整体搬到甲板插槽：

| 事件 | 以前 | 现在 |
|---|---|---|
| job/registered、job/settled | job 聊天卡 | 插槽 jobs（插件原有）+ 结束 emove:true |
| compaction/start|summary|prune|end | 4 张 alert 卡 | 插槽 compaction（压缩中）→ 结束 emove:true |
| llm/retry、llm/retry-started | alert 卡 | **新增插槽 etry**（"第 N 次重试中…"，warn）→ ssistant/message/step/end/	urn/end 自动移除 |
| gent/inbox/spliced（插话排队） | "插话已入队" alert 卡 | **新增插槽 inbox**（kind=list、count=N、ction=queue）→ 队列空 emove:true |
| goal/change | alert 卡 | 插槽 goal（原有）→ 非激活 emove:true |
| permission/preset、sandbox/mode、pproval/policy | alert 卡 | 插槽 permission（原有） |
| gent-preset/selected、model/selection | alert 卡 | 不再出卡（模型/预设信息在 permission 插槽 detail 里；后续可加独立槽） |
| subagent/catalog + 活动 | subagent_card | 插槽 subagents（原有）+ 甲板轨迹卡（保留，durable） |

**顺手修的关键 bug**：插件历史上有两种"移除"写法 —— {id} 空帧 和 emove:true，
但 App 的 RemoteSlotBridge 只认后者，于是"压缩中/子代理/任务"结束后**槽位只变成空壳留在总线里**
（甲板挂一条空白）。现在两种写法等价：	itle/detail 都为空且 count<=0 → DockSlotBus.remove(id)。

保留在消息流的（durable，"发生过什么"）：	odo/write、deliverables/presented、command/run|done、
	ool/call|result、	ool/ptc-dispatch*、workspace/changes、working-directory/change、
developer/message、system/message、	eam/* 消息、eedback/*、hook/*、image/offload。

单测：DshEventCoverageTest 5/5（62 种事件仍全部有归属；插话入队改为"不出卡但已识别"断言）。

## 9. DSH 专用动态组件 + 动态 layout（2026-11-14，插件推送 → 手机本地绘制）

用户口径：「可以创建 dsh 专用的动态原组件及动态 layout 功能了，你使用推送然后本地绘制。」

### 9.1 链路

```
插件 POST /v2/card { session_id?, key, type, props }
        │
        ├─ streamBroadcast → WS/SSE 帧 { type:'ui/card', data:{key,type,props} }
        ▼
手机 AIChatActivity.onChannelEvent → case "ui/card"
        │  同 key 覆盖更新；没有就追加一条 durable 的 REMOTE_CARD 消息
        ▼
ChatAdapter.bindRemoteCard
        │  remoteCardView.component = {type, props}
        ▼
ComponentRegistry.render(ComponentData.of(type, props))
        ├─ 内置卡：alert_card / code_card / list_card / file_list / …
        ├─ "layout" + props.layout：NativeLayoutRenderer 原生控件树
        └─ 未注册类型：DynamicCardView 兜底（标题 + content/items + 原始 JSON 折叠）
```

**关键点**：App 不解释语义，只按 `type/props` 走既有组件管线 —— 所以 DSH 以后新增卡片
可以完全不发版；需要复杂排版时插件直接给 `layout` 树。

### 9.2 用法

```bash
curl -X POST http://<电脑IP>:8218/v2/card \
  -H "Authorization: Bearer <令牌>" -H "Content-Type: application/json" -d '{
    "session_id": "session-...",
    "key": "demo-layout",
    "type": "layout",
    "props": {
      "title": "DSH 动态卡",
      "layout": { "type": "column", "children": [
        { "type": "text", "text": "这张卡是插件推送、手机本地绘制的", "bold": true }
      ]}
    }
  }'
```

三种 `type` 的取法：

| type | props | 用途 |
|---|---|---|
| 内置类型（`alert_card` / `code_card` / `list_card` / `file_list` / `steps` / `todo_card` …） | 各自字段 | 最常见，一行映射搞定 |
| `layout` | `{layout: <控件树>, title?}` | 复杂排版，原生控件树（column/row/scroll/card/wrap/grid/tabs/stack/accordion/carousel + text/image/input/button…） |
| 任意新名字（如 `dsh_future_card`） | 任意 | App 不认识 → 动态兜底卡，**不用发版** |

### 9.3 落点与边界

- 卡片进**消息流**（`MessageType.REMOTE_CARD`，durable，随会话落盘）——"发生过什么"；
- 会变的瞬时状态仍走**插槽**（`ui/slot`）——"现在是什么"（见 §8）；
- 同 `key` 重复推送 = 原地更新（改一张卡不用追加）。

### 9.4 验证

- 插件：`POST /v2/card` → `{"ok":true,"key":"demo-layout","type":"layout","pushed":true}`；
- App：`ChatAdapter.bindRemoteCard` 新增，编译通过（`BUILD SUCCESSFUL`）；
- APK：860MB，`sha256 2847571d…`（本轮构建；adb 掉线未装机，USB 恢复后 `adb install -r` 即可）。
## 10. 推送暂存 + 进页面补发（2026-11-14）

ui/card 是瞬态推送：手机不在聊天页时没有订阅者，帧会丢。按用户口径补上暂存：

- 插件：PUSHED_CARDS（按 会话 × key，上限 50 条 / TTL 6 小时）；
  POST /v2/card 先 ememberPushedCard 再广播；
  新订阅接入时 eplayPushedCards 补发 —— **WS hello 与 SSE ready 两条链路都挂**；
- App：同 key 覆盖，重复补发不会叠卡（indRemoteCard 幂等）；
- 真机验证：App 被 force-stop 时推 stash-demo → 启动进聊天页后日志出现
  [ui/card] type=alert_card key=stash-demo 并落盘。

### 10.1 顺带修的落盘 bug

ChatHistoryManager.saveCurrentChatAsSession 只保留 USER/AI 消息 →
**没有正文的 REMOTE_CARD 被静默丢掉**（"推送的卡片一重进会话就没了"）。
新增 isDurableForSession(msg)：带 emoteCardView.component 的 REMOTE_CARD 也算 durable；
旧 v2 view 片段保持原状。验证：会话文件里出现
"remoteCardView":{"component":{"type":"alert_card","props":{...}},"__key":"persist-demo"}。

### 10.2 使用约束

ui/card 由聊天页处理；手机在首页/工具页时靠上面的暂存补发，但**必须回聊天页才会落屏**。
"任何页面都能弹"要用 phone_ui 的 toast/notification，不是这条链路。
## 11. 抄 dsh-Go 的渲染语言与校验器（2026-11-14）

用户口径：「抄，然后他的渲染思路也值得。」

### 11.1 渲染：语义令牌 + 降级（NativeLayoutRenderer）

插件只给**语义**，像素/颜色/深浅色由 App 定：

| 属性 | 取值 | 说明 |
|---|---|---|
| size | 数字 或 caption/footnote/body/subtitle/title/headline/display | 六档字号，token 名与 dsh-Go 一致；旧写法（数字、ontSize）不变 |
| weight | egular/medium/semibold/bold | old:true 仍是等价写法 |
| 	one | good/warn/bad/info/muted（别名 success/warning/danger/error/note/secondary） | 语义色，color 显式给了就以 color 为准 |
| spans | [{text, color\|tone, bold, weight, mono, size}] | 富文本片断（SpannableString） |

**降级口径**（抄 dsh-Go 的"单点坏不毁整块"）：

| 情况 | 之前 | 现在 |
|---|---|---|
| 不认识的 	ype | ⚠ 未知控件: xxx（可用 register_type…） —— 把开发者提示塞给用户 | 灰色一行「这部分无法显示」，日志记详情，其余照常渲染 |
| 节点 > 400 / 深度 > 9 / layout > 64 KiB | 直接渲染（大卡会把主线程拖住） | 降级成「内容过多，已省略」/「层级过深，已省略」 |

### 11.2 校验：推卡前先校（cardcheck.js）

/v2/card 现在先跑 checkCard()，**坏卡不进手机**：

`json
POST /v2/card {"type":"alert_card","props":{"title":"只有标题"}}
→ 422 {"error":"card_invalid","diagnostics":[{"path":"/props","level":"error",
     "message":"缺少必填字段：content 或 text 或 message"}],"hint":"✗ /props: 缺少必填字段…"}
`

规则（思路抄 sdk/plugin-kit 的 view-check / limits / effects）：

- **error（拒收）**：缺 	ype、props 不是对象、体积 > 32 KiB、组件必填字段缺失、layout 节点 	ype 非法、layout 字符串不是 JSON；
- **warn（照发）**：嵌套 > 8 层、节点 > 400、节点缺 	ype（App 按 column 兜底）；
- **放行**：不认识的组件类型（App 有动态兜底卡）；
- orce:true 可绕过拒收（排障用）。

自测：
ode tools/dsh-plugin-smartquiz-remote/cardcheck.test.mjs → **15 passed**。
## 12. 转换器瘦身 + 假事件卡清理（2026-11-14）

用户口径：「还是要排查一下卡，再看看客户端的插件，消息转换器不行。」

### 12.1 病历（真机会话实测）

`
messages=93  file=10.4 MB
一条 AI 消息：content 2.6 KB + thinking 215 KB + 245 个 tool_call 组件
`

结论：**不是卡片画得不对，是转换器把一整个 turn 的原始数据全塞进一条消息**——
思考 215 KB 一条、245 张工具卡各带完整命令/输出，落盘 10 MB，加载解析把主线程拖住
（watchdog 抓到 8.9 秒）。DSH 自己的界面也不这么做：思考折叠、工具行只留摘要、详情按需展开。

### 12.2 投影预算（DshProjection）

| 字段 | 上限 | 超限写法 |
|---|---|---|
| 思考 	hinkingContent | 8000 字 | …（思考已截断，原文 N 字） |
| 工具参数 rgs | 400 字 | …（参数已截断，原文 N 字） |
| 工具输出 esult | 800 字 | …（输出已截断，原文 N 字） |
| 官方 meta（diff/read 卡要用） | 4000 字 | 同上 |

截断标注带原始长度：用户能看出"这里被省了"，不是数据丢了；完整内容仍在电脑端会话日志。

### 12.3 假事件卡一次性清理

修掉兜底逻辑之前落盘的 31 张「电脑事件 · job/settled|ui/slot|compaction/*」卡，
用 ChatHistoryManager.cleanDshUnknownCardsOnce() 从会话文件里删掉（幂等，开机后台跑）。
实测：清理假事件卡: 31 张（会话文件 2 个）。

判据只删 	ype=dynamic && props.type=unknown_event && event_type ∈ 纯状态/合成帧，
真人可见的未知事件卡不动。
## 13. 历史显示：为什么在线 agent 不卡、DSH 卡（2026-11-14）

用户口径：「是历史显示没处理好……看看在线 agent 的消息怎么渲染的，消息流怎么回收性能的。」

### 13.1 在线 agent 的渲染（parts 管线）

`
ChatMessage ──AssistantTurn──▶ parts[]：reasoning / text / tool_call / tool_group / data
                                  │
                          TurnPartsHost：按 part id 复用视图，按内容指纹只重绑变化的那段
`

- **一条消息 = 一次模型生成 = 1~5 个 part**（思考折叠成一行、正文一段、连续工具折叠成「已运行 N 个工具」一张组卡、产物卡一张）；
- PartsRendererFlag 出厂开（2026-10-24），DSH 同步过来的消息**也走这条**；
- 复用粒度是 **part**：重绑时只重建指纹变了的 part，WebView/图表/卡片不白建。

### 13.2 消息流怎么回收性能

| 机制 | 位置 | 值 |
|---|---|---|
| 分页加载 | HISTORY_PAGE_SIZE | 50 条一页 |
| 内存窗口 | HISTORY_ARCHIVE_KEEP / CHUNK | 保留 150，超出每次移出 50 |
| 视图缓存 | setItemViewCacheSize | 4 |
| 固定尺寸 / 关动画 | setHasFixedSize(true) / setItemAnimator(null) | — |
| 稳定 id | setHasStableIds(true) | — |
| 重组件释放 | eleaseAndClearComponents | 滚出屏幕即放 WebView/播放器 |

### 13.3 DSH 为什么还是卡

回收是按**消息**做的。DSH 一个 turn 有 51 步，转换器把它压成**一条消息**：

`
1 条消息 = 51 段思考 + 51 段正文 + 245 张工具卡 ≈ 150+ 个 part 视图
`

150 个视图在一个 item 里，RecyclerView 无从回收；每次滚动重绑都是"重建一个巨型 item"。
在线 agent 一条 1~5 个 part，同样的列表、同样的回收配置就不卡 —— 差异在**消息粒度**，不在数据量。

### 13.4 修法：轮次合并（MAX_ROUNDS_PER_MESSAGE = 6）

DshProjection.mergeExtraRounds()：超过 6 步的部分合并进最后一轮 ——
正文按原顺序拼接（内容不丢）、思考拼起来后按 8K 截断、工具 id 归到这一轮，
同时修正 contentRoundBounds / contentRoundToolIds。

效果：一条消息的 part 数从 150+ 降到 ≈ 13，回到在线 agent 的量级，
RecyclerView 的回收重新有效；后续要更彻底可以按 step 拆成多条消息（数据模型改动更大）。
## 14. 动态卡三块能力：keyValue / compare、image data:、绑定 + 有界公式（2026-11-15）

用户口径：「补全动态卡（ui/card + layout）三块能力，并验证、提交。」本轮只加可选字段/新类型，
既有卡片（alert/list/metric/progress/layout）行为不变。

### 14.1 两张原生卡

| type | 别名 | props 形状 | 上限/降级 |
|---|---|---|---|
| `key_value` | `keyValue`、`dsh_keyvalue`（`ComponentTypes.resolve` 归一） | `items` 三种形态：`[["名称","值"],…]`、`[{label,value,tone?}]`、`{"名称":"值"}`；可选 `title` | 最多 24 项，超出 footer 显示「还有 N 项」；0 条有效数据显示空态；点击整卡复制「名称：值」全文 |
| `compare` | 无（`compare` 本身） | `items:[{title,badge?,rows}]`，`rows` 支持 `[["名称","值"]]` 或 `{"名称":"值"}`；可选顶层 `title` | 最多 4 项，超出显示「还有 N 项未显示」；0 项空态；不足 2 项也照常渲染（避免模型先给 1 项时掉原始 JSON 兜底） |

实现：`KeyValueCardView` / `CompareCardView`（`CardShell` + `ComponentTheme`/`ComponentColors`），
共用纯 JVM 解析 `CardKeyValues`（三形态归一、24 项截断、数字样值等宽字体、语义 tone）。
已注册进 `ComponentRegistry`，目录补 `key_value` / `compare` 两条 schema；别名 `keyValue` 先走
`normalize → keyvalue` 再命中 alias，旧 DSH 写法不用改。

### 14.2 image 支持 `data:image/...;base64,...`

新增 `DataImageDecoder`（单张硬限制：base64 payload ≤ 512 KiB、单边 ≤ 4096；先 bounds 再按
2048 采样解码）。接入五处：

- `NativeLayoutRenderer` 的 `image`、`avatar`、`avatar_group`、`carousel`（头像用圆形
  `RoundedBitmapDrawable`）；
- 内置 `image_grid` 的单图/网格项，以及 `ImagePreviewUtil` 的全屏预览（data URI 不进
  Glide/文件/网络分支）。

超限、格式坏、OOM 一律不抛异常：layout 侧显示浅底「图片无法显示」占位，`image_grid`
显示同文案占位且不提供无意义的「点击重试」。

**体积闸门配套调整**：`MAX_LAYOUT_CHARS=64 KiB` 仍然限制结构体积，但 data:image 的 base64
载荷不计入该 64 KiB（否则一张 512 KiB 的图永远过不了入口检查）；总图片载荷另设 2 MiB
上限（4 张 × 512 KiB）。`cardcheck.js` 同口径：结构体积与图片载荷分开算，并单独拦总载荷。

### 14.3 layout 表单绑定 + 有界公式

- **绑定**：任意节点属性可写 `{"$":"data.a.b"}`，渲染前从 `props.data` 取值；取不到按空值
  处理。文本节点就是显示解析出来的值。
- **公式**：只允许写在 `text` 的 `text` 与 `stat`/`metric` 的 `value` 位置
  `{"=":"a * b"}`。求值器 `LayoutFormula` 只认：
  - 数字（整数/小数）、`+ - * /`、一元正负号、括号；
  - `data.` / 裸数据路径（`a`、`data.a.b`、`items[0].price`，数值字符串也接受）；
  - `min / max / round / abs` 四个函数（round/abs 单参，min/max ≥1 参）。
  其它字符、未知函数、未知路径、非数值、除零、>256 字符、>24 层、>128 token 全部返回
  `null` 并降级成「—」；没有反射/脚本/字符串/赋值。
- **输入变化重算**：`LayoutBindingScope` 在渲染同棵树时登记绑定/公式 TextView；渲染完
  接线 EditText / Spinner / CompoundButton / RadioGroup / SeekBar，值变化写回 `props.data`
  并只刷新登记的文本节点（不重建 View，不丢焦点）。初始值双向同步：`props.data` 有值
  先灌控件，没有则把控件当前值写回 data，所以「input value=3 + 公式 a*2」首屏就是 6。
- **限制（如实写）**：date/time/color/rating/toggle/stepper/slider_range/tag_input 这些
  自定义 ref 控件没有统一监听接口，暂不参与「改动即重算」；提交按钮仍会经
  `collectValues` 回传。公式写在非 text/metric 位置一律「—」，绑定写在哪里都生效。

### 14.4 插件校验器

`cardcheck.js` 增加 `key_value` / `compare` 必填 `items` 规则（`keyValue` /
`dsh_keyvalue` / `compare_card` 先归一），以及 data:image 载荷预算。自测：
`node tools/dsh-plugin-smartquiz-remote/cardcheck.test.mjs` → **23 passed, 0 failed**。

### 14.5 验证

- 新增单测全绿：`KeyValueCardViewTest` 9、`LayoutFormulaTest` 9、
  `DataImageDecoderTest` 3、`LayoutBindingScopeTest`（Robolectric）1；
- 受影响旧单测全绿：`ComponentTypesTest` 7、`ComponentFieldIntegrityTest` 5、
  `ComponentDisplayTest` 14、`NewCardShapeTest` 3、`ComponentNameIntegrityTest` 6；
- APK：`.\gradlew.bat --offline assembleDebug` BUILD SUCCESSFUL，约 901 MB，
  `sha256 74AD5818DD6BA7148D0563246DED00FEB0A4C939FF64CE733BDCF0FA18B5AAD2`，
  `adb -s 192.168.1.10:41193 install -r` Success；
- 真机推卡（插件 `/v2/card`，令牌/会话见本轮任务）：
  - `compare` 3 项：三张小卡 + badge + 键值行，数值等宽，截图 `build/verify.png` /
    `build/verify5.png`；
  - `layout` 含绑定/公式：`{"$":"data.name"}` 显示「答题宝」、`{"=":"price * qty"}` 与
    `metric` 都显示 50，输入框 12.5/4，截图 `build/verify5.png`；
  - 另推一张公式卡，首屏显示 6（`a=2,b=3`），截图 `build/verify12.png`；
  - 真机手动改输入值触发重算这一步没有截到稳定画面（会话持续推进导致列表滚动），
    该行为由 Robolectric `LayoutBindingScopeTest` 覆盖：`b` 3→5 后公式 6→10，
    `props.data.b` 同步为 "5"。

### 14.6 没做到/降级

- 自定义选择类控件（日期/取色/评分等）不参与实时重算（见 14.3 限制）；
- `compare` 少于 2 项不拒绝，按 1 项渲染（产品口径是 2~4，但拒绝会掉原始 JSON 兜底，
  故选择可渲染 + 文档说明）；
- data:image 按单张 512 KiB、总 2 MiB 硬限；更长的图需要拆卡或走文件 URL；
- `cardcheck.js` 的结构体积现在显示「结构体积」而不是总体积，调用方若按旧日志文本做
  正则匹配需要同步。

## 15. 事件分流：批处理 / 快照 / 背压（2026-11-15，任务书 §14）

工具连续调用时电脑端 50–100 事件/秒，旧链路是
`OkHttp/SSE 回调 → runOnUiThread → 每条 reduce → 每条投影 → 每条 notifyItemChanged`。
主线程全花在重绑同一行上，队列越积越长，用户看到的是「比电脑慢半拍、越用越延迟」。
按 DSH/dsh-Go 的「合并 → 丢弃 → 断」口径重做，分三件事。

### 15.1 批处理（A）

落点：`EventBatchQueue`（纯逻辑，线程安全有界队列）+ `AIChatActivity` 的帧循环。

| 参数 | 值 | 说明 |
|---|---|---|
| 帧间隔 | 16 ms | `Handler.postDelayed`，等价 60fps 节拍；队列空即停，不空转 |
| 单帧批大小 | ≤ 50 条 | 归约在帧内连续做，**不逐条投影** |
| 队列上限 | 2000 条 | 满了丢**最旧**的并计数（正在发生的不能丢） |
| ack 水位 | 批内最大 seq | **整批处理完**才 `confirmApplied`；逐条 ack 会在中途崩溃时丢事件 |
| 投影节流 | 80 ms（沿用） | 卡片/收尾/阶段变化强制投影，token 增量按 80ms |

- 网络回调只 `offer`；主线程 `drain(50)` → 连续 `DshTimeline.reduce` → **帧末只投影一次、
  只 notify 一次**；
- 顺序：FIFO 为主；同一批内如果双通道重叠/补发导致乱序，按 seq 升序稳定排序，无 seq 的
  合成帧（审批/问卡/ui）跟着它入队时的水位走；
- 兼容旧语义：分段（`splitLiveIntoSegments`）、投影预算、DSH id 桶、甲板插槽、审批弹窗
  全部保持原样；只是「谁在什么时候投影」从每条一次变成每帧一次。
- `onEvent` 里不再 `runOnUiThread`（原来每条事件一个主线程消息），批处理循环也不再
  空转（队列空就退出）。

### 15.2 快照兜底（B）

**触发**（`EventFlowPolicy`，主判据是连续性，数量阈值只当辅助）：

- 收到服务端 `snapshot` 帧 / `__force_resync` → 无条件转快照；
- `seq` 不连续：**连续两次跳号**（真断了，不是单条被 caps 过滤或乱序补发）→ 转快照；
  单次跳号超过 **200 条**（可调常量 `SNAPSHOT_GAP`）→ 直接转快照；
- `state` 帧里 `last_seq - 本页已落屏` > 200 → 转快照（辅助判据）；
- 比水位更旧的 seq（乱序/补发）不算断档（时间线按 seq 幂等）。

**重建**：

- 请求走服务端已有路径 `GET /events?since=-1&follow=0&snapshot_threshold=50`（live 会话
  `since<0` 直接回 `snapshot{turns}`，非 live 走 log 分支），一次往返、读到第一帧就断开；
- 清空时间线/组件 id，水位对齐快照 seq，**丢掉队列里 seq ≤ 水位的旧事件**（`dropUpTo`），
  传输游标也一并跳到水位（`RemoteConnection.resetCursorTo`）；
- 拿不到快照 → 兜底：重置时间线 + **主动重连**（按已 ack 游标重放），不静默缺一段。

### 15.3 背压（C）：合并 / 丢弃 / 断

对齐 dsh-Go 的三级，**没有「客户端慢就切成拉模式」这一档**：

1. **合并**：同类增量只保留最新（客户端侧就是队列 + 每帧≤50 + 帧末只投影一次；
   token 增量本来就 80ms 节流）；
2. **丢弃**：队列满 2000 条丢最旧、计数（`Batch.droppedBefore`），日志
   `[flow] 队列满丢弃最旧 N 条`；
3. **断**：累计丢弃 ≥ 500 条（`HARD_DROP_LIMIT`）或单批丢弃 ≥ 200 条
   （`HARD_DROP_BURST`）→ 清空积压 + 请求快照 + `RemoteConnection.forceReconnect()`
   主动重连，只按快照水位续传。

**为什么没采用「切拉模式」（原方案已按评审撤回）**：

- DSH/dsh-Go 的背压协议只有「合并 / 丢弃 / 断」三级（4 Hz/250ms 合并窗口、>32 KiB 丢
  整份、待发 >1 MiB 服务端 1013 断连），没有「客户端慢 → 改成轮询」这一档；
- 拉模式要客户端与插件同时改协议（`mode=notify` + `wake` 帧 + pull 循环），
  一旦半新半旧就是双份投递或静默丢事件；而且要掐流重开，延迟反而翻倍；
- 正确的解法是「追不上就丢旧的、必要时断线重建」，快照兜底已经覆盖了状态正确性。
  因此插件（`tools/dsh-plugin-smartquiz-remote/index.js`）本轮**未改**，
  `node --check` 通过、`src/main/assets/remote_dsh/plugin/index.js` 与源码仍字节一致。

### 15.4 日志与验收口径

真机 `adb logcat -s DshProbe` 看三类行（1 秒最多一条汇总，不刷屏）：

```
[flow] 模式=推 批=128 事件=2410 队列=36/2000 峰值=87 丢弃=0 本段丢弃=0 快照=0 断档=0 落后=12
[batch] n=23 归约=23 跳过=0 投影=0 耗时=2ms 队列剩=5
[flow] 转快照 reason=seq 断档 → 3021 queue=180 dropped=0
[flow] 快照重建 seq=3021 dropped=180
[flow] 队列满丢弃最旧 12 条（上限 2000，本段累计 12）
[flow] 背压过线：清空积压 40 条，转快照并重连（累计丢弃 512 条）
```

- 「模式」：`推` / `快照`（重建中）/ `重连`（硬线后）；
- 「批/事件」：累计帧数与事件数；「峰值」是本窗口队列峰值；「丢弃」=累计；
  「本段丢弃」=自上次快照/重连以来（硬线判据）；「断档」=策略判定过的断档次数；
  「落后」=服务端 seq − 本页已落屏；
- 顶部副标题只在有积压/重建时补一段（`· 推（积压 N）` / `· 重建中`），不弹 toast。

### 15.5 验证

- 纯 JVM 单测（新增）：
  - `EventBatchQueueTest` 10：FIFO 顺序、乱序按 seq 排序、合成帧位置、单帧 50 上限、
    ack=批里最后一条、上限丢最旧并计数、`dropUpTo(快照水位)`、峰值深度；
  - `EventFlowPolicyTest` 14：连续性判定（连续/重复/小跳/连续两次跳/大跳 200）、
    快照后水位复位、`state` 落后判据、硬线（累计 500 / 单批 200）与阈值可注入；
  - `RemoteSubtitleTest` 5：副标题模式尾巴（新增 `flowLabel` 重载）。
- `.\gradlew.bat --offline assembleDebug`：BUILD SUCCESSFUL；
- 装机与真机日志见本轮交付报告（`adb -s 192.168.1.10:41193 install -r`）。

### 15.6 没做到 / 降级

- **快照只有最近 20 条 role/text**：重建只对齐「当前这一轮」的正文，工具卡/思考/子代理
  轨迹不会从快照恢复（会等后续事件或下一轮补），这是服务端 `snapshotTurns` 的结构决定；
- **帧循环用 `postDelayed(16ms)` 而不是 `Choreographer`**：节拍等价，省一个
  FrameCallback 生命周期；代价是后台/低帧率时最多晚一帧；
- **队列上限按条数（2000）不按字节**：逐条 `toString()` 估字节的开销大于收益，
  1 MiB 口径留给后续版本；
- 快照请求并发收敛为「同一时刻只有一个在飞」（`dshResyncInFlight`），后续请求合并；
- 「断」这一档只做主动重连（`forceReconnect`），没有像 dsh-Go 那样发 1013 让服务端断连
  —— 我们的服务端没有对应帧，客户端主动断更直接。

## 16. WS 通道帧对齐（2026-11-15，WS-FRAMES）

### 16.1 病历：为什么 WS 上只有合成帧

真机日志（01:36:30 / 01:37:12，`adb logcat -s DshProbe`）里，`RemoteConnection` 报
「WS 已连接」，但手机只收到 `ui/card`、`ui/slot` 这类合成帧，**一条真实 `event` 都没有**。
对照代码，是三处缺口叠加：

| # | 位置（改动前） | 问题 |
|---|---|---|
| ① | `sendEventFrame()`（SSE `GET /events`） | 只 `sse(out)` 直写响应，**从不进 `STREAM_HUB`** → WS 订阅者根本收不到事件 |
| ② | `CHANNELS['/ws/events'].out` | 只有 `event/pulse/ui/card/ui/slot`；`__force_resync`、`approval`、`question` 及 `*_gone` 到 `handleWsConn` 的 `ch.out.has(o.type)` 全被丢 |
| ③ | 手机 `RemoteChannel.runSocketOnce()` | 只开 `/ws/events` + `/ws/ui`，**从未打开 `/ws/alert`**；审批/反问在 WS 链路上 100% 收不到 |

所以「WS 只下发合成帧」不是某一条过滤写错，而是**事件生产者只接了 SSE 一条线**，
WS 白名单又把仅剩的控制帧挡掉。`/ws/events` 的 `event` 和 `/ws/alert` 的审批帧
都只是「声明了、没人发/没人订」。

### 16.2 改了什么（只改插件）

| 改动 | 说明 |
|---|---|
| 新增 `wsBroadcast(sid, frame)` | 只投 `transport === 'ws'` 的 `STREAM_HUB` 订阅者；SSE 仍由各自 handler 直写响应，**不会双份** |
| `ctx.on('session/event')` 最前处 | 所有早退之前发 `wsBroadcast(sid, { type:'event', event })`，与 SSE `sendEventFrame` 完全同构；caps 与端点白名单仍由 `sub.send` 过滤 |
| WS 订阅者加标记 | `sub = { sid, caps, transport:'ws', channel, send }`，供 `wsBroadcast` 区分 |
| `/ws/events` 白名单 | 追加 `__force_resync`、`approval`、`approval_gone`、`question`、`question_gone`（都是**既有帧类型**，未新增类型） |
| 不动的部分 | `since/head`、`anchor`、`pull/batch`、`ack`、`ui/card`/`ui/slot` 合成帧、`/ws` 全功能、`/ws/ui`、`/ws/file`、`file_*` 全部保持原样 |

### 16.3 验证方式

**A. Node 自测（真 WebSocket + 真 HTTP，假 ctx，随机端口，不碰运行中的 8218）**

`tools/dsh-plugin-smartquiz-remote/ws_frames.test.mjs`，9 项全过：

```text
node ws_frames.test.mjs
  ok  握手：/ws/events 的 hello 声明了 event / __force_resync / approval / question
  ok  合成帧保持现状：POST /v2/card → ui/card 仍到 /ws/events
  ok  真实事件：session/event → {type:event, event} 到 /ws/events
  ok  __force_resync：POST /v2/action action=resync → 帧到 /ws/events
  ok  approval / approval_gone：debug.approval 推帧，应答后收回
  ok  question：user-questions/request 回调 → 帧到 /ws/events
  ok  caps 过滤仍生效：只声明 events.stream 的连接收 event、不收 approval
  ok  端点白名单没被打穿：/ws/ui 收不到 event
  ok  pull 语义未动：/ws/events 发 pull 仍回 batch
```

**B. 真实宿主链路（本机 8218，插件重载后）**

- 另起真 WS 客户端订阅当前会话 `561ebba7-…`，实测收到**真实事件帧**：
  `job/settled(708) → tool/result(709) → step/end(710) → step/start(711) →
  delivery-accepted(712) → assistant/message(713) → tool/call(714)`，
  全部带 `seq`（客户端断档检测可用）；同一条连接也收到 `__force_resync`、
  `approval`、`ui/card`、`ui/slot`。
- 手机（`192.168.1.10:41193`，插件重载后重启 App 回到 WS）：

| 证据 | 内容 |
|---|---|
| `/status` | `ws_clients=2`，`/ws/events=1`、`/ws/ui=1` |
| 连接日志 | `RemoteConnection: [DSH] connection=true WS 已连接`，`sid=session-cb82ea23-…` |
| approval | `[DSH] 审批帧 id=test-d74556b9057a`；`[flow] 模式=推 批=2 事件=6` |
| 批处理 | 12 条并发 `ui/slot` → `[batch] n=5 归约=5 跳过=0 投影=0 耗时=1ms` |
| `__force_resync` | `[DSH] 服务端要求重建（__force_resync）→ 快照` → `快照重建完成 seq=14841` |

### 16.4 边界与没做到

- 手机端这次取到的 WS 新样本是 **approval / `__force_resync` / ui-slot**；真实 `event`
  帧的手机侧样本没取到——手机跟随的 `session-cb82ea23` 在验证窗口内处于「等子代理」
  状态，没有新事件。真实 `event` 帧由 **真 WS 客户端 + 真 `session/event` 总线**验证，
  帧格式与手机 `case "event"` 的解析口径一致（`o.optJSONObject("event")`）。
- 插件重载会断一次 WS；手机客户端只在 `run()` 开头试 3 次 WS，失败后进 SSE 循环，
  **不会事后重新升级**。本次重载后手机先退回 SSE（`ws_clients=0`），重启 App 才回到 WS。
  这是客户端重连策略，不在本次插件改动范围。
- `pulse` 仍未由 WS 主动发（只有协议级 ping；白名单里的 `pulse` 保持原状）；
  非 live 会话的 WS 实时事件仍靠 `pull` 补（SSE 侧是轮询持久化日志，两条线仍有差异）。
- `approval_gone` / `question_gone` 一并放行（否则对话框收不掉），属于既有帧、非新协议。
- 未改 Android Java、未改 `/ws` 全功能端点、未改文件帧与 ack 语义。

## 17. WS 连接周期重试（2026-11-15，§17 WS-RETRY）

### 17.1 病历：WS 掉线后永远回不去

`RemoteChannel.run()` 开头只试 **3 次** WS（每次之间 `sleep(800)`），任一失败就 `break` 进 SSE
主循环，之后再也不会尝试 WS。插件重载 / 网络抖动把长连接踢掉后，手机就永久停在 SSE：

- 重载前 `/status` 的 `ws_clients=2`（`/ws/events` + `/ws/ui`）；
- 重载后变 `0`，手机只在 SSE 上收事件；只有杀进程重开 App 才回到 2。

§16 已把插件侧 `/ws/events` 白名单补齐；本节处理的遗留问题在客户端重连策略。

### 17.2 退避参数（`WsRetryPolicy`，纯 JVM）

| 参数 | 值 |
|---|---|
| 首次重试 | 1000ms |
| 增长 | ×2：1s → 2s → 4s → 8s → 16s |
| 基准封顶 | 30000ms（第 6 次起恒为 30s） |
| 抖动 | ±20%（`base × [0.8, 1.2]`，封顶那档实际最大约 36s） |
| 放弃条件 | 无（`retryWindowMs=0` 周期重试永不放弃；非 0 仅作限流护栏） |
| 重置 | 握手成功即 `reset()`，下一次断线仍从 1s 起 |

`nextDelayMs(attempt)` 是纯函数（含抖动）；`noteFailure()` / `nextDelayMs()` / `reset()` /
`shouldRetry(elapsedMs)` 供调度与单测使用。

### 17.3 与 SSE 的切换语义

两种通道**保持互斥**（不再双通道重复投递），只把「放弃 WS」改成「周期重试」：

1. **启动**：WS 监管线程与 SSE 主循环并行启动；WS 没连上时 SSE 照常兜底，事件不断。
2. **WS 握手成功**：置 `wsActive`、`onConnection(true, "WS 已连接")`、打
   `[ws] 已连接，停 SSE`；`cancelEventStreams()` + `closeFollowStreams()` 立刻掐断 follow SSE
   （只动主循环那条，导入历史/一次性补拉不受影响），1.5s 后再清扫一次，关掉
   「SSE 线程检查完标志、正要开流」的竞态窗口。
3. **WS 断开**（`onFailure` / `onClosed`）：`wsActive=false` 唤醒 SSE 主循环恢复兜底，
   同时按策略排下一次重连；握手成功过的连接先 `reset()`，所以首次退避仍从 1s 起。
4. **重试期间**：SSE 持续兜底；每次失败记录
   `[ws] 重试第 N 次，延迟 Xms（握手失败|连接断开）`。
5. **关闭通道**：`close()` 移除所有 `postDelayed`、取消 event/ui socket、翻代号掐流，
   连接线程不再留下泄漏的 socket。

不变的部分：`pull` 批、`ack`、`anchor`、文件帧（`file_begin/chunk/end`）、`ui/card` / `ui/slot`、
`confirmApplied` 的落屏游标语义全部未改；`AIChatActivity` 的批处理/背压参数未改。

### 17.4 验证

- **单测**：`WsRetryPolicyTest` 8 项全绿（退避序列、30s 封顶、±20% 抖动范围、
  `reset` 后从头开始、`noteFailure` 计数、默认永不放弃、可选窗口）。
- **编译/装机**：`.\gradlew.bat --offline assembleDebug` → BUILD SUCCESSFUL；
  `adb -s 192.168.1.10:41193 install -r` 成功（APK sha256 `81F9553D…9592F8`）。
- **插件重载**：两次 `plugin_manager set_bundle @local/smartquiz-remote enabled=false`
  均被 `HMR transactions cannot be nested` 挡住，**未能完成真机插件重载验证**。
- **回退验证（杀进程重启）**：`ws_clients` 2 →（`force-stop`）0 →（重启 App）2；
  日志 `[ws] 已连接，停 SSE`。
- **补充：链路级断开/恢复（不重启 App、不重启 DSH）**：手机上把 `base_url` 临时指向
  本机 TCP 代理（19000 → 8218），杀掉代理模拟插件不可达、再恢复代理：

  | 时刻 | 证据 |
  |---|---|
  | 01:59:28.709 | `[ws] 连接结束：WS 失败: Connection reset`，`ws_clients` 2 → 0 |
  | 01:59:28.713 起 | `[ws] 重试第 1/2/3/4/5 次，延迟 848 / 2258 / 4063 / 8872 / 16266ms` |
  | 02:00:01.293 | `[ws] 已连接，停 SSE`，`ws_clients` 0 → 2（全程未重启 App） |
  | 之后 | `session_streams=0`（无 SSE 双通道）；`[flow] 模式=推`、`[batch] n=8` 正常 |

  代理关闭后已把 `base_url` 恢复为 `http://192.168.1.2:8218` 并重启 App 复核 `ws_clients=2`。

### 17.5 没做到 / 降级

- **插件重载场景未验证**：HMR 事务嵌套错误无法绕开，按要求改用「杀 App 进程再启动」验证；
  另用 TCP 代理模拟链路中断，验证了「不重启 App 自动回到 WS」，但它走的是连接失败路径，
  与「插件重载瞬间服务端主动关闭 socket」不完全等价。
- 重试调度在主线程 `Handler.postDelayed` 上：主线程若被长时间阻塞，重连时机会顺延
  （与既有 UI 线程模型一致，未另起调度器）。
- WS 常连后 `/ws/ui` 仍未独立保活：event 通道断开时会一起重建，ui 单边断开不自愈
  （与改动前一致，不在本次范围）。
