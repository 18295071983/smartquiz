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

## 18. 能力协商体检（2026-11-15，§18 CAPS-AUDIT）

### 18.1 服务端契约（`caps.js`）

`CAP_VERSION = 1`；`SERVER_CAPS` 共 **25** 项：

```
view.cards.status / checks / connections / session / approval / timeline / schedule / subagents / plan,
parts.approval, parts.tool-cards, parts.data-cards, parts.stream, parts.questions,
events.stream, events.replay, events.ack,
schedule.read, schedule.write, plan.read, plan.write, questions.answer,
subagents.panel, cmd.ui, cmd.shell
```

**解析规则**（`normalizeCaps` / `allows`）：

| 规则 | 行为 |
|---|---|
| 输入形态 | 数组 / 逗号分隔字符串；`null`、空串、对象结构 → 空列表 |
| 规范化 | 每项 `trim`、去空、按首次出现去重 |
| 命名 | 点号命名，**大小写敏感**（`Parts.Approval` ≠ `parts.approval`） |
| legacy | 列表为空 = 未声明 → `allows` **全部放行**，`missing=[]`（不裁剪） |
| 不认识 | 不影响放行；单独进 `unknown`，供排障 |
| 版本 | `version` 只是回执里的服务端常量，**不参与任何判定** |

**帧 → cap 映射**（`eventAllowed`，在通道白名单之前执行）：

| 帧类型 | 需要的 cap | 说明 |
|---|---|---|
| `approval` / `approval_gone` / `question` / `question_gone` | `parts.approval` | 四个共用一组；`parts.questions` 当前**不是**独立门禁 |
| `stream/delta` / `stream/end` | `parts.stream` | 不声明就只等整段 `assistant/message` |
| `subagent/activity` | `subagents.panel` | |
| `plan/mode` | `plan.read` | |
| `schedule/changed` | `schedule.read` | |
| `__force_resync` | 无 | 控制帧，任何客户端都放行（设计如此，见 18.7） |
| 其余（`event` 包装层、`ui/card`、`ui/slot`、`pulse`…） | 无 | 前向兼容：未登记类型不裁 |

`VIEW_CARD_CAP` 把 10 种 `card.kind` 映射到 9 个 `view.cards.*`（`caps` 卡复用 `view.cards.status`）；
`filterViewCards` 对未知 kind **不裁**。`/v2/schedule|plan|subagents|answer|action|card` 另有
`caps_denied` 硬门禁（`schedule.read/write`、`plan.read/write`、`subagents.panel`、
`questions.answer`、`cmd.ui`）。

### 18.2 手机端实际声明（原文）

`src/main/java/com/oilquiz/app/ai/tool/RemoteCaps.java`：

```java
public static final int VERSION = 1;

public static final String[] ALL = {
        "view.cards.status", ... "cmd.ui", "cmd.shell",
};

/** 逗号分隔（HTTP query / hello body 都用这一种表示） */
public static String csv() {
    return String.join(",", ALL);
}
```

`RemoteDshTool.openLiveSocket`（WS，`/ws/events` 与 `/ws/ui` 都用它）：

```java
String url = wsBase + path + "?session_id="
        + java.net.URLEncoder.encode(sid == null ? "" : sid)
        + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv())
        + "&token=" + java.net.URLEncoder.encode(tool.getToken());
```

SSE（`RemoteDshTool.streamOnce`）：

```java
String url = tool.getBaseUrl() + "/events?session_id=" + ... + "&since=" + since
        + "&follow=" + ... + "&timeout=" + ...
        + "&snapshot_threshold=" + ...
        + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8")
        + (types == null || types.isEmpty() ? "" : "&types=" + ...);
```

结论：

- **声明链路是齐的**：WS/SSE 两条链路都带同一个 `caps=` query；`/v2/events/pull`、
  `/v2/view`、`/v2/schedule`、`/v2/plan`、`/v2/subagents` 也带；`/v2/hello`、`/v2/answer`、
  `/v2/action`、`schedule`/`plan` 写操作在 JSON body 里带 `caps`。**没有** `X-Dsh-Caps`
  之类的 header。
- **声明内容是全集**：`RemoteCaps.ALL` 与 `SERVER_CAPS` 25 项同名同量（`RemoteCapsTest`
  与新增 `caps.test.mjs` 双向比对），**声明层零缺失**。
