# DSH 远程 UI 架构：插槽 / 动态渲染 / 配置下发 迭代设计

> **已被取代（2026-11-13）**：本文是第一版设计稿，结论与优先级已被
> [`docs/dsh-remote-ui-design/00-overall.md`](dsh-remote-ui-design/00-overall.md)（总体）、
> [`10-modules.md`](dsh-remote-ui-design/10-modules.md)、
> [`90-tests-acceptance.md`](dsh-remote-ui-design/90-tests-acceptance.md)、
> [`99-review-findings.md`](dsh-remote-ui-design/99-review-findings.md) 取代。
> 保留本文仅为追溯设计演进；**实现以设计目录下的四份文档为准**。

- 日期：2026-11-13
- 范围：AI 对话页插槽布局、DSH 消息转换器、动态渲染交互组件、配置表与下发机制、插件服务端
- 状态：**设计稿（待评审）**，本轮不改代码
- 关联代码：`AIChatActivity` / `ChatAdapter` / `DshTimeline` / `DshProjection` / `ComponentRegistry` / `PartRendererRegistry` / `ChatIdDispatcher` / `ChatHistoryManager` / `tools/dsh-plugin-smartquiz-remote`

---

## 0. 结论摘要（先看这段）

调查后有三个判断，直接决定设计走向：

1. **动态渲染层不该新造**。项目里已经有 67 个卡片组件 + `ComponentRegistry`（按 category 分类）+ `PartRendererRegistry`（part 类型 → 渲染器）+ `DynamicCardView` / `LayoutPrimitiveCardView`（通用/图元布局）。正确做法是**扩展注册表**，不是再写一整套渲染器。
2. **插槽冲突是布局问题，不是数据问题**。现状：`dock_slot_host`(404) 与 `agent_progress_slot`(416) 上下紧邻、都在输入区上方，都表达"正在进行中"。要合并成**一个状态甲板容器**，用显式优先级排序，而不是各占一块。
3. **配置下发的正确形态是"三张表 + 一个版本 + 一条帧"**，而不是若干散落常量。客户端只认表，不认硬编码。

据此给出 P0–P4 五步迭代，每步可独立验收、可回滚；P0 只动布局（小时级），P3 才碰服务端。

---

## 1. 现状调查

### 1.1 对话页布局（插槽冲突现场）

`src/main/res/layout/activity_ai_chat.xml` 自顶向下：

| 行 | 视图 | 作用 |
|---|---|---|
| 32 | `service_status_bar` | 顶部服务状态（模型/服务连接） |
| 404 | **`dock_slot_host`** | 2026-11-13 新增的 DSH 插槽容器 |
| 416 | **`agent_progress_slot`** | 推理状态机 + 长会话工具进度（标题/百分比/进度条/消息/元信息） |
| 482 | `queue_bar` | 排队消息条 |
| 611 | `tool_drawer` | 底部工具抽屉 |

**冲突本质**：`dock_slot_host` 就插在 `agent_progress_slot` 正上方，两者都在输入区上方、都在表达"电脑端正在做事"。推理进行时（progress 可见）+ DSH 插槽（jobs/goal/…）同时出现，聊天区被挤、语义重复。

### 1.2 消息流

```
插件 /events (SSE)
→ RemoteConnection / RemoteChannel
→ AIChatActivity.onChannelEvent        落屏确认（处理完才推进游标）
   └ handleChannelEvent                __force_resync / seq 去重 / 控件帧过滤 / user 镜像 / 插槽
      └ applyDshEvent(raw, live, idx)
         ├ dshTimeline.reduce(DshEvent)      纯归约：按 (turn, step) 归并、seq 去重
         ├ DshProjection.project(...)        写 content / thinkingContent / roundSnapshots / components
         └ pushLiveSync → notifyItemChanged  80ms 节流
```

### 1.3 渲染管线

```
ChatAdapter.bindAIMessage
 └ bindWithParts
    ├ AssistantTurn.fromMessageForParts(msg)      完成态：取 roundSnapshots + components
    └ AssistantTurn.fromMessageForStreaming(msg)  流式：取 contentRoundBounds 切片
         ↓ 现建 Part(id, type, ...)
    TurnPartsHost：按 part id 复用视图 + 按内容指纹只重绑变化段
```

