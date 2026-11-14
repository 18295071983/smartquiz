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