- **版本号没有上行**：`RemoteCaps.VERSION = 1` 只存在于本地；请求里只有 CSV，
  `client.ver="2.0"` 是 App 版本而不是 caps 协议版本。
- **hello 回执从未消费**：`RemoteSdk.hello()` 实现了但**全仓库无调用点**；WS 的
  `hello` 帧以前只取 `seq`/`anchor`，服务端 `caps` 块没人解析，也没有任何校验/日志。

### 18.3 差距表

**cap 层（25 项）**：手机端全部声明，故"缺失后果"一列描述的是**若未声明**会怎样，
用来确认每一项都真的有用；不是当前缺陷。

| cap 名 | 服务端是否需要 | 手机是否声明 | 缺失后果（若未声明） |
|---|---|---|---|
| `view.cards.status` | 是（卡片裁剪） | 是 | 状态卡被 `filterViewCards` 裁掉 |
| `view.cards.checks` | 是 | 是 | 自检卡不显示 |
| `view.cards.connections` | 是 | 是 | 连接呼吸卡不显示 |
| `view.cards.session` | 是 | 是 | 会话卡不显示 |
| `view.cards.approval` | 是 | 是 | 审批视图卡不显示（审批**帧**另由 `parts.approval` 管） |
| `view.cards.timeline` | 是 | 是 | 时间线卡不显示；hello `features.view_timeline=false` |
| `view.cards.schedule` | 是 | 是 | 日程卡不显示 |
| `view.cards.subagents` | 是 | 是 | 子代理卡不显示 |
| `view.cards.plan` | 是 | 是 | 计划卡不显示 |
| `parts.approval` | 是（帧门禁） | 是 | `approval`/`question`/`*_gone` 全被挡，审批卡只能靠更早的弹窗 |
| `parts.questions` | 声明（**无独立门禁**） | 是 | 当前不影响：反问实际由 `parts.approval` 放行 |
| `parts.tool-cards` | 声明（无门禁） | 是 | 当前无过滤；预留给以后工具卡开关 |
| `parts.data-cards` | 声明（无门禁） | 是 | 当前无过滤；预留 |
| `parts.stream` | 是（帧门禁） | 是 | `stream/delta`/`stream/end` 不下发，正文只能等整段 |
| `events.stream` | 声明（无门禁） | 是 | 当前无过滤；语义上表示"能消费事件流" |
| `events.replay` | 声明（无门禁） | 是 | 当前无过滤；重放实际由 `seq/since` 决定 |
| `events.ack` | 声明（无门禁） | 是 | ack 接口不校验，缺了也能 ack |
| `schedule.read` | 是（403 门禁） | 是 | `/v2/schedule` 回 `caps_denied`，日程页空 |
| `schedule.write` | 是（403） | 是 | 建/改/跑日程 403 |
| `plan.read` | 是（403） | 是 | `/v2/plan` 403，计划卡不可用 |
| `plan.write` | 是（403） | 是 | 切换计划模式 403 |
| `questions.answer` | 是（403） | 是 | `/v2/answer` 403，反问点不了 |
| `subagents.panel` | 是（403 + 帧门禁） | 是 | `/v2/subagents` 403；`subagent/activity` 不下发 |
| `cmd.ui` | 是（403） | 是 | `/v2/action`、`/v2/card`、`phone_ui` 全部 403 |
| `cmd.shell` | 声明（无门禁） | 是 | 当前无过滤；预留给 shell 类命令 |

**协议/链路层（真正的缺口）**：

| # | 缺口 | 体检结论 | 缺失后果 |
|---|---|---|---|
| ① | caps 版本不上行 | 手机只发 CSV，`VERSION` 留在本地；服务端 `version` 是常量 | 两端协议版本漂移在握手里不可见 |
| ② | hello 回执不消费 | `RemoteSdk.hello()` 无调用点；WS hello 的 `caps` 从未解析 | 手机不知道 `missing`/`unknown`，插件也不知道手机缺什么 |
| ③ | 握手帧无 caps 摘要 | 改前：WS hello 只有 `features/limits/anchor`；SSE `ready/snapshot` 没有 `caps` | 手机没有 `[caps]` 日志，排障只能猜 |
| ④ | `/status` 无客户端视角 | 只有 `ws_clients`/`ws_channels`/`ws_stats` | 数得清连接数，看不出"哪条连接缺 `parts.approval`" |
| ⑤ | 手机无只读 getter | `RemoteConnection` 没有 caps 摘要字段 | 设置页没有数据源 |
| ⑥ | `/ws/alert` 从未打开 | 手机只开 `/ws/events` + `/ws/ui`；审批/反问已并入 `/ws/events` 白名单 | 不是 caps 缺失；极端 slot 洪水时审批与 slot 共用一条 TCP（本次不改） |
| ⑦ | `parts.questions` 无独立门禁 | 反问与审批共用 `parts.approval` | 只声明 `parts.questions` 的客户端收不到反问（当前手机两者都声明） |
| ⑧ | `__force_resync` 无 caps 门禁 | 控制帧，任何客户端都收 | 设计口径：旧客户端也靠它重建；加门禁会卡死旧端（本次不改） |

