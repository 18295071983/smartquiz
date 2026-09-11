# ChatKit 组件清单（33 个 Java + 1 布局）

## 逻辑层（22，零 UI）

| 组件 | 包 | 职责 | 关键 API |
|---|---|---|---|
| ChatContextBuilder | ai.chat.context | token 预算/历史收集/工具痕迹/提示词变更标记 | build(...)/Config 接口 |
| HistoryEntriesBuilder | ai.chat.context | 普通/Agent 历史条目构建+同角色合并 | buildHistoryEntries(...) |
| MessageRouteDecider | ai.chat.route | 9 态路由纯决策（附件/在线/Agent/本地） | decide(DecisionInput)/decideNoAttachment(...) |
| FileUriUtils | ai.chat.file | URI→路径/复制缓存/文件名大小/体积格式化 | resolvePath/copyToCache/formatFileSize |
| AttachmentPromptBuilder | ai.chat.attachment | 附件安全分析/摘要/Agent 增强/失败判定 | buildPrompt(...) |
| AttachmentFactory | ai.chat.attachment | Uri→附件（MIME 推断/image→thumbnailPath） | fromUri(...) |
| VisionInferenceHelper | ai.chat.vision | 视觉历史 12 条/附件解析/批量落盘 Future | buildLocalVisionHistory/... |
| AttachmentVisionPipeline | ai.chat.vision | 附件过滤/图片定位/视觉历史/可用性决策/base64 | filterAttachments/canFollowUpLocalVision/isOnlineVisionModel |
| ChatSessionController | ai.chat.session | 会话清空/新建/切换/删除/刷新（Store+EngineControl+Host） | clear/newSession/switchSession/... |
| RegenerationPlanner | ai.chat.history | AI 消息→前置 user→回滚起点纯计算 | planRollback(history, aiIndex) |
| GenerationStreamController | ai.chat.stream | 生成状态机/消息定位/标签缓冲/token 分流/安全更新 | handleStreamTokenLegacy/safeUpdateMessage/beginGeneration |
| ChatMessageSender | ai.chat.send | 发送守卫/构建/分发（决策与执行分离） | dispatch(...)/buildUserMessage(...) |
| TokenStatsTextBuilder | ai.chat.stat | 统计文本拼接 | buildRequestText(...)/... |
| ChatTextUtils | ai.chat.util | phaseToCn/formatCtxWindow/containsKeyword/countThinkingLines | 全静态 |
| LinkOpener | ai.chat.util | 链接打开（内部浏览器/外部） | open(...) |
| ThinkingSegmentRoller | ai.chat.ui | 思考段增长跳最新/停顿轮播（持状态） | next(think) |
| ModeInstructionInjector | ai.chat | 模式切换指令后台注入 | inject(...) |
| ToolParamResolver | ai.agent | 补参判定/时间日期填充/路径参数/选择器推断 | fillTimeParams/hasParamValue/... |
| ToolExecutionOrchestrator | ai.agent | 工具执行闭环：预检/执行/解读/智能恢复/补参/重试 | preCheckThenExecute/executeToolWithRecovery |
| ChatSpeechController | ai.speech | toSpeakableText/朗读编排/二次点击停止/自动朗读 | speak(...)/stop()/Host 接口 |
| ChatVoiceRecorder | ai.speech | MediaRecorder 生命周期+录音状态回调 | start/stop/cancel/RecorderListener |
| ChatSpeechInputController | ai.speech | 语音输入编排：权限/ASR 判定/预热/识别/自动 TTS 状态 | startVoiceRecognition/toggleAutoTts |

## 渲染层（8，View 组件）

| 组件 | 职责 | 关键 API |
|---|---|---|
| GenerationStatusBar | 生成状态条（GenPhase/KV/思考刮刀动画/800ms 轮询） | refresh/startPolling/onThinkingSegment |
| TokenStatsBar | 统计条（流式实时+完成态，在线/本地自动切换） | updateFromStreaming/updateFromStats |
| ChatStateOverlay | 思考指示器+空态切换（薄） | showLoading/hideLoading/setEmpty |
| ModeChipGroup | 模式芯片组（互斥选中+主题配色注入） | setChecked/setChipStyle(checkedTint,uncheckedTint,radiusDp) |
| ChatBottomSheet | 通用弹窗壳（标题/内容/按钮组） | title/message/content/action/show |
| AttachmentChipsView | 附件预览条（缩略图/名称/大小/删除） | setItems/setThumbnailProvider |
| RecordingIndicatorView | 录音指示（脉冲+计时+停止/取消） | start/stop/stopAndHide |
| GuideStepFlowView | 步骤引导流（四态+自动滚动） | setSteps/setStepState |

## 装配层（1）

| 组件 | 职责 | 关键 API |
|---|---|---|
| ChatShellView | 整页壳：状态区+消息流+输入栏一键装配 | bindSources/setAdapter/attachInput/onResume/onStreamingToken/onTokenStats/onDestroy |

## 接入件（2 + 1 布局）

| 组件 | 职责 | 关键 API |
|---|---|---|
| ChatInputBar | 输入栏组件（已接入对话页） | attachManager(activity, callback, attachmentList) |
| ChatMessagesView | 消息容器（空态+滚动，已接入对话页） | setAdapter/notifyDataSetChanged/scrollToBottom |
| view_chat_input_bar.xml | ChatInputBar 布局（src/res/layout） | — |

## 既有复用资产（不在本包，宿主自带）

`ChatAdapter`（消息流渲染）、`AgentExecutionView`（Agent 执行渲染）、
`AgentSessionView`（Agent 会话全局容器）、`ImagePreviewUtil`（全屏预览）、
30+ CardView 卡片族（Markdown/Html/Chart/File/Quiz...）。
