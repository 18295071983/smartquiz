# 变更日志

## [2026-08-16] 思考过程插入主消息气泡（主消息包裹全部内容）
用户要求"思考过程插入主显示，主消息将模型回复全部包裹，不要单独思考布局"：
1. **布局重构**：item_ai_message.xml 新增 message_bubble 容器（承载 ai_message_background 主气泡背景 + 内边距），把 thinking_label / thinking_content / inference_progress_view / component_container（工具卡片）/ attachments_recycler / content_host（正文）全部移入——思考过程、工具调用、模型回复现在都在**同一个主气泡**内自上而下显示。
2. **思考内容去独立背景**：thinking_content 去掉 thinking_message_background（不再单独一个浅色块），文字用 colorOnSurfaceVariant 次级色与正文（colorOnSurface）区分；新增 thinking_divider 细分隔线在思考区与正文之间，思考展开时可见、折叠/无思考时隐藏（updateThinkingContent / expandThinkingContent / collapseThinkingContent 统一控制）。
3. message_text 去掉自身 ai_message_background 背景（背景上移到 message_bubble），保留正文文字样式。
- 现在视觉：一个主气泡内 = 思考标签 + 思考内容（次级色）→ 细分隔线 → 工具卡片 → 正文，主消息完整包裹模型回复全部内容。

## [2026-08-16] 在线 Agent 思考/工具调用合并到 AI 消息内嵌显示（去掉独立思考消息）
用户反馈"工具调用集合到思考过程中，在线 Agent 独立思考消息与本地模型内嵌思考区合并显示，单独显示不合适"：
1. **思考合并**：AgentCallbackImpl.onThinkingToken 不再创建独立 THINKING 消息（addThinkingMessage），改为写入 AI 消息内嵌思考区（appendAgentThinkingToken——此方法原本存在但从未接线，现启用），与本地模型完全一致：思考中展开显示、思考完毕折叠、可点击展开。
2. **思考结束处理**：新增 finalizeAgentThinking（思考内容写入 AI 消息 thinkingContent + 折叠），替代原 finalizeThinkingMessage（针对独立消息）。
3. **工具调用已单通道**：确认在线 Agent 模式下工具调用仅以组件形式显示在 AI 消息内（appendAgentToolCall），addToolCallMessage 被 currentAgentGroupId 拦截不建独立卡片——思考区在上、工具卡片在下、正文最后，天然合并显示。
4. **删除死代码**：addThinkingMessage / updateThinkingMessageUi / finalizeThinkingMessage / resolveThinkingIndex / currentThinkingMessageIndex / currentThinkingMessageId / lastThinkingUiUpdateTime；ChatMessage.createThinkingMessage / createThinkingRoundMessage（无调用方）；MessageType.THINKING 保留（兼容历史会话数据渲染）。
- 现在本地模型与在线 Agent 的思考显示完全一致：都在 AI 消息气泡内，思考区 + 工具卡片 + 正文自上而下。

## [2026-08-16] 思考过程改为主消息色系（去掉紫色）
用户反馈"紫色不好，改为主消息色系"：
1. thinking_message_background.xml：背景 @color/thinking_background（淡紫 #EDE9FE）→ ?attr/colorSurfaceVariant（与 AI 主消息气泡同色系浅灰）。
2. item_ai_message.xml + item_thinking_message.xml：思考标签/正文文字 @color/thinking_text（深紫 #6D28D9）→ 标签 ?attr/colorOnSurfaceVariant、正文 ?attr/colorOnSurface（与主消息文字一致）。
3. 删除死代码：colors.xml / values-night/colors.xml 中 thinking_background/thinking_text/thinking_label 三个紫色定义（已无引用）；item_chat_message.xml 死布局（无引用方）。
- 思考区现在与主消息同色系（浅灰底 + 深色文字），仅在圆角/字号上与正文区分。

## [2026-08-16] 统一思考过程组件布局 + 删除死代码
用户反馈"有好几个思考过程组件布局各不相同，本地模型的思考布局好，统一一下"：
1. **统一思考布局**：item_thinking_message.xml（在线 Agent 多轮思考独立消息）改为与 AI 消息内嵌思考区（item_ai_message 的 thinking_label/thinking_content，用户认可的那套）完全一致的视觉——同一 thinking_message_background、thinking_text 紫色、11sp label / 12sp 正文 / 1.2 行距 / 8dp 内边距、label 统一"💭 思考过程"；ThinkingMessageViewHolder label 文案同步（Agent 轮次保留"第N轮思考"）。
2. **删除死思考组件**：
   - agent_block_thinking.xml + AgentExecutionPanel.java（软件层 Agent 面板的思考块，布局早已从 item_ai_message 解绑、无实例化方）；
   - thinking_dots_view.xml（无任何引用）。
   - ChatAdapter 清理 agentExecutionPanel 字段及 PAYLOAD_AGENT_UPDATE/状态绑定中的死逻辑（该字段恒 null）。
3. 保留：AgentExecutionView（agent_execution_view.xml）——仅 AIImportActivity 导入进度展示使用，非聊天思考，不动。
- 现在思考 UI 只有两套且视觉统一：本地模型（AI 消息内嵌思考区）与在线 Agent（独立思考消息），样式一致。

## [2026-08-16] 修复对话消息乱显示/乱插入：全部改为按消息 id + toolCallId 定位
用户反馈"组件没有 id 判断导致乱显示乱插入、UI 渲染不按先后顺序、新对话 id 混用、发送消息位置错乱"：
1. **工具调用回调链传递 toolCallId**：AgentCallback.onToolCallStart/onToolCallComplete 接口增加 toolCallId 参数；OnlineAgentEngine 回调传 tc.id/tr.toolCallId（引擎内部原有，之前未透出）；AgentChatHandler.AgentChatCallback 同步。
2. **工具组件按 id 精确更新**：appendAgentToolCall 生成的 tool_call 组件 props 增加 toolCallId 字段，完成时按 id 匹配更新（不再"找最后一张 running 卡片"，修复并行工具/乱序更新错位）；ChatMessage.ToolCallInfo 增加 toolCallId 字段，createToolCallMessage 支持带 id；新增 findToolCallMessageById 按 id 更新独立工具卡片（替代 findLastSpecialMessage 猜位置）。
3. **消息定位全部按 id**：新增 findMessageIndexById + resolveStreamingIndex（优先按 currentStreamingMessageId 查找，回退索引）；safeUpdateMessage / safeUpdateMessageFromStreamingManager / completeGeneration / appendAgentToolCall / appendAgentThinkingToken / OutputRouter 回调（onTextOutput/onThinkingStart/onThinkingContent/onThinkingEnd/onError/onStreamComplete）/ onlineUpdateRunnable / handleGenerationError / onGenerationError / onGenerationStopped / StreamingTokenHandler.onError / handleStreamTokenLegacy / AITokenReceiver / AIResultReceiver / onInferenceProgress 全部改用 id 定位，不再依赖会漂移的 currentStreamingMessageIndex。
4. **思考消息 id 锚定**：新增 currentThinkingMessageId + resolveThinkingIndex，updateThinkingMessageUi/finalizeThinkingMessage 按 id 定位；各重置点同步清 thinking id。
5. **新对话 id 清理**：clearStreamingState / handleGenerationError / processChatMessage 各路径重置 currentThinkingMessageId。

## [2026-08-16] 对话折叠策略调整：思考中默认展开，思考完毕自动折叠；主回复全部展开
用户要求"思考过程中默认展开不要折叠，思考完毕后你再折叠；主回复默认展开，长内容不自动折叠"：
1. **思考过程**：ChatAdapter.updateThinkingContent 改为流式（GENERATING/IN_PROGRESS）时强制展开显示思考链，思考完毕后按 thinkingExpanded（默认 false）折叠；ThinkingMessageViewHolder.bind 同步（processing 时强制展开）。AIChatActivity.finalizeThinkingMessage 改为思考完毕一律折叠（原来"有内容保持展开"）。
2. **主回复**：handleLongContent 删掉长内容（>500 字符）自动折叠 + 展开按钮逻辑，全部直接展开（maxLines 无限）；删除无调用方的 applyExpansionState 方法。
3. 更新 ChatMessage.thinkingExpanded 注释（原注释"流式中保持折叠"已过时）。

