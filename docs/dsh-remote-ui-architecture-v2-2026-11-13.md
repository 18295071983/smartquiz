# DSH 远程 UI 架构 v2：接入既有基础设施

- 日期：2026-11-13
- 状态：设计稿（v2，取代同日 v1；v1 的三处错误结论见 §12）
- 方法：两轮共 10 个子代理并行只读调查，结论均附 file:line
- 范围：状态甲板（插槽）、DSH 动态卡、交互上行、配置下发、插件侧、缺陷修复

---

## 0. 执行摘要

调查后最重要的结论是**减法**：你要的"动态渲染 / 交互回传 / 配置下发"三件事，项目里都已经有可用的基础设施，只是 DSH 侧没接进去。

| 你要的能力 | 已有设施 | DSH 侧要做的 |
|---|---|---|
| 服务端决定画什么（动态 UI） | `layout` 组件：`LayoutCardView` + `NativeLayoutRenderer`（11 容器 / ~70 图元 / style / 表单）+ 现成卡片（`tool_call`/`steps_card`/`progress_card`/`todo_card`） | 把事件投影成 `ComponentData{type:"layout" 或现成卡}` |
| 服务端注册新组件类型 | `UIComponentTypeRegistry.registerType`（`name → render{card\|layout}`，落盘 `ui_component_types.json`，无需发版） | 直接用，不新造 |
| 交互回传 | `actions[]` 协议 → `CardShell.addActions` → `ComponentActions.perform` → `notifyResult` | 投影时带 `actions`，回传路由改用 `ActionSink` |
| 状态展示位（插槽） | `DockSlotBus`（监听+回放、同 id 覆盖、按优先级） | 与推理状态机合并成甲板 |
| 配置下发 | `ToolGuideConfigLoader`（覆盖链）+ `ProviderConfigManager`（版本闸）+ `McpMarketCatalog`（容错） | 组合成 `DshUiCatalogLoader` |

**因此不新增**：part 类型、渲染引擎、表单组件、回传协议、配置框架。

---

## 1. 既有基础设施（10 项调查结论）

### 1.1 动态 UI 引擎（最强，之前被低估）

- `NativeLayoutRenderer`：11 容器（column/row/scroll/card/wrap/grid/space/tabs/stack/accordion/carousel）+ 约 70 图元（文本/媒体/表单/图表/二维码/日历/步骤/时间线…）+ `define/use` 模板
- `LayoutCardView`：把 `props.layout`（或 `render`）一次性渲进聊天流，失败显示"⚠ layout 渲染失败"
- 表单闭环：带 `key` 的输入控件 + `button` → `ComponentActions.collectValues` 把整树 key 值随动作回传
- 硬约束：≤150 节点 / 单容器 ≤40 子 / ≤8 层 / ≤200KB / ≤3 活画布 / ≤5 次编辑/秒；style 夹取（padding≤32dp、radius≤24dp、border≤4dp）
- **纯展示的 `LayoutCardView` 不走约束检查**（只有 `layout_canvas`/`layout_editor` 走）

### 1.2 组件注册与兜底

- `ComponentRegistry.render(context, data)`：未注册 type → `DynamicCardView`（通配）；`canRender=false` → 同样兜底；`createView` 抛异常 → 返回 null，调用方画"⚠ 错误壳"
- `DynamicCardView` 只画「标题 + 正文 + 键值行 + 折叠原始 JSON + 按钮」，**没有表格/图表/媒体**
- 运行时 `register(ChatComponent)` 可行（public + ConcurrentHashMap），但**无类加载**，服务端只能注册数据：`UIComponentTypeRegistry.registerType`（`render.card` 必须是已内置卡名）、`UIComponentPluginManager`

### 1.3 卡片契约与归属

- 卡片是**落定即快照**，不能原地 update（`AgentProgressBus` 注释、`ui_component update` 说明）→ 连续进度只能走插槽总线或重建卡
- 归属不靠 View 树：产物 props 带 `producerToolCallId/producerToolName`，`attachOwnedProducts` 折进对应 `tool_call` 的 `Part.products`
- `ComponentDisplay`：HIDDEN / PEEK / INLINE 三档；`ProductCards.mount` 是唯一挂载口

### 1.4 `/v2/view` 卡片链（**不可交互，别用**）

