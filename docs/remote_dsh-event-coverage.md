# DSH 事件覆盖表（电脑 → 手机）

> 实测口径：本机 `~/.workbuddy/dsh-home/sessions` 下 **112 条会话**、**105,964 条持久事件**，
> 解压统计出的真实事件类型共 **51 种**。另有插件源码里声明、但本机尚未出现过的类型，单独列在最后。
> 统计时间：2026-11-12。

## 一、手机端渲染状态

- **正文**：进入时间线气泡（思考/正文/工具交错）
- **卡片**：`ChatMessage.components` 里的卡片（alert / steps / todo_card / file_list / markdown_doc）
- **状态**：只更新进度/阶段，不出内容
- **忽略**：模型面向或纯内部记录，不该出现在聊天界面

| 事件类型 | 本机次数 | 手机端 | 说明 |
|---|---:|---|---|
| `turn/start` | 393 | 状态 | 阶段=思考 |
| `turn/end` | 392 | 状态 | 收尾/落库（`reason.kind` 可判错/中断/超长） |
| `step/start` | 5,817 | 状态 | 第 N 步 |
| `step/end` | 5,816 | 状态 | 第 N 步完成 |
| `user/message` | 831 | 忽略 | 手机自己发的话本地已上屏；电脑端手敲的暂不镜像（见"待办"） |
| `assistant/message` | 5,799 | 正文 | 正文+思考权威值 |
| `assistant/attempt` | 18 | 卡片 | 模型失败尝试 →「模型报错」 |
| `tool/call` | 6,714 | 卡片 | 工具卡（调用中） |
| `tool/result` | 6,758 | 卡片 | 工具卡（结果/失败） |
| `stream/delta` | 实时 | 正文 | 逐字流（思考/正文） |
| `stream/end` | 实时 | 状态 | 一轮流结束 |
| `system/message` | 51 | 忽略 | 模型系统提示，非 UI |
| `developer/message` | 9 | 忽略 | 模型面向的开发者消息 |
| `request/header` | 207 | 忽略 | 请求快照（日志用） |
| `request/context` | 91 | 忽略 | 路由元数据（日志用） |
| `session/end-seed` | 179 | 忽略 | fork/继承边界 |
| `session-log-deepseek/delivery-accepted` | 4,442 | 忽略 | 落盘回执 |
| `reasoning-chunks` / `assistant/chunk` / `text-chunks` / `tool-call-chunks` | 34,654 / 15,638 / 4,444 / 7,378 | 忽略 | 会话日志压缩后的流记录，不是实时事件 |
| `web/deepseek-search-llm-request` | 144 | 忽略 | 搜索插件的内部请求记录 |
| `session/title-llm-request` | 29 | 忽略 | 自动起标题的内部请求 |
| `agent/inbox/spliced` | 935 | 忽略 | 向模型收件箱插消息（steering） |
| `session/title` | 113 | **未渲染** | 电脑端标题变化，手机标题栏不跟着变（待办） |
| `workspace/changes` | 141 | 卡片 | 「文件变更」（只有 turn，明细在电脑端） |
| `sandbox/mode` | 137 | 卡片 ✅新增 | 「沙箱模式」 |
| `approval/policy` | 137 | 卡片 ✅新增 | 「审批策略」 |
| `permission/preset` | 128 | 卡片 ✅新增 | 「权限档位」 |
| `agent-preset/selected` | 2 | 卡片 ✅新增 | 「智能体预设」 |
| `subagent/catalog` | 3 | 卡片 | 子代理 |
| `subagent/descriptor` | 62 | 卡片 ✅新增 | 子代理（标签/模式） |
| `job/registered` | 2,140 | 卡片 | 「后台任务」（按 turn 聚合） |
| `job/settled` | 2,107 | 卡片 ✅新增 | 同上，补完成/失败状态 |
| `todo/write` | 9 | 卡片 | 待办清单 |
| `deliverables/presented` | 8 | 卡片 | 本轮产物（文件列表） |
| `command/run` | 7 | 卡片 | 执行命令 |
| `command/done` | 7 | 卡片 ✅新增 | 命令结果 |
| `plan/mode` | 实时 | 卡片 | 计划模式开关 |
| `goal/change` | 4 | 卡片 ✅新增 | 目标状态（原来只认旧名 `goal/activation-changed`，实际事件名已变） |
| `goal/activation-changed` | 0 | 卡片 | 旧版本事件名，保留兼容 |
| `compaction/start` | 2 | 卡片 | 压缩中 |
| `compaction/summary` | 2 | 卡片 | 压缩摘要 |
| `compaction/prune` | 44 | 卡片 ✅新增 | 历史清理（tokens） |
| `compaction/end` | 2 | 卡片 ✅新增 | 压缩完成 |
| `working-directory/change` | 8 | 卡片 ✅新增 | 工作目录切换 |
| `llm/retry` | 11 | 卡片 | 模型重试计划 |
| `llm/retry-started` | 11 | 卡片 ✅新增 | 第 N 次重试中 |
| `approval/asked` | 11 | 卡片 ✅新增 | 审批请求（历史回放也能出卡） |
| `approval/decided` | 11 | 卡片 ✅新增 | 审批结果 |
| `approval` / `approval_gone` / `question` / `question_gone` | 实时 | 卡片 | 插件合成的实时审批/反问 |
| `schedule/changed` | 实时 | 状态 | 触发重拉日程 |
| `tool-workflow/run-start` | 1 | 卡片 | 工作流开始 |
| `tool-workflow/agent-start` | 2 | 卡片 | 工作流子任务 |
| `tool-workflow/agent-end` | 2 | 卡片 | 工作流子任务完成 |
| `tool-workflow/run-end` | 1 | 卡片 | 工作流结束 |

## 二、仍待补的两项

1. **`session/title`（113 次）**：电脑端会话标题变化没有同步到手机标题栏。
   需要在 `DshTimeline.Update` 里透出 title，由 `AIChatActivity` 更新标题控件。
2. **`user/message`（831 次）**：电脑端手敲的消息不会镜像到手机。
   手机自己发的消息已经本地落过，直接渲染会重复；需要按「文本 + 时间窗」去重后再插入 USER 气泡。

其余未渲染类型均为模型面向/纯日志记录，按设计不该出现在聊天界面。

## 三、源码里声明、本机尚未出现的类型

`agent-preset/selected`、`approval/asked`、`approval/policy`、`command/run|done`、
`compaction/start|summary`、`deliverables/presented`、`developer/message`、`image/offload`、
`permission/preset`、`plan/mode`、`sandbox/mode`、`session-log-deepseek/delivery-accepted`、
`step/start|end`、`subagent/model-selection-policy`、`team/member`、`team/message/delivered`、
`team/message/queued`、`team/mode`、`team/task`、`todo/write`、`turn/start|end`、
`working-directory/change`、`workspace/changes`。

其中 `team/*`（多智能体协作）和 `image/offload`（图片落盘）手机端**还没有对应卡片**，
等实际用到再补。
