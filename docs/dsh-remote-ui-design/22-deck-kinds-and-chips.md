# 22 · 甲板分类设计：开关 / 动作 / 显示 / 列表（方案 · 待审批）

> 目标：把 DSH 卡片里那些"**当前状态**"的职责接过来，让消息流只留"**发生过什么**"。
> 依据：`DshCards` 的 44 个事件映射、`RemoteStatusSlots` 的槽位发布、`DockSlotBus`/`StatusDeckController`/`DeckRowModel` 现有实现、三个 `Chip` 的接法。

---

## 1. 现状证据

- **事件→卡片 44 条**（`DshCards.map`），其中真正属于"历史"的只有少数：`assistant/message`、`command/run|done`、`approval/asked|decided`、`todo/write`、`team/message/*`、`feedback/*`、`system|developer/message`。
  其余如 `permission/preset`、`sandbox/mode`、`approval/policy`、`goal/*`、`job/*`、`subagent/descriptor`、`team/mode`、`agent-preset/selected`、`working-directory/change`、`compaction/*`、`workspace/changes`、`deliverables/presented`、`llm/retry*` 都是**"现在是什么"**，每来一次就多一张卡，时间线因此变重。
- 甲板现有能力：`DockSlotBus.Slot{id, priority, title, detail, action, warn}`、`StatusDeckController.select()`（2/5 行上限，HARD_MAX 6）、`DeckRowModel`（title/detail 截 28 字）、`renderDeckRows`（整行样式）。
- Chip 现成：`com.google.android.material.chip.Chip`，28dp 高 / 11sp / 图标 14dp / `checkable`，`chipLocalAgent` 已经是"开关"接法（`setChecked` + 写 `aiConfig`）。

---

## 2. 四类槽位（`Slot.kind`）

| kind | 交互 | 渲染 | 例子 | 状态持久 |
|---|---|---|---|---|
| `toggle` | 点一下切换，立即生效 | `Chip`（`checkable`，选中态=开着） | 深度思考 / 联网 / 本地智能体（本机）；远程侧：自动允许、跟随 | 是（写回配置） |
| `action` | 点一下执行一次 | `Chip`（无选中态，带图标） | 改权限 / 切换会话 / 导入历史 / 重连 | 否 |
| `display` | 只读（点击可打开详情） | `Chip`（灰色，无箭头；有 action 才可点） | 权限 · 完全访问 / 模型 · deepseek-flash / 上下文 42% | 否 |
| `list` | 点开全屏列表 | `Chip` 显示「任务 3」+ 箭头 | 任务 / 目标 / 产物 / 待处理 / 子代理 | 否 |

`Slot` 新增字段：`kind`（上面四种）、`board`（分组：`alert|task|asset|state`）、`pinned`（固定显示）、`count`（list 类条数）。
`DeckRowModel` 相应增加 `chipText`（≤12 字）与 `count`，渲染层从"整行"换成"chip 行"。

---

## 3. 固定显示 vs 动态显示

**固定（连上电脑就占位，值为空时显示"—"）**

| board | 槽位 | kind | 说明 |
|---|---|---|---|
| state | 权限 | display → 点开 action | 「权限 · 完全访问」 |
| state | 模型/上下文 | display | 「deepseek-flash · 41%」 |
| task | 任务 | list | 「任务 1」点开列表 |
| task | 跟随会话 | display → action | 「跟随「修复 ask…」」 |

**动态（有才显示，消失即移除）**

| board | 槽位 | kind | 出现条件 |
|---|---|---|---|
| alert | 待处理 | list（warn） | 审批/提问数 > 0 |
| alert | 重试中 / 压缩中 | display（warn） | `llm/retry*`、`compaction/*` 进行中 |
| task | 目标 | list | 有 goal |
| asset | 产物 / 文件变更 | list | `deliverables/presented`、`workspace/changes` 有新条目 |
| state | 子代理 | list | 有子代理活动 |
| state | 沙箱/审批策略 | display | 与"权限"合并成一条，不单独占位 |

---

## 4. 渲染形态（多甲板 + chip）