### 18.4 改法（只加，不改既有语义）

| 位置 | 改动 |
|---|---|
| `tools/.../caps.js` | 新增 `clientCaps(raw)`：`{version, legacy, declared[], declared_count, missing[], unknown[]}`。`summarize()`（`declared` 布尔 + `client[]`）**原样保留**，`/v2/hello` 兼容旧口径 |
| `tools/.../index.js` | ① `/status` 增加 `caps`：`{version, server_count, clients:[{session_id, transport, channel, version, legacy, declared[], declared_count, missing[], unknown[]}]}`（数据源 `STREAM_HUB`，每条 SSE/WS 连接一条）；② WS `hello` 帧带 `caps: clientCaps(caps)`；③ SSE `ready`/`snapshot` 帧也带 `caps`（两条链路同口径）；④ SSE 订阅者补 `transport:'sse'`/`channel:'/events'` 元信息（此前只有 WS 有） |
| `RemoteCaps.java` | **未改**：25 项本来就齐，不删不增 |
| `CapsSummary.java`（新增） | 纯 JVM 解析/格式化：兼容新 `clientCaps` 与旧 `summarize` 两种形状；`line()` 输出 `[caps] 声明=25 缺失=0 未知=0 version=1 来源=ws:hello`；`local()` 兜底时 `缺失=?`，不假装零缺失 |
| `RemoteDshTool.java` | `DshSocketListener` / `DshEventListener` 各加 `default onHello(JSONObject)`；WS `hello/ready`、SSE `ready/snapshot` 把原始帧交给上层 |
| `RemoteChannel.java` | 收到握手帧 → `CapsSummary.parse(frame.caps)` → `Log.i("DshProbe", line)` + 回调 `Listener.onCaps`；服务端没带摘要（旧插件）时用本地声明兜底，每个通道生命周期只打一次；新增 `lastCapsSummary()` |
| `RemoteConnection.java` | 存 `volatile CapsSummary lastCapsSummary`，新增只读 `lastCapsSummary()`（**不做 UI**） |

### 18.5 声明格式与 `/status` 字段（契约）

**客户端 → 服务端**：`caps=cap1,cap2,…`（URL-encoded CSV）

- WS：`/ws/events?session_id=&caps=&token=`、`/ws/ui?…`（同一 URL 拼装函数）；
- SSE：`/events?session_id=&since=&follow=&caps=`；
- 其它：`/v2/events/pull`、`/v2/view`、`/v2/schedule`（GET catalog 与 POST 写）、`/v2/plan`、
  `/v2/subagents` 用 query；`/v2/hello`、`/v2/answer`、`/v2/action`、`phone_ui` 用 JSON body
  的 `caps` 字段。**无** `X-Dsh-Caps` header。

**服务端 → 客户端**（`/status` 的 `caps.clients[]`、WS `hello.caps`、SSE `ready/snapshot.caps`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `version` | int | caps 协议版本（当前 `CAP_VERSION=1`，服务端常量） |
| `legacy` | bool | true = 客户端没带 caps，服务端不裁剪 |
| `declared` | string[] | 客户端**已声明**的能力（不是布尔；旧 `summarize` 的布尔同名，已在新接口避开） |
| `declared_count` | int | `declared.length` |
| `missing` | string[] | 服务端有、客户端没声明的；legacy 恒为 `[]` |
| `unknown` | string[] | 客户端声明了但服务端不认识 |

`/status.caps.clients[]` 额外带 `session_id`、`transport`（`ws`/`sse`）、`channel`
（`/ws/events` `/ws/ui` `/events`）。手机端 `RemoteConnection.lastCapsSummary()` 与
`RemoteChannel.lastCapsSummary()` 返回同一份 `CapsSummary`。

### 18.6 验证

