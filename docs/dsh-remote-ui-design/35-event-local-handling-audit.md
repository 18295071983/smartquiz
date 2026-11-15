# 事件本地处理盘点（2026-11-16）

> 触发：用户指出「**不能一概而论** —— 先要回答'我发的事件本地能不能处理'，
> 而且有些事件复用本地卡片/插槽/UI 通道，语义对齐比'加不加 action'更重要」。
>
> 本文只做**事实盘点**：每条事件 → 手机侧处理点 → 是否需要回传 → 现状。
> 标 ❓ 的是代码里没找到明确处理点、**需要实测确认**的项，不要当成结论。

## 1. 手机侧的处理面（代码事实）

| 处理层 | 认得的事件 | 位置 |
|---|---|---|
| 时间线归约 | `turn/start·end`、`step/start·end`、`stream/delta·end`、`assistant/message`、`user/message`、`tool/call`、`tool/result`、`approval/asked`、`approval/decided`、`plan/mode`、`session/title`、`schedule/change·changed`、`subagent/activity`、`subagent/catalog`（18 种） | `DshTimeline` |
| 卡片构造 | `job/registered`、`job/settled`、`compaction/start·summary·prune·end` | `DshCards` |
| 帧白名单（非归约） | `ui/slot`、`ui/card`、`state`、`view`、`pulse`、`hello`、`cmd`、`view_def` | `DshTimeline` 帧白名单 |
| 甲板槽位 | `ui/slot`（校验 + 渲染） | `RemoteSlotBridge` / `RemoteDshTool` |
| 同步 | `job/settled` | `RemoteSyncService` |
| 历史导入 | `job/registered`、`command/run`、`compaction/*` | `DshHistoryImporter` |

## 2. 逐事件盘点（插件发出 ≈56 种，含 1 个误匹配）

### 2.1 已归约 + 不需要回传（纯展示，**不要加 action**）

| 事件 | 处理点 | 回传 |
|---|---|---|
| `turn/start` `turn/end` | DshTimeline | 否 |
| `step/start` `step/end` | DshTimeline | 否 |
| `stream/delta` `stream/end` | DshTimeline（高频草稿） | 否 |
| `assistant/message` `user/message` | DshTimeline | 否 |
| `tool/call` `tool/result` | DshTimeline | 否（结果回传走工具层，不是卡片 action） |
| `session/title` | DshTimeline | 否 |
| `schedule/change` `schedule/changed` | DshTimeline | 否 |
| `subagent/activity` `subagent/catalog` | DshTimeline | 否（catalog 可点开列表 = 导航，不是回传） |

### 2.2 已处理 + **需要回传**

| 事件 | 处理点 | 需要的动作 | 现状 |
|---|---|---|---|
| `approval/asked` | DshTimeline + 原生审批对话框 | 批准 / 拒绝 | ✅ 已有（`POST /session action=approval`） |
| `approval/decided` | DshTimeline | 无（结果通知） | ✅ |
| `plan/mode` | DshTimeline | 切换（toggle） | 待核对是否有交互入口 |
| `job/registered` `job/settled` | DshCards（复用本地 `job` 卡） | 取消 / 重跑 | ⚠️ 本地 `job` 卡是"活动卡轮询本地日志"，DSH 的 job 拿不到日志 → 状态可显示、取消未接 |
| `goal/change` | 目标槽（`board=goal`） | 暂停 / 恢复 / 清除 | ✅ 已实现（返回 `not_supported`/`agent_not_live` 时如实上报） |
| `ui/slot` | RemoteSlotBridge | 按 `kind`：`toggle`/`action` 需要；`display`/`list` 不需要 | ✅ kind 已区分，但**插件侧 kind 是否标对**待核对 |
| `ui/card` | 帧白名单 + ComponentRegistry | 卡片 `actions` → `ActionSink` 上行 | ⚠️ 需确认卡片是否带 `component_id` + `source` |

### 2.3 本地**未见处理点**（❓需实测；这些是"发了手机看不到"的高风险项）