## [2026-08-16] 清理在线 Agent 不可达假功能（暂停/恢复/参数验证 UI 链）
- OnlineAgentEngine 从不触发 onNeedMoreInfo/onExecutionPaused/onExecutionResuming/onInputValidationResult（在线引擎无暂停恢复概念），AgentChatHandler 中对应 4 个回调覆写 + showInputDialog/showValidationError 两个对话框方法 + getCurrentResponse/getCurrentThinking 空包装均为不可达假功能 → 全部删除。
- AgentRouter 删除 5 个空方法（getCurrentResponse/getCurrentThinking/getRetryCount/resumeExecution/cancelPause）。
- AgentCallback 接口删除 4 个无调用方 default 方法；物理删除 InputValidator.java（仅被死回调引用）。
- 删除 dialog_input_parameter.xml / dialog_validation_error.xml 布局 + strings.xml 中对应的 Agent 输入验证/校验错误字符串块（约 60 行，无引用）。
- AgentChatHandler 清理 9 个无用 import（AlertDialog/TextUtils/LayoutInflater/View/Button/EditText/LinearLayout/TextView/Toast/R）。

## [2026-08-16] 修复删除后编译错误 + 清理 OnlineAgentEngine 本地回退死代码
1. 修复删除 UnifiedAgentEngine 后 3 处编译错误：OnlineAgentEngine 补 `shutdown()` 方法；AgentChatHandler 两处 `engine.getCurrentMode()` 改为固定字符串"在线"（在线无推理模式概念）。
2. 物理删除 3 个死模式 handler：AgentModeHandler / CreativeWritingModeHandler / ThinkingModeHandler（均无引用方）；ChatOrchestrator 清理对应 import。
3. OnlineAgentEngine 彻底清除本地回退机制（约 170 行）：删 LocalFallbackHandler 接口、localChat/memoryManager/localFallbackEnabled/consecutiveOnlineFailures/isUsingLocalFallback/localFallbackHandler 字段、setLocalFallbackHandler/setLocalFallbackEnabled/isUsingLocalFallback/forceLocalFallback/resetFallbackState/shouldFallbackToLocal/canUseLocalModel/executeWithLocalModel/buildLocalPrompt/recordOnlineFailure/emergencyMemoryCleanup 方法及 3 处调用点（doExecute/主循环/streamOneIteration），删 ALChat/ModelMemoryManager import。在线失败现在直接 notifyError，不再回退。

## [2026-08-16] 删除本地 Agent 引擎（在线 Agent 独立化 + 代码物理删除）
用户要求"先改在线模式再删本地 Agent 代码"：
1. **在线独立**：OnlineAgentEngine.doExecute 删除本地回退分支（在线不可用直接报错，不回退本地）；AgentRouter 重写为纯在线路由（删 localEngine/本地委托/回退方法）。
2. **物理删除**：UnifiedAgentEngine.java + EnhancedAgentHandler.java + CreativeWritingEngine.java + ServiceRouter.java + TaskDecompositionManager.java（5 个本地 Agent 组件，均无外部引用）。
3. 引用修复：AgentChatHandler setReasoningMode 忽略（在线无推理模式）、import 清理；AgentRouter.setReasoningMode 兼容签名。
- 现在：在线模型 = 完整 Agent（OnlineAgentEngine）；本地模型 = 普通对话；深度思考 = 模式（思考指令）。本地 Agent 代码全部移除。

## [2026-08-16] 清理死代码（模式 chip / 对话框 / 本地 Agent 入口）
用户要求清理死代码：
1. 删除快捷工具卡片废弃模式 chip（Agent/创意/思考辅助）——layout activity_ai_chat.xml 删除 3 个 Chip；AIChatActivity 对应字段/findViewById/监听删除。
2. 删除 ModeSelectorDialog.java + dialog_mode_selector.xml（模式切换已改点击切换，不再使用）。
3. 本地 Agent 入口全部切断：AgentRouter.execute 本地分支不再执行（提示弃用）；UnifiedAgentEngine.execute 本地分支防御性返回——本地 Agent 方法体保留（被在线组件/外部引用，硬删有破坏风险）但运行不可达。
- 在线 Agent 完整保留（OnlineAgentEngine + AgentRouter 在线分支）。

## [2026-08-16] 模式切换改点击即切换（去掉复杂对话框）
- 用户要求：深度思考/普通对话切换改为点击切换，不用复杂画面。
- 顶部模式按钮（btnModeSelect）：点击直接在 普通 ↔ 深度思考 间切换（toggleMode），不再弹 ModeSelectorDialog 复杂对话框；按钮文本去掉"▼"。
- 删除 showModeSelectorDialog 方法 + import；ModeSelectorDialog 类保留（未被引用）。

## [2026-08-16] AI 对话模式重构（简化 + 删除本地 Agent 使用）
用户要求"清理对话模式、重构 UI、本地模型普通对话、在线完整 Agent、深度思考用指令"：
- ChatMode 从 5 个精简为 **2 个（普通/深度思考）**：删除 创意写作/Agent/思考辅助。
- 路由简化：`processChatMessage` = 在线模型 → 完整 Agent（processChatMessageWithAgent）；本地模型 → 普通对话（processChatMessageNormal）。AGENT 模式分支删除。
- UI：废弃模式入口隐藏（chipAgentMode/chipCreative/chipThinkingAssist + ModeSelectorDialog 的 3 个选项）。
- 深度思考：保留为模式（= 思考指令 + enableThinking），非独立行为。
- ChatOrchestrator.isAgentMessage 返回 false（Agent 不再独立模式）。
- 本地 Agent 引擎代码保留但路由不再调用（待后续清理）。

## [2026-08-16] 本地 Agent 引擎弃用（本地模型降级普通对话）
- 用户决定放弃本地 Agent（工具调用/思考链在 4B 上多轮修复仍不稳定）。
- 变更：AGENT 模式 + 本地模型 → 统一降级普通对话（提示"本地模型暂不支持 Agent 工具调用"）；在线模型 Agent 完整保留（processChatMessageWithAgent 仅在线路径）。
- 本地 Agent 引擎代码保留（可恢复），路由层不再调用。

## [2026-08-16] 推理慢排查：flash attention 改 AUTO（此前误判 AWQ）
- 更正：模型实为 Q4_K_M（文件名确认），"Awq" 是 general.name 元数据命名，非量化类型——上一轮 AWQ 判断错误。
- 慢的根因候选：flash attention 强制 ENABLED，Vulkan 后端 kernel 在部分 Adreno 上性能差（0.4 t/s 异常慢）→ 改为 AUTO（llama.cpp 自动判断，不支持的 GPU 回退标准 attention Vulkan kernel，通常更快）。
- 待验证：重载后速度是否提升。

## [2026-08-16] Agent 生成状态监控（解决"一直等待无反馈"）
- 现象：本地模型推理慢（0.4 t/s），Agent 生成期间 UI 无"处理中"反馈（状态栏只在思考/工具/完成时更新），用户感觉卡死等待。
- 修复：processChatMessageWithAgent 启动 Agent 前状态栏立即显示"⏳ 模型处理中..."（busy），后续 onThinkingToken（🧠 思考中）/onToolCallStart（🔧 调用）/onComplete（✅ 执行完成）正常覆盖更新——生成全程有明确状态反馈。
- 提示：0.4 t/s 为设备推理性能问题（Adreno GPU 未充分利用/设备档位），状态栏反馈已改善 UX；性能优化另计。