**A. 插件纯逻辑（新增 `caps.test.mjs`）**：11 项全过——解析/去重/大小写、legacy 全放行、
`approval/question/*_gone` 要 `parts.approval`、缺 `parts.approval` 拒绝、
`__force_resync` 不受 caps 门禁、`stream/plan/schedule/subagents` 按各自 cap、
`clientCaps` 字段、`summarize` 旧形状不变、与 `RemoteCaps.ALL` 双向同名。

**B. 插件真 WebSocket + 真 HTTP（`ws_frames.test.mjs`，随机端口，不碰 8218）**：10 项全过，
新增两项断言：

```text
ok  握手：/ws/events 的 hello 声明了 event / __force_resync / approval / question
    （含 hello.caps：version=1、legacy=false、declared_count=3、missing 不含 parts.approval）
ok  /status 按连接列出 caps 摘要（declared/missing/unknown/version）
```

**C. 手机 JVM 单测**：

```text
CapsSummaryTest   tests=8 failures=0 errors=0
RemoteCapsTest    tests=3 failures=0 errors=0
BUILD SUCCESSFUL（exit 0；testDebugUnitTest UP-TO-DATE）
```

**D. 编译/装机**：`.\gradlew.bat --offline assembleDebug --console=plain` → `BUILD SUCCESSFUL`；
`adb -s 192.168.1.10:41193 install -r` → `Success`；
APK `答题宝-debug-2.2.apk`，901,561,190 字节，sha256
`B4BFA2587ADAFF4D50BABBF59E7E924C85ED9A262F8FA521FA9FA73B537A5587`；
设备 `versionName=2.2 versionCode=4 lastUpdateTime=2026-11-15 02:12:14`。

**E. 真机（新 APK，force-stop 后冷启动）**：

```text
11-15 02:12:27.377 RemoteConnection: [DSH] open sid=session-cb82ea23-… since=15006
11-15 02:12:27.391 DshProbe: [caps] 声明=25 缺失=? 未知=0 version=1 来源=local
11-15 02:12:27.413 RemoteConnection: [DSH] connection=true WS 已连接
11-15 02:12:27.413 DshProbe: [ws] 已连接，停 SSE
```

`缺失=?` 是因为**当前运行的仍是旧插件**（见 F）——手机端新代码正确走了"服务端没回摘要 →
本地兜底"，声明数 25 = `RemoteCaps.ALL` 全集。审批链路仍通：`POST /v2/action
{action:'debug.approval'}` 推 `test-df228e3ed6a4` 后，手机
`DshSvc: [approval] 发独立审批通知 id=test-df228e3ed6a4`（随后 `decision=allow` 回收）。

**F. `/status` 新字段**：真机 8218 上的插件**未重载**（见 18.7），所以它仍回旧结构
（`ws_clients=2`，无 `caps`）。用新代码 + 手机同款 25 项 caps 的探针（假 ctx、随机端口、
真 WS/HTTP）得到的等价 curl 片段：

```json
hello.caps = {"version":1,"legacy":false,"declared":[...25 项...],
              "declared_count":25,"missing":[],"unknown":[]}
