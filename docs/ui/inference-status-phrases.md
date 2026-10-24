# 推理/生成状态文案（多套备用 + 按进度切换）设计记录

2026-10-25。用户反馈："思考中推理状态你可以设计多套备用文案，按照推理进度及生成进度切换可以吗。
防止用户等不及，你看看业界是怎么设计这套文案的"。

## 1. 业界怎么设计（调研结论）

| 来源 | 做法 | 对我们的启示 |
|---|---|---|
| [NN/g：Response Times: The 3 Important Limits](https://www.nngroup.com/articles/response-times-3-important-limits/) | 1s 内不必特别反馈；超过 1s 要让用户知道"在工作"；超过 10s 必须有持续进度反馈 + 明确的中断入口。工作量未知时，无法百分比就退化为"绝对工作量反馈"或通用忙碌指示（点/转圈/状态行） | 推理耗时不可预测 → 用"阶段 + 已耗时"做推进感；我们有停止按钮 = 中断入口；t/s 就是"绝对工作量反馈" |
| [Perplexity 设计研究](https://blakecrosley.com/guides/design/perplexity)（PhaseIndicator：`Searching the web…` → `Reading sources…` → `Writing answer…`） | "Show the process, not just results"：把等待拆成用户能理解的阶段，逐段换文案 | 我们同构：等待响应 → 思考（理解/梳理/推敲/验证/收敛）→ 生成（组织/撰写/补充/收尾） |
| [Ant Design X：生成过程确认规范](https://x.ant.design/docs/spec/confirm-generation-process) | 生成过程要"可感知、可中断、可确认"，随阶段给出不同状态提示 | 状态行常驻在输入框上方；停止按钮同排可用 |
| ChatGPT / Claude 的 thinking 模式（业界通行做法；如 [windmill PR 把 thinking 折叠成带 `thought for` 时长的状态行](https://github.com/windmill-labs/windmill/pull/10515)） | 生成中显示 Thinking/Working 状态；结束后折叠成 "Thought for Xs" | 我们的"已深度思考 X 秒"就是同一模式；活动态才挂动态文案，结束回完成态 |

结论：**不编假百分比**，用"阶段 + 阶段内耗时 + 实时速度"三类真实信号；
文案拆成多段（每段 ≤10 个汉字，保证一行放得下"文案 + 速度 + 余额"）；
只在当前阶段内推进，阶段切换重置；结束回到"已深度思考 X 秒"。

## 2. 我们的文案表

实现：`ai/chat/InferenceStatusPhrases#phrase(phase, phaseElapsedMs)`（纯函数、可单测）。
底部生成状态行与气泡内「深度思考」折叠头共用它，保证同屏同词。

### 等待响应（已发出，等首个 token；同时覆盖初始化/加载模型/预处理）

| 本阶段耗时 | 文案 |
|---|---|
| 0–3s | 等待模型响应… |
| 3–8s | 正在连接模型… |
| 8–20s | 响应较慢，仍在等待… |
| ≥20s | 仍在等待响应… |

### 思考中（reasoning tokens 流式阶段）

| 本阶段耗时 | 文案 |
|---|---|
| 0–4s | 正在理解问题… |
| 4–10s | 正在梳理思路… |
| 10–20s | 正在推敲关键点… |
| 20–35s | 正在验证推理… |
| 35–60s | 正在收敛结论… |
| ≥60s | 思考较久，仍在继续… |

### 生成中（正文 tokens 流式阶段）

| 本阶段耗时 | 文案 |
|---|---|
| 0–3s | 正在组织回答… |
| 3–10s | 正在撰写回答… |
| 10–25s | 正在补充细节… |
| ≥25s | 正在收尾… |

### 结束

- 底部状态行整行收起（回到空闲）；
- 气泡内思考块折叠头回到 **"已深度思考 X 秒"**；
- 多个思考块只有当前活跃块用动态文案（parts 路径由 `AssistantTurn#markLiveStreaming`
  只给 live 轮打 streaming；legacy 只对最后一组生效）。

## 3. 实现要点

- `ChatMessage.InferenceProgress#phaseStartTime`（transient）：当前阶段开始时刻，
  `markStreamingPhase` 阶段变化时重置；渲染侧按 `now - phaseStartTime` 选文案。
- 底部状态行：`AIChatActivity#statusPhraseTicker` 每 1.2s 推进一次；
  `markStreamingPhase` 立即写首句；结束停心跳并清空阶段词表。
- 思考块折叠头：`ReasoningPartRenderer#stateLabel` 与 `ChatAdapter#activeThinkingPhrase`
  （legacy 路径）用同一函数 + `phaseStartTime` 计算；文案变化时 Activity 用
  `PAYLOAD_STATUS_UPDATE` 重绑流式那一条，让折叠头跟着换（1.2s 节奏，不逐 token）。
- 速度仍由 `updateStreamingTokenStats` / `noteReasoningTokenForSpeed` 单独刷新，
  不参与文案选择（避免每个 token 改文案）。

## 4. 验证

- `InferenceStatusPhrasesTest` 5 条覆盖三个阶段的阈值、等待类阶段共用、结束态返回 null；
  `InferenceStatusLabelTest` 5 条保留（短标签回退/活跃判定）。
- 全量 `testDebugUnitTest --offline --no-build-cache` **959 / 0 skipped / 0 failures / 0 errors**；
  `assembleDebug --offline` 通过。按用户要求未做真机测试。