| Part 类型 | 渲染器 |
|---|---|
| `reasoning` | `ReasoningPartRenderer` |
| `text` | `TextPartRenderer` |
| `tool_call` / `tool_result` / `tool_group` | `ToolCallPartRenderer` / `ToolResultPartRenderer` / `ToolGroupPartRenderer` |
| `approval` / `question` | `ApprovalPartRenderer` / `QuestionPartRenderer` |
| `error` | `ErrorPartRenderer` |
| `data` | `DataPartRenderer`（组件卡） |

**part id 规则**：工具用 `toolCallId`；组件用 `data:<组件id>`；轮次用 `callId(turnId, n)`。**id 稳定 = 视图复用成立的前提**。

### 1.4 组件体系（内置 + 动态）

- 卡片组件 67 个：`TableCardView` `StepsCardView` `ProgressCardView` `TodoCardView` `ChartCardView` `MetricCardView` `ListCardView` `CodeCardView` `MarkdownCardView` `HtmlCardView` `ImageGridCardView` `FileCardView` `DshCardViews` …
- 注册与分类：`ComponentRegistry`、`ComponentTypes`（`content/data/media/task/scene`）
- 通用/动态：`DynamicCardView`、`LayoutCardView`、`LayoutPrimitiveCardView`、`LayoutCanvasManager`
- 动作：`ComponentActions`、`CardActionTarget`、`ChatComponent`、Activity 侧 `handleAction`

**结论**：动态渲染的"版式库"已经存在（表格/步骤/进度/待办/图表/指标/列表/代码…），缺的只是**表单类交互**（按钮组/输入/选择/勾选）与**服务端帧到组件的映射**。

### 1.5 插槽系统

`DockSlotBus`（2026-11-13）：`Slot(id, priority, title, detail, action, warn)`，同 id 覆盖、按 priority 排序、注册回放；`renderDockSlots` 最多铺 3 行、11sp、两行截断、可点。

生产方：`ui/slot`（插件下发）、以及更早就有的 `job/*`、`goal/*`、`compaction/*` 事件（已统一到插件侧派生）。

### 1.6 历史系统

- 会话 JSON + 按会话历史文件；`HISTORY_PAGE_SIZE = 50`（本轮下调）
- 会话元数据 `maxTurnId`；启动 `getMaxTurnNumberAcrossSessions()` 把 ID 派发器抬到全库最高（**只读元数据，不解析消息**）
- 回收站：`conversations_trash/` + `history/<批次>/`；恢复入口 `restoreTrashedSession`

### 1.7 ID 系统

`ChatIdDispatcher`：主 id `Tn`（持久化计数器，跨进程不回收）+ 子 id `T{n}-{码}{k}`（按 `主id|码位` 分桶）；`adoptIds` / `seedFromTurnIds` 负责恢复认领；`raiseMasterFloor` 抬下界。

**保持不变**。动态 part 的组件 id 复用现有 `dshComponentId(key)` 表（key→id），不新开号段。

### 1.8 原生交互

- 审批：`ApprovalPartRenderer` + `onApprovalPartDecision` → 回电脑端
- 反问：`QuestionPartRenderer` + `onQuestionPartAnswer`
- 卡片动作：`handleAction` 统一入口
- 长按/复制/重试等：`ChatAdapter` 内部回调 + `OnActionClickListener`

### 1.9 配置表与更新（已有先例，直接复用）

- `ToolGuideConfigLoader`：`filesDir/tool_guide_flow.json`（覆盖档）→ `assets/...`（随包档）→ 兜底
- `ConfigSyncManager`：assets 默认 → 本地缓存 → 远程 fetch → 版本比较 → 落库

**即：覆盖链与远程更新都有现成范式，无需发明。**

---

## 2. 问题清单