- `RemoteCardView` **没有 kind 分发**：kind 只用于兜底标题；未知 kind 有通用渲染（`rows/items/messages` 或 `detail/error`），否则丢卡
- 审批点击未接线：`approval_id` 只挂行 tag，`approvalIdOf` 无调用者
- cards 是**请求时快照**，`buildView` 硬编码 7 种 kind，**没有事件→卡片转换**；`caps.js` 预留的 `schedule/subagents/plan` 没有构造点
- **两条通道口径不一致**：GET 走 `filterViewCards` 裁剪，SSE `pushView` 没传 caps

### 1.5 消息流与渲染管线

```
插件 /events → RemoteConnection → onChannelEvent（落屏确认）
  → handleChannelEvent（resync/去重/控件帧过滤/user 镜像/插槽）
  → applyDshEvent → DshTimeline.reduce（纯归约）
  → DshProjection.project（写 content/thinkingContent/roundSnapshots/components）
  → ChatAdapter.bindWithParts → AssistantTurn.fromMessageForParts/ForStreaming
  → PartRendererRegistry → TurnPartsHost（按 part id 复用 + 指纹重绑）
```

- Part 类型：reasoning/text/tool_call/tool_result/tool_group/approval/question/error/data
- `data` part id = `data:<组件id>`；组件 id 必须挂**所属消息的 turnId** 作前缀
- 加 part 类型有 5 个必改点，**漏一个整条消息回落 legacy**：`TYPE_*` 常量、生产者、PartRenderer、`createDefault()` 注册、`ChatAdapter` allowed 白名单

### 1.6 对话页结构（甲板的落点约束）

- 骨架：顶栏 / 消息 FrameLayout（weight=1）/ 输入区竖列；v2 外壳（`ChatUiV2Chrome`）运行时隐藏了 `service_status_bar`、`remote_dsh_bar`、`thinking_indicator`，`ChatStatsBar` 用 `setSuppressed` 收起但**仍在刷新**
- **不变量：adapter position 严格等于 `chatHistory` 下标**（约 15 处 `notifyItemInserted(size-1)`）→ 甲板**不能做成 item**
- 贴底：`StickToBottom` 距底 ≤32dp 才 pinned；视口变化**只有 ime insets 一个** `onViewportChanged` 调用点
- 锚点：`ScrollAnchorKeeper` 在 pinned/非 IDLE/非 LinearLayoutManager 时让路
- 性能：`setItemAnimator(null)` + `setHasFixedSize(true)` + `setItemViewCacheSize(4)` + stable ids + WebView 上限 4 + 流式 20 token/50ms + UI 120ms 节流

### 1.7 插槽现状

- `DockSlotBus`：监听 + 注册回放、同 id 覆盖、按 priority 升序；UI 最多 3 条、11sp、两行截断
- **`DockSlotBus.clear()` 在生产代码里没有调用方**（只有单测）→ 生成结束/断连后插槽残留
- **高度实测**：推理中 + 3 条插槽 ≈ **130dp**，800dp 屏上聊天区损失 ≈ **23%**
- `queue_bar` 与插槽无耦合（点击语义是"切忙时模式"）

### 1.8 历史与 ID（硬约束）

- 会话文件**只存 USER/AI**；动态卡必须挂 AI 消息的 `components` 才能持久化
- `deepCopyForSave` 是**白名单补拷**（`turnId/subId/components/roundSnapshots/…`）→ 新增消息字段必须同时加进这张表
- 分段保存必须带真实 `unloadedFrontCount`（传 0 会截掉未加载头部）；`maxTurnId` 取 `max(头部, 尾部)`
- 恢复必须 seed → adopt → 补发（`ensureRecoveredIds`），且在 notify 之前
- `adoptId` 只认 `T<数字>-<码><数字>`；UUID/`dsh-<n>`/自增序号都不认
- **既存缺陷**：DSH 镜像气泡 `turnId=T{n}` 但 `id/subId` 是 UUID；导入器消息完全没有 turnId/subId

### 1.9 交互与上行

- 消息区唯一出口：`OnActionClickListener.onAction(Action{type,messageId,content})` → `handleAction`
- 卡片动作：`props.actions[]` → `CardShell.addActions` / `ComponentActions.renderActions` → `ComponentActions.perform` → `notifyResult`
- **隐藏 bug**：`notifyResult` 用 `componentId.startsWith("dsh-")` 判断是否回电脑端，但 DSH 卡 id 实际是 `T{n}-C{k}` → **回不去**；且 `DshProjection` 目前不产 `actions`
- 审批 `/session action=approval`、反问 `/v2/answer`、卡片 `/session action=component_action` 三条上行已存在
- 通知按钮只来自反向指令（`RemoteCommandExecutor` + `RemoteCmdActionReceiver` → `cmd_ack`）；DSH 审批提醒本身无按钮
- 没有 `action://` 注册表；`cmd.ui` 只是 `caps.js` 的标签，无处理函数

