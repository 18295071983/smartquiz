# 23 · 消息流专用卡片与事件分流（方案 · 待审批）

> 用户口径：**留在消息流的事件也不能一直复用 DSH 卡**——按事件种类建专用卡片，事件分流接上。
> 依据：`DshCards.map` 现在的映射（一条 `dsh_notice` 兜了十几种事件）。

---

## 1. 现状问题（实测映射）

`DshCards.map` 里，**至少 17 种事件都落到同一个 `dsh_notice`**：

```
agent-preset/selected   approval/asked        approval/decided
compaction/end          compaction/prune      compaction/summary
developer/message       feedback/message-put  feedback/message-delete
goal/activation-changed llm/retry-started     subagent/descriptor
system/message          team/member           team/message/queued
team/mode               workspace/changes
```

后果：
- 用户看到的都是"一条灰边框 + 标题 + 一行正文"，**事件之间没有视觉差异**，扫不出来；
- 每加一类事件就只是换个文案，卡片层没法按语义做交互（重试卡可以点"重试"、审批卡可以点"去处理"）；
- `dsh_notice` 成了"万能卡"，`DshCardViews` 无法针对性优化。

---

## 2. 卡片分型（专用卡）

| 卡片 type | 事件 | 形态 | 交互 |
|---|---|---|---|
| `msg_text` | `assistant/message` | 正文（已有） | 复制/重答 |
| `cmd_card` | `command/run`、`command/done` | **终端卡**：`$ cmd` 等宽字、右上「运行中/退出码 0」、输出折叠 N 行、耗时 | 展开输出、复制命令 |
| `approval_card` | `approval/asked`、`approval/decided` | **审批卡**：黄底、命令/文件/原因三行、已决定显示「已允许/已拒绝」 | 去处理（跳甲板）、复制 |
| `todo_card` | `todo/write` | **清单卡**：勾选态 + 进行中高亮（已有，继续用） | 只读 |
| `team_card` | `team/member`、`team/message/queued|delivered`、`team/mode` | **团队卡**：成员头像行 + 消息计数 + 模式徽标 | 展开明细 |
| `retry_card` | `llm/retry-started`、`llm/retry` | **重试卡**：橙底、第 N 次、原因一行 | 查看错误 |
| `sys_card` | `system/message`、`developer/message` | **系统卡**：灰、小字、默认折叠一行 | 展开 |
| `file_card` | `workspace/changes`、`deliverables/presented` | **文件卡**：变更文件列表（+N / -M）、产物图标 + 点开 | 点文件 → 渲染 |
| `feedback_card` | `feedback/message-put`、`-delete` | **反馈卡**：引用原文 + 动作（点赞/删除） | 只读 |
| `compact_card` | `compaction/start|end|summary|prune` | 过程卡（默认折叠成一行「上下文压缩 · 12k→3k」） | 展开 |
| `subagent_card` | `subagent/descriptor`（已有） | 轨迹卡 | 展开步骤 |

> `goal/*`、`job/*`、`permission/*`、`sandbox/*`、`agent-preset` 按 22 号方案迁到甲板；消息流里只在"发生的那一刻"留一张极简历史卡（可选），默认不再出卡。

---

## 3. 分流架构：`CardSpec` 注册表代替 switch

现在的写法是 `switch (event.type) { case "x": return new Card(...) }` + 一个兜底。改成表驱动：

```java
// ai/remote/cards/CardSpec.java
final class CardSpec {
    final String type;          // 渲染用的卡片 type
    final String keyMode;       // "event" 每条一张 | "id:jobId" 同 id 覆盖 | "command:commandId"
    final int priority;         // 同一位置多卡时的稳定顺序
    final CardBuilder builder;  // (ev, ctx) -> Card(props)
}

// ai/remote/cards/CardRouter.java
CardRouter.of(type)  →  CardSpec（查表，O(1)）
CardRouter.register(spec)   // 每类事件一份，独立文件
```

每个专用卡一个文件（`CmdCard.java` / `ApprovalCard.java` / …），实现 `CardBuilder`，**互不影响**；
新增事件只加注册，不改已有代码——这正是"分流接上"的意思。

配套：
- `CardSpec.keyMode` 决定合并语义（命令按 commandId 原地补输出，重试按次一条，其余的每条一条）；
- 未知事件走 `generic_notice`（保留现在的 dsh_notice 作为兜底，不再当主力）。

---

## 4. 渲染实现（按现状扩展）

- 渲染仍走 `ComponentRegistry` + `ChatComponent`（`DshCardViews` 那套），每类卡片一个 `ChatComponent` 实现；
- **不需要新 part 类型**（设计稿约束）；
- 专用卡之间共享一段"卡壳"（圆角底 + 左侧色条 + 标题行 + 折叠体），只是 body 不同——抽个 `CardShell`，避免十份重复代码。

---

## 5. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **C1** | `CardSpec` / `CardRouter` + `CardShell` 骨架；把现有 `dsh_notice` 的 6 类事件先迁成专用卡（sys/retry/team/compact/feedback/approval） | 单测：事件 → 卡片 type 一一对应；真机视觉可区分 |
| **C2** | `cmd_card` 专业化（等宽命令、退出码、耗时、输出折叠 N 行） | 真机：一条命令跑完，卡上有退出码和耗时 |
| **C3** | `file_card` 专业化 + 点文件走 `/ws/file` 拉取 → `FileRenderActivity` | 真机：点变更文件能打开 |
| **C4** | 删掉现在 `DshCards.map` 里的对应分支，只留注册 | 回归：所有事件仍能出卡 |

---

## 6. 风险

| 风险 | 处置 |
|---|---|
| 卡片类型变多 → 代码重复 | `CardShell` 统一外壳；每类只写 body |
| 渲染更重（用户之前担心"卡太重"） | 专用卡默认**折叠**：命令输出/团队明细/sys 正文都收起，展开才铺开 |
| 老数据里的 `dsh_notice` 卡还在 | 渲染层保留 `dsh_notice` 组件（只读兼容），新事件不再产生 |
| 分流表写错 → 事件不出卡 | `CardRouter` 单测覆盖 44 类；未知类型回落到 generic |