| # | 问题 | 影响 | 证据 |
|---|---|---|---|
| P-1 | `dock_slot_host` 与 `agent_progress_slot` 位置冲突 | 推理进行时聊天区被挤、语义重复 | 布局 404 / 416 |
| P-2 | 转换器字段拼写只认一种 | 工具卡整张丢失 | `DshTimeline` `tool/call`（本轮已修）|
| P-3 | 组件 id 挂在本地 `currentTurnId` | 恢复时按回合聚合归错组 | `dshComponentId`（本轮已修）|
| P-4 | 缺"服务端下发任意 part"的通路 | 新功能必须发版 | 无 `ui/part` 帧 |
| P-5 | 缺表单类交互组件 | 服务端无法下发按钮组/输入/选择 | 组件库无 Form 类 |
| P-6 | 事件类型散落在插件与转换器两处 | 换 DSH 版本易漏 | 插件 `EVENT_CATALOG`（本轮已建）/ `DshTimeline` switch |
| P-7 | 配置表无版本/无下发 | 改配置必须发版 | 无 `ui/config` |

---

## 3. 目标与非目标

**目标**

1. 插槽与推理状态机在同一容器内共存，显式优先级，永不互相挤压。
2. 服务端可下发新 part（展示/交互）而**客户端零改动**。
3. 事件类型、part 版式、槽位三张表**可远程更新**，带版本与回滚。
4. 交互结果（点击/输入/选择）可回传服务端，通路幂等。
5. 全部复用现有组件与 parts 管线，不新造渲染框架。

**非目标**

- 不新增视觉形态（配置表只能选已有版式，不能凭空画新图形）。
- 不改 ID 派发器语义。
- 不引入新的消息存储格式（动态 part 仍落在 `components`）。

---

## 4. 总体架构（分层）

```
┌ 传输层 ─────────────────────────────────────────────┐
│ /events SSE：event / view / pulse / ui/slot / ui/part / ui/config │
│ /v2/*：status、jobs、goal、permission、action、events/catalog     │
└───────────────┬─────────────────────────────────────┘
                │ DshEvent
┌ 逻辑层 ────────▼─────────────────────────────────────┐
│ DshTimeline（纯归约，无 Android 依赖）                │
│ DshProjection（时间线 → ChatMessage：roundSnapshots / components）│
│ DshConfigStore（三张表 + 版本 + 覆盖链）              │
└───────────────┬─────────────────────────────────────┘
                │ ChatMessage（持久化）
┌ UI 层 ─────────▼─────────────────────────────────────┐
│ AssistantTurn.parts → PartRendererRegistry → TurnPartsHost │
│ ComponentRegistry → 卡片组件（67 个，含 Dynamic/Layout）    │
│ DynPartBinder（type=dyn → kind → 已有卡片 / FormCardView） │
└───────────────┬─────────────────────────────────────┘
                │ 用户动作
┌ 交互层 ───────▼─────────────────────────────────────┐
│ ComponentActions / handleAction → approval/question    │
│ PartActionRouter → 回传（ui/slot 或 ui/part 的 action）│
└──────────────────────────────────────────────────────┘
```

---

## 5. 插槽系统重设计（解决 P-1）

### 5.1 合并容器

把 `dock_slot_host` 与 `agent_progress_slot` 合并为**一个** `status_deck` 容器（沿用 `agent_progress_slot` 所在位置），内部固定三档：

| 档 | 内容 | 显示条件 | 上限 |
|---|---|---|---|
| D1 | 推理状态机（现有 progress 面板） | 生成/工具进行中 | 1 行（不可折叠） |
| D2 | 长会话工具行（现有工具进度摘要） | 有活动工具 | 1 行 |
| D3 | DSH 插槽（`DockSlotBus`） | 有插槽 | **D1 可见时 1 行，否则 2 行** |

规则：D1 可见时，D3 只保留**最高优先级一条**；D1 不可见时 D3 可铺 2 行。D2 与 D3 互不占用对方名额。

### 5.2 槽位预留（配置表 `slots` 段）

```
deck.progress    D1 固定
deck.tool        D2 固定
deck.1 / deck.2  D3 动态（服务端占用）
```

