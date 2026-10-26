# 本地 Agent 多阶段架构设计（intent → plan → execute → reflect → reply）

日期：2026-10-25 · 范围：端侧（llama.cpp / NPU）`AgentLoopEngine` 链路 · 状态：**设计蓝本，未实现**

## 0. 一句话结论

多阶段（意图识别 / 任务分解 / 分步执行 / 反思 / 生成回复 + 清缓存 / 拼提示词 / 续跑）的**引擎件基本都在仓库里，只是没接线**；本地 4B + ~7.7K prompt 预算 + KV 前缀字节稳定这三条硬约束，决定了正确做法是"**固定前缀 + 阶段以追加消息进入会话尾部**"，而不是每阶段重写 system/tools（那会毁 KV、触发全量 re-prefill ~20s）。

## 1. 业界 agent 循环（借鉴对象）

三条正交范式（业内共识）：**ReAct = 内循环，Plan-and-Execute = 外编排，Reflexion = 复盘层**。真实生产栈多为 **ReAct + Reflexion**，复杂长任务再加 Plan-and-Execute（DAG/拓扑 + 失败节点重规划）。

- **ReAct**：Thought → Action(选工具+参数) → Observation(工具结果) → 循环到信息足够出 Final Answer。`max_iterations` 是必须的护栏（不设上限 = token/费用失控、死循环，是生产事故高发点）。
- **Plan-and-Execute**：Planner 先产出显式步骤列表 `plan: list[str]`；Executor 逐步执行（每步内部可再跑 ReAct）；`completed_steps` 累积；失败/新观察触发**重规划**。LangGraph 官方 `create_react_agent` 是预置起点；复杂分支/状态机/HITL 才升级到 LangGraph 图（State 管上下文、Node 管每步、Edge 管下一步、`remaining_steps` 管迭代上限）。
- **Reflexion**：在最后加一道自检——agent 评价自己输出、指出"哪步证据不足"、把教训存进 memory，再带着它重试一次。叠在 ReAct 之上，不改主链走向。
- **上下文工程（长任务续跑的关键）**：滑动窗口 / 摘要压缩 / 向量检索 / 结构化存储；有经验的阈值：窗口 60% 触发清理、70% 摘要压缩、80% 抽关键信息存键值。答案级缓存用 prompt-hash（命中直返）。

**可读的开源循环代码（参考）**：LangGraph `prebuilt/chat_agent_executor.py`（`agent` 节点 + `tools` 节点 + `should_continue` 条件边 + `ToolMessage` 追加历史 + `remaining_steps` 限步）；HuggingFace `smolagents`（`CodeAgent`/`ToolCallingAgent` 的 `step()` 循环：模型出 thought/action → 执行工具 → 写回 memory，直到 final）；OpenAI Agents SDK / Claude Agent SDK（run loop）、AutoGen / CrewAI（多角色）。

## 2. 本仓库现状（复用清单，均带 file:line 依据）

**已是 ReAct 内循环**：`AgentLoopEngine.run` 的迭代骨架（`buildToolsJson → trimHistoryToFit → buildRequestJson → generateWithTools/ChatJson → 解析 tool_calls(native PEG) → 并行 executeToolSafely → 回填 tool 结果 → 续`），`MAX_ITERATIONS_BASE=16` / `MAX_TOOL_ROUNDS=8` / `TOTAL_TIME_BUDGET_MS=300000`；无工具调用即正文为答案，早退走 `generateFinalAnswer` 总结。→ **execute 阶段现成**。

**已存在但未被循环调用（接上即用）**：
- 反思：`thinking/ReflectionEngine.reflect()`（NpuEngineRouter.generate + 解析 REFLECTION/BLIND_SPOT/ASSUMPTION）；多视角 `PerspectiveEngine`；摘要 `Summarizer`；追问 `QuestionGenerator`；打分 `ThinkingAnalyzer`。
- 意图 + 粗分解：`SmartIntentRecognizer.recognize()`（关键词/正则/LLM，LLM 路径为保 KV 本地禁用）+ `splitIntoSubTasks`（按"然后/接着/再"切）+ `recognizeMultiIntent`。→ **意图与廉价分解现成**（但当前路由没在本地循环前 gate 它）。
- 状态/阶段块：`AgentExecutionState.BlockType` 已声明 `PLANNING/OBSERVATION/REFLECTION`，但本地路径从不 emit。
- 任务：`TaskTool` add/update/complete/fail/list + `ChatHistoryManager.saveAgentTaskList/loadAgentTaskList` 持久化。→ **但 "task" 不在 `DEFAULT_CORE_TOOLS`，本地 FC 循环调不到**，只能被 `buildTaskSummary` 只读注入。
- 清缓存/拼提示：`LlamaHelper.clearHistory()`(native clear) / `AIService.clearHistory`、`ResponseCacheManager`(prompt-hash 答案缓存、clearSession)、`trimHistoryToFit`+`compactionSummary`（已实现"摘要压缩 + 只增不改"保 KV）。`NpuEngineRouter.generate/generateWithTools/chatSend/chatJson` 为生成入口。

