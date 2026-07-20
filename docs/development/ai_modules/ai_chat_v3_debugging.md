# AI助手界面 v3.0 调试方法文档

> 版本: 3.0 | 更新日期: 2026-07-18

---

## 1. 调试工具

---

## 1. 日志系统

### 1.1 日志级别

| 级别 | 用途 | 开启方式 |
|------|------|---------|
| VERBOSE | 详细调试信息 | Log.v |
| DEBUG | 调试信息 | Log.d |
| INFO | 一般信息 | Log.i |
| WARN | 警告信息 | Log.w |
| ERROR | 错误信息 | Log.e |

### 1.2 日志标签

| 组件 | 标签(TAG) | 用途 |
|------|---------|------|
| ChatOrchestrator | "ChatOrchestrator | 协调器日志 |
| StreamingBroadcaster | "StreamingBroadcaster | 广播器日志 |
| MessageQueue | "MessageQueue" | 消息队列日志 |
| DeepThinkingEngine | "DeepThinking" | 深度思考引擎日志 |
| CreativeWritingEngine | "CreativeWriter" | 创意写作引擎日志 |
| ChatModeManager | "ChatModeManager" | 模式管理器日志 |
| ChatAdapter | "ChatAdapter" | 适配器日志 |

### 1.3 关键日志位置

#### ChatOrchestrator

```java
// 发送消息日志
Log.i(TAG, "Sending message: " + truncate(content, 100);

// 模式切换日志
Log.d(TAG, "Mode change: " + oldMode + " -> " + newMode);

// 错误日志
Log.e(TAG, "Error in message handling", exception);
```

#### StreamingBroadcaster

```java
// 订阅日志
Log.d(TAG, "Subscriber added for message: " + messageId);

// 广播日志
Log.d(TAG, "Broadcasting event: " + event.type + " for message: " + event.messageId);

// 事件处理日志
Log.d(TAG, "Event delivered to " + subscriberCount + " subscribers");
```

### 1.4 日志过滤命令

```bash
# 查看所有AI聊天相关日志
adb logcat -s ChatOrchestrator:*
adb logcat -s StreamingBroadcaster:*
adb logcat -s DeepThinking:*

# 组合过滤
adb logcat -s ChatOrchestrator:* StreamingBroadcaster:* MessageQueue:*

# 过滤错误日志
adb logcat *:E | grep -E "(ChatOrchestrator|StreamingBroadcaster|MessageQueue
```

---

## 2. 常见问题排查

### 2.1 流式生成不显示

**症状**: AI回复不显示或更新

**可能原因**:

1. **事件未广播**
   - 检查StreamingBroadcaster未正确初始化
   - ChatAdapter未订阅事件

2. **Payload更新失败**
   - notifyItemChanged参数错误
   - ViewHolder未正确绑定

**排查步骤**:

```bash
# 步骤1: 检查日志检查
adb logcat -s StreamingBroadcaster:*
# 查看是否有 "Broadcasting event" 日志

# 步骤2: 检查ChatAdapter日志
adb logcat -s ChatAdapter:*
# 查看是否有事件处理日志

# 步骤3: 检查消息ID匹配
# 检查消息ID是否一致
# 发送端messageId
# 接收端messageId
```

**修复方案**:
1. 确保ChatAdapter在onStart时订阅，onStop时取消订阅
2. 确保消息ID格式正确: msg_{timestamp}_{random}_{sequence}
3. 确保使用Payload进行局部更新

---

### 2.2 模式切换不生效

**症状**: 切换模式后功能未变化

**可能原因**:

1. **pendingMode未应用**
   - 生成中切换，未触发
   - setGeneratingState(false)未调用

2. **上下文未重建**
   - onContextNeedRebuild未调用

**排查步骤**:

```bash
# 步骤1: 检查模式切换日志
adb logcat -s ChatModeManager:*
# 查看 "Mode changed to: ...

# 步骤2: 检查生成状态
adb logcat -s ChatOrchestrator:*
# 查看isGenerating状态

# 步骤3: 检查上下文重建
# 查看onContextNeedRebuild是否被调用
```