### 1.10 反向指令与 caps

- `/cmd` + `CMD_ALLOW`（ui_component/toast/dialog/notification/reminder）→ 手机轮询（Activity 4s / Service 5s，双执行器互斥）→ 执行 → `cmd_ack`
- `caps.js` 的 `filterViewCards` 只作用于 GET `/v2/view`
- `ui/slot` **无 caps 映射** → 永远放行、不按会话过滤

---

## 2. 不可违反的硬约束（7 条）

1. 甲板**不能是 RecyclerView item**（position 即下标）。
2. 甲板显隐改变列表高度时，**必须调用 `onViewportChanged`**（否则贴底留裁切）。
3. 动态卡必须是 `ComponentData`（`id/type/round/props`），否则不落盘。
4. 新增 `ChatMessage` 字段必须同步 `deepCopyForSave` 白名单。
5. 组件 id 必须以**所属消息的 turnId** 作前缀，且跨重投影稳定。
6. 不新增 part 类型（5 个必改点，漏一个整条消息回落 legacy）。
7. 交互必须走现有三条上行之一，不新造协议。

---

## 3. 分层设计

```
┌ 传输层 ────────────────────────────────────────────────┐
│ /events（durable 事件）· ui/slot（插槽）· /v2/view（快照卡）│
│ /cmd 轮询（反向指令）· /session action（审批/卡片上行）    │
└──────────────┬─────────────────────────────────────────┘
┌ 逻辑层 ──────▼─────────────────────────────────────────┐
│ DshTimeline（纯归约）→ DshProjection（→ ChatMessage）   │
│ DshUiCatalog（三张表：events/parts/slots + 版本）        │
└──────────────┬─────────────────────────────────────────┘
               │ ChatMessage{components:[ComponentData]}
┌ UI 层 ───────▼─────────────────────────────────────────┐
│ AssistantTurn → parts → PartRendererRegistry → TurnPartsHost │
│ ComponentRegistry → 现成卡 / layout 组件 / DynamicCardView  │
│ StatusDeck（并入 DockSlotBus + 推理进度 + ChatStatsBar 数据）│
└──────────────┬─────────────────────────────────────────┘
               │ 用户动作（actions[]）
┌ 交互层 ──────▼─────────────────────────────────────────┐
│ ComponentActions.perform → ActionSink(source)           │
│   source=dsh → /session action=component_action         │
│   本机 → PythonToolManager.notifyChatComponentResult     │
│ 审批 → /session action=approval；反问 → /v2/answer      │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 状态甲板（插槽重设计）

### 4.1 位置

- **删掉 `dock_slot_host`**(activity_ai_chat.xml:403-410)，把 `agent_progress_slot`(415-425) 原地扩成 `status_deck`
- 放**输入区竖列首位**（消息 FrameLayout 之外、`ChatInputBar` 之上），一个背景 + 一个 margin 块
- 规避 `btn_load_more_history`（占用消息 FrameLayout 顶部）：甲板在列表下方，天然不撞

### 4.2 三档

| 档 | 内容 | 数据源 | 上限 |
|---|---|---|---|
| D1 | 推理/工具进度（标题/百分比/进度条/msg） | `AgentProgressBus` + `AgentActivityBus` | 1 行（不可折叠）|
| D2 | 任务计数 + 上下文/耗时摘要 | 现有 meta 行 + `ChatStatsBar` 的实时值 | 1 行 |
| D3 | DSH 插槽（`DockSlotBus`） | 按 priority 升序 | **D1 可见时 1 条，否则 2 条** |

**总行数 ≤3**（与现护栏一致）。`queue_bar` 保持独立。

### 4.3 生命周期

- deck 显隐：`D1‖D2‖D3` 任一非空才 VISIBLE，全空 GONE 且不留 padding
- **甲板显隐必须调 `onViewportChanged`**（新增，防贴底裁切）
- `endGeneration`：`AgentProgressBus.clear()` 已有；**补 `DockSlotBus.clear()`**
- 断连/切会话：同样补 `DockSlotBus.clear()`（当前会残留）

### 4.4 验收

- 推理中聊天区高度损失 < 5%（现状 23%）
- 生成结束 2 秒内 D3 消失
- 甲板出现/消失时贴底不裁切（静止状态也测）
- `DockSlotBusTest` 补"截断到 1 条"用例

---

## 5. 动态卡（复用 layout 组件）

### 5.1 三种下发形态（按复杂度递增）

| 形态 | `ComponentData.type` | props | 适用 |
|---|---|---|---|
| 现成卡 | `progress_card` / `steps_card` / `todo_card` / `tool_call` / `table_card` | 各自契约 | 单点状态、步骤、清单、表格 |
| 纯布局 | `layout` | `{layout:{...}}` | 任意嵌套 + 70 图元 + 表单 |
| 注册类型 | `UIComponentTypeRegistry` 注册的 name | `{render:{kind:"card"\|"layout"}, ...}` | 反复使用、需要语义化名字 |

**全部无需新增 part 类型**：都走 `data` part。

### 5.2 表单与回传

- 输入控件带 `key`，`button` 点击 → `ComponentActions.collectValues` 收集整树 key 值
- 回传字段：`{component_id, value:{...}}` → `ActionSink(source=dsh)` → `/session action=component_action`
- `component_id` 由 DSH 投影显式写入 props（不要依赖 `T{n}-C{k}` 推断）

### 5.3 验收

- 服务端只改 JSON（`layout`）→ 手机重启后渲染出新界面，**不发版**
- 表单点提交 → 电脑端会话出现 user 消息
- 超大布局（>150 节点）被拒时给出可见提示，不白屏

---

## 6. 配置表与下发

### 6.1 一张表，三段

`assets/dsh_ui_catalog.json`（可被 `filesDir/` 覆盖）：

```json
{
  "version": "2026-11-13-v1",
  "events": [{ "type": "tool/call", "forward": true }],
  "parts":  [{ "kind": "steps", "card": "steps_card" }],
  "slots":  [{ "id": "deck.1", "max_rows": 1 }]
}
```

组件类型表**复用现成** `ui_component_types.json`（`UIComponentTypeRegistry`），不并入。

### 6.2 加载

照 `ToolGuideConfigLoader` 骨架 + 补它缺的两级：

```
filesDir/dsh_ui_catalog.json（覆盖档，必须严格新于随包才生效）
→ assets/dsh_ui_catalog.json（随包档）
→ 内置兜底（硬编码最小集合，保证损坏时功能不失效）
```

- 校验：`ERROR` 整份拒绝并回退；`WARN` 保留
- 版本：`YYYY-MM-DD-vN`，日期段字典序 + `-vN` 数值比较（照 `ProviderConfigManager`）
- 写盘：临时文件 → 校验 → `rename`，保留 `.bak`

### 6.3 生效

- 无现成热更新监听器 → 新增 `CopyOnWrite` 监听总线（照 `DockSlotBus.addListener` **注册即回放当前值**）
- 写盘成功后 `invalidate()` + 广播 → 已打开页面重绘；未打开页面下次读取即生效

---

## 7. 交互与上行

### 7.1 一处改造：前缀嗅探 → ActionSink

```java
// 现状（ComponentActions.java:262）
if (componentId.startsWith("dsh-")) { 回电脑端 }   // DSH 卡 id 是 T{n}-C{k} → 回不去

