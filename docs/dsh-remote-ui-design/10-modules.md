# DSH 远程 UI · 分模块设计

- 版本：v2.0（2026-11-13）
- 上位文档：`00-overall.md`；调查依据：`../dsh-remote-ui-architecture-v2-2026-11-13.md`
- 每个模块：职责 / 接口 / 依赖 / 改动面 / 验收 / 风险

---

## M1 状态甲板（P0）

### 职责
把"推理进度 + 工具活动 + DSH 插槽"合成一个容器，三档显示，控制总行数与会话结束时的清理。

> **收敛（v2.2，依据 `99-review-findings.md` §14–§15 与 DSH 官方文档，以此为准）**
> - **建模照 DSH Slots**：dock 是 `single`，D3 行是内部 `list`；**scope = session**（避免 `ui/slot` 全局广播串会话）
> - **先声明后注册**：未声明的 slot key 直接拒绝（DSH 在插件激活时失败），从源头阻止 B8 类漂移
> - **D1 数据源分模式**：本机 → `AgentProgressBus`；**DSH → `live.inferenceProgress` / `setDshPhaseText`**（DSH 从不写 AgentProgressBus）
> - **D2 数据源**：服务端 `/status.text_lines`（不本地拼文案）
> - 回调 `onDeckHeightChanged(wasVisible, nowVisible)`；Host 内**先取 `isPinned()`** 再改可见性，post 后调 `onViewportChanged`
> - `refresh()` 触发集合写死：slot / 进度 / queue / ticker
> - **注册不要在 `:824`**（早于 `stickToBottom` 与 `setAdapter`）；放 `onResume` 或 post 到下一帧；**必须可注销**
> - `clear()` 由 Host 注入，不转调 `AgentProgressBus.clear()`（其静态 Handler 会让 JVM 测试崩）
> - **DSH `turn/end` 不走 `endGeneration()`** → 清理点：DSH ended / 切会话 / 断连，且只清 transient

### 接口（新建 `ai/chat/status/StatusDeckController`）
```java
interface Host {
    void showD1(boolean visible);          // 推理进度
    void showD2(String text);              // 工具/摘要行（空串隐藏）
    void renderD3(List<DockSlotBus.Slot>); // 插槽，内部按上限截断
    void onDeckVisibilityChanged(boolean visible);  // 宿主据此调 onViewportChanged
}
StatusDeckController.bind(Host host);
void refresh();   // D1‖D2‖D3 变化后调用
void clear();     // 会话结束/断连
```

### 规则
- 总行数 ≤3；D1 可见时 D3 ≤1，否则 ≤2
- `D1‖D2‖D3` 全空 → 整块 GONE，不留 padding
- `onDeckVisibilityChanged` 是**唯一**允许改列表高度的入口

### 改动面
- 布局：删 `dock_slot_host`，`agent_progress_slot` 扩为 `status_deck`
- `AIChatActivity`：只保留 `bind()` + 三个回调的接线（≤20 行）
- `DockSlotBus`：`clear()` 在 `endGeneration` 与断连处被调用（B1）

### 验收
- 推理中聊天区损失 <5%（现状 ~23%）
- 生成结束 2s 内 D3 消失
- 甲板显隐时贴底不裁切（静止状态同样测）

### 风险
- 视口通知漏调 → 贴底留裁切；用"显隐必走 Host 回调"的单一入口规避
- `btn_load_more_history` 与甲板同列 → 甲板放列表下方，天然不撞

---

## M2 动态卡（P2）

### 职责
服务端只下发数据，客户端渲染出卡片/表单，并把交互结果回传。

### 载荷优先级（v2.2 收敛：官方载荷优先）

| 优先级 | 载荷 | 来源 | 说明 |
|---|---|---|---|
| **1** | **`tool/result.meta`** | DSH 官方 | JSON 序列化在源头校验、replay 复现同一张卡；**`DshProjection` 现在完全没读（B17）** |
| 2 | `layout` 组件 | `ComponentRegistry` | 任意嵌套 + 64 图元 + 表单，适合无工具归属的展示卡 |
| 3 | 现成卡 | `progress_card`/`steps_card`/`todo_card`/`table_card`/`tool_call` | 单点状态；契约固定 |
| 4 | 注册类型 | `UIComponentTypeRegistry` | 语义化复用组合（`render.card`/`render.layout`） |

**边界（B14/§15.2）**：仓外插件**无法**新增持久化事件类型，所以"新卡"只能走 `meta` 或 `components`，不能靠发明事件。

### 关键约束
- 只用 `components` 承载（自动落盘/恢复），**不新增 part 类型**
- 卡片是快照，不能原地 update → 连续进度走 M1 的插槽
- 表单值由 `ComponentActions.collectValues` 收集，随动作回传
- `component_id` 由投影显式写入 props，不依赖 `T{n}-C{k}` 推断

### 验收
- 服务端只改 JSON → 重启后渲染新界面（不发版）
- 表单提交 → 电脑端会话出现 user 消息
- >150 节点被拒时有可见提示

### 风险
- props 顺序漂移 → 指纹抖动（B3：序列化前排序）
- 组件 id 跨回合撞车（B2：key 加 turnId）

---

## M3 配置表（P3）

### 职责
一张表三段（events / parts / slots），带版本闸与三级回退。

### 接口（新建 `ai/remote/DshUiCatalog`）
```java
String version();                       // "2026-11-13-v1"
boolean forwards(String eventType);     // 未登记 → 按兜底策略
String cardFor(String kind);            // kind → 卡片/组件
int maxRows(String slotId);
void invalidate();                      // 覆盖档更新后调用
void addListener(Runnable);             // 注册即回调一次（照 DockSlotBus）
```

