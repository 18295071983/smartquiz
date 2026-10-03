# AI 对话界面设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述 AI 对话界面（AIChatActivity）的**模块化设计**——Activity 是薄壳，逻辑全部委托给独立模块类。

## 一、设计思想

`AIChatActivity` 采用**薄壳 + 模块化**结构：Activity 只做装配（`initModules()`）和生命周期转发，业务逻辑全部放进独立、单一职责的 Manager 模块。每个模块通过 **Callback 接口** 与 Activity 解耦。

```
AIChatActivity（薄壳）
├── initModules()  → 装配各 Manager 并注入 Callback
├── initView()     → 绑定布局控件
└── initListener() → 监听器
```

## 二、模块清单（真实装配）

| # | 模块 | 类 | 包 | 职责 |
|---|------|-----|-----|------|
| 1 | 服务状态栏 | `ServiceStatusManager` | `ai.chat.status` | 模型状态/进度/详情对话框 |
| 2 | 对话框 | `ChatDialogHelper` | `ai.chat.ui` | 模型/模式/清空/新对话 |
| 3 | 历史抽屉 | `ChatHistoryController` | `ai.chat.history` | 会话加载/切换/删除 |
| 4 | 原生恢复 | `NativeRecoveryHandler` | `ai.chat.recovery` | 原生崩溃断点恢复 |
| 5 | 输入 | `ChatInputManager` | `ai.chat.input` | 输入框/发送/附件/语音按钮/附件列表 |
| 6 | 附件处理 | `AttachmentProcessor` | `ai.chat.input` | 附件文本抽取/AI摘要 |
| 7 | 生成生命周期 | `GenerationLifecycleManager` | `ai.chat.lifecycle` | 生成开始/流式/完成/错误 |
| 8 | 流式管道 | `StreamingTokenPipeline` | `ai.chat.streaming` | token 分流（思考/正文/工具） |
| 9 | 输出路由 | `OutputRouter` | `ai.chat.parser` | 输出类型解析 |
| 10 | 消息渲染 | `ChatAdapter` | `ai.chat` | RecyclerView 渲染 |

## 三、装配点（`initModules()` 真实代码）

```java
private void initModules() {
    serviceStatusManager = new ServiceStatusManager(this, uiHandler, new ServiceStatusManager.Callback() {
        public void onAddSystemMessage(String m, ChatMessage.SystemMessageType t){ addSystemMessage(m, t); }
        public void onAddErrorMessage(String t, String d, boolean r){ addErrorMessage(t, d, r); }
        public void onShowToast(String m){ showToast(m); }
        public void onShouldUseOnlineModel(){}
        public void onHideLoading(){ hideLoading(); }
    });
    serviceStatusManager.bindViews(serviceStatusBar, serviceStatusIcon, serviceStatusText,
                                   serviceStatusProgress, serviceStatusElapsed, thinkingIndicator);

    dialogHelper = new ChatDialogHelper(this, new ChatDialogHelper.Callback() { ... });

    historyController = new ChatHistoryController(this, new ChatHistoryController.Callback() {
        public void onClearChat(){ clearChat(); }
        public void onSwitchToSession(ConversationSession s){ switchToSession(s); }
        public void onStartNewConversation(){ startNewConversation(); }
    });
    historyController.init(drawerLayout, historyList);

    recoveryHandler = new NativeRecoveryHandler(this, uiHandler, new NativeRecoveryHandler.Callback() { ... });

    inputManager = new ChatInputManager(this, new ChatInputManager.Callback() {
        public void onSendMessage(String t){ sendMessage(); }
        public void onAttachFile(){ handleAttachFile(); }
        public void onShowToast(String m){ showToast(m); }
    });
    inputManager.init(inputMessage, btnSend, btnAttach, attachmentList);

    attachmentProcessor = new AttachmentProcessor(this);
    attachmentProcessor.setCallback(new AttachmentProcessor.Callback() { ... });

    lifecycleManager = new GenerationLifecycleManager(this, uiHandler, new GenerationLifecycleManager.Callback() {
        public void onUpdateMessageContent(int i, String c){ chatHistory.get(i).content = c; chatAdapter.notifyItemChanged(i, ChatAdapter.PAYLOAD_CONTENT_UPDATE); }
        public void onAddAIMessage(ChatMessage m){ chatHistory.add(m); chatAdapter.notifyItemInserted(chatHistory.size()-1); scrollToBottom(true); }
        public void onScrollToBottom(){ scrollToBottom(); }
    });

    streamingPipeline = new StreamingTokenPipeline(new StreamingTokenPipeline.TokenListener() {
        public void onContentToken(String t){ lifecycleManager.handleToken(t); }
        public void onThinkingToken(String t){ lifecycleManager.handleThinkingToken(t); }
    });
}
```