## [2026-08-16] KV 增量优化：工具循环后续轮次不再全量重解码
- 优化点（用户指定）：每轮全量重解码 prompt（1-2 秒/轮）→ KV 增量复用。
- 实现：C++ 新增 `nativeAppendMessagesAndGenerate`——只把 appendStart 起的新增消息（assistant/tool）按 Qwen3 im_start 格式手动渲染 → tokenize → 分块 decode 进既有 KV（不清 cache）→ 加 generation prompt → `generateFromEvaluatedContext` 从当前位置生成。
- Java：`executeNativeAgentLoop` 第一轮全量（decode system+历史+工具定义），后续轮调增量（只新增消息）；增量失败自动回退全量（committedMsgCount 重置）。
- 效果：多轮工具循环从"每轮 1-2 秒全量 decode"降为"只 decode 新增的百 token 级消息"，提速显著。

## [2026-08-16] Agent 继续优化：中文工具清单 + 表达式净化
1. system prompt 加**中文工具清单**（ai_weather=查天气/network_search=网络搜索/...）——模型不用理解英文模板工具说明，直接知道工具名和用途（配合 REQUIRED + 中文示例，工具调用链路完整）。
2. 参数净化扩展到 `python_calculate.expression`：剥离"请问/等于/多少"等中文干扰词 + 转换中文运算符（乘以→*、除以→/、加→+、减→-）+ 中文括号→英文括号，计算表达式更干净。

## [2026-08-16] 解码设计优化：n_batch 1024 + tool_choice REQUIRED
针对"本地库解码设计问题"的修复：
1. **n_batch 256 → 1024**（MAX_BATCH_SIZE）：此前 256 导致 prompt（1397 tokens）分 6 批 decode 变慢、且单批易超限崩溃；1024 覆盖常见 prompt 2 批内，GPU 吞吐更高。
2. **工具场景 tool_choice AUTO → REQUIRED**：generateWithTools 仅 Agent 阶段1（已判定涉及工具）使用，强制模型输出 tool_call（llama.cpp 对 Qwen3 支持 REQUIRED），解决"模型空输出/直接回答不调工具"。
3. 已知保留设计：生成逐 token 自回归（本质）、每次全量重解码 prompt（正确但慢，KV 增量可后续优化）。

## [2026-08-16] 修复 prompt 单批 decode 超 n_batch 的 SIGABRT（"思考链中断"真因）
- 日志：`prompt_tokens=1397 → Native inference crashed (signal 6)`——主循环生成崩溃，降级到直接回答（用户看到"思考链中断/没继续"）。
- 根因：`llama_batch_get_one(tokens.data(), 1397)` **单批 decode 全部 prompt**，而 context n_batch=256（GPU）→ **llama_decode 对 batch > n_batch 触发 assert → SIGABRT**。此前 2323 tokens 被"Prompt too long"先拦截没到 decode；现在 1397 走到 decode 即崩。
- 修复：generateStream / generateStream 另一路径 / NativeChatContext.encodeTokens / generateStreamWithImage 历史评估共 4 处 **prompt 分块 decode**（batch ≤ n_batch 循环 llama_decode）。

## [2026-08-16] 空输出根因：中文模型看不懂英文 FC 指令 → 加中文 tool_call 示例
- 日志定位：主循环模型生成空（第一个 token 即 EOS）——模板注入的 <tools> 工具说明是英文（"You may call one or more functions..."），中文 Qwen3-4B 看不懂要输出什么 → 直接 EOS 空输出。
- 修复：工具场景 system prompt 加**中文 tool_call 输出格式示例**（`<tool_call>{"name":"ai_weather","arguments":{"city":"北京"}}</tool_call>`），教模型输出格式，避免空输出。

## [2026-08-16] 提示词修正：去掉"现成话术"导致模型复述不调工具
- 日志真相：主循环（带工具 prompt）模型空输出 ×2 → 降级直接回答输出"暂时无法获取实时数据"（11 字符）——**模型复述了 system prompt 里的现成话术**（"无法获取时必须说明'暂时无法获取实时数据'"），而不是先调用工具。
- 修复：① 核心提示词去掉现成话术，改为"先通过工具获取真实数据，不编造；工具失败才如实说明"；② 工具规则精简为 3 条核心（不淹没 4B）；③ 保留"必须调用工具（缺参数也调用）"。

## [2026-08-16] 工具定义恢复完整（模型不调用工具的根因修复）
- 现象：AGENT 模式模型不调用工具，回复"暂时无法获取实时数据"（不编造规则生效但工具未调用）。
- 根因：工具定义被精简过度（ai_weather 描述仅"查天气"3 字、参数描述截断）——**4B 模型看不懂工具用途，自然不调用**。
- 修复：filterToolsForNative 恢复为**纯白名单过滤（保留完整原始定义）**——7 个工具全量定义约 1200-1500 tokens，预算足够（非思考 512 上限下 prompt+maxTokens 远小于 n_ctx）。

## [2026-08-16] AGENT 模式回退非思考 FC（优先保证工具调用）
- 现象：AGENT 模式启用 thinking 后，Qwen3-4B 思考完"忘记"调用工具（回复"无法获取实时数据"而非输出 tool_call）——thinking+FC 组合对小模型是难点（llama.cpp issue #20260 同类）。
- 修复：AGENT 模式 enableThinking 回 false（仅深度思考模式启用思考链）——非思考 FC 模式工具调用更稳定；深度思考模式（非 Agent）单独体验思考链。

## [2026-08-16] Agent 恢复应用内 UI 操作能力（app_operation）
- 用户反馈"本地 Agent 不能调用 UI"——根因：工具精简时把 `app_operation`（应用内部页面跳转工具）从白名单删除了。
- 修复：加回 `app_operation` 到 NATIVE_CORE_TOOLS（7 个工具）——支持 navigate 跳转页面（用户/题库/答题/错题本/OCR/AI 等）、list_pages、go_home/go_back、open_settings、share；Agent 现在能操作应用 UI。
- 同时保持精简（7 个高频工具），避免 4B 选择负担过大。

## [2026-08-16] Agent 自主思考修复（思考链 + 工具调用组合）
用户反馈"Agent 不能自主思考/无思考链/思考链中断/工具不调用"，三处设计修复：
1. **AGENT 模式默认启用思考链**（AIChatActivity）：此前 enableThinking 仅 DEEP_THINKING 模式为 true，AGENT 模式恒 false（ChatMode 单选互斥）→ 现在 AGENT 模式也开思考，模型先自主思考再决定工具。
2. **思考模式 maxTokens 放大 1024**（UnifiedAgentEngine）：此前工具循环 512，思考链 300-500 tokens 被截断（"思考链中断"）→ 思考模式 1024（思考+工具 JSON 空间足够）。
3. **工具精简到 6 个核心**（天气/搜索/计算/翻译/定位/文件读取）：4B 对 11 工具选择负担大（选错/不调用），减半降低负担 + 释放上下文空间（工具定义 tokens 减半）。

## [2026-08-16] 提示词系统分层设计（场景差异化）
- 拆分提示词为可组合层：`buildNativeSystemPromptCore`（L1 角色 + L2 动态时间环境，所有场景）+ `buildNativeSystemPrompt`（Core + L3 工具协议，仅工具场景）。
- 场景注入：工具循环（阶段1）用完整版（L1+L2+L3）；直接问答/干净回退（阶段2）用精简版（L1+L2，无工具协议，模型更专注回答）。
- L4 历史（跨轮）与 L5 请求保持既有注入。

## [2026-08-16] 本地 Agent 引擎重新设计（推翻旧设计）
参考 small-model-agent-skill 协议（一次一任务、工具少而精、尽早总结、失败快速降级），重写本地 Agent：
- **阶段0 意图分流**（规则，零 LLM 成本）：消息含工具域关键词（天气/搜索/计算/翻译/定位/文件/系统/应用）或历史有工具上下文 → 工具循环；纯聊天直接问答（`executeNativeDirectAnswer`，不走工具减少空输出）。
- **阶段1 工具循环**（原生 FC）：≤4 轮（原 10 轮，小模型长循环退化）、每轮 maxTokens 512（工具 JSON <200）、空响应连续 2 次直接退出到阶段2（原 6 次+降级重试）。
- **阶段2 保证输出**：直接问答/干净回退用原始问题普通生成，仍空才返回明确错误信息。
- 保留：跨轮记忆、工具精简、参数净化、结果回喂、system prompt 分层设计。