### 加载链
```
filesDir/dsh_ui_catalog.json（覆盖档，必须严格新于随包）
→ assets/dsh_ui_catalog.json（随包）
→ 内置兜底（最小集合）
```

### 规则
- 校验 `ERROR` 整份拒绝并回退；`WARN` 保留
- 写盘：临时文件 → 校验 → rename，保留 `.bak`
- 组件类型表**复用** `ui_component_types.json`

### 验收
- 改覆盖档重进会话生效；损坏时自动回退且功能不失效
- `invalidate()` 后已打开页面重绘

### 风险
- 无现成监听总线 → 自建 CopyOnWrite（照 `DockSlotBus.addListener`）
- 覆盖档"旧版压新版" → 版本闸必须严格比较

---

## M4 交互上行（P1）

### 职责
把卡片/动态卡的动作分流到正确的上行通道，替代 `componentId.startsWith("dsh-")` 嗅探。

> **审查修正（v2.1）：这个接口的前置比它本身多**
> 1. **`Source` 在数据层不存在**：`ChatMessage` 只有 `ApprovalRequest.source`，`ComponentData` 无 source → 必须新增 `ComponentData.source` + 投影置值 + `deepCopyForSave`/`clone` + `focusClone` + 6 个渲染调用点
> 2. **`DshProjection` 现在根本不注入 `actions`/`component_id`** → M4 的验收前提不成立，先补投影
> 3. **保持现行为**：发送失败要**回落本机回显**（现状如此），不要严格分派
> 4. `sendComponentAction` 是**同步 HTTP（30s）**，必须移出 UI 线程并给失败回调
> 5. `Source`/`Port` 放 `ai/chat/component` 或 `ai/remote`，实现注入（避免 `ai/chat/component→ai/tool` 新依赖边）

### 接口（新建 `ai/tool/ActionSink`）
```java
void submit(String componentId, String value, Source source);
enum Source { DSH, LOCAL }
// DSH  → RemoteDshTool.sendComponentAction
// LOCAL → PythonToolManager.notifyChatComponentResult
```

### 三条既有上行
| 场景 | 通道 |
|---|---|
| 审批 | `POST /session action=approval` |
| 反问 | `POST /v2/answer` |
| 卡片/动态卡 | `POST /session action=component_action` |

### 验收
- 带 `actions` 的 DSH 卡点击后，电脑端会话出现 user 消息
- 本机卡动作仍走本地回显（不回归）

### 风险
- `source` 必须从消息/组件带下来，不能靠 id 形态猜

---

## M5 插件事件（P4）

### 职责
事件枚举、插槽帧、动态卡构造、caps 裁剪一致性。

### 改动点
- **事件目录按三层重建**（B14）：`session`（63 条真实事件）/ `host`（Cordis 事件）/ `synth`（插件合成帧），各自标 `forward`/`slot`
- 补 36 条可产出类型；把 12 条非 session 事件迁到 host/synth；加 `plugin:` 前缀放行
- `noteEventType` 对 `session-log-` 前缀静默跳过 → 改为按前缀族登记
- `buildView` 追加动态卡；`VIEW_CARD_CAP` 加 `dynamic` 映射
- **`pushView` 补 caps + `filterViewCards`**（B5）
- `ui/slot` 加 `eventAllowed` 门 + **session 作用域**（B18）；新 cap 名要先在客户端声明，否则现网收不到
- 拆出 `ui_cards.js` / `ui_catalog.js`，`index.js` 只 `import`（ESM）；改完**同步 assets 副本**

### 验收
- 服务端加卡不发版；GET 与 SSE 两条通道裁剪结果一致

### 风险
- 老客户端不认新帧 → 保持"未知 type 忽略"语义，不影响旧功能

---

## M6 消息转换（已有，已修）

### 职责
事件 → 时间线 → 消息。字段容错（本轮已修）。

### 已修
- `tool/call` 认 `callId`/`call_id`/`toolCallId`/`tool_call_id`，缺失打日志
- 组件 id 挂到气泡自己的 `turnId`
- 兜底 id 改递增（不再用 hashCode）

### 待修
- B2：`dshComponentId` key 加 turnId 前缀
- B9：`remoteCardView` 未进 `deepCopyForSave` → 远程卡重进为空

---

## M7 落盘恢复（P1）

### 职责
与在线/本地 agent 同构的持久化。

### 已确认模型
```
UI 真值 = content + roundSnapshots + components + bounds + approvals/questions
assistantTurn = 令牌（DSH 为 null）
tool_group / 展开态 / 运行统计 = transient
写盘时机 = 轮边界 / 终态
```

### 待办
- **B11（P0，最高）**：`approvals`/`questions` 非 transient 却不在 `clone()`/`deepCopyForSave` → 每次保存被清空，重启后 pending 审批消失
- **B12（P0）**：`/v2/view` 远程卡重复且不可交互 → 建议整条删除（B7/B9 随之消失）
- B6：DSH 气泡 `id = subId = T{n}-A{k}`（窄修，不动派发器）
- B10：会话文件补 fsync / `.bak`（有整文件丢失窗口）
- 动态卡展开态：若需持久化，新建非 transient 字段 + 两处补拷；否则保持 transient（与现有一致）

### 验收
- 落盘后重进会话：动态卡在、id 不变、审批/问答可重试
- 崩溃后文件可读（`maxTurnId` 不倒退）
