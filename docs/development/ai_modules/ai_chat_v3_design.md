# AI助手界面 v3.0 技术设计文档

> 版本: 3.0 | 更新日期: 2026-07-18 | 基于PRD: ai_chat_v3_prd.md

---

## 1. 架构概览

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        AI Chat Architecture                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│  │   AIChat   │    │ ChatModeManager │    │   DeepThinking │     │
│  │  Activity    │    │              │    │    Engine     │     │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘     │
│         │                    │                    │                 │
│         ┌─────▼────────────────────────────────────────▼──────────────────┐
│         │              ChatOrchestrator (新增)                      │
│         │                                                      │
│         │  协调器：统一管理消息流程、模式切换、引擎调用          │
│         └──────────────┬───────────────────────────────────────────┘
│                        │
│         ┌─────────────▼─────────────┐    ┌───────────────────────┐
│         │ StreamingBroadcaster      │    │  MessageQueue         │
│         │ (流式生成广播器)      │◄───┤ (消息生成队列)       │
│         └──────────┬──────────┘    └──────────┬───────────┘
│                    │                            │
│         ┌──────────▼──────────┐    ┌──────────▼───────────┐
│         │   ChatAdapter   │    │   AIService       │
│         │ (UI层)        │    │ (推理服务)         │
│         └──────────────────┘    └───────────────────────┘
│                                                                 │
│  ┌──────────────────────────────────────────────────────┐          │
│  │         CreativeWritingEngine                   │          │
│  │         (创意写作引擎)                          │          │
│  └──────────────────────────────────────────────────────┘          │
│                                                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 核心组件职责

| 组件 | 职责 | 现有/新增 |
|------|------|----------|
| **ChatOrchestrator** | 协调器：统一管理消息流程、模式切换、引擎调用 | 新增 |
| **StreamingBroadcaster** | 流式生成广播器：多订阅者、按ID路由 | 新增 |
| **MessageQueue** | 消息生成队列：优先级、并发控制 | 增强（基于InferenceQueue） |
| **DeepThinkingEngine** | 深度思考引擎 | 现有，需集成 |
| **CreativeWritingEngine** | 创意写作引擎 | 现有，需集成 |
| **ChatModeManager** | 聊天模式管理器 | 现有，需完善 |
| **ChatAdapter** | 聊天适配器 | 现有，需修复和增强 |
| **AIService** | AI推理服务 | 现有，核心推理 |

---

## 2. 详细设计

### 2.1 ChatOrchestrator 设计

#### 2.1.1 类设计

```java
public class ChatOrchestrator {
    
    private final ChatModeManager modeManager;
    private final AIService aiService;
    private final DeepThinkingEngine deepThinkingEngine;
    private final CreativeWritingEngine creativeWritingEngine;
    private final StreamingBroadcaster broadcaster;
    private final MessageQueue messageQueue;
    
    // 模式处理器映射
    private final Map<ChatMode, ModeHandler> modeHandlers;
    
    public interface ModeHandler {
        ChatMessage handleMessage(ChatMessage userMessage, String messageId);
    }
    
    public void sendMessage(String content, List<ChatMessage.Attachment> attachments);
    public void switchMode(ChatMode newMode);
    public void cancelGeneration(String messageId);
    public void cancelAll();
}
```

#### 2.1.2 消息处理流程

```
用户发送消息
    ↓
ChatOrchestrator.sendMessage()
    ↓
┌─────────────────────────────────────────┐
│ 1. 生成唯一messageId              │
│ 2. 创建初始AI消息占位            │
│ 3. 广播 MESSAGE_CREATED 事件        │
│ 4. 根据模式选择处理器          │
└─────────────────────────────────────────┘
    ↓
模式判断
    ├─ 普通模式 → NormalModeHandler
    ├─ 深度思考模式 → DeepThinkingModeHandler
    ├─ Agent模式 → AgentModeHandler
    └─ 创意写作模式 → CreativeWritingModeHandler
    ↓
消息入队 (MessageQueue)
    ↓
异步处理
    ↓
流式更新 (StreamingBroadcaster)
    ↓
UI更新 (ChatAdapter)
```

#### 2.1.3 模式切换处理