**真缺**：① 由循环驱动的显式 PLANNING 阶段（意图编排当初被移除）；② 反思 GATE（评价→回滚/重跑某步）；③ 本地引擎的**可恢复长任务队列 / pause-resume-step**（只有在线 `AgentRouter` 有）；④ 结构化任务图（现只有跨轮扁平列表）；⑤ 把 `task` 变成本地可调用（薄工具或薄动作）。

## 3. 设计：贴合端侧约束的多阶段编排

核心不变式：**system + tools 前缀一次定死、跨阶段字节不变（KV 可增量）；所有"阶段"用追加到会话尾部的消息表达**（plan 消息、步骤观察、反思消息），每阶段是一次独立的小 generate（前缀 KV 命中 → 只付新增后缀的 prefill）。这与 LangGraph"每次无状态重建"不同——端侧靠 KV 复用，必须**追加、不重建**。

组件（复用现有件，新增的是"编排 + 接线"）：

1. **IntentRouter（前置，纯代码优先）**：进入 run 前用 `SmartIntentRecognizer`（关键词/正则，零 LLM）判：闲聊/直答 vs 需要 agent；单步 vs 多意图（`splitIntoSubTasks`）。产出 `mode ∈ {chat, react, plan}`。**只读代码路径，不动 KV 前缀。**
2. **Planner（仅 mode=plan）**：把 `splitIntoSubTasks` 的粗步骤（或一个受限的小 generate，要求输出 JSON 步骤数组）写进一个**追加的 `[PLAN]` 尾消息** + 落到 `TaskTool`（作为可恢复的持久队列）。步骤是数据，不是新 system。
3. **Executor（复用现有 ReAct 循环）**：对当前步骤跑 `AgentLoopEngine` 的既有工具轮；步骤完成把 `[STEP_DONE i: 结果摘要]` 追加回会话。薄工具集（read_file/write_file/calculate/search_questions/recall_history + 4 查询）按需要扩，但**只读、小 schema、会话开始静态注入**。
4. **Reflector（复盘 gate，复用 `ReflectionEngine`）**：步骤级或答案级触发一次 `reflect()`；判"证据不足/方向错"则**追加 `[REFLECT] 教训`** 并重规划/重跑该步（上限 1–2，对齐 `MAX_RETRIES`）。不推翻已入历史的前缀，只在尾部加。
5. **Finalizer**：全部步骤 done → 走现有 `generateFinalAnswer` 汇总；`Summarizer` 可选压缩长会话。
6. **ResumeDriver（长任务续跑）**：跨调用从 `loadAgentTaskList` 读未完成队列 → 注入为 `[PLAN pending]` 尾消息 → 继续 Executor。这就是"继续完成"。**只有这条链路需要"中途加东西"，但加的是尾部消息（会话序列本就逐字节 replay），不破前缀。**
7. **Cache/Prompt 控制**：阈值式——历史 >60% 预算 `trimHistoryToFit`（已有）；触发 `compactionSummary`（已有）；答案级 `ResponseCacheManager`；显式清 KV = `LlamaHelper.clearHistory`（在**会话切换/重置**时，不在中途，否则丢前缀）。

## 4. 分阶段落地（PoC 优先，可回退）

- **P0（接线，最小风险）**：把 `ReflectionEngine` 作为**答案级 gate**挂到 `generateFinalAnswer` 后：草稿 → reflect → 不合格则带 `[REFLECT]` 重写一次（上限 1）。纯尾部追加、零前缀改动、不动 core tools。验证"反思确能提质"再往下。
- **P1（计划）**：启用 `IntentRouter`（纯代码）+ `mode=plan` 时把 `splitIntoSubTasks` 结果作为 `[PLAN]` 尾消息，Executor 逐步勾选；步骤持久化到 `TaskTool`（把 `task` 以**薄动作**暴露给本地循环：`next_step`/`complete_step`，不注入完整 TaskTool schema）。
- **P2（可恢复长任务）**：ResumeDriver + `loadAgentTaskList` → 续跑；加"步数/时间/上下文"三重预算（对齐现有 `MAX_TOOL_ROUNDS/TOTAL_TIME_BUDGET`）。
- 全程：每阶段一个守护测试（阶段消息只追加不改前缀 → 断言 schema 指纹跨阶段稳定；反思 gate 上限；续跑读队列）。