## [2026-08-16] 移除模糊回退话术，直接返回错误信息
- buildNativeFallback 无工具结果时不再返回"我暂时无法提供详细回答"模糊话术，改为明确错误信息："处理失败：本地模型未能生成回复...建议重发/检查模型/重新下载"——真实告知用户失败原因。

## [2026-08-16] 回退 repeat penalty（疑似导致主循环全空输出）
- 现象：加 penalties sampler 后主 FC 循环每轮 EMPTY（模型什么都不生成，6 轮全空）。回退 4 处 penalties（repeat 1.1）——重复问题另用 Java 侧方案（重复检测/截断）或后续调参数。
- 同时确认：工具结果回喂链路存在（history tool role + 下一轮传递），但**模型当前连 tool_call 都不输出**（主循环空），需先解决"空输出"才能验证工具回喂。

## [2026-08-16] Agent 工具调用强制规则（缺参数也要调用工具）
- 问题：模型把"缺参数"当成不调用工具的理由（"今天天气怎么样"→直接回复"请提供城市"而非调用 ai_weather）——应走工具链路：调用工具 → 工具返回"缺少城市参数" → 模型转告用户。
- 修复：system prompt 工具规则明确——涉及天气/搜索/计算/翻译/定位等**必须调用工具，即使参数不全也要调用**（工具返回缺什么）；工具提示缺参后转告用户，不编造结果。

## [2026-08-16] 模型输出重复修复：sampler 加重复惩罚
- 现象：模型回复"请直接询问天气情况...×2"重复两遍。根因：C++ 所有 sampler chain（generateStream/generateFromEvaluatedContext/chatSend 共 4 处）只有 top_k/top_p/temp/dist，**无 repeat_penalty**——4B 模型无惩罚易输出重复。
- 修复：统一加 `llama_sampler_init_penalties(n_vocab, 64, 1.1, 0, 0)`（repeat 1.1 温和抑制，工具调用 JSON 不受影响）。

## [2026-08-16] Agent 流程系统性重设计（提示词/上下文/工具系统）
按分层设计重构本地 Agent：
- **上下文注入系统**（5 层）：L1 角色 + L2 当前时间环境（"今天/明天"有基准，天气查询才正确）+ L3 工具协议一句话（与模板原生 FC 一致）+ L4 跨轮历史（多轮记忆）+ L5 当前请求。
- **工具系统**：白名单 11 工具精简定义；参数描述智能截断（枚举型如 action 保留完整 ≤60，其余 ≤20）；工具结果回喂带工具名前缀 `[ai_weather] 结果`（模型识别来源）；参数净化 + 失败结果原样回喂（自纠错）。
- **Agent 循环**：组装上下文 → 生成(≤1024) → 解析 → 有工具执行回喂循环≤10 / 无工具最终答案 / 空响应降级 / 上限干净回退。

## [2026-08-16] 本地 Agent 设计修复：跨轮对话记忆（多轮上下文）
用户反馈"肯定是设计问题"——确认根因：executeNativeAgentLoop 每次调用新建 history（只有 system+当前 user），**无跨轮记忆**——"今天天气怎么样"→补"银川"时模型不知道前文（天气意图），只能重复输入（"银川银川"）。
- 修复：新增 `nativeConversationHistory` 跨轮历史（实例字段）：每次 Agent 调用时 history = system + 之前轮次的 user/assistant + 当前 user；结束时 `rememberNativeTurn` 记录本轮 user+assistant（裁剪 NATIVE_MAX_HISTORY）；`clearContext`/新对话清空。
- 效果：多轮对话（问天气→补城市→再问）模型能结合前文正确调用工具。

## [2026-08-16] 本地 Agent 框架流程修复（3 处核心 bug）
用户反馈"agent框架及流程有问题"，日志还原三处缺陷：
1. 🔴 **emptyResponseStreak 跨调用泄漏**：实例字段未在每次 executeNativeAgentLoop 开头重置，前次残留值（10/11）导致主 FC 循环只跑 1 次就被误判"连续空响应"直接 abort，全部依赖 clean fallback（用户看到的"请提供具体城市名称"/"银川银川"都是 fallback 输出）。已重置。
2. 🔴 **token 预算低估 + 上限过大**：估算 promptEst 1419 vs 实际 1676，budget 4341 仍超 n_ctx 5888（1676+4341>5888）→ 主循环"Prompt too long"。工具调用轮输出 <200 tokens，直接上限 1024，彻底消除超限。
3. 🟡 **推理速度异常**：0.125-0.4 t/s（8 秒生成 1 token），clean fallback 95 tokens decode 2.7s——疑似设备（Redmi Note 13, Mali GPU）Vulkan 后端问题，待验证。

## [2026-08-16] 本地 Agent 回退流程修复：失败时重新问模型，不再直接硬编码
- 问题：Agent 达到最大迭代（工具链路失败）时直接返回 buildNativeFallback 硬编码文本（"关于...无法提供详细回答"），未利用模型本身的问答能力。
- 修复：新增 generateCleanFallback——失败时用**原始问题 + 精简历史**（不带工具定义）重新调用模型生成真实回复；仅当模型也未能生成时才用硬编码兜底。

## [2026-08-16] 思考组件：默认折叠、有思考内容自动展开
- 行为调整：思考组件默认折叠（已有 thinkingExpanded=false）→ 思考 token 流式到达时**自动展开**显示思考链 → 思考结束保持展开（用户可手动折叠；无内容才保持折叠）。
- Agent 模式修复：此前 Agent 模式（currentAgentGroupId 非空）思考消息被拦截不插入（addThinkingMessage return -1），本地 Agent 原生 FC 的思考链丢失——现在所有模式都插入思考组件，与工具卡片互补。

## [2026-08-16] 修复 system prompt 与原生 FC 冲突
- 问题：buildNativeSystemPrompt 引导模型"用 JSON 文本输出工具调用" + 中文工具名（"天气/网络搜索"）——与 C++ chat template 注入的原生 FC 格式（<tool_call> XML）和真实工具名（ai_weather 等）冲突，模型收到两套矛盾指令可能困惑/用错名字。
- 修复：system prompt 精简为只保留角色 + 参数精确性约束；工具调用格式与工具列表完全交给模板（原生 FC 路径），消除冲突。

## [2026-08-16] 本地 Agent 系统性修复（5 层，根治空响应）
问题链：Agent prompt（11 工具 JSON ≈2323 tokens）+ maxTokens 4096 > n_ctx 5888 → "Prompt too long" → 连续 10 次空响应 → 兜底"无法提供详细回答"。
1. **工具定义精简**（根治）：filterToolsForNative 现在压缩工具定义为短描述 + 精简参数（描述≤12字），prompt tokens 从 ~2323 降至 ~800，与 4096 maxTokens 叠加也不超 n_ctx。
2. **Agent token 预算**：computeGenerationTokenBudget 用真实 n_ctx（llama_n_ctx，含 memoryPool 预算钳制，此前误用 contextSize 字段导致预算失效）计算安全 maxTokens。
3. **空响应自动降级**：连续 2 次空响应 maxTokens 减半（≥256）；连续 6 次提前中止，不再白跑 10 轮。
4. **错误显性化**：C++ "Prompt too long" 等错误经 onError 回调打印（此前静默空响应）。
5. **KV 回退 F16**：Q8_0 在部分设备解码 SIGABRT，暂不自动启用（API 保留）。

## [2026-08-16] Agent 推理 SIGABRT 崩溃修复（KV 回退 F16 + n_ctx 修正）
- 崩溃日志：`Processing prompt as single batch, size=2321 → Fatal signal 6 during inference`——prompt 解码时 SIGABRT（abort，非 C++ 异常，try-catch 无法捕获），每次 generateWithTools 都崩。
- 判断：Q8_0 KV 量化在部分设备**解码时**触发 kernel assert（创建时不崩、解码时崩）。
- 修复：AIService 不再自动启用 Q8_0（KV 恒 F16，最稳；setKvCacheType API 保留供设备白名单手动开启）。
- 顺带修正：Agent token 预算改用新 JNI `nativeGetModelNctx`（主 context 真实 n_ctx，此前 getContextSize 在 chat 未创建时回退 Java 缓存 16384 导致预算失效仍传 4096）。