> 之前把 `dock.1~3` 三个都算动态，实际与 D1/D2 抢位置。修正为 **D3 只有 2 个动态槽**，且受 D1 可见性约束。

### 5.3 验收

- 推理进行时：恰好看到 D1 + 最多 1 条 D3，聊天区高度不减少超过 N dp（实测记录）。
- 推理结束后：D3 可扩到 2 条。
- 冲突用例进单测：`DockSlotBus` 的"同一 id 覆盖"与 deck 的截断逻辑。

---

## 6. 动态渲染与交互（解决 P-4/P-5）

### 6.1 part 模型（复用现有）

服务端下发的动态 part **写成 `ComponentData`**：

```java
ComponentData { id: "Tn-Ck", type: "dyn", props: { kind, title, rows[], chips[], actions[], ... } }
```

链路：`DshProjection` 写入 `msg.components` → `AssistantTurn` 生成 `TYPE_DATA` part（id=`data:<组件id>`）→ `TurnPartsHost` 复用/指纹重绑 → `DataPartRenderer` 里 `type=="dyn"` 委托 `DynPartBinder`。

**收益**：持久化、恢复、增量渲染、按 id 复用全部免费；不新增存储格式。

### 6.2 kind → 已有卡片（复用清单）

| kind | 复用组件 | 备注 |
|---|---|---|
| `table` | `TableCardView` | 列头 + 行 |
| `steps` | `StepsCardView` | 当前步 |
| `progress` | `ProgressCardView` | 百分比 + 文案 |
| `todo` | `TodoCardView` | 勾选态 |
| `chart` | `ChartCardView` | 数据序列 |
| `metric` | `MetricCardView` | 大数字 |
| `list` | `ListCardView` | 多行 |
| `code` | `CodeCardView` | 语言标签 |
| `markdown` | `MarkdownCardView` | |
| `html` | `HtmlCardView`（沙箱） | 需白名单 |
| `imageGrid` | `ImageGridCardView` | |
| `file` / `fileList` | `FileCardView` / `FileListCardView` | |
| `diff` | `DiffCardView` | |
| `layout` | `LayoutCardView`（图元） | 服务端给布局 JSON |

### 6.3 需要新增的（唯一缺口：表单类）

新增 **`FormCardView`**（一个组件覆盖 5 种交互，避免 5 个类）：

- `actions`：按钮组 → 回传 `{slot, action}` 
- `input`：单行文本 → 回传 `{slot, action, value}` 
- `select`：选项组 → 回传 `{slot, action, value}` 
- `checkbox`：多选 → 回传 `{slot, action, value[]}` 
- `toggle`：布尔 → 回传 `{slot, action, on|off}` 

统一约束：**点击后立即置灰**（幂等）、回传带 `msgId + partId + actionId`。

### 6.4 回传协议

```
手机 → POST /v2/action  { action:"ui.part", slot_id, part_id, action_id, payload }
插件 → 广播 { type:"ui/part/action", data:{...} }（不解释语义，只做投递）
```

服务端若需要业务处理，在插件里对 `slot_id/action_id` 做路由（插件是服务端，改它不用发版）。

### 6.5 验收

- 本地构造一个 `dyn` 组件（不经服务端）能渲染出 table/steps/actions 三种，且滚动复用不闪。
- 按钮点击回传一次，第二次点不动（置灰）。
- 落盘后重进会话，动态卡仍在、id 不变。

---

## 7. 配置表体系（解决 P-6/P-7）

### 7.1 三张表，一个文件

`assets/dsh_ui_catalog.json`（可被 `filesDir/dsh_ui_catalog.json` 覆盖）：

```json
{
  "version": 1,
  "events": [
    { "type": "tool/call", "forward": true, "desc": "工具调用" },
    { "type": "some/new-event", "forward": true, "render": "row", "desc": "新类型也有兜底" }
  ],
  "parts": [
    { "kind": "table", "card": "TableCardView", "fields": ["columns","rows"] },
    { "kind": "actions", "card": "FormCardView", "fields": ["actions"] }
  ],
  "slots": [
    { "id": "deck.progress", "fixed": true },
    { "id": "deck.tool", "fixed": true },
    { "id": "deck.1", "max_rows": 1 },
    { "id": "deck.2", "max_rows": 1 }
  ]
}
```