```java
public void switchMode(ChatMode newMode) {
    if (messageQueue.isGenerating()) {
        // 生成中，延迟切换
        pendingMode = newMode;
        broadcaster.broadcast(new StreamingEvent(
            messageId, STATUS_CHANGED, "模式将在当前生成完成后切换"));
    } else {
        // 立即切换
        modeManager.setManualMode(newMode);
        broadcaster.broadcast(new StreamingEvent(
            null, STATUS_CHANGED, "已切换到" + newMode.displayName + "模式"));
    }
}
```

### 2.2 StreamingBroadcaster 设计

#### 2.2.1 类设计

```java
public class StreamingBroadcaster {
    
    // 订阅者接口
    public interface StreamingSubscriber {
        void onEvent(StreamingEvent event);
    }
    
    // 订阅者列表（按消息ID订阅）
    private final Map<String, List<StreamingSubscriber>> subscribers = new ConcurrentHashMap<>();
    
    // 全局订阅者（所有消息）
    private final List<StreamingSubscriber> globalSubscribers = new CopyOnWriteArrayList<>();
    
    // 订阅特定消息
    public void subscribe(String messageId, StreamingSubscriber subscriber);
    
    // 订阅所有消息
    public void subscribeGlobal(StreamingSubscriber subscriber);
    
    // 取消订阅
    public void unsubscribe(String messageId, StreamingSubscriber subscriber);
    
    // 广播事件
    public void broadcast(StreamingEvent event);
    
    // 广播到特定消息
    public void broadcastTo(String messageId, StreamingEvent event);
}
```

#### 2.2.2 事件类型

```java
public class StreamingEvent {
    public enum Type {
        MESSAGE_CREATED,      // 消息创建
        TOKEN_APPENDED,     // Token追加
        STATUS_CHANGED,   // 状态变更
        THINKING_STEP,     // 思考步骤更新
        ATTACHMENT_UPDATE, // 附件更新
        MESSAGE_COMPLETED, // 消息完成
        MESSAGE_FAILED,  // 消息失败
        MESSAGE_CANCELLED // 消息取消
    }
    
    public final String messageId;
    public final Type type;
    public final Object data;
    public final long timestamp;
}
```

#### 2.2.3 订阅示例

```java
// ChatAdapter 订阅
broadcaster.subscribeGlobal(new StreamingSubscriber() {
    @Override
    public void onEvent(StreamingEvent event) {
        switch (event.type) {
            case TOKEN_APPENDED:
                // 更新消息内容
                break;
            case STATUS_CHANGED:
                // 更新状态显示
                break;
            // ... 其他事件
        }
    }
});
```

### 2.3 MessageQueue 设计

#### 2.3.1 基于现有InferenceQueue增强

```java
public class MessageQueue {
    
    private final InferenceQueue inferenceQueue;
    private final StreamingBroadcaster broadcaster;
    
    // 消息状态跟踪
    private final Map<String, MessageTask> activeTasks = new ConcurrentHashMap<>();
    
    public static class MessageTask {
        String messageId;
        String prompt;
        ChatMode mode;
        TaskState state;
        List<ChatMessage.Attachment> attachments;
        long startTime;
        long endTime;
    }
    
    // 提交消息
    public String submitMessage(String prompt, ChatMode mode, 
                          List<ChatMessage.Attachment> attachments,
                          StreamingSubscriber subscriber);
    
    // 取消消息
    public boolean cancelMessage(String messageId);
    
    // 取消所有
    public void cancelAll();
    
    // 获取状态
    public MessageTask getTaskStatus(String messageId);
}
```

#### 2.3.2 队列配置

| 优先级：
- HIGH: 用户取消操作
- NORMAL: 普通消息生成
- LOW: 后台任务

并发控制：
- MAX_CONCURRENT_GENERATIONS = 2 （避免资源限制

### 2.4 DeepThinkingEngine 集成

#### 2.4.1 集成点