/status.caps = {
  "version": 1, "server_count": 25,
  "clients": [{
    "session_id": "caps-probe-session", "transport": "ws", "channel": "/ws/events",
    "version": 1, "legacy": false,
    "declared": [...25 项...], "declared_count": 25, "missing": [], "unknown": []
  }]
}
```

**G. 插件重载**：`plugin_manager set_plugin target=include:smartquiz-remote enabled=false`
→ `application:"failed", error:"HMR transactions cannot be nested", changed:false`；
插件仍在运行（`/status` 正常、`ws_clients=2`），按约束**不重启 DSH**，新 `/status`/hello
要重启 DSH 后生效。

### 18.7 边界与没做到

- **声明层没有可补的 cap**：手机端 25/25 已声明，本次没有新增/删除任何能力名；
  补的是"回执消费 + 可观测性"，不是声明。
- **版本号仍不上行**：没有加 `caps_version` query / header，也没有在 `allows` 里校验版本；
  `version` 字段目前只是服务端常量。真正的版本协商（不兼容时降级）未做。
- **`parts.questions` 仍未独立门禁**：反问继续由 `parts.approval` 放行；拆开会让
  "只声明 parts.questions" 的客户端行为变化，本次按"不改既有语义"不动。
- **`__force_resync` 仍未门禁**：理由见 18.3 ⑧。
- **`/ws/alert` 仍未打开**：审批/反问走 `/ws/events`，功能可用；审批与 slot 抢同一条 TCP
  的风险仍在，属端点分工遗留，不在 caps 范围。
- **`cmd.shell` / `parts.tool-cards` / `parts.data-cards` / `events.stream` / `events.replay` /
  `events.ack` 是"声明但服务端未用作门禁"**：保留为前向契约，不删（删了会破坏两端同名约定）。
- `/status` 的 `clients[]` 来自 `STREAM_HUB`，只覆盖**当前订阅中的** SSE/WS 连接；
  `pull`、`/v2/view` 这类短请求不出现（它们没有长连接身份）。
- 手机端设置页 UI 未做（按要求只暴露 getter）。
- **插件未能在真机重载**：`HMR transactions cannot be nested` 挡住 `set_plugin`，真机 8218
  仍是旧代码——`/status` 的 `caps` 字段与 WS hello 的 `caps` 只在 Node 自测/探针里验证过；
  真机日志因此走的是 `缺失=? 来源=local` 兜底，不是服务端 `missing=[]`。重启 DSH 后即可看到
  `缺失=0 来源=ws:hello`。
- **审批真机只验到"帧到达手机"**（独立审批通知 + `test-*` id + 应答 `allowed-once`），
  没有在手机 UI 上人工点"允许"（远程操作界面不做）；caps 放行逻辑由 `caps.test.mjs` /
  `ws_frames.test.mjs` 的断言覆盖。
- **caps 版本未上行**：见 18.7 前面的说明；本次没有真实版本不兼容样本可验。

## 19. 尾段增量投影（2026-11-15，§19 TAIL-PROJECTION）

### 19.1 病历：批很小，投影却是全量

事件分流（§15）落地后，`AIChatActivity` 每 16ms 一批（≤50 条）连续 `DshTimeline.reduce`，
帧末只调一次投影。但投影入口仍是：

```java
DshProjection.project(live, dshTimeline, key -> dshComponentId(key, live.turnId), now);
// 等价于 project(..., 0, Integer.MAX_VALUE)
```

一段 DSH 回合在手机上按 6 步一条消息派发（`T12-G2`），可**流式期间只有一条气泡**：
每批事件（真机 1–10 条）都重建整条消息的 `roundSnapshots / components / content`，
已经写完的段（例如 8 步回合的 G1）被反复重建。dsh-Go 的做法是「只重排尾段」：
行主键带起点、已完成行缓存不重建。本次把同一口径搬到手机端。

验收口径：**优化后「已完成段重建」必须为 0**。

### 19.2 封口判据（`SegmentSealPolicy`，纯 JVM）

一条回合按 6 步切段（与历史导入/旧 `splitLiveIntoSegments` 同粒度）：

| 概念 | 规则 |
|---|---|
| 段数 | `ceil(总步数 / 6)`，0 步 = 0 段 |
| 第 k 段范围 | `[6k, 6k+6)`（最后一段由总步数截断） |
| 封口 | `段号 < 最后一段`，**或**该回合已经 `turn/end` |
| 活动段 | 流式期间**永远是最后一段** |

```java
public static boolean isSealed(int segmentIndex, int lastSegmentIndex, boolean hasActiveWork) {
    if (segmentIndex < 0) return true;
    if (segmentIndex < lastSegmentIndex) return true;
    return !hasActiveWork;
}
```

**为什么不用“同段但已无活动工具/流式标志”作为流式期的封口依据**：一步结束到下一步
开始之间工具确实都停了，但正文/思考随时可能再追加；按“无活动工具”封口会把最后一段
提前锁死，尾部文本再也进不去。所以流式期最后一段一律算活动段，只有“不再是最后一段”
或“回合结束”才封口 —— 这也让**纯尾部文本追加**天然不触发任何封口段失效。

### 19.3 缓存与失效条件（`SegmentProjectionCache`，纯 JVM）

- **缓存载体就是段消息本身**：封口段的 `ChatMessage` 不再调用 `project()`，实例、已绑定
  的 `roundSnapshots/components` 与 RecyclerView 视图都不动；只有活动段每帧重建。
- **段内容指纹** `Progress`：`steps / textChars / thinkingChars / runningTools /
  finishedTools / toolResultChars / cards / approvals`。`plan()` 每帧对每个封口段比一次
  指纹，相等就跳过（计入 `skippedSealed`）。
- **失效条件**（每条都有单测）：

| 条件 | 判定 | 结果 |
|---|---|---|
| 新增段 | 段数增加 / 新槽位未投影 | 只投影新段；旧段仅内容变了才收口 |
| 工具结果晚到 | 段内 `running/finished/resultChars` 变化 | 重建该封口段 |
| 卡片增删 | `roundOfCard` 落在该段的卡片数变化 | 重建该封口段 |
| 审批插入 | 该段审批计数变化（消息级字段） | 重建该段 |
| 权威正文晚到 | 段内 `textChars/thinkingChars` 变化 | 重建该段 |
| 空步补内容 | 段内 `steps` 变化 | 重建该段 |
| **纯尾部文本追加** | 只改最后一段 | **封口段不失效** |
| 跨段瞬间 | 段从活动变封口且内容有变 | 一次“收口”投影（单列，不算封口重建） |

### 19.4 Activity 接线

- `ensureMirrorBubble()` 同回合返回**活动尾段**（审批/反问消息级状态写尾段），
  `dshMirrorMessage` 仍是本回合第 0 段（`mirrorUserMessage` 插用户消息的锚点要往前插）。
- 新回合/新会话/清空/快照：`resetDshSegments()` 清空段列表、段审批计数与守卫计数；
  第 0 段是 `ensureMirrorBubble` 建的原气泡，后续段在流式期按 6 步边界用
  `ChatIdDispatcher.nextDshSegmentId()`（`DSH_SEGMENT`，`T12-G1`）新建，组件 id 按
  `:s{段号}` 分桶（与旧拆分一致）。
- 删除了 `splitLiveIntoSegments()`：原来一轮结束才拆，现在流式期已经按同一粒度落屏，
  `turn/end` 再拆会重复插段。
- `projectDshTail()` 只对 `plan.rebuilds()` 调 `project(seg, timeline, ids, now, from, to)`；
  投影后按对象定位 `safeNotifyItemChanged()`（封口段重建不会走 `pushLiveSync`，那只刷尾段）。
- `pushLiveSync()` 不再用 `dshTimeline.answer()/thinking()` 覆盖正文：那是**整轮**文本，
  会把段消息写成全文；正文/思考只由按区间的投影写。
- **真机抓到的坑（turn 92 → 93）**：一帧里同时有上一轮 `turn/end` 和下一轮 `step/start`
  （重连补拉会这样），`ended` 是整批标志，直接拿来封当前时间线的口，会把新回合第一段
  误封成已完成段 → 下一帧 `重建=封口1`。修法：只有 `endedMessages` 包含当前尾段时
  才算本回合结束（`endedHere`），并把它用于投影与 `pushLiveSync`。

### 19.5 守卫计数口径

投影入口（`projectDshTail` 的 `markProjected`）记账，一行到 `DshProbe`：

```
[proj] 段=总/封口/活动 重建=封口N 活动M 耗时=Xms
```

| 字段 | 口径 |
|---|---|
| `段=总/封口/活动` | 当前时间线切出的段数 / 已封口段数 / 活动段数 |
| `重建=封口N` | **上一次投影决策时已封口**、本帧又调 `project()` 的次数；验收目标 = 0 |
| `活动M` | 活动段 + 新建段 + 封口瞬间“收口”投影（允许且必要） |
| `耗时=Xms` | 本帧所有 `project()` 调用的总耗时（单帧只 1–2 次） |
| 归零 | 新回合 / 快照重建时 `reset()`；按回合看验收 |

口径刻意把“封口瞬间的收口投影”排除在 `封口N` 之外：那是一次段内内容的最终落屏，
不是“已完成段被反复重建”；否则每跨一段都会误报 1。

### 19.6 真机数据

设备 `25113PN0EC` / `192.168.1.10:41193`；装机 APK sha256
`A03850C7DB0D7379FA34C19BC5DAF21A4DBA0821490A2E06414AAEB1F75C7D9D`。

**优化前（HEAD 真机基线，8 分钟）**：`[batch]` 14 行全部 `投影=1`，每行都是一次
`project(msg, timeline, ids, now)` 全量投影（示例 `[batch] n=10 归约=9 跳过=1 投影=1
耗时=3ms`）。窗口内跟随会话的轮次都 ≤6 步，所以这批基线没直接打出“封口段”数字；
按旧实现口径折算：同一个 8 步 / 2 段回合，旧实现每帧全量投影都会重建已完成段 G1
（本轮的 9 个投影帧 → 9 次）。

**优化后（真机串行 8 步回合）**：turn 94 有 step1..step8，`[proj]` 共 22 行，其中
9 行进入两段态，**封口重建始终为 0**：

```
[proj] 段=1/0/1 重建=封口0 活动12 耗时=1ms
[proj] 段=2/1/1 重建=封口0 活动13 耗时=1ms   ← 第 7 步跨进 G1，只新建/投影 G1
[proj] 段=2/1/1 重建=封口0 活动14 耗时=1ms
[proj] 段=2/1/1 重建=封口0 活动15 耗时=0ms
[proj] 段=2/1/1 重建=封口0 活动16 耗时=0ms
[proj] 段=2/1/1 重建=封口0 活动17 耗时=0ms
[proj] 段=2/2/0 重建=封口0 活动17 耗时=0ms   ← turn/end 收口，封口段不再重建
```

| 指标 | 优化前（同 8 步 / 2 段口径） | 优化后（真机 turn 94） |
|---|---|---|
| 每次投影覆盖 | 整条消息（0..MAX） | 活动段 `[6k,6k+6)` |
| 已完成段重建 | 9 次（9 个投影帧 × G1） | **0 次**（0/22 行） |
| 段数 | 流式期 1 条（结束才拆） | 流式期即时 2 条（A1 + G1） |
| 单次投影耗时 | 未单独打点（`[batch]` 3–8ms 含归约） | **0–1ms**（22 帧平均 0.18ms） |

落盘核对（`files/ai_chat_history.json`）：串行 8 步回合保存为两条消息 ——
`T1013-A1`（6 rounds / 10 components，前 6 步）+ `T1013-G1`（3 rounds / 6 components，
尾部工具与总结正文）；第二轮 `T1016-A1` + `T1016-G1` 同构。截图
`build/proj-verify/chat-now.png`：尾段只含 echo 7/echo 8 与总结，前 6 个 echo 在上一段。
说明消息流/工具卡/正文仍正确，且相邻两段是独立消息（不再是一轮结束后重新拆）。

单测：新增 `SegmentSealPolicyTest` 4 项 + `SegmentProjectionCacheTest` 13 项，全绿
（封口判定 / 新增段 / 工具结果晚到 / 卡片增删 / 审批插入 / 纯尾部文本不失效 /
收口一次 / 正常增长全程封口=0）。全量 `testDebugUnitTest` 仍有既有失败
（如 `DshProjectionMetaTest` 期望 `props.meta` 是 JSONObject，HEAD 投影写入的是 String），
与本次改动无关。

### 19.7 边界 / 没做到

- **文本旁路未做**：活动段的纯文本追加仍走区间 `project()`（0–1ms），没有改成
  `updateAIMessageContent` 之类的轻量更新。原因：投影同时要维护
  `roundSnapshots/contentRoundBounds/components`，只改 `content` 会让三份视图不一致。
- **审批是消息级字段**：审批/反问落在段消息的 `msg.approvals/questions` 上，
  不进 `DshProjection`。守卫里按段记了审批计数（失效口径），但审批实际只写当前尾段；
  封口段事后收到审批时，投影重建不会搬动审批卡，只保证指纹口径可见。
- **子代理卡不在指纹里**：`subagent_card` 是 `deck_only`，且投影会把当前全部子代理
  写进每段；封口段里的子代理卡可能落后于甲板实时值（消息流不画它，落盘数据除外）。
  本次未把子代理指纹纳入（纳入会让子代理活跃时所有封口段每帧失效）。
- **同一帧里跨两轮**：`endedHere` 已避免把新回合第一段误封；但如果“上一轮结束”和
  “下一轮开始”在同一帧，上一轮的多段不会补最后收口投影（下一轮开始会 reset 段列表）。
  实际事件流里极少见，重连补拉时可能出现。
- **快照重建**：`applySnapshotResync` 会 reset 段缓存并对所有已落屏段重投影一遍；
  这是数据源整体换掉，守卫计数随 reset 归零，不计入“封口重建”。
- **未改**：`EventBatchQueue` / `EventFlowPolicy` 参数与语义、插件、卡片渲染。
- **卡片跨段重复**：`DshProjection` 仍把全轮卡片/子代理写进每个段（旧拆分也这样），
  本次未改投影的卡片归属，避免动渲染口径。