### 7.2 加载与更新

- 加载链：`filesDir` → `assets` → 内置兜底（照 `ToolGuideConfigLoader`）
- 更新：插件的 `/v2/events/catalog` 带 `version`；手机连上后比对，服务端更新则拉取并落 `filesDir`
- 推送：SSE `{ type:"ui/config", data:{version, catalog} }`，手机校验（JSON 合法 + version 递增）后原子替换（写临时文件 + rename）
- 回滚：保留上一版 `dsh_ui_catalog.json.bak`，校验失败自动回退

### 7.3 客户端如何使用

| 表段 | 用途 | 不命中时 |
|---|---|---|
| `events` | 决定未识别事件是否"渲染成一行" | 丢弃 + 打点 |
| `parts` | `kind → 卡片`映射 | 渲染成纯文本卡（兜底） |
| `slots` | 槽位与行数上限 | 用内置默认 |

---

## 8. 插件（服务端）设计

| 能力 | 现状 | 目标 |
|---|---|---|
| 事件枚举 | `EVENT_CATALOG` 33 条 + `/v2/events/catalog` | 加 `version`，与配置表同源 |
| 插槽下发 | `ui/slot` | 不变（title/detail 兼容旧客户端） |
| 动态 part | 无 | 新增 `ui/part` 帧（id/kind/props） |
| 配置下发 | 无 | `ui/config` + 版本协商 |
| 能力声明 | `/status` | 加 `features: ["ui.slot","ui.part","ui.config"]`，手机按能力降级 |

**兼容策略**：老客户端不认 `ui/part` 会忽略（未知 type 丢弃），不影响旧功能；新客户端遇到老插件则只用 `ui/slot`。

---

## 9. 迭代计划（每步可独立验收）

### P0 插槽整合（纯布局，半天）

- 合并 `status_deck`，D1/D2/D3 三档 + 可见性规则
- 验收：推理中聊天区不被挤；截图对比；`DockSlotBusTest` 补截断用例
- 回滚：还原布局 + `renderDockSlots`

### P1 配置表骨架（1 天）

- `DshUiCatalog`（解析 + 覆盖链 + 版本）、`dsh_ui_catalog.json`
- 暂不接任何渲染，仅 `/v2/events/catalog` 与本地表对齐校验
- 验收：单测（解析/版本/覆盖链）；表损坏时走兜底

### P2 动态 part 渲染（2 天）

- `dyn` 组件类型 + `DynPartBinder`（kind→已有卡片）+ `FormCardView`
- 本地构造 `dyn` 组件验证（不经服务端）
- 验收：5 种展示 + 5 种交互；复用不闪；落盘恢复 id 不变

### P3 服务端下发（2 天）

- 插件 `ui/part` + `ui/config` + `features`
- 手机：`ui/config` 落盘、`ui/part` 投影
- 验收：改插件配置 → 手机重启后生效；新增 kind 不改客户端可显示

### P4 回传闭环 + 加固（1 天）

- `ui.part` 动作回传、幂等置灰、插件广播
- 验收：单测 + 真机点击回路；重复点击不重复发送

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 指纹抖动（props 顺序不同 → 重绑）| 写 props 时按 key 排序；`DshProjection` 统一构造 |
| part id 漂移 | 复用 `dshComponentIds`（key→id）；同一 key 不重新发号 |
| 落盘膨胀 | 只落最终态 part；流式临时帧不写 components |
| 配置表损坏 | 临时文件 + rename + `.bak` 回退；解析失败用内置默认 |
| 新旧混用 | `features` 能力协商；`ui/slot` 与 `ui/part` 并存 |
| 插槽再冲突 | deck 三档固定优先级；D3 受 D1 可见性限制 |

---

## 11. 附录：关键文件