| 事件 | 可能影响 | 建议 |
|---|---|---|
| `approval/request` | 若插件发的是 `request` 而非 `asked` → **审批不弹窗** | 先确认插件实际发哪个名；若是 `request`，二选一：插件改名 或 手机加处理 |
| `approval/policy` `permission/preset` | 甲板"权限/审批策略"状态不更新 | 补 `display` 槽位 |
| `remote/error` | **远端报错用户完全看不到** | 至少弹提示 + 写时间线一条 |
| `command/run` `command/done` | 实时流可能不显示命令执行 | 归约或映射到 `tool/call` |
| `deliverables/presented` `workspace/changes` | 产物/文件变更提示缺失（甲板 `asset` 槽位空转） | 补 `list` 槽位 |
| `todo/write` | 待办不显示 | 归约或 `list` 槽位 |
| `model/selection` | 甲板"模型 · deepseek-flash"不更新 | 补 `display` 槽位 |
| `sandbox/mode` | 沙箱状态不显示 | 与权限合并 `display` |
| `llm/retry` | "重试中"告警不显示（`alert` 槽位空转） | 补 `display(warn)` |
| `team/member` `team/mode` `team/task` | 团队面板缺失 | 待评估是否需要 |
| `subagent/descriptor` | 子代理详情缺 | 待评估 |
| `agent/created` `session/event` `request/context` `request/header` `feedback/record` `hook/invoked` `hook/result` `image/offload` `ui/config` | 大概率是内部/审计事件，**手机端不需要展示** | 明确"不处理"，写进白名单注释避免以后误判为 bug |

## 3. 复用本地通道的语义对齐检查（比加 action 更关键）

| 复用点 | 对齐项 | 风险 |
|---|---|---|
| `ui/slot` | `kind` 是否标对（`toggle` 点得动、`display` 不该可点） | 标错的直接表现是"点了没反应"或"该点却没有交互" |
| `ui/card` | `component_id` + `source` + `actions` 三件套是否齐 | 缺 `component_id` → 点了无处回；缺 `source` → 分流错（`ActionSink` 依赖它） |
| `job/registered` | 本地 `job` 卡默认按"活动卡"轮询日志 | DSH job 无本地日志 → 只能显示状态，`cancel` 未接 |
| `compaction/*` | 同时进 `DshCards` 与 timeline 会重复显示 | 需确认只走一条 |

## 4. 建议的落地顺序

1. **先确认 `approval` 事件名**（`request` vs `asked`）—— 影响最大、排查最简单；
2. **`remote/error` 补可见性**（现在完全静默，属于用户会"觉得坏了"的类型）；
3. `ui/slot` kind 一致性盘点（用现有 `--es mode` 入口喂样本）；
4. 甲板空转槽位补齐：`llm/retry`、`model/selection`、`deliverables`、`todo`、`permission/policy`；
5. 明确"不处理"白名单（2.3 最后一组），写注释防止以后当 bug 修。

## 5. 已查实的关键结论（2026-11-16 代码核对）

**审批事件名不是错配，但存在两条不同的审批路径，需要分别对齐：**

| 事件 | 插件声明（EVENT_CATALOG） | 手机侧处理 | 结论 |
|---|---|---|---|
| `approval/asked` | `layer: session, forward: true`（durable 审批请求） | ✅ `DshTimeline` / `DshCards` / `RemoteLiveStatus` 都有分支 | 已通 |
| `approval/request` | `layer: host, forward: true`（宿主网关审批请求） | ❌ 手机侧**没有** `approval/request` 分支 | ⚠️ **宿主网关审批可能不弹窗** |
| `approval/policy` | `layer: session, forward: true, slot: permission` | ❌ 未见处理点 | ⚠️ 甲板"权限/审批策略"槽位不更新 |
| `approval/decided` | `layer: session, forward: true` | ✅ 有分支 | 已通 |
| `user-questions/request` | `layer: host, forward: true` | 未见处理点 | ⚠️ 反问（网关路由）待核对 |
| `goal/activation-changed` | `layer: host, forward: true, slot: goal` | 目标槽已实现（但事件名待核对是否被映射） | ❓ |

也就是说：**同一件"审批"，durable 通道（`asked`）通了，宿主网关通道（`request`）没通** ——
这正是用户说的「不能一概而论」：要看事件走的是哪条 layer。

### 后续动作（按风险排序）

1. **补 `approval/request` 的手机侧处理**（或让插件把 host 层审批也发成 `asked`）——
   否则宿主网关触发的审批在手机上完全静默；
2. 补 `approval/policy` → 甲板 `permission` 槽位（`display`，不需要回传）；
3. 核对 `user-questions/request` 的反问是否已由 `/v2/answer` 覆盖；
4. 其余"本地不处理"的事件按 2.3 的白名单显式声明，避免以后误当 bug。

## 6. 更正（2026-11-16，以 32-dsh-event-binding.md 为准）