```java
public class DeepThinkingModeHandler implements ModeHandler {
    
    private final DeepThinkingEngine engine;
    private final StreamingBroadcaster broadcaster;
    
    @Override
    public ChatMessage handleMessage(ChatMessage userMessage, String messageId) {
        
        // 1. 开始思考
        engine.startThinking(userMessage.content);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP, 
            new ThinkingStepData("UNDERSTAND", "理解问题"));
        
        // 2. 问题分解
        engine.analyzeProblemDecomposition(userMessage.content);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("DECOMPOSE", "问题分解"));
        
        // 3. 知识检索
        engine.gatherKnowledge(extractTopic(userMessage.content));
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("KNOWLEDGE", "知识检索"));
        
        // 4. 假设评估
        List<String> hypotheses = generateHypotheses(userMessage.content));
        engine.evaluateHypotheses(hypotheses);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("EVALUATE", "假设评估"));
        
        // 5. 逻辑推理
        engine.logicalReasoning(premise, conclusion);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("REASON", "逻辑推理"));
        
        // 6. 验证答案
        engine.verifySolution(intermediateSolution);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("VERIFY", "验证答案"));
        
        // 7. 综合答案
        String finalAnswer = generateFinalAnswer(engine);
        engine.synthesizeAnswer(finalAnswer);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, MESSAGE_COMPLETED, finalAnswer));
        
        return createAIMessage(finalAnswer);
    }
}
```

#### 2.4.2 思考步骤数据结构

```java
public class ThinkingStepData {
    public final String stepType;      // UNDERSTAND, DECOMPOSE, KNOWLEDGE, EVALUATE, REASON, VERIFY, SYNTHESIZE
    public final String description;
    public final int progress;        // 0-100
    public final String detail;
    
    public ThinkingStepData(String stepType, String description) {
        this(stepType, description, 0, null);
    }
    
    public ThinkingStepData(String stepType, String description, int progress, String detail) {
        this.stepType = stepType;
        this.description = description;
        this.progress = progress;
        this.detail = detail;
    }
}
```

### 2.5 CreativeWritingEngine 集成

#### 2.5.1 集成点

```java
public class CreativeWritingModeHandler implements ModeHandler {
    
    private final CreativeWritingEngine engine;
    private final StreamingBroadcaster broadcaster;
    
    @Override
    public ChatMessage handleMessage(ChatMessage userMessage, String messageId) {
        
        // 1. 主题提取
        String theme = engine.extractTheme(userMessage.content);
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, STATUS_CHANGED, "识别主题: " + theme));
        
        // 2. 大纲生成
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("OUTLINE", "生成大纲"));
        String outline = engine.generateOutline(userMessage.content, theme);
        List<String> sections = engine.parseOutline(outline);
        
        // 3. 分段撰写
        StringBuilder fullContent = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            broadcaster.broadcastTo(messageId, new StreamingEvent(
                messageId, THINKING_STEP,
                new ThinkingStepData(
                    "WRITING",
                    "撰写第" + (i + 1) + "/" + sections.size() + "部分",
                    (i * 100) / sections.size(),
                    sections.get(i)
            ));
            
            String sectionContent = writeSection(sections.get(i), theme);
            fullContent.append(sectionContent).append("\n\n");
            
            // 流式更新部分内容
            broadcaster.broadcastTo(messageId, new StreamingEvent(
                messageId, TOKEN_APPENDED, sectionContent));
        }
        
        // 4. 润色总结
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, THINKING_STEP,
            new ThinkingStepData("POLISH", "润色总结"));
        
        String polishedContent = polishContent(fullContent.toString());
        
        broadcaster.broadcastTo(messageId, new StreamingEvent(
            messageId, MESSAGE_COMPLETED, polishedContent));
        
        return createAIMessage(polishedContent);
    }
}
```

### 2.6 ChatAdapter 修复与增强

#### 2.6.1 现有问题分析

**问题1:
- notifyItemChanged 导致整个ViewHolder重绘
- 没有使用DiffUtil
- 流式更新与Compose布局冲突

**解决方案**

#### 2.6.2 修复方案

```java
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    // 使用DiffUtil
    private final List<ChatMessage> messages = new ArrayList<>();
    
    public void updateMessages(List<ChatMessage> newMessages) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(
            new ChatMessageDiffCallback(messages, newMessages));
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
    }
    
    // 局部更新
    public void updateMessageContent(String messageId, String newContent) {
        int position = findPosition(messageId);
        if (position >= 0) {
            messages.get(position).content = newContent;
            notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
        }
    }
    
    // 新增: 流式更新专用方法
    public void appendToken(String messageId, String token) {
        int position = findPosition(messageId);
        if (position >= 0) {
            ChatMessage msg = messages.get(position);
            if (msg.content = (msg.content == null ? "" : msg.content) + token;
            notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
        }
    }
}
```