## [2026-08-16] 本地 Agent "无法提供详细回答" 根因修复（token 预算）
- 根因（日志确认）：`Prompt too long: 2323 tokens + maxTokens 4096 > n_ctx 5888`——Agent 原生 FC 的 prompt（system + 11 工具定义 JSON ≈ 2323 tokens）加上默认 maxTokens 4096 超出 n_ctx 5888，每次生成前被拒 → 连续 10 次空响应 → 走兜底消息"关于...我暂时无法提供详细回答"。
- 修复：`UnifiedAgentEngine.computeGenerationTokenBudget`——每次生成按 `n_ctx - 估算prompt tokens - 128保留` 动态计算 maxTokens（拿不到 n_ctx 时保守 1024）；防止超限空跑。
- 预期：budget ≈ 5888 - 2323 - 128 ≈ 3400（工具调用轮足够），不再触发 Prompt too long。

## [2026-08-16] 回退内存激进改动（保留崩溃修复）
- 按用户要求回退三处内存改动（恢复原始加载行为）：
  1. KV Q8_0 阈值 4500→2000 的改动回退（4B 恢复 F16 KV）
  2. 上下文按可用内存降档逻辑移除（恢复模型尺寸感知档位）
  3. 加载前内存预检（拒绝加载）移除
- 保留：Vulkan 异常捕获 + Q8_0→F16 回退（native 崩溃修复，必要）、KV 量化机制本身（setKvCacheType API）、8B 的 Q8_0 场景（≥4500MB 仍生效）。

## [2026-08-16] 防 LMK 杀进程：加载前内存预检
- 背景：非应用主动退出，是 Android LMK 在内存超限时强制杀进程；现有 onTrimMemory 防护在模型加载期间无效（释放逻辑检查 isModelInitialized，加载完成前为 false，且与加载线程并发竞态）。
- 修复：loadModelLocked 在 initModel 前做内存预检——预期峰值（权重 + KV(contextSize×70/140KB每token) + 400MB 余量）> 可用内存时**拒绝加载**并明确提示"清理后台/换小模型"，不再硬加载到被 LMK 杀。
- 叠加既有 KV Q8_0 强制 + 上下文按内存降档，4B 预期峰值 ~3GB < 6GB 可用，可稳定加载。

## [2026-08-16] 应用自动关闭修复（模型加载内存被杀）
- 根因（日志确认）：HyperSentinel 显示模型加载时 Rss 峰值冲到 4.47GB（Qwen3-4B 权重 2.3GB + F16 KV 1.2GB + 加载峰值），设备 12GB 但 MIUI 后台占用后可用仅 6GB → 内存压力震荡 → 系统 LMK 杀进程（"自动关闭"）。
- 修复（AIService）：
  1. KV Q8_0 阈值 4500MB → 2000MB（4B 模型也强制 Q8_0，KV 1.2→0.6GB；shader 不兼容时 native 自动回退 F16，无风险）
  2. 上下文按可用内存降档：可用 <4GB → 上限 6144；<6GB 且模型 ≥2GB → 上限 8192（叠加模型尺寸档位取最小）
- 预期：4B 常驻约 2.9GB（原 3.5GB），峰值显著下降，12GB 设备（可用 6GB）可稳定加载。

## [2026-08-16] 上下文初始化失败崩溃修复（Vulkan 异常捕获）
- 根因（日志确认）：`AdrenoVK-0: Failed to link shaders → Pipeline create failed → libc++abi: terminating due to uncaught exception (vk::SystemError: createComputePipeline: ErrorUnknown)`——ggml Vulkan backend 在 Adreno 750 上 shader pipeline 创建失败抛出 C++ 异常，native 层未捕获 → 进程崩溃（用户见"上下文初始化失败"）。
- 修复：native-lib.cpp 的 `llama_init_from_model` / `llama_model_load_from_file` 全部调用点包 try-catch（含首次、CPU 回退、CPU 重载）；主 context 创建失败且 KV 为 Q8_0 时**自动回退 F16 KV 重试**（Q8_0 KV shader 在部分 GPU 不兼容），仍失败走既有 GPU→CPU 回退。
- 应用侧无需改动：AIService 加载失败已有错误提示。

## [2026-08-16] 日志审查修复
- 修复 AILogger EPERM：`getLogDirectory` 在 appContext 未初始化（LlamaHelper 类加载早于 AILogger.init）时走外部存储 /storage/emulated/0/OilQuiz/ → Android 13+ 无权限 EPERM 刷错误日志。改为返回 null（logcat-only），AILogger.init 后落到应用私有目录（getFilesDir，无需权限）。
- 温度修复验证：GpuCapabilityDetector.getTemperature 已归一化毫度 + 按 type=cpu/gpu 选择传感器 + 范围校验，性能面板温度显示正常。

## [2026-08-16] 本地多模态识别改为 GGUF 权重元数据（不再靠模型名）
- 新增 JNI `nativeGetModelArchitecture` → `LlamaHelper.getModelArchitecture()`：读模型 `general.architecture` 元数据（llama_model_meta_val_str，兼容旧 GGUF 回退 general.name）。
- `AIService.autoLoadMultimodalIfAvailable` 重写：① 读架构，非视觉架构（如 qwen2.5/gemma3 文本版）直接跳过 mmproj；② 视觉架构（qwen2vl/qwen3vl/gemma3v/llava/mllama/minicpmv/glm4v/internvl 等 24 个）才尝试；③ 预设配对优先，配对失败**逐个尝试目录所有 mmproj**（loadMultimodal 成功即匹配，解决多 mmproj 场景，失败安全返回 false 不崩）。
- 配合之前的 mmproj 加载成功判定（isMultimodalLoaded），本地视觉能力 = 架构元数据 + 投影加载成功双确认，完全脱离文件名/模型名依赖。

## [2026-08-16] 在线视觉能力识别：能力字段真实化（不再只靠模型名判断）
- 说明：在线模型没有权重文件可读，视觉能力只能靠配置/模型名。发现 `OnlineModelConfig.supportsVision` / `capabilities.supportsImageInput` 是死字段（从未赋值、未持久化）。
- 修复：`OnlineModelManager` 加载模型列表后 `refreshAllSupportsVision()` 按模型名自动填充 supportsVision（新增静态 `isVisionModelName`）；save/load 持久化 supportsVision/supportsCode。
- `AIChatActivity.isOnlineVisionModel` 改为**配置能力字段优先**（hasCapability("vision")），模型名关键词仅兜底——为未来"用户手动设置视觉能力"留出位置。

## [2026-08-16] 在线多模态增强：在线视觉模型直接看图（base64）
- 此前在线模型图片走 OCR 文本注入 Agent，在线视觉 API 未利用。现增强：
- `OnlineInferenceService.generateStreamWithImages` + `callOpenAIAPIWithImages`：图片 base64 以 OpenAI 多模态 content 数组格式注入最后一条 user 消息（[{type:text},{type:image_url}]），支持 Qwen-VL/GPT-4o 等兼容模型；Anthropic 端点明确报错提示。
- `AIChatActivity`：ViaAgent 附件路径新增在线视觉分支——`isOnlineVisionModel()`（模型名含 vl/vision/4o/omni/gemini/glm-4v）+ 单图 → `handleOnlineMultimodalImage`（base64 ≤4MB → 流式看图回答）；图片过大/读取失败/异常回退 OCR+Agent。
- 最终图片处理优先级：本地多模态（离线免费）→ 在线视觉（直接看图）→ OCR 文本 + Agent（兜底）。

## [2026-08-16] 本地/在线多模态配合策略修正
- 发现主发送路径分支问题：有图片附件时走 `processMessageWithAttachmentsViaAgent`（OCR 文本 + Agent），本地多模态分支此前加在无图片才走的 OCR 路径上——实际永不触发。已把本地多模态判断移到 ViaAgent 的 thenAccept（正确位置）：vision 模型 + mmproj + 单图 → 直接 `handleMultimodalImage` 本地视觉理解；否则 OCR + Agent。
- 配合策略：本地多模态优先（离线免费），在线模型场景图片走 OCR 文本注入 Agent（在线视觉 API 未利用，为现状，可后续增强），两者互斥不冲突。