| 用途 | 文件 |
|---|---|
| 对话页布局 | `src/main/res/layout/activity_ai_chat.xml`（404/416/482）|
| 插槽总线 | `ai/chat/status/DockSlotBus.java`、`AIChatActivity.renderDockSlots` |
| 消息转换 | `ai/remote/DshTimeline.java`、`DshProjection.java`、`DshEvent.java` |
| parts 管线 | `ai/chat/turn/AssistantTurn.java`、`ai/chat/parts/*` |
| 组件体系 | `ai/chat/component/ComponentRegistry.java`、`ComponentTypes.java`、`DynamicCardView` |
| ID / 历史 | `ai/chat/ChatIdDispatcher.java`、`ai/util/ChatHistoryManager.java` |
| 配置范式 | `ai/agent/ToolGuideConfigLoader.java`、`ai/usage/ConfigSyncManager.java` |
| 插件 | `tools/dsh-plugin-smartquiz-remote/index.js` |

---

## 12. 勘误：与既有架构的对齐（2026-11-13 复核后新增）

写前 12 节时我没有先对齐 `docs/remote_dsh-architecture-review.md`。复核后**有三处结论需要修正**——既有架构已经提供了本文提出的机制，正确做法是"挂上去"，不是"再造一套"。

### 13.1 既有约定（证据）

| 约定 | 出处 |
|---|---|
| **一个源、一条流、一套视图**：状态由服务端派生 ViewModel，两端渲染同一份 | `remote_dsh-architecture-review.md` §2 |
| **手机端通用 card renderer = `RemoteCardView`**，已支持 `status/checks/connections/session/approval/timeline/caps` | 同上 §2.4、未落地表 #4 已标 ✅ |
| **能力协商 `RemoteCaps`（14 项，含 `cmd.ui`）**，服务端按 caps 裁剪卡片与事件 | 同上 §2.5 / `RemoteCaps.ALL` |
| **事件带唯一 `id` + 单调 `seq`**；数据卡按 `ComponentData.round` 插到对应步骤后 | 同上 §2.3 / §2.4 |
| **服务端事件 → 手机原生卡片**是既定模式（12 类事件卡片） | 提交 `907ceda3`、`e23eca1e` |
| **反向指令通道**（电脑端 → 手机）已跑通，含通知按钮回传 | 提交 `f8de9fe1`、`ce7f9660`、`c781a1d5` |

### 13.2 修正一：动态渲染层 = 扩展 `RemoteCardView`，不是新渲染器

- 原设计（§6）：新增 `DynPartBinder` + `FormCardView` + `kind→已有卡片` 映射。
- **修正**：动态卡片走 `RemoteCardView`（它已经是"通用 card renderer"）。需要补的是：
  1. `RemoteCardView` 的 kind 集合扩到配置表里声明的那些（table/steps/progress/form…）；
  2. 复杂卡片复用 `ComponentRegistry` 里的现成 View（`TableCardView`/`StepsCardView`/`ProgressCardView`），`RemoteCardView` 只做分发；
  3. **表单类**（actions/input/select/checkbox/toggle）确实是缺口，作为 `RemoteCardView` 的一个 `form` kind 新增，不另立 `FormCardView` 体系。

### 13.3 修正二：下发走既有 ViewModel，不新增 `ui/part` 帧

- 原设计（§8）：新增 SSE `ui/part` + `ui/config`。
- **修正**：`/v2/view` 已经是"服务端派生 ViewModel"的通道，卡片就是 ViewModel 的字段。动态卡直接作为 ViewModel 里的 `cards[]` 下发（`kind + props`），**不新增事件类型**；配置表随 ViewModel 的 `ui` 段下发（`{version, catalog}`），手机缓存到 `filesDir` 后热应用。
- 好处：复用已有的 `caps` 裁剪、`ComponentData.round` 步骤插入、`/v2/view` 幂等重放与补拉。

### 13.4 修正三：回传走 `cmd.ui`，不新增 `/v2/action` 动作

- 原设计（§6.4）：`POST /v2/action {action:"ui.part"}`。
- **修正**：反向指令通道已存在（`cmd.*`，`RemoteCaps` 里有 `cmd.ui`）。交互回传用 `cmd.ui`：
  ```
  手机 → {type:"cmd.result", cmd_id, kind:"ui.action", payload:{slot_id, action_id, value}}
  ```
  插件按 `slot_id/action_id` 路由（插件是服务端，改它不发版）。