#### 2.6.3 DiffUtil实现

```java
public class ChatMessageDiffCallback extends DiffUtil.ItemCallback<ChatMessage> {
    
    @Override
    public boolean areItemsTheSame(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
        return oldItem.id.equals(newItem.id);
    }
    
    @Override
    public boolean areContentsTheSame(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
        return oldItem.content.equals(newItem.content)
            && oldItem.status == newItem.status
            && oldItem.thinkingContent.equals(newItem.thinkingContent)
            && oldItem.attachmentsEquals(newItem);
    }
    
    @Nullable
    @Override
    public Object getChangePayload(@NonNull ChatMessage oldItem, @NonNull ChatMessage newItem) {
        List<Object> payloads = new ArrayList<>();
        if (!oldItem.content.equals(newItem.content)) {
            payloads.add(PAYLOAD_CONTENT_UPDATE);
        }
        if (oldItem.status != newItem.status) {
            payloads.add(PAYLOAD_STATUS_UPDATE);
        }
        // ... 其他变更
        return payloads.isEmpty() ? null : payloads;
    }
}
```

#### 2.6.4 流式更新优化

```java
// 在onBindViewHolder with payloads
@Override
public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, 
                            @NonNull List<Object> payloads) {
    if (payloads.isEmpty()) {
        super.onBindViewHolder(holder, position, payloads);
        return;
    }
    
    ChatMessage message = messages.get(position);
    
    for (Object payload : payloads) {
        if (payload instanceof String) {
            String payloadStr = (String) payload;
            
            if (PAYLOAD_CONTENT_UPDATE.equals(payloadStr)) {
                // 仅更新内容
                if (holder instanceof AIMessageViewHolder) {
                    ((AIMessageViewHolder) holder.messageText.setText(message.content);
                }
            } else if (PAYLOAD_STATUS_UPDATE.equals(payloadStr)) {
                // 仅更新状态
                updateMessageStatus((AIMessageViewHolder) holder, message);
            }
        }
    }
}
```

### 2.7 附件处理

#### 2.7.1 AttachmentViewHolder

```java
public static class AttachmentViewHolder extends RecyclerView.ViewHolder {
    
    ImageView attachmentIcon;
    TextView attachmentName;
    TextView attachmentSize;
    ImageView attachmentPreview;
    ProgressBar uploadProgress;
    
    public AttachmentViewHolder(View itemView) {
        super(itemView);
        attachmentIcon = itemView.findViewById(R.id.attachment_icon);
        attachmentName = itemView.findViewById(R.id.attachment_name);
        attachmentSize = itemView.findViewById(R.id.attachment_size);
        attachmentPreview = itemView.findViewById(R.id.attachment_preview);
        uploadProgress = itemView.findViewById(R.id.upload_progress);
    }
    
    public void bind(ChatMessage.Attachment attachment) {
        // 根据类型显示
        attachmentIcon.setText(attachment.getEmoji());
        attachmentName.setText(attachment.name);
        attachmentSize.setText(attachment.getDisplaySize());
        
        // 图片附件特殊处理
        if ("image".equals(attachment.type)) {
            attachmentPreview.setVisibility(View.VISIBLE);
            // 加载缩略图
            loadThumbnail(attachmentPreview, attachment);
        } else {
            attachmentPreview.setVisibility(View.GONE);
        }
        
        // 点击事件
        itemView.setOnClickListener(v -> openAttachment(attachment));
    }
}
```

#### 2.7.2 附件操作

| 操作 | 实现 |
|------|------|
| **预览** | 使用系统Intent打开或自定义预览Activity |
| **保存** | 复制到公共目录 |
| **分享** | 使用系统分享 |
| **删除** | 从消息删除，更新消息 |

### 2.8 推理状态显示

#### 2.8.1 InferenceProgressView

```java
public class InferenceProgressView {
    
    // 阶段显示
    private final TextView phaseText;
    private final ProgressBar progressBar;
    private final TextView statsText;
    private final TextView resourceText;
    
    public void update(InferenceProgress progress) {
        // 阶段
        phaseText.setText(progress.phase.getFullDisplay());
        
        // 进度条
        if (progress.phase == InferencePhase.GENERATING) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(progress.getPercentage());
        } else {
            progressBar.setIndeterminate(true);
        }
        
        // 统计
        statsText.setText(String.format(
            "已生成: %d token · 速度: %.1f t/s",
            progress.processedTokens,
            progress.tokensPerSecond
        ));
        
        // 资源
        resourceText.setText(String.format(
            "GPU: %d层 · 内存: %.1fGB",
            gpuLayers,
            gpuMemoryGB
        ));
    }
}
```