## [2026-08-16] 清理构建残留（810MB）
- 删除无引用的 CMake 残留目录：`src/main/cpp/build/`（392MB）、`build-android/`（208MB）、`build-test/`（210MB）——Gradle 实际用 `.cxx/`，CMake 产物输出到 `src/main/jniLibs/`（CMakeLists LIBRARY_OUTPUT_DIRECTORY），三个 build* 目录均无脚本/配置引用。
- 删除死脚本 `build_android.sh`（引用不存在的根目录 llama.cpp，git clone 目标路径错误）与 `build-native.sh`（路径指向另一个项目 /d/quzp/）。
- 修正 `build.gradle` 过时注释：原"本地库分离到 msys2 预编译"与现状不符（Gradle externalNativeBuild 直编，jniLibs 是实时产物）。
- 清理后验证构建正常（CMake 走 .cxx 重新配置）。

## [2026-08-16] mmproj 精确配对 + 切换模型自动卸载
- 修复两个问题：
  1. mmproj 匹配错配：原实现扫描目录任意 `*mmproj*.gguf`——目录有多个投影（gemma-3 + qwen2.5-vl）时可能加载错误的投影文件。改为：先按预设（模型下载文件名 → mmprojUrl 文件名）精确配对；目录仅一个 mmproj 时兜底；多个则跳过不猜测。
  2. 切换模型残留：非 vision 模型加载时不释放旧 mmproj → `isMultimodalLoaded()` 误报 true，发图会用错投影。改为 `autoLoadMultimodalIfAvailable` 开头强制 `releaseMultimodal()` 清除旧投影。
- 卸载链确认：`nativeRelease` 先释放 s_mtmdCtx 再删 InferenceContext——`LlamaHelper.release()`（模型卸载/重载/切换时调用）会连同 mmproj 一起卸载；重载后按新模型重新配对加载。

## [2026-08-16] 多模态模型接通主流程（图片附件直接视觉理解）
- 现状核查：native 多模态链路（mmproj 加载 + mtmd 图片编码 + generateWithImage）此前完整但未接入主流程——AIService 加载 vision 模型不加载 mmproj（仅 MultiModelManager 有，但无 UI 入口）；UI 图片附件统一走 OCR 文本路径，generateWithImage 无调用者。
- 接通 1（AIService）：模型加载成功后自动扫描模型目录 `*mmproj*.gguf`（下载预设时已自动下载）并 `loadMultimodal`，失败仅日志不影响主流程。
- 接通 2（AIChatActivity）：图片附件发送时，若 `isMultimodalLoaded() && 模型已加载 && 单张图片` → 新 `handleMultimodalImage` 走 `generateWithImage` 流式视觉理解（用户消息 + AI 流式消息 + 完成/错误处理 + 历史保存）；否则回退原有 OCR 附件路径。
- 崩溃防护：native 有 s_mtmdMutex + 图片加载/编码失败返回错误码 + 超上下文清 KV；Java 侧 generateWithImage 共用推理写锁 + try-catch 兜底提示，不会崩。
- GPU 层数说明：mtmd_context_params 无独立 GPU 层数（仅 use_gpu=true 视觉编码全 GPU）；文本部分仍用 gpuLayers，视觉编码器由 mtmd 内部管理。

## [2026-08-16] 手动 GPU 层数模式下的智能建议
- 手动设置 GPU 层数时，性能监控同样给建议：按模型尺寸估算推荐区间（8B级 10~30 层且可用内存<2.5GB 时上限 20；3-7B 15~30；小模型 10~25），对比手动值——
  - 手动过高（超推荐上限/温度≥45°C）→ 提示降低（可一键执行）
  - 手动偏低且 GPU 空闲 → 提示提高或「恢复自动」
  - 手动合理 → 提示当前配置正常
- 新增动作 `restore_auto`：删除 gpu_layers_manual + 重载模型，恢复自动计算；一键优化不触碰手动设置（不静默删除）。
- RuntimeSnapshot 增加 manualGpuLayers 字段（-1=自动模式）。

## [2026-08-16] 配置竞态审查 + 手动 GPU 层数优先级修复
- 审查结论：活着的配置设置链唯一（loadModelLocked 内 modelInitLock 单线程串行，顺序确定）；`applyXiaomi14Optimizations`/`applyAdrenoOptimizations`/`GpuAdaptiveTuner.startTuning` 均为死代码（无调用者），不会造成竞态。
- 修复真问题：用户手动 GPU 层数会被自动计算覆盖（重载时 loadModelLocked 用新计算值覆盖用户设置）。
- 实现：独立持久化 key `gpu_layers_manual`（与 ModelStateCache 自动保存的 gpu_layers 隔离）——存在则加载时优先（钳制 0-30），不存在用自动计算；状态页对话框新增「恢复自动」（删除 key + 重载）；性能面板 ±10 也写该 key。

## [2026-08-16] memoryPoolSize 真正利用：作为 KV cache 内存预算
- 此前 memoryPoolSize 是死配置（native 只存值不参与分配；AIService 902 行算了也只打日志）。
- 实现（native-lib.cpp）：context 创建时按模型参数估算每 token KV 字节（2×n_layer×n_head_kv×head_dim×元素字节，Q8_0=1/F16=2），若 memoryPoolSize 预算超出则钳制 n_ctx，并日志输出预算/峰值 KV 内存。
- Java（AIService）：initModel 前真正 `setMemoryPoolSize`（此前只用于日志），预算 = min(可用内存×30%, 2048MB, ≥256MB)。
- 效果：如 8B 用 F16 KV 且可用内存低时，memoryPoolSize 预算会自动把 n_ctx 从 6144 降到预算允许值，防止 KV 撑爆内存。

## [2026-08-16] 内存优化：KV cache 量化（Q8_0，内存减半）
- 发现 `fKvCacheType` 是死配置：InferenceConfigManager 设了 KV 类型但 native 层从未应用（context 始终 F16 KV）。
- 实现：C++ InferenceContext 新增 `kvCacheType`（0=Q8_0 省一半内存 / 1=F16 默认）+ JNI `nativeSetKvCacheType`；context 创建时按类型设置 `type_k/type_v`。
- Java：`LlamaHelper.setKvCacheType`；`AIService.loadModelLocked` 在 initModel 前自动选择——模型 ≥4.5GB（8B 级）或可用内存 <2.5GB → Q8_0（8B@6144ctx：KV 0.9GB→0.45GB；4B@8192ctx：1.2GB→0.6GB），否则 F16。
- 备注：`memoryPoolSize` 同样为死配置（native 只存值不参与分配），无实际内存影响，暂不动。

## [2026-08-16] AI 服务初始化流程审查修复（3 处）
- 🔴 **竞态 bug（高）**：`release()` 走多线程 executorService、`initializeAsync()` 走 modelInitSerialExecutor，两个线程池并发抢 modelInitLock 顺序不定——此前 GPU 重载/性能面板用的 `release()+initializeAsync()` 组合可能"initialize 先完成、release 后执行"导致模型被卸载却提示"重载完成"。新增 `AIService.reloadModelAsync(callback)`：同一串行执行器内原子完成 release→initialize；AIServiceStatusActivity 与 PerformanceDashboardFragment 改用它。
- 🟡 **chat context 大小脱节（中）**：`tryCreateChatContextWithFallback` 从 16384 开始尝试，与模型加载上下文（如 8B→6144）脱节（C++ 侧 min 钳制使前两次尝试无效）。首值改为 0（native chatCreate 对 ctxSize≤0 直接取模型 n_ctx），失败再逐级降级。
- 🟢 **硬编码等待（低）**：initChatContext 销毁 chat context 后固定 `Thread.sleep(100)` 改为轮询 isModelInitialized（最多 2 秒）。
- 确认无问题项：chat context 复用主 llama_context（`initFromExistingContext` ownsContext=false，无第二份 KV cache）；applyOptimizations 不设 GPU 层数（由 loadModelLocked 统一计算）；模型按需加载设计（构造函数不自动恢复）符合预期。

