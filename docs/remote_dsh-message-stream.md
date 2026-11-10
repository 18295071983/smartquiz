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
| `tool/call` / `tool/result` | `Part.toolCall` / `Part.toolResult`（按 turn/step 交错） | — | ✅ |
| `approval/request` | 原生 `AlertDialog`（允许/拒绝/交给电脑） | — | ✅ 已接线 |
| `user-questions/request` | 系统消息提示（只读） | — | ✅ 只读 |
| `todo/write` | `Part.data` → `todo_card` | `{title, items:[{text,done}]}` | ✅ |
| `deliverables/presented` | `Part.data` → `file_list` | `{title, files:[{name,path,type}]}` | ✅ |
| `goal/activation-changed` | `Part.data` → `alert` | `{title, content, level}` | ✅ 尽力解析 |
| `workspace/changes` | `Part.data` → `alert` | — | ✅ 仅提示 |
| `job/registered` | `Part.data` → `alert` | — | ✅ |
| `command/run` | `Part.data` → `alert` | — | ✅ |
| `plan/*` | 可用 `steps` 卡（`{title, steps:[{title,status}]}`） | — | ⛔ 未接 |
| `subagent/*` | 可用 `alert` / `markdown_doc` | — | ⛔ 未接 |
| `turn/end` | 阶段 → COMPLETED；落 `last_sync_reply` | — | ✅ |

## 三、实现要点

- 一条同步回复只建**一条** `AssistantTurn`，挂在 `ChatMessage.assistantTurn`；
  `PartsTurnBinder` 按 `part.id` 增量复用视图，所以 id 必须稳定
  （`dsh-r-<turn>-<step>`、`dsh-t-<turn>-<step>`、`dsh-call-<callId>`、`dsh-todo-<turn>`…）。
- 同 id 的 data part 用覆盖式更新（`upsertDshDataPart`），避免 todo 反复写堆一屏。
- 事件里的 `turn` / `step` 决定 part 顺序：按 turn/step 升序，每步
  `reasoning → text → 该步工具调用（含结果）`，与 App 引擎的编排一致。
- `org.json` 的 `put/append` 在本工程是受检异常，统一走 `jput/jadd` 包装，坏字段只丢字段。

## 四、未接的部分

- `user-questions` 的手机代答：缺网关期望的答案 payload 样本，先只读提示。
- `plan` / `subagent` / `schedule` / `workflow-run`：事件形状未采样，留待实测补。