---

## 3. 数据流设计

### 3.1 消息ID生成规则

```
格式: msg_{timestamp}_{random}_{sequence}

示例: msg_1716200000000_a1b2c3_001

组成部分:
- timestamp: 毫秒级时间戳
- random: 6位随机字符串
- sequence: 3位序号（同一毫秒内并发）
```

### 3.2 事件流

```
用户发送消息
    ↓
MESSAGE_CREATED (messageId, initialContent)
    ↓
[生成开始
    ↓
TOKEN_APPENDED (messageId, token1) ← 重复直到完成
TOKEN_APPENDED (messageId, token2)
TOKEN_APPENDED (messageId, token3)
...
    ↓
THINKING_STEP (messageId, stepData) ← 深度思考/创意写作模式
    ↓
MESSAGE_COMPLETED (messageId, finalContent, stats)
    ↓
完成
```

### 3.3 错误流
```
MESSAGE_CREATED
    ↓
TOKEN_APPENDED × N
    ↓
MESSAGE_FAILED (messageId, error)
    ↓
或
MESSAGE_CANCELLED (messageId)
```

---

## 4. 并发与线程安全

### 4.1 线程模型

| 组件 | 线程 | 说明 |
|------|------|------|
| ChatOrchestrator | 主线程调用 | 入口，内部异步 |
| MessageQueue | 线程池 | ExecutorService |
| StreamingBroadcaster | 主线程回调 | Handler.post |
| ChatAdapter | 主线程 | UI更新 |

### 4.2 同步机制

```java
// 使用ConcurrentHashMap
private final Map<String, MessageTask> activeTasks = new ConcurrentHashMap<>();

// 使用Atomic变量
private final AtomicBoolean isGenerating = new AtomicBoolean(false);

// 使用synchronized
public synchronized void submitMessage(...) { ... }
```

---

## 5. 性能优化

### 5.1 流式更新节流

```java
// 现有StreamingUpdateManager参数:
- minUpdateIntervalMs = 50ms
- forceUpdateIntervalMs = 200ms
- minTokensBeforeUpdate = 3

优化:
- 积累3个token或50ms后更新
- 最多200ms强制更新
```

### 5.2 列表优化

```java
// 使用setHasStableIds(true)
// 使用DiffUtil
// 使用Payload局部更新
// 使用Image加载使用Glide/Coil
```

### 5.3 内存管理

```java
// 限制历史消息数
private static final int MAX_HISTORY_LIMIT = 100;

// 分页加载
public void loadMoreMessages(int page);
```

---

## 6. 测试策略

### 6.1 单元测试

| 测试项 | 内容 |
|---------|------|
| ChatOrchestrator | 模式切换、消息处理 |
| StreamingBroadcaster | 订阅/广播、多订阅者 |
| MessageQueue | 队列操作、并发控制 |
| ChatAdapter | DiffUtil、Payload更新 |

### 6.2 集成测试

| 测试项 | 内容 |
|---------|------|
| 端到端流程 | 发送→生成→完成 |
| 模式切换 | 生成中切换、立即切换 |
| 流式生成 | Token追加、状态更新 |
| 错误处理 | 失败重试、取消 |

### 6.3 性能测试

| 测试项 | 指标 |
|---------|------|
| 滚动帧率 | ≥60fps |
| 流式更新延迟 | <16ms |
| 内存占用 | <100MB |

---

## 7. 依赖关系

```
ChatOrchestrator
├── ChatModeManager
├── DeepThinkingEngine
├── CreativeWritingEngine
├── StreamingBroadcaster
├── MessageQueue
│   └── InferenceQueue
└── AIService
└── ChatAdapter
    └── RecyclerView
```

---

## 8. 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 流式更新导致UI卡顿 | 使用节流、Payload更新 |
| 深度思考延迟高 | 并行处理、进度显示 |
| 附件类型多 | 分阶段实现 |
| 模式切换复杂 | 状态机设计 |