**修复方案**:
1. 确保生成完成后调用setGeneratingState(false)
2. 确保pendingMode在生成完成后自动应用
3. 确保onContextNeedRebuild触发上下文正确重建

---

### 2.3 深度思考不显示

**症状**: 深度思考步骤不显示

**可能原因**:

1. **思考步骤未广播**
   - DeepThinkingModeHandler未发送THINKING_STEP事件

2. **ChatAdapter未处理**
   - 事件类型不匹配

**排查步骤**:

```bash
# 步骤1: 检查深度思考日志
adb logcat -s DeepThinking:*
# 查看各步骤是否执行

# 步骤2: 检查广播日志
adb logcat -s StreamingBroadcaster:*
# 查看THINKING_STEP事件

# 步骤3: 检查事件处理
# ChatAdapter是否处理THINKING_STEP事件
```

**修复方案**:
1. 确保DeepThinkingModeHandler每步调用broadcaster.broadcastTo()
2. 确保ChatAdapter.onEvent()处理THINKING_STEP类型
3. 确保ThinkingMessageViewHolder正确显示

---

### 2.4 附件显示异常

**症状**: 附件不显示或显示错误

**可能原因**:

1. **数据结构不匹配
   - Attachment字段未正确设置
   - MIME类型错误

**排查步骤**:

```java
// 检查Attachment数据
Log.d(TAG, "Attachment type: " + attachment.type);
Log.d(TAG, "Attachment mimeType: " + attachment.mimeType);
Log.d(TAG, "Attachment url: " + attachment.url);
```

**修复方案**:
1. 确保type字段正确设置（image/document/audio/video
2. 确保url是有效路径或URI
3. 确保mimeType正确

---

### 2.5 性能问题

**症状**: 滚动卡顿、布局闪烁

**可能原因**:

1. **频繁notifyItemChanged
   - 流式更新过于频繁
   - 未使用Payload

**排查步骤**:

```bash
# 使用Systrace
# 查看UI线程阻塞

# 检查帧率
adb shell dumpsys gfxinfo <package>
```

**修复方案**:
1. 使用StreamingUpdateManager节流
2. 使用DiffUtil优化
3. 使用Payload局部更新

---

## 3. 断点调试

### 3.1 关键断点位置

#### ChatOrchestrator.sendMessage()

```java
// 消息发送入口
public void sendMessage(String content, List<ChatMessage.Attachment> attachments) {
    // 断点1: 消息ID生成
    String messageId = generateMessageId();  // 断点

    // 断点2: 模式选择
    ChatMode mode = modeManager.determineMode(content);

    // 断点3: 消息入队
    messageQueue.submitMessage(...);
}
```

#### StreamingBroadcaster.broadcastTo()

```java
public void broadcastTo(String messageId, StreamingEvent event) {
    // 断点1: 消息ID匹配
    List<StreamingSubscriber> subscribers = subscribers.get(messageId);

    // 断点2: 订阅者数量
    int count = subscribers != null ? subscribers.size() : 0;

    // 断点3: 事件分发
    for (StreamingSubscriber subscriber : subscribers) {
        subscriber.onEvent(event);  // 断点
    }
}
```

#### ChatAdapter.onEvent()

```java
public void onEvent(StreamingEvent event) {
    // 断点1: 事件类型
    StreamingEvent.Type type = event.type;

    // 断点2: 消息位置
    int position = findPosition(event.messageId);

    // 断点3: 更新UI
    notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);  // 断点
}
```

---

### 3.2 调试技巧

1. **条件断点**

```java
// 仅在特定消息ID时断点
if (messageId.contains("msg_1716200000000")) {
    // 断点
}

// 仅在错误时断点
if (event.type == StreamingEvent.Type.MESSAGE_FAILED) {
    // 断点
}
```

2. **日志断点**
```java
// 在关键位置添加日志
Log.d(TAG, "Current state: " + state);
Log.d(TAG, "Message ID: " + messageId);
Log.d(TAG, "Position: " + position);
```

---

## 4. 性能调试

### 4.1 性能监控

#### 帧率监控