**上一版盘点的方法有误**：我只 grep 了 `DshTimeline` 的事件分支名，漏掉了两条既有通道：

1. **主动拉取通道**：审批/反问走 `RemoteDshTool.fetchPendingActions()`（`action=approval_state`
   直接读电脑端网关 pending 表）+ `answerApproval()`（先答先赢），**不依赖事件分支**；
2. **事件绑定表**：`docs/dsh-remote-ui-design/32-dsh-event-binding.md` 记录 2026-11-14 已经做到
   **63/63 事件有明确归属** —— 19 条进 timeline、37 条由 `DshCards.handles` 出卡、
   13 条进 `IGNORED_TYPES`（不渲染但登记为已识别）。

按权威表更正本文件第 2.3 节的判断：

| 我原判 | 实际归属（32 号文档） |
|---|---|
| `approval/request` 静默 | 由 `fetchPendingActions` 拉取覆盖；`approval/policy` → **`dsh_notice` 提示卡** |
| `todo/write` 缺失 | → **`todo_card`** 专用卡 |
| `deliverables/presented` 缺失 | → **`file_list`** 专用卡 |
| `command/run` `command/done` 缺失 | → **`dsh_command`** 专用卡 |
| `job/registered` `job/settled` | → 专用卡（已确认） |
| `model/selection` `sandbox/mode` 等 | 属"提示卡/内部事件"登记范畴，**不是未处理** |

也即：**"本地能不能处理"这个问题昨天已经有答案（63/63 有归属）**，本文件第 2.3 节的
"未见处理点"清单属于我 grep 方式造成的误判，**不要据此改代码**。

### 仍然值得做的（真正未验证的部分）

| 项 | 需要什么 |
|---|---|
| 甲板 `permission` 槽是否与 `approval/policy` 的 `dsh_notice` 同时出现（重复展示？） | 实测一次策略变更 |
| `fetchPendingActions` 是否覆盖宿主网关（`layer: host`）的审批 | 电脑端造一次 host 层审批，看手机 pending 表 |
| `ui/slot` 的 kind 与实际行为是否一致 | 实测样本 |

> 结论：**先不要改代码**。以 32 号文档为权威，本文件仅作"待实测清单"。

## 7. 三项实测的代码层核对结果（2026-11-16，未改代码）

### 7.1 `approval/policy` 会不会和甲板 `permission` 槽重复？

**不重复，是"各自一个载体"：**

| 来源 | 产出 | 形态 |
|---|---|---|
| 事件 `approval/policy` | `DshCards` L335 → `permissionStateCard()` → card id `permission-state`，`alert_card` | **消息流里一张提示卡**（标题"权限"，带「改权限」action） |
| 甲板槽 | `ui/slot` 帧（`slot=permission`） | **甲板一行 chip** |

两者用途不同（一个留痕在对话里、一个常驻甲板），**不构成重复展示**。
若要"只留甲板"，那属于产品取舍，不是 bug。

### 7.2 宿主层（`layer: host`）审批是否被 `fetchPendingActions` 覆盖？

代码层：`RemoteDshTool.fetchPendingActions()` 调 `POST action=approval_state`，返回
`{id, kind: approval|question, request, agent_id}` —— 它读的是**电脑端网关 pending 表**，
与事件 `layer` 无关（session/host 都进同一张 pending 表）。
❓仍需实测一次 host 层审批确认（手机侧无法单方面造出 host 审批）。

### 7.3 `ui/slot` kind 一致性（插件 vs 客户端）

| 侧 | 支持的 kind | 归一化 |
|---|---|---|
| 插件 `normalizeSlotFields`（index.js L1936） | `state / progress / list / action`（`SLOT_KINDS`） | 空 → state；未知 → 降级 + 记 warning |
| 客户端 `SlotKind.normalize` | 同上四种；`display`/`toggle` 是**旧别名**，静默按 state | 必填不匹配 → warn + 降级 state |

**两边规则一致（同规则同输出）**，且插件实际发的是 `list ×9`、`action ×2`（无未知 kind）。
结论：**kind 这一层没有错配**，之前担心的"标错导致点了没反应"不成立 ——
`action` 必须带 `actions`，否则客户端会 warn 并降级成 state（这属于防御已到位）。

### 7.4 结论

三项核对里 **7.1 / 7.3 已用代码证明没问题**；7.2 只差一次真机 host 审批实测。
因此**不需要为这三项改代码**，文档保持为"事实记录 + 待实测一项"。