## [2026-08-16] 性能监控系统：实时检测 + 规则自动分析 + 可执行建议（死代码真实化）
- 原 PerformanceDashboardFragment 为死代码（Random 模拟数据 + 空方法 + 无宿主），现改为真实系统：
- 新增 `PerformanceRuleEngine`：输入实时运行快照（TPS/GPU 层数/GPU 状态/温度/系统内存/模型大小/上下文占用），规则集自动判定异常（内存高压/推理过慢/GPU 未启用或异常/设备过热/模型偏大/上下文将满/GPU 层数可提升/模型未加载），按优先级输出建议。
- Fragment 真实化：3 秒采样 LlamaHelper（getInferenceSpeed/getGPULayers/isGPUWorking/isModelInitialized）+ GpuCapabilityDetector（getTemperature/getMemoryUsageInfo）+ AIService（getContextUsagePercent/getCurrentModelName），性能评分由真实指标计算，TPS 图表改为本地 Canvas 绘制（去掉外部 CDN 依赖）。
- 建议一键执行：提高/降低 GPU 层数（±10 → 持久化 + 重载模型）、清理对话上下文、切换模型、重载模型；「一键优化」自动应用所有高/中优先级可执行项。
- 新增 `PerformanceActivity`（容器，manifest 注册），AI 服务状态页新增「性能监控（实时检测 & 优化建议）」入口按钮。

## [2026-08-16] GPU 层数设置 + 自动重载模型（让手动改 GPU 层数真正生效）
- 问题：`nativeSetGPULayers` 运行时仅赋值不生效（层数在 initModel 时固化），用户手动改 GPU 层数后无感知。
- 新增：AI 服务状态页「GPU 层数」输入行（0-30，0=纯CPU）+「应用并重载」按钮——保存到 `model_state_cache/gpu_layers`（与 ModelStateCache 同 key，重启恢复沿用）→ `setGPULayers` → 确认对话框 → `release()` + `initializeAsync()` 重载模型；模型未加载时保存待下次加载生效；状态页刷新时同步输入框显示。
- 8B 等大模型用户可通过此入口手动调整 GPU 层数（如降层减少显存压力或升层加速），改完即重载，无需重启应用。

## [2026-08-16] 8B 大模型加载卡顿优化（模型尺寸感知上下文）
- 问题：小米 17（12GB）加载 Qwen3-8B（Q4_K_M≈4700MB）后明显卡顿。根因：设备档位判定（FLAGSHIP→8192 ctx）不看模型尺寸——8B 常驻内存 = 权重 4.7GB + KV(8192)≈1.2GB ≈ 5.9GB，可用内存波动时触发 GC/杀后台。
- 修复：`AIService.calculateOptimalContextSize` 与 `InferenceConfigManager` 档位均加模型尺寸感知——模型权重 ≥4500MB 上下文上限 6144（KV≈0.9GB，常驻 5.6GB）、≥7000MB 上限 4096、≥3000MB 上限 8192。
- GPU 层数链路确认：加载时 `setGPULayers` 在 `initModel` 前设置生效（Vulkan 混合推理 30 层 + CPU 余层）；`nativeSetGPULayers` 运行时仅赋值不生效是已知设计（需重载才变）。

