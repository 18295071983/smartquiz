# DSH 远程 UI · 总体设计

- 版本：v2.0（2026-11-13）
- 关联：`docs/dsh-remote-ui-architecture-v2-2026-11-13.md`（调查与决策依据）、`10-modules.md`（分模块）、`90-tests-acceptance.md`（测试与验收）
- 状态：设计定稿，实现按 P0→P4 推进

---

## 1. 目标

让 DSH（电脑端）的**状态与业务**能出现在手机聊天页，且**新增业务只改服务端**：

1. 状态可见：推理进度、后台任务、目标、权限、日程等不挤占聊天区。
2. 内容可扩展：服务端下发数据即可渲染新卡片/表单，客户端不发版。
3. 交互可回传：点击/输入/选择能回到电脑端，成为会话的一部分。
4. 不重造：渲染、交互、配置、落盘全部复用既有基础设施。

## 2. 非目标

- 不下发代码、不动态加载类（无类加载器）。
- 不做任意复杂动画/增量 patch（布局组件是整树渲染）。
- 不改 ID 派发器语义、不改消息存储格式。

## 3. 分层

```
┌ 传输层 ─────────────────────────────────────────────────────┐
│ /events（durable 事件）· ui/slot（插槽帧）· /v2/view（快照卡）│
│ /cmd 轮询（反向指令）· /session action（审批/卡片上行）        │
└──────────────┬──────────────────────────────────────────────┘
┌ 逻辑层 ──────▼──────────────────────────────────────────────┐
│ DshTimeline（纯归约，无 Android 依赖）                        │
│ DshProjection（时间线 → ChatMessage.components/roundSnapshots）│
│ DshUiCatalog（事件/卡片/槽位三张表 + 版本闸）                  │
└──────────────┬──────────────────────────────────────────────┘
               │ ChatMessage（落盘真值）
┌ UI 层 ───────▼──────────────────────────────────────────────┐
│ AssistantTurn → parts → PartRendererRegistry → TurnPartsHost │
│ ComponentRegistry → 现成卡 / layout 组件 / DynamicCardView    │
│ StatusDeckController → 甲板三档（D1 进度 / D2 摘要 / D3 插槽） │
└──────────────┬──────────────────────────────────────────────┘
               │ 用户动作（actions[]）
┌ 交互层 ──────▼──────────────────────────────────────────────┐
│ ComponentActions.perform → ActionSink(source)                │
│   dsh → /session action=component_action                     │
│   本机 → PythonToolManager.notifyChatComponentResult          │
│ 审批 → /session action=approval；反问 → /v2/answer            │
└──────────────────────────────────────────────────────────────┘
```

## 4. 模块清单

| 模块 | 职责 | 主要落点 | 阶段 |
|---|---|---|---|
| **M1 状态甲板** | 三档状态合成、显隐、视口通知、插槽清理 | `StatusDeckController`（新）+ `DockSlotBus` | P0 |
| **M2 动态卡** | 服务端数据 → `ComponentData` → 现成卡/layout 组件 | `DshProjection` + `ComponentRegistry` | P2 |
| **M3 配置表** | 事件/卡片/槽位三张表 + 版本闸 + 覆盖链 + 监听 | `DshUiCatalog`（新） | P3 |
| **M4 交互上行** | 动作分流与回传，替代前缀嗅探 | `ActionSink`（新）+ `ComponentActions` | P1 |
| **M5 插件事件** | 事件枚举、插槽帧、动态卡构造、caps 裁剪 | `index.js` / `caps.js` | P4 |
| **M6 消息转换** | 事件 → 时间线 → 消息；字段容错 | `DshTimeline` / `DshProjection` | 已有 |
| **M7 落盘恢复** | 与在线/本地 agent 同构的持久化 | `ChatHistoryManager` / `ChatIdDispatcher` | P1 |

## 5. 数据流

```
插件事件（durable）           ui/slot 帧                   用户动作
      │                          │                            │
      ▼                          ▼                            ▼
DshTimeline.reduce ──► DshProjection ──► ChatMessage.components ──► ComponentRegistry
      │                          │                                  │
      └── phaseText/cards ───────┴──► StatusDeckController ◄── DockSlotBus
                                             │
                                    onViewportChanged → StickToBottom
```

- **渲染真值**：`components` + `roundSnapshots` + `content` + `bounds`（与在线/本地 agent 同构）
- **令牌**：`assistantTurn` 可重建，DSH 侧恒为 `null`
- **节流**：投影 80ms、UI 120ms、流式 20 token/50ms

## 6. 状态机

| 状态 | 进入条件 | 甲板表现 |
|---|---|---|
| 空闲 | 无生成、无工具、无插槽 | 甲板 GONE |
| 生成中 | `AgentProgressBus` 有快照 | D1 可见；D3 只留 1 条 |
| 工具中 | `AgentActivityBus` 有活动 | D1 + D2 |
| 断连 | `RemoteConnection` 离线 | 清空插槽 + 状态栏红点 |
| 删除/切会话 | 会话切换 | `DockSlotBus.clear()` |

## 7. 七条硬约束（实现时不得违反）

1. 甲板**不能是 RecyclerView item**（adapter position 即 `chatHistory` 下标）。
2. 甲板显隐改变列表高度时**必须调 `onViewportChanged`**。
3. 动态卡必须是 `ComponentData`（否则不落盘）。
4. 新增 `ChatMessage` 字段必须同步 `deepCopyForSave` 白名单与 `clone()`。
5. 组件 id 以**所属消息的 turnId** 作前缀，且跨重投影稳定。
6. 不新增 part 类型（5 个必改点，漏一个整条消息回落 legacy）。
7. 交互只走三条既有上行，不新造协议。

## 8. 与工作区规范

- 新增逻辑进新类（可 JVM 测）；`AIChatActivity`/`ChatAdapter`/`index.js` 只留接线（对应 ROADMAP §2.1 拆分清单）。
- 提交按 `feat:/fix:/docs:/refactor:/test:` 前缀。
- 资源命名 `activity_*` / `item_*`。

## 9. 阶段总览

| 阶段 | 内容 | 工期 | 依赖 |
|---|---|---|---|
| P0 | M1 甲板合并 + `DockSlotBus.clear()` | 1 天 | — |
| P1 | B2/B4/B9 等缺陷 + M4 ActionSink + M7 对齐 | 1-2 天 | — |
| P2 | M2 动态卡（layout + actions） | 1-2 天 | P1 |
| P3 | M3 配置表 + 下发 | 1-2 天 | — |
| P4 | M5 插件动态卡 + caps 一致性 | 1-2 天 | P2/P3 |