// 改为
ActionSink.submit(componentId, value, source);
// source 从 ChatMessage/ComponentData 带下来（复用 isDshMessage）
// source=dsh → RemoteDshTool.sendComponentAction
```

### 7.2 上行收敛

| 场景 | 上行 | 状态 |
|---|---|---|
| 审批 | `POST /session action=approval` | 已有 |
| 反问 | `POST /v2/answer` | 已有 |
| 卡片/动态卡 | `POST /session action=component_action` | 已有 |
| 通知按钮 | `cmd_ack`（反向指令） | 已有 |

手机侧只需把 `source` 判断收口，**不新增端点**。

---

## 8. 缺陷清单（P1 必修）

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| B1 | `DockSlotBus.clear()` 无生产调用方 | 生成结束/断连后插槽残留 | `endGeneration` + 断连处补调用 |
| B2 | `dshComponentId` key 不含 turnId | 跨回合同名卡 id 撞车，`TurnPartsHost` 只留一张 | key 前加 `turnId|` |
| B3 | props 指纹不排序（`props.toString().hashCode()`） | 服务端换 key 顺序 → 多一次重绑（闪） | 序列化前按 key 排序 |
| B4 | 回传用 `startsWith("dsh-")` 判断 | DSH 卡动作回不到电脑端 | 改 `ActionSink(source)` |
| B5 | `pushView` 绕过 caps 裁剪 | 两条通道口径不一致 | 补 `caps` + `filterViewCards` |
| B6 | DSH 气泡 `id=UUID` 但 `turnId=T{n}` | 恢复时无法按 id 认领/聚合 | `id = subId = T{n}-A{k}`（窄修，不动派发器） |
| B7 | `RemoteCardView` 审批点击未接线 | `/v2/view` 的审批卡点不动 | 接 `approvalIdOf` 回调（或废弃该卡） |
| B8 | caps 声明 `view.cards.schedule/subagents/plan` 但无构造点/渲染 | 声明与实际脱节 | 要么补构造+渲染，要么从 caps 删掉 |

---

## 9. 迭代计划

| 步 | 内容 | 工期 | 验收 | 回滚 |
|---|---|---|---|---|
| **P0** | 甲板合并（删 dock 容器、三档、总行 3）+ 补 `onViewportChanged` + B1 | 1 天 | 聊天区损失 <5%；结束 2s 内插槽消失；贴底不裁切 | 还原布局 + `renderDockSlots` |
| **P1** | B2/B3/B4/B6 + 契约单测 | 1-2 天 | 跨回合不撞、换序不重绑、动作能回传 | 单文件回退 |
| **P2** | DSH 投影接 `layout` 组件 + `actions[]`（本地构造验证） | 1-2 天 | 服务端 JSON 渲染出界面；表单回传成功 | 只影响 DSH 消息 |
| **P3** | `DshUiCatalogLoader`（三段 + 版本闸 + 兜底 + 监听） | 1-2 天 | 改配置重进会话生效；损坏自动回退 | 删除覆盖档 |
| **P4** | 插件：动态卡构造点 + `VIEW_CARD_CAP` + B5 | 1-2 天 | 服务端加卡不发版；两通道裁剪一致 | 插件回退上一版 |

**依赖关系**：P0 独立；P1 独立；P2 依赖 P1 的 B4；P3 独立；P4 依赖 P2/P3。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 甲板改变视口 → 贴底裁切 | 显隐处统一走 `onViewportChanged` |
| 动态卡 props 顺序漂移 → 指纹抖动 | 序列化前排序（B3） |
| 布局 JSON 过大/过深 | `LayoutCanvasConstraints` 兜底 + 可见失败提示 |
| 卡片快照不可原地更新 | 连续进度走插槽总线；状态卡用重建 |
| 落盘膨胀 | 只落最终态组件；流式帧不进 components |
| 配置表损坏 | 三级回退 + `.bak` + 整份拒绝 |
| 格式漂移（字段拼写） | 转换器容错（已修）+ 事件目录登记未识别类型 |

---

## 11. 待评审

1. 甲板合并接受吗（P0 是唯一有视觉变化的一步）？
2. `layout` 组件作为动态卡主通道，够不够？还需不需要专用卡（比如"审批+说明"组合卡）？
3. `dsh_ui_catalog.json` 三段合一，还是拆三文件？
4. B6（DSH 气泡 id 改 `T{n}-A{k}`）是否现在做——它触及落盘 id，但不动派发器本身。

---

## 12. v1 的三处错误结论（已作废）

| v1 结论 | 实际 |
|---|---|
| 需要新写 `DynamicSlotRenderer`（13 种 kind）| `layout` 组件 + ~70 图元已覆盖 |
| 需要新写 `FormCardView` | `NativeLayoutRenderer` 已有表单 + `collectValues` |
| 需要新增 `ui/part` 帧与 `/v2/action` 回传 | 走 `components` + `component_action`，不新增协议 |
| 配置表需自建"服务端下发"机制 | `UIComponentTypeRegistry` 已是现成通路 |
| 插槽可铺 3 条动态行 | 总行数 3，D1 可见时 D3 只 1 条 |

---

## 13. 证据索引

| 主题 | 关键文件 |
|---|---|
| 布局引擎 | `ai/python/NativeLayoutRenderer.java`、`ai/chat/component/LayoutCardView.java`、`LayoutCanvasConstraints.java` |
| 组件注册 | `ComponentRegistry.java`、`ChatComponent.java`、`UIComponentTypeRegistry.java`、`DynamicCardView.java` |
| parts 管线 | `turn/AssistantTurn.java`、`parts/PartRendererRegistry.java`、`parts/TurnPartsHost.java`、`parts/ProductCards.java` |
| 状态甲板 | `res/layout/activity_ai_chat.xml`(403/415/482)、`status/DockSlotBus.java`、`AIChatActivity.renderDockSlots` |
| 交互 | `component/ComponentActions.java`、`ChatAdapter.setupItemViewInteraction`、`RemoteDshTool`(approval/answer/component_action) |
| 历史/ID | `util/ChatHistoryManager.java`、`chat/ChatIdDispatcher.java`、`AIChatActivity.ensureRecoveredIds` |
| 配置范式 | `ai/agent/ToolGuideConfigLoader.java`、`ai/usage/ProviderConfigManager.java`、`mcp/McpMarketCatalog.java` |
| 插件 | `tools/dsh-plugin-smartquiz-remote/index.js`、`caps.js` |

---

## 14. 三方落盘对照（在线 agent / 本地 agent / DSH）

11 个子代理中的 3 路专门核查了这条链路，结论：**三个 agent 模式的 UI 落盘模型完全同构**，DSH 现在走的就是正确模型，只差两处补齐。

### 14.1 对照表

| 维度 | 在线 agent | 本地 agent | DSH（现状） |
|---|---|---|---|
| 上下文层 | `online_agent_history_<id>_<model>.json`：OpenAI 方言（`tool_calls`/`tool_call_id`/`pv`）+ `.round` 检查点 | `local_chat_history_<id>_default.json`：`[{role,content}]` 裸数组，300 条上限 | **无**（历史在电脑端持有） |
| 上下文写 | `AtomicFileText`（tmp + fsync + `.bak`），损坏回落 `.bak` | `new FileWriter` 直写，**非原子、无备份** | — |
| UI 真值 | `conversations/<id>.json`，整条 `ChatMessage` | 同左 | 同左 |
| UI 写 | `.tmp → rename`，无 fsync / 无 `.bak` | 同左 | 同左 |
| 工具表达 | `components[{type:"tool_call"}]` + `roundSnapshots[].toolCallIds` + `contentRoundBounds` | 同左 | 同左（`DshProjection` 直接产 `tool_call`） |
| 独立工具消息 | agent 模式 `addToolCallMessage` 直接 `return -1` | 同左 | 同左 |
| 工具组 | **不落盘**，`groupTools` 构建期重折叠 | 同左 | 同左 |
| 思考真值 | `roundSnapshots[].thinking` → turn parts | 同左 | 同左 |
| parts 真值 | 渲染时 `fromMessageForParts` 现建；落盘 `assistantTurn` 只是"生产者跑过"的令牌 | 同左 | 显式 `assistantTurn = null` |
| 审批/反问 | `approvals/questions` 落盘 + 投影成 part | 本地链路不产 | `addDshApproval/addDshQuestions` 产 |
| 汇总 | `agentSummaryInfo` transient 且**全仓无生产者**（汇总卡已删） | 同左 | — |
| 展开态 | 全 transient，重进回默认档（缺省 peek） | 同左 | 同左（`focusCollapsed` transient） |
| 保存时机 | 轮边界 flush、终态 `saveHistoryAsync` | 同左 | `turn/end` 触发保存 |
| 流式帧 | **不进文件** | 不进 | 不进 |

### 14.2 统一模型（三个模式共用）

```
UI 真值 = content + roundSnapshots + components + contentRoundBounds + approvals/questions
assistantTurn = 令牌（可重建，DSH 直接 null）
tool_group / agentSummaryInfo / 展开态 / 运行统计 = transient
写盘时机 = 轮边界 / 终态，流式帧不落
```

### 14.3 DSH 只需对齐，不需要新模型

- 动态卡 = `components` 条目（`id/type/round/props`）→ 天然落盘、天然恢复；顺序靠 `roundSnapshots`/bounds
- 工具卡已与本地/在线同构（`DshProjection` 产 `tool_call`），无需改造
- `assistantTurn=null` 与两个 agent 的"令牌"结论一致，保留

### 14.4 新增缺陷

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| **B9** | `remoteCardView` 非 transient 但**未被 `deepCopyForSave` 补拷** | DSH 远程卡重进会话后是**空卡**（`ChatAdapter` 直接 return） | 在 `ChatHistoryManager:324-335` 补一行；或废弃该字段改走 components |
| **B10** | 会话文件 `.tmp→rename` 但**无 fsync、无 `.bak`**（在线 agent 有） | 断电/崩溃可能留下半截文件 | 可选：复用 `AtomicFileText` 或补 `.bak` |

### 14.5 动态卡展开态怎么存（调查结论）

- 现有逐卡展开集合**全部 transient**（`componentBodyExpandedIds`/`toolGroupExpandedIds`/`toolRowExpandedIds`/`toolSectionExpandedIds`），重进即回默认档
- `stateKey` 用的是**已落盘的组件 id** → 若要持久化，只改存储位置即可
- 正确做法：新建**非 transient** `Set<String>`，且**两处都要补**——`deepCopyForSave` 与 `ChatMessage.clone()`；只标非 transient 会像 `thinkingExpanded` 一样**静默失效**
- **不要塞进 `ComponentData.props`**：props 是模型可写的展示声明，混写会重演 `internalTemplate` 的教训

---

## 15. 与工作区规范的符合性（补读 `DEVELOPMENT_GUIDE.md` / `README.md` / `ROADMAP.md` 后）

### 15.1 规范要求（摘录）

| 规范 | 出处 | 对本设计的要求 |
|---|---|---|
| Commit 前缀 `feat:/fix:/docs:/refactor:/test:/chore:/style:` + 功能分支流程 | DEVELOPMENT_GUIDE §八 | 本次会话的改动**尚未提交**，应按前缀切成若干提交 |
| Activity 继承 `BaseActivity` | §九 架构规范 | 新增页面必须遵守；已有 `RemoteDshInsightActivity` 是 `AppCompatActivity`（既有偏差） |
| 模块分层 Model → DAO → Repository → ViewModel → Activity | §九 | DSH 链路当前是 Activity 直连，新代码应尽量下沉到可 JVM 测的类 |
| **超大文件拆分（P0）** | ROADMAP §2.1 | `AIChatActivity` 11,691 → 今约 14,4xx 行；`ChatAdapter` 6,387；`index.js` ~3,100；`NativeLayoutRenderer` 4,189。**新功能不得继续膨胀这些类** |
| 资源命名 `activity_*` / `item_*` / `ic_*` / `bg_*` | §九 | 新增布局按此命名；配置表文件不适用（JSON 资产） |
| CHANGELOG 是变更历史权威 | README 多处指向 | 落地后按项目习惯补条目 |

### 15.2 对迭代计划的修正

原计划把甲板逻辑写进 `AIChatActivity.renderDockSlots`、把动态卡映射写进 `ChatAdapter`——这两处都在 P0 拆分清单上，**继续加代码会加剧拆分难度**。改为：

| 步 | 原落点 | 改为 |
|---|---|---|
| P0 甲板 | `AIChatActivity` 内 | 新建 `ai/chat/status/StatusDeckController`（纯逻辑：三档合成 + 显隐 + `onViewportChanged` 回调），Activity 只保留 5 行接线 |
| P1 缺陷 | 分散在 Activity/Adapter | B2/B3 在 `DshProjection`/`AssistantTurn` 内即可；B4 抽 `ActionSink`；B9 在 `ChatHistoryManager` |
| P2 动态卡 | `ChatAdapter` | 不改 Adapter：`layout` 组件走 `ComponentRegistry`，DSH 只产 `ComponentData` |
| P3 配置表 | `AIChatActivity` | 独立 `DshUiCatalog`（`ai/remote/`），无 Activity 依赖 |
| P4 插件 | `index.js` | 新增 `ui_cards.js` / `ui_catalog.js` 两个模块，`index.js` 只 require |

**原则**：新逻辑进新类（可 JVM 测），Activity/Adapter/`index.js` 只留接线。

### 15.3 本次会话的提交建议

当前 `git status` 有 56 个 `src` 文件 + 3 个 `tools` + 3 个 `docs` 未提交。按规范建议切成：

```
fix(chat): 修 converter 字段容错与组件 id 回合前缀
feat(chat): 旧消息折叠 + 渲染窗口 + 分页 50
feat(remote): 状态栏 v2 / dock 插槽 / 审批通知
feat(chat-ui): 扩展面板改版（壁纸遮罩 + 折叠 + 刷新按钮）
docs(remote): DSH 远程 UI 架构 v2
```