### 13.5 修正后的迭代计划

| 步 | 内容 | 变化 |
|---|---|---|
| P0 | `status_deck` 合并（D1/D2/D3 三档） | 不变 |
| P1 | 配置表骨架（`filesDir→assets` 覆盖链 + 版本） | 不变 |
| P2 | `RemoteCardView` 扩 kind（复用现成 View）+ 新增 `form` kind | **替代**原 `DynPartBinder`/`FormCardView` |
| P3 | 服务端在 ViewModel `cards[]` 里下发动态卡 + `ui` 配置段 | **替代** `ui/part`/`ui/config` 帧 |
| P4 | 交互回传走 `cmd.ui` | **替代** `/v2/action` |

### 12.6 一句话结论

> 本文 §6–§8 的"新渲染器 / 新帧 / 新回传动作"作废；**动态卡 = ViewModel 的 cards + `RemoteCardView` 的 kind 扩展 + 配置表控制 kind 映射 + `cmd.ui` 回传**。§5 的插槽 deck 合并、§7 的配置表版本与覆盖链、§10 的风险对策仍然有效。

---

## 13. 待评审决策
1. `status_deck` 合并是否接受（P0 唯一有视觉变化的步骤）？
2. 动态 part 是否认可"写进 `components` 复用 parts 管线"（而不是新开渲染通道）？
3. 配置表是否三合一（一个文件三段）还是拆三个文件？
4. `ui/part` 帧的字段命名是否沿用 `kind/title/rows/chips/actions`？

---

## 14. 实证调查结论（五路并行核查，2026-11-13）

本节把 §12 里仍属推测的部分换成了**带 file:line 的实证**。结论：动态卡不需要动 part 层，难度比 §6 预估低一个量级。

### 14.1 动态卡：链路已经通了 80%

```
服务端 ComponentData{id, type:"dynamic", props:{card:"table", ...}}
 → DataPartRenderer（无需改）
 → ComponentRegistry.render：查 "dynamic" 失败 → DynamicCardView 兜底（通配 *dynamic*）
 → 【唯一缺口】DynamicCardView 里按 props.card 二次分发给目标组件
```

证据：`DataPartRenderer.java:22-24`（注入 `ComponentRegistry.render`）、`ComponentRegistry.java:184-199`（未知 type → dynamicFallback）、`DynamicCardView.java:40-47`。

**因此不需要**：`TYPE_*` 常量、新 PartRenderer、`PartRendererRegistry.createDefault()` 注册、`ChatAdapter` allowed 白名单、`ComponentTypes.SCHEMA`。原因：加 part 类型有 5 个必改点，**漏一个就整条消息回落 legacy**（`ChatAdapter.java:3531-3543`、`PartRendererRegistry.java:51-64`）。

### 14.2 `/v2/view` 的卡是快照，不是事件派生

- `buildView` 硬编码 7 种 kind（status/checks/connections/session/approval/timeline/caps），**没有事件→卡片的通用转换**（index.js:2483-2591）。
- `caps.js` 预留的 `schedule/subagents/plan` **没有构造点**，永远出不来（caps.js:46-57 vs index.js）。
- **两条通道口径不一致**：GET `/v2/view` 走 `filterViewCards` 裁剪（caps.js:106-115），SSE 的 `pushView` **没传 caps**（index.js:3291-3293）。
- `RemoteCardView` **没有 kind 分发**，kind 只用于兜底标题；未知 kind 只要有 `rows/items/messages` 或 `detail/error` 就通用渲染，否则丢卡（RemoteCardView.java:54-97、143-164、210-222）。

### 14.3 交互回传：复用 `component_action`

- `component_action`（index.js:4431-4468）**把点击变成 user 消息注入会话，agent 可见**——这是现成的"一个源一条流"回传，不需要新协议。
- `cmd.ui` 只是 `caps.js:41` 的协商标签，**index.js 里没有任何处理函数**；`CMD_ALLOW` 里是 `ui_component` 等 5 个工具。
- `/cmd` 是**手机轮询**（3659-3729），无 SSE 推送。
- `RemoteCardView` 的审批点击**没接线**：`approval_id` 只挂行 tag，`approvalIdOf` 无调用者。