## 5. 不要做（端侧特有）

- **别每阶段重写 system prompt / 动态换 tools 集合** → KV 前缀破坏、每轮全量 prefill（真机 ~4K token/20s 首字）。阶段一律走**尾部追加**。
- **别把 Plan-and-Execute 的完整多 agent 图（AutoGen/CrewAI 那种）搬进来** → 4B 扛不住长指令 + 多角色，schema 也撑爆预算。
- **别给本地开 python_execute/linux_shell/大 schema 工具**（本会话已定）；要能力就加**薄工具**。
- **别让反思无限迭代**：硬上限 1–2，且每次反思是一次小 generate（要算进 `TOTAL_TIME_BUDGET_MS`）。
- **别在会话中途 clear KV**（丢前缀 = 变慢）；清只在会话切换。

## 6. 一句话给决策

先在 **P0（反思 gate，纯接线、可回退）**验证"多阶段是否真的提质、4B 是否扛得住额外一次小 generate"，再决定 P1/P2。要不要我从 P0 开始落一个可回退的 PoC？

## 7. 前置状态（同日基础设施体检后更新）

体检结论：**地基能跑单循环，但多阶段前有两件事必须先落地**，否则"长任务"只是把一个停不下来的循环放大到几分钟。

- **已完成（`f1c6239c`，已并入 `feat/chat-parts-renderer` 并上 origin）**：本地 Agent 停止链路四处断点修复。
  根因是 `AgentChatHandler.isGenerating()` 只转发 `AgentRouter`（后者只看在线引擎），
  导致 Activity 里 6 处 `if (isGenerating()) cancel()` 在本地运行时全部跳过取消；
  再加循环内没有取消标志、`cancel()` 提前谎报空闲、重入静默丢弃。
  现在有 `cancelRequested` 令牌（每轮循环 / 两处工具等待 / 统一退出都看得见），
  取消分支不发起任何解码也不注入兜底话术。**P0 反思闸可以直接建在这条链路上**——
  反思会额外增加小 generate 次数，若停止还不可用，用户就没有出口。
- **仍未做（按优先级排在 P0 之后或之前自行判断）**：① 本地链路没有逐次 token/KV/耗时观测
  （`AgentStats` 只在内存、`ModelUsageRecorder` 只覆盖在线、`NpuSignalAdapter.kvStats` 返回 null）
  —— 第 4 节里"反思每次是一次小 generate，要算进预算"这句话现在**无法量化验证**，
  建议 P0 顺手补最小记账；② `LlamaHelper.chatSend` 不持 `inferenceLock`，
  靠 `hasActiveGeneration` check-then-set，当前只因唯一调用方 `AIService` 先持锁而安全（约定式）；
  ③ 热切换 `release()` 拿不到写锁就"放弃释放"且调用方不检查；
  ④ 超 `n_ctx` 两路行为不一致（llama 直接报错拒绝，NPU 静默裁历史）——
  多阶段会把 prompt 撑大，这条在 P1/P2 会更容易踩到。
- **纠正一条容易误传的结论**：`AgentExecutionEngine`（每消息新建 layer → 序列每次为空 → 全量 prefill）
  和 `ChatSessionController` 都是**零引用的死脚手架**，不是现行路径；现行路径是
  `AIChatActivity → AgentChatHandler`（layer 只 new 一次），KV 增量复用确实生效。
  但这条约束对多阶段仍然成立：**编排层必须复用同一个引擎实例**，别学那两个类的写法。
- 分支现状：`feat/agent-local-multi-phase` 已并入并删除（本地与远端 tip 关系为祖先，无提交丢失），
  后续多阶段开发直接在 `feat/chat-parts-renderer` 上继续。

---
### Sources
- [ReAct、Plan-Execute、Reflexion 三种 Agent 架构怎么选](https://m.blog.csdn.net/2601_96557149/article/details/167117479)
- [Agent Loop Design Patterns: ReAct, Plan-and-Execute, Reflection, Production Control Flow](https://scixa.com/article?slug=agent-loop-design-patterns)
- [从 ReAct 原理到 LangChain/LangGraph 工程落地](https://m.blog.csdn.net/yonggeit/article/details/165277866)
- [死磕 LangGraph + ReAct：手撕带记忆多工具 Agent（含 plan-execute 代码）](https://developer.cloud.tencent.com/article/2726893)
- [LangGraph 预置 ReAct 图（agent/tools 节点 + should_continue 边 + remaining_steps 限步）](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py)
- [smolagents 开源 agent 循环](https://github.com/huggingface/smolagents)
- [Agent 架构体系总览（推理范式/记忆/框架/MCP）](https://developer.cloud.tencent.com/article/2762436)