```bash
# 查看帧率
adb shell dumpsys gfxinfo com.oilquiz.app

# 持续监控
adb shell dumpsys gfxinfo com.oilquiz.app framestats

# 分析帧率
python systrace.py -a com.oilquiz.app -t 5 -o trace.html
```

#### 内存监控

```bash
# 查看内存使用
adb shell dumpsys meminfo com.oilquiz.app

# 实时监控
adb shell top -n 1 -d 1 | grep com.oilquiz.app

# 泄漏检测
# 使用LeakCanary
```

#### GPU监控

```bash
# GPU使用
adb shell dumpsys gpuinfo com.oilquiz.app
```

### 4.2 性能分析

#### 流式更新性能

```java
// 记录更新耗时
long startTime = System.currentTimeMillis();
notifyItemChanged(position, PAYLOAD_CONTENT_UPDATE);
long endTime = System.currentTimeMillis();
Log.d(TAG, "Update took " + (endTime - startTime) + "ms");
```

#### 列表滚动性能

```java
// 使用RecyclerView优化
recyclerView.setHasFixedSize(true);
recyclerView.setItemViewCacheSize(20);
recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 20);
```

---

## 5. 网络调试

### 5.1 模型加载

```bash
# 查看网络请求
adb logcat -s OkHttp:*

# 查看请求耗时
adb logcat -s AIService:*
```

### 5.2 错误处理

```java
// 记录错误
try {
    // 操作
} catch (Exception e) {
    Log.e(TAG, "Error: " + e.getMessage(), e);
    // 断点
}
```

---

## 6. 测试命令

### 6.1 单元测试

```bash
# 运行单元测试
./gradlew testDebugUnitTest

# 运行特定测试
./gradlew testDebugUnitTest --tests "com.oilquiz.app.ai.chat.ChatOrchestratorTest"
```

### 6.2 集成测试

```bash
# 运行instrumented测试
./gradlew connectedDebugAndroidTest

# 运行特定测试
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.oilquiz.app.ai.chat.ChatIntegrationTest
```

### 6.3 手动测试

**深度思考模式测试:

1. 打开AI助手
2. 切换到深度思考模式
3. 输入 "为什么天空是蓝色的"
4. 观察思考步骤显示
5. 验证最终答案

**创意写作模式测试:

1. 打开AI助手
2. 切换到创意写作模式
3. 输入 "写一个科幻故事"
4. 观察大纲生成
5. 观察分段撰写
6. 验证最终作品

---

## 7. 常见错误码

| 错误码 | 描述 | 解决方案 |
|--------|------|-----------|
| ERR_001 | 消息ID生成失败 | 检查时间戳和随机数 |
| ERR_002 | 事件路由失败 | 检查订阅者是否存在 |
| ERR_003 | 模式切换失败 | 检查生成状态 |
| ERR_004 | 附件加载失败 | 检查文件路径和权限 |
| ERR_005 | 推理超时 | 检查模型和设备性能 |

---

## 8. 调试检查清单

### 开发前检查

- [ ] 日志级别正确设置
- [ ] 关键位置有INFO日志
- [ ] 错误有ERROR日志
- [ ] 使用正确TAG
- [ ] 无敏感信息

### 开发中检查

- [ ] 关键方法入口有DEBUG日志
- [ ] 异常捕获和堆栈
- [ ] 性能监控
- [ ] 性能指标

### 测试中检查

- [ ] 功能完整
- [ ] 性能达标
- [ ] 无内存泄漏
- [ ] 兼容性

---

## 9. 报告模板

### 问题报告模板:

```
问题描述: [详细描述问题]
复现步骤: [1. 步骤1 2. 步骤2 ...]
预期行为: [描述预期结果]
实际行为: [描述实际结果]
日志信息: [粘贴相关日志]
设备信息: [设备型号, Android版本]
模型信息: [模型名称, 大小]
```

### 修复方案: [如果有]
```

---

## 10. 参考资料

- [Android Studio调试指南
- [OkHttp调试
- [Systrace使用指南
- [LeakCanary使用指南
- [RecyclerView优化指南