### 14.4 插槽：130dp ≈ 聊天区 23%

- 可见状态类只剩 4 个（v2 外壳已隐藏 service_status_bar / remote_dsh_bar / thinking_indicator / session_stats_bar，`ChatUiV2Chrome.doApply:127-158`）。
- 推理中 + 3 条 dock ≈ **130dp**；800dp 屏上聊天区损失 ≈ **23%**（`slot-layout` 实测估算）。
- **真 bug**：`DockSlotBus.clear()` **全仓无调用方** → 生成结束插槽仍占 2 行。
- 合并方案：删 `dock_slot_host`(403-410)，把 `agent_progress_slot`(415-425) 原地扩成 `status_deck`，一个背景一个 margin；总行数 3；D1 可见时 D3 只留 1 条；`queue_bar` 保持独立。

### 14.5 配置表：抄三家，各取一段

| 来源 | 取什么 | 丢弃什么 |
|---|---|---|
| `ToolGuideConfigLoader` | 两级加载骨架、`ERROR/WARN` 校验、ERROR 整份拒绝 | 它**没有内置兜底**（§7 写错了），也没有版本闸 |
| `ProviderConfigManager` | 字符串版本 `YYYY-MM-DD-vN` + "覆盖档必须严格新于随包"闸、热更新写盘 | — |
| `McpMarketCatalog` | 条目级 `problems` 容错 + 契约单测 | 它是死资源（无覆盖档）|
| ~~`ConfigSyncManager`~~ | **不用** | Room 四表、无内容校验、无事务、缓存层断线、无调用方、无测试 |

**热更新通知**：三个装载器都没有监听器；可抄 `DockSlotBus.addListener`（注册即回放当前值）或 `ThemeManager.notifyThemeChanged` + CopyOnWrite。

### 14.6 两个必须先修的 bug（与动态卡无关，但会互相放大）

1. `dshComponentId` 的 key **不含 turnId**（`"card:"+key` / `"tool:"+callId`，DshProjection.java:101/128）→ 同一 key 跨回合出现 → `data:<id>` 撞车 → `TurnPartsHost.viewById` 只留一张卡。
2. props 指纹**不排序**（`(type+"|"+props.toString()).hashCode()`，AssistantTurn.java:1213-1216）→ 服务端换 key 顺序就变指纹，多一次重绑（闪）。

### 14.7 修正后的迭代计划

| 步 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **P0** | `status_deck` 合并 + `endGeneration` 补 `DockSlotBus.clear()` + 插件 `ui/slot` 加 caps 门（`eventAllowed`） | 半天 | 推理中聊天区高度损失 < 5%；结束 2 秒内插槽消失 |
| **P1** | 修两个 bug（key 加 turnId、props 排序）+ 契约单测 | 1 天 | 跨回合同名卡不撞、换 key 顺序不重绑 |
| **P2** | `DynamicCardView` 加 `props.card` 二次分发 + `DshUiCatalogLoader`（三段 + 版本闸 + 内置兜底 + 监听总线） | 1-2 天 | 本地构造 dynamic 组件能渲染 table/steps/actions |
| **P3** | 插件：`buildView` 追加动态卡 + `VIEW_CARD_CAP` 映射 + `pushView` 补 caps | 1-2 天 | 服务端加卡不改客户端；两条通道裁剪一致 |
| **P4** | 交互回传走 `component_action`；顺带接回 `RemoteCardView` 的审批点击 | 1 天 | 点击后电脑端会话里出现 user 消息 |

### 14.8 一句话总结

> 不用造渲染层：**卡片已有兜底（`DynamicCardView`），回传已有通路（`component_action`），配置已有三套范式可抄**。真正要写的只有：deck 合并、两个 bug 修复、`DynamicCardView` 的一层 `props.card` 分发、一张配置表、插件侧动态卡构造点。
