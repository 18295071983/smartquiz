# DSH 消息流组件 → 手机端 parts / 卡片 对照

插件 `/events` 原样转发 DSH 会话事件，手机端转换器（`AIChatActivity` 里的 DSH 转换分支）
把事件映射成 `AssistantTurn.parts`。这份表是"电脑端有什么、手机端落在哪"的对照，
新增事件类型时照此补映射即可。

## 一、电脑端的消息流组件（按包名/事件归集）

| 电脑端包 / 事件 | 作用 |
|---|---|
| `dsh-client-ui-chat` / `-conversation` / `-renderer` | 气泡、正文 Markdown、流式渲染 |
| `dsh-client-ui-approval` + `approval/request` | 工具审批弹窗 |
| `dsh-client-ui-user-questions` + `user-questions/request` | agent 反问 |
| `dsh-client-ui-tool` + `tool/call` `tool/result` | 工具卡片 |
| `dsh-client-ui-goal` + `goal/activation-changed` | 目标条 |
| `dsh-client-ui-plan` + `plan/*` | 计划/步骤 |
| `dsh-client-ui-jobs` + `job/registered` `job/settled` | 后台任务 |
| `dsh-client-ui-deliverables` + `deliverables/presented` | 产物文件卡 |
| `dsh-client-ui-session` + `workspace/changes` | 会话/工作区变更 |
| `dsh-client-ui-subagent` + `subagent/*` | 子代理活动 |
| `dsh-client-ui-schedule` | 定时任务 |
| `dsh-client-ui-workflow-run` | 工作流运行 |
| `dsh-client-ui-message-feedback` / `dsh-tool-todo` | 反馈、待办 |

## 二、映射到手机端（parts / 原生卡片）

| DSH 事件 | 手机端落点 | 卡 props | 状态 |
|---|---|---|---|
| `assistant/message`（reasoning part） | `Part.reasoning` → `ReasoningPartRenderer` | — | ✅ |
| `assistant/message`（text part） | `Part.text` → `TextPartRenderer` | — | ✅ |
| `agent/assistant-stream`（合成 `stream/delta`） | 追加到该步 `text/reasoning`（不落盘） | — | ✅ 逐字流 |
| `tool/call` / `tool/result` | `Part.toolCall` / `Part.toolResult`（按 turn/step 交错） | — | ✅ |
| `approval/request` | `Part.approval` → 时间线审批卡（允许/拒绝） | `{permission, prompt, status}` | ✅ |
| `user-questions/request` | `Part.question` → 问答卡（选项/多选/自由文本，整批提交） | `{callId, questions[]}` | ✅ 可代答 |
| `todo/write` | `Part.data` → `todo_card` | `{title, items:[{text,done}]}` | ✅ |
| `deliverables/presented` | `Part.data` → `file_list` | `{title, files:[{name,path,type}]}` | ✅ |
| `goal/activation-changed` | `Part.data` → `alert` | `{title, content, level}` | ✅ 尽力解析 |
| `workspace/changes` | `Part.data` → `alert` | — | ✅ 仅提示 |
| `job/registered` | `Part.data` → `alert` | — | ✅ |
| `command/run` | `Part.data` → `alert` | — | ✅ |
| `plan/mode` | `Part.data` → `alert`（计划模式状态卡）；`/v2/plan` 开关 | `{active, pending}` | ✅ |
| `subagent/catalog` + 合成 `subagent/activity` | `alert`（子代理卡，实时更新）+ `/v2/subagents` 面板 | `{child_id, label, last}` | ✅ |
| `schedule/*`（**不是会话事件**） | `/v2/schedule` 拉 `ctx.schedule`；`schedule/changed` 提示重拉 | `{title, kind, status, scheduledAt}` | ✅ |
| `turn/end` | 阶段 → COMPLETED；落 `last_sync_reply` | — | ✅ |

## 三、实现要点（2026-11-12 重构后）

- 转换规则全部在 `com.oilquiz.app.ai.remote`：`DshEvent`（信封）→ `DshTimeline`（归约/卡片/动作）
  → `DshProjection`（`roundSnapshots` + `components`）；Activity 只做"投影 + 落屏 + 副作用"。
- 顺序真值是 `roundSnapshots`：一步一条（思考/正文/该步工具），渲染层按
  `reasoning → text → tool_group → 下一步…` 装配；数据卡/审批卡/问答卡带 `round`
  插到该步之后（`ComponentData.round` / `Part` 轮次）。
- 组件 id 由 `ChatIdDispatcher` 发号并按逻辑 key 复用（`Tn-Fn` / `Tn-Cn`），
  补拉重投影不会换 id、不闪；历史导入用 `dsh-I{n}`。
- 同一 ask 的多个问题必须在**一张卡**里、一次提交整批答案（DSH 要求 `BAD_ANSWER` 校验）；
- `seq` 去重与游标：`DshTimeline` 按 seq 去重；Activity 在投影落屏后才持久化 `last_seq`。

## 四、仍未接的部分

- CLI 子进程的 token 流：`dsh headless` 是一次性进程，没有进程内 `agent/assistant-stream`；
  它的输出仍以 step 粒度落盘（插件可在 `runCliTask` 里转发，但会话 id 与手机订阅的 target 不完全一致，
  本轮没接）。
- 日程的"新建/改时间规则"：手机目前只做执行/删除/看历史；新建走电脑面板或 `/v2/schedule` 直接调。
- 外部子代理后端（ACP/Codex）若不在会话日志里写 `subagent/catalog`，子代理面板只能看到登记过的部分。
- 计划正文的富展示：`exit_plan_mode` 的计划 markdown 现在走问答卡的 detail/普通 tool 参数，
  还没做专门的 plan 卡排版。