```
┌ 甲板（最多 3 行，每行一条 board）──────────────────────────┐
│ alert : 🟠 待处理 2 ›                                      │
│ task  : 🏃 任务 1 ›   🎯 目标 1 ›   ↔ 跟随「修复…」          │
│ state : 🔒 权限·完全访问 ›   🤖 deepseek-flash · 41%         │
└────────────────────────────────────────────────────────────┘
[ + 输入框 ]
```

- 每行 = `HorizontalScrollView` + 一串 `Chip`；行内最多 3 个，超出末尾 `+N` chip（点开全屏列表）；
- board 顺序固定：`alert` → `task` → `state`；空 board 整行不渲染；
- chip 高度 28dp（复用现有尺寸），一行 28dp，比现在整行甲板省一半高度。

---

## 5. 迁移表：哪些 DSH 卡搬到甲板

**搬到甲板（从消息流移除 / 不再每次出卡）**

| 事件 | 去哪个槽位 |
|---|---|
| `permission/preset`、`sandbox/mode`、`approval/policy` | `state.权限`（display，点开改） |
| `agent-preset/selected`、`working-directory/change` | `state`（display 合并） |
| `goal/change`、`goal/activation-changed` | `task.目标`（list） |
| `job/registered`、`job/settled` | `task.任务`（list） |
| `subagent/descriptor`、`subagent/activity` | `state.子代理`（list） |
| `team/mode` | `state`（display） |
| `workspace/changes`、`deliverables/presented` | `asset`（list，带条数） |
| `compaction/*`、`llm/retry*` | `alert`（进行中才显示） |

**留在消息流（历史/证据，必须可回看）**

`assistant/message`、`command/run|done`（执行的证据）、`approval/asked|decided`（审计）、`todo/write`（清单本身是内容）、`feedback/*`、`system|developer/message`、`team/message/*`、工具调用与结果。

> 判据：**"重放一遍还能看懂吗"** —— 能看懂的是历史，看不懂的（"现在权限是什么"）是状态。

---

## 6. 与 dsh-Go / Qoder 的对应

| 我们 | dsh-Go | Qoder Mobile |
|---|---|---|
| `toggle` chip | 插槽胶囊（输入框上方） | 任务卡上的开关 |
| `action` chip | `actions` 组件（≤4 个） | 任务卡操作 |
| `display` chip | `keyValue` / `metric` 的紧凑形态 | 一行摘要 |
| `list` chip + 全屏页 | `list` 组件（≤50 行）+ 面板 | 任务列表页 |
| 固定 / 动态 | 清单里声明插槽，App 决定显隐 | 任务卡按状态出现 |

---

## 7. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **D1** | `Slot` 加 `kind/board/pinned/count`；`RemoteStatusSlots` 按新字段发布；旧整行渲染保留开关 | 单测：分组/排序/固定优先，旧样式不回退 |
| **D2** | `DeckChipBoard` 渲染（每 board 一行 chip，3 个 + `+N`）；`+N` → 全屏列表 | 真机：三行 chip，点 `+N` 进列表 |
| **D3** | `toggle` 类接上（远程自动允许、跟随），状态写回配置 | 真机：点 chip 立刻切换且重启保留 |
| **D4** | 按第 5 节把状态类事件**停止出卡**，只更新槽位 | 真机：跑一轮后消息流里不再有权限/任务/目标小卡 |

回滚：D1/D2 用开关切回整行渲染；D4 用开关恢复出卡（保留 `DshCards` 映射，只是不渲染）。

---

## 8. 风险

| 风险 | 处置 |
|---|---|
| 状态类事件不再出卡 → 用户"没看见过" | 甲板固定槽位常驻 + 全屏列表可回看当前值 |
| chip 太密 → 点错 | 行内 ≤3 个、间距 8dp、点击区 40dp 高（chip 28 + 上下留 6） |
| 远程 toggle 误触（改权限是危险操作） | `toggle` 只放"安全开关"（自动允许/跟随）；**改权限仍是 action → 弹确认卡** |
| 固定槽位占地方 | 空值显"—"，且 `display` 类可被用户长按隐藏（存偏好） |