## 四、消息分类（ChatAdapter，真实 ViewType）

| ViewType | 值 | 用途 |
|----------|-----|------|
| VIEW_TYPE_USER | 0 | 用户消息 |
| VIEW_TYPE_AI | 1 | AI 回答 |
| VIEW_TYPE_SYSTEM | 2 | 系统消息 |
| VIEW_TYPE_THINKING | 3 | 思考内容 |
| VIEW_TYPE_TASK | 4 | 任务节点 |
| VIEW_TYPE_TOOL_CALL | 5 | 工具调用 |
| VIEW_TYPE_AGENT_STEP | 6 | Agent 过程步骤 |
| VIEW_TYPE_AGENT_SUMMARY | 7 | Agent 汇总 |
| VIEW_TYPE_ERROR | 8 | 错误消息 |
| VIEW_TYPE_TOOL_RESULT | 9 | 工具结果 |
| VIEW_TYPE_AGENT_REFLECTION | 10 | Agent 反思 |
| VIEW_TYPE_SUMMARY | 11 | 对话摘要 |
| VIEW_TYPE_INFERENCE_PROGRESS | 12 | 推理进度 |

## 五、布局控件（`res/layout/activity_ai_chat.xml`）

### 5.1 主内容区

| 区域 | 控件 id |
|------|---------|
| 主容器 | `drawer_layout` / `main_content` / `tool_drawer`（右侧工具抽屉 300dp） |
| 状态栏 | `service_status_bar` / `service_status_icon` / `service_status_text` / `tv_api_balance` / `service_status_progress` / `service_status_elapsed` |
| 消息区 | `message_list` / `empty_state_view` / `empty_state_chips` / `thinking_indicator` |
| 附件区 | `attachment_list` / `voice_recording_bar` / `tv_voice_recording_dot` / `tv_voice_recording_time` |
| 输入区 | `input_message` / `btn_send` / `btn_attach` / `btn_voice` / `hold_to_talk` / `btn_stop_generation` |
| 快捷 chip | `chip_normal_chat` / `chip_deep_think` / `chip_clear_chat` |
| 历史抽屉 | `history_drawer`（左侧 320dp）/ `history_list` / `btn_close_history` / `btn_clear_all_history` |

### 5.2 工具抽屉 `view_tool_drawer.xml`

`btn_model_select` / `btn_mode_select` / `quick_actions_chip_group` / `btn_agent_manager` / `btn_auto_tts` / `btn_clear_chat` / `btn_history` / `btn_log_viewer`

## 六、状态机（AIChatViewModel）

由 `ai.chat.viewmodel.AIChatViewModel` 管理。

```java
public enum AIState {
    IDLE,      // 空闲
    LOADING,   // 加载中
    READY,     // 就绪
    INFERRING, // 推理中
    ERROR,     // 错误
    UNLOADED   // 已卸载
}
```

迁移：`IDLE → LOADING → READY → INFERRING →（ERROR/UNLOADED）→`。

Watchdog：推理期间 20s 无心跳（`pushHeartbeat`）判定超时。

## 七、数据源

`AIChatViewModel` 内部使用 `MutableChatList`（`ai.chat.live`），通过 LiveData 暴露：
- `getChatMessages()` → `LiveData<List<ChatMessage>>`
- `isGenerating()` → `LiveData<Boolean>`
- `getAIState()` / `getError()` / `getInferenceProgress()`

Activity 观察这些 LiveData 驱动 ChatAdapter 渲染。

## 2026-09/10 更新

- **深蓝渐变背景全量可读性适配（9/29）**：root 层统计/时间戳/模型信息、统计胶囊、空状态、输入框、AI 消息操作栏全部改浅色或半透明白描边胶囊，确保深蓝渐变背景上文字可读。
- **edge-to-edge 适配**：root 背景/壁纸延伸进系统栏，系统栏透明 + 根容器按 insets padding（状态栏/手势条/导航栏不重叠）；深浅图标自动切换；底部贴边弹窗（语音/OCR 模型选择器）经 EdgeToEdgeHelper.applyDialog 适配。
- **历史加载修复**：新对话/清空后异步历史加载回灌顶掉新消息 → historyLoadStale 作废未完成加载（9/29）。

## 相关文档

- [项目架构总览](01-project-overview.md)
- [AI Agent 架构设计](02-ai-agent-architecture.md)
- [AI 服务与推理设计](04-ai-service-inference.md)