## [2026-08-16] 本地 Agent 模型更换：2507 → 原版 Qwen3-4B（思考链+FC 双全）
- **决策依据（联网核实）**：Qwen 官方 2507 分家为 Instruct-2507（模板无思考链）与 Thinking-2507（`<think>` 标签缺失 bug）；且 llama.cpp [issue #20809](https://github.com/ggml-org/llama.cpp/issues/20809) 确认 Instruct-2507 工具调用被 false thinking detection 破坏——2507 系列不满足 Agent 需求。
- 预设替换：`qwen3-4b-2507` → `qwen3-4b`（Qwen/Qwen3-4B-GGUF 官方原版 Q4_K_M，2.33GB，40K 上下文）。
- 模板核验（hf-mirror API 获取 GGUF 内嵌模板全文）：原版含 `<think>` 思考渲染 + `enable_thinking` 检测分支 + `<tools>`/`<tool_call>`/`<tool_response>` 完整原生 FC——`common_chat_templates_support_enable_thinking` 将通过，**深度思考模式与工具调用同时生效**，C++ 解析/回喂链路无需改动。
- 设备 Redmi Note 13 5G（25113PN0EC）4B 尺寸友好（6/8/12GB 内存均可）；8B 作为高性能备选仍在预设。

## [2026-08-16] 本地 Agent 工具参数可靠性（双保险）
- 事实确认：Qwen3 官方将 2507 拆为 Instruct-2507（无思考链，工具调用特化）与 Thinking-2507（带思考链但有模板 bug）；本项目用 Instruct-2507，enableThinking 被 C++ 模板检测正确关闭，非配置问题。
- 第一层（减少犯错）：本地 Agent 系统提示加入参数约束（city 只写纯城市名、禁止天气/今天等修饰词；expression 只写表达式）。
- 第二层（纠正犯错）：新增 `sanitizeNativeToolCall` + `sanitizeCityName` 规则净化——ai_weather.city 剥离"今天/天气/怎么样"等噪声词、去"市"后缀，净化结果非空才生效（否则保留原样交给工具失败回喂纠错），执行前替换参数，日志可见"参数净化 city xxx → yyy"。
- 参数捕获缺陷修复：C++ 解析的 arguments 非合法 JSON 时不再静默清空，先 `tryFixArguments` 剥外层引号（处理字符串化 JSON），仍失败才置 {}。

## [2026-08-16] 修复本地 Agent 第三层静默降级（AgentRouter）
- **重大修复**：`AgentRouter.execute` 本地模型分支此前「静默降级」直接 return 不执行——UI 两层降级虽已移除，但引擎路由层这第三层拦截导致本地 Agent 全部调优（原生 FC、思考链、温度 0.7）均为死代码，用户点发送无任何反应。
- 现改为本地模型 → `UnifiedAgentEngine.execute` → 原生 FC Agent 循环（generateWithTools），完整链路打通：
  `AGENT模式 → processChatMessageWithAgent → AgentChatHandler.startAgentLoop → AgentRouter(本地分支) → executeNativeAgentLoop（原生FC + 思考链流式 + 温度0.7）`。
- 本地意图识别确认：原生 FC 路径完全由模型原生完成意图/任务/工具选择，无本地硬编码规则；规则识别（SmartIntentRecognizer）仅用于在线 ReAct/CoT/Plan 路径。

## [2026-08-16] 本地 Agent 针对 Qwen3-4B-2507 调优
- 本地 Agent 原生函数调用路径针对 Qwen3-4B-2507 调优：深度思考模式（enableThinking）透传至原生生成（此前本地路径忽略思考链）；温度固定 0.7 平衡工具调用稳定性与多样性。
- Qwen3 思考过程流式展示：原生 FC 循环中模型输出的 thinking 内容分块回调 UI（🧠 思考中状态栏 + 思考消息块，节流渲染），结束后折叠；此前思考内容只写日志不展示。
- C++ 层确认：generateWithTools 通过 `common_chat_templates_support_enable_thinking` 检测模板（Qwen3 支持），思考/正文分段解析 + 超限强制结束已有兜底，与 Java 侧流式回调衔接。
- 构建通过（BUILD SUCCESSFUL），待设备重连后安装验证：工具调用 + 思考链 + 长对话上下文稳定性。

## [2026-08-16] 题库详情字段 + 天气横幅/详情整改
### 题库管理 & 答题
- 题目详情页：选项改为动态渲染 A~L（此前只显示 A~D，E~L 丢失）；多选答案（如"AB"）正确高亮；补全题型/知识点/标签/提示/详细解析字段（空值隐藏）；编辑/删除按钮绑定真实功能；收藏读写数据库（此前假收藏）；分享文本含全部选项。
- 答题页：解析显示与列表一致（详细解析 analysis 优先，回退 explanation）。

### 天气横幅 & 详情页
- 修复横幅滑动误触跳转（移除 dispatchTouchEvent 全 UP 触发）；恢复点击跳转（所有子 View 统一绑定点击，任意位置可点）。
- 横幅信息行由 4 个零散 chip（体感/湿度/风力/气压/能见度）改为「天气详情总介绍」：今日白天/夜间+温差段 + 当前温度/天气/体感/湿度/风/能见度/气压段（今日段异步拉取预报，3 小时缓存）。
- 实时天气缓存 15 分钟 → 5 分钟（与横幅刷新周期匹配，刷新更及时）；错误响应不缓存。
- 横幅↔详情页联动补齐：详情页切换城市后同步横幅（写共享缓存+广播）；GPS 定位同步（原有）。
- 地址显示改为「城市名 + 副行具体地址」：顶部显示城市名（如"北京"），具体地址（区+路）作副行/总介绍末尾补充；定位与搜索粒度统一。
- 天气字段增加：横幅气压字段显示、完整总介绍。

## [2026-08-14] 模板管理并入导出页（废弃独立三级流水线）
- 「模板管理」按钮改为弹出全部 8 个场景模板选择对话框（名称+描述），选中即应用字段组合/开关并高亮对应 chip。
- 模板、自定义字段、导出开关统一在同一页面双向联动（选模板→字段/开关联动；自定义字段→取消模板高亮、开关自动开启）。
- 废弃独立模板三级流水线入口（TemplateSelectionActivity → FieldConfigActivity → ExportProgressActivity），类保留但不再导航，Manifest exported 改回 false。
- 解决：字段配置页显示异常、模板流水线与导出页自定义字段不联动的问题。

## [2026-08-14] 导入/导出系统大整改（修复全部遗留问题 + 新增功能）

### 导入流程（ImportActivity / WebViewFilePreview / ExcelUtil）
- 修复「AI 导入」页完全不可用：选择文件后 `currentFile` 从未赋值导致无法开始导入；多选模式被按钮前置检查拦截；取消按钮对 v2 管线无效（实际运行的是 v2，此前只取消 Orchestrator）。
- 移除 AI 导入页双通道死代码（「智能导入 vs AI 导入」二选一对话框、Orchestrator 四阶段路径、Excel 工作表选择器），统一为 v2 智能管线单通道；隐藏无回调的死 UI 区域。
- WebView 映射下拉可选项与导入实际支持的字段严格对齐：补选项 G~L、空 1~12 答案，移除无效的「分值/知识点」；拆分预览虚拟列支持 G~L 映射；自动映射值始终可见可选。
- Excel 单元格读值修复：公式单元格按缓存结果取值（此前恒为空）；数字用 BigDecimal 十进制化避免科学计数法。
- 导入页两个死按钮绑定功能：「开始导入」重新打开文件选择器；「AI 解析」对当前文件走 v2 智能管线。
- 新增：导入前检测未完成断点，可选「继续续导 / 删除断点重来」；source 目录批量导入前弹出文件清单确认。

### 导入自动化（v2 离线管线）
- 修复批量导入在 UI 主线程同步执行（ANR）：改后台线程 + 结束统一发 all-done 信号（结果摘要可正常弹出）+ 重置取消/批量标志。
- 断点续导改为记录「已写入分片行号」，消除检查点粒度重叠导致的 CSV 重复写入；解析失败清断点避免重复分片；分片部分丢失显式报错而非静默丢数据。
- 库内去重查询失败不再静默放行（整批拆半重试→单条失败），杜绝重复入库；Java/Python 空白归一化统一（含全角空格）堵住变体逃逸。
- AI 缺失字段填充改为循环分批全量覆盖（此前仅前 500 条被填充，其余落死值「未分类/难度1/空解析」）；本地填充 token 上限放宽防止整批截断；续导后旧分片缺失字段同样被扫描填充。
- Python 预处理流式化：xlsx 两遍读逐 sheet 产出（跨 sheet 表头不一致自动跳过）、CSV 流式解析、DB 大表游标逐行、SQL 加 5 万行上限——万行+ 文件不再 OOM。
- 批量导入扩展名清单补齐 .db3；apply_fills 原子写（tmp+rename）防止崩溃损坏分片。

### 导出流程
- 修复 Excel 导出空指针：表头行 `getRow(0)` → `createRow(0)`（空工作表 getRow 返回 null）。
- Excel/HTML 从 Python 通道切回 Java 实现：openpyxl 实际未打包进 APK（导出必然失败）+ Python 序列化缺失 optionG~L 等字段，切 Java 后默认 Excel 导出稳定完整。
- 模板流水线与题库页旧导出路径补复制到公共「下载/OilQuiz」目录（此前只存私有缓存，用户找不到文件）。
- 长图片导出高度计算与绘制统一口径（开启答案/解析后不再截断重叠）。
- 字段体系统一：`ExportUtils.EXPORTABLE_FIELDS` 单一来源（36 个），移除无意义的配图/音频/母题ID/时间戳/状态等字段；模板字段全部对齐；自定义字段对话框加滚动并显示完整。
- 「包含答案/解析/难度」开关对 CSV/Excel/JSON 生效（此前只按字段列表判断，取消勾选无效）。
- 自定义字段与模板/开关联动：勾选答案类字段自动开启对应开关、自定义后模板 chip 取消高亮。
- 死代码清理：删除 EnhancedExportManager 整类、ExportManager 状态机方法/exportToLongImage/exportTasks、getExporter 死方法。
- 模板选择页：格式下拉只保留 9 种可用格式（删除增强HTML/Python/Java）；中文标签；修复 selectedFormat 空指针崩溃（Spinner 回调不触发时兜底默认值）。

### 稳定性 / 线程 / 生命周期
- 所有异步回调（导出完成/失败、v2 导入回调、WebView 渲染完成）增加页面销毁保护，避免 BadTokenException 崩溃。
- ExportManager.startExport 加互斥，防止连续触发导出并发写同一文件。
- ImportActivity/AIImportActivity 页面销毁时取消后台导入任务。

## [2026-08-13] 导出功能全面优化

### 新增功能

#### 1. HTML学习查看版导出
- 新增HTML学习查看版导出格式，专门用于查看、学习和背诵
- 现代卡片式布局，每道题目独立卡片展示
- 答案折叠/展开功能，适合先自测再查看答案
- 解析折叠/展开功能，适合深入学习
- 难度星级显示（⭐⭐⭐）
- 分类、知识点彩色标签
- 一键展开/折叠全部功能
- 响应式设计，支持移动端显示

#### 2. 模板字段重新设计
- 根据各场景模板功能重新设计字段配置
- **标准完整版** (Excel)：12个核心字段，保留完整题目数据
- **数据分析版** (CSV)：10个统计字段，便于数据分析
- **答案解析版** (PDF)：5个核心字段，突出答案和解析
- **记忆卡片版** (长图片)：仅2个字段（题干+答案），极简设计
- **刷题训练版** (打印材料)：6个字段，不含答案
- **讲义备课版** (HTML)：12个字段，按分类分组
- **错题回顾版**：11个字段，完整题目+解析
- **模拟考试版**：11个字段，不含解析

### 修复问题

#### 1. Excel/CSV解析容错机制
- FileReaderTool: 当行中列数少于表头时，自动填充空字符串
- 避免因字段缺失导致的空指针异常

#### 2. Excel生成容错机制
- OfficeGeneratorUtil: 当rowData长度少于headers时，自动补齐
- 避免因字段缺失导致的生成失败

#### 3. JSON生成容错
- FileGeneratorTool: 添加null值检查和处理
- 避免因null值导致的JSON序列化失败

#### 4. export.py修复
- 删除`export_to_excel`函数中引用不存在变量`questions_raw`的错误代码
- 解决导出时NameError导致的"导出失败但无具体错误"问题

### 技术改进

- 新增PythonExporter类，统一Python导出入口
- 新增PythonExportBridge.exportToHtml()方法
- 重构ExportManager，HTML格式改用Python导出
- 模板配置同步更新（Java端 + Assets端）
