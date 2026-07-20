# Changelog V2.0 - AI对话功能全面升级

> 更新日期: 2026-07-18
> 版本: 2.0.0

## 一、Agent 软件层架构

### 1.1 新增文件

| 文件 | 功能 |
|------|------|
| `AgentSoftwareLayer.java` | Agent 软件层主入口 |
| `IntentRecognizer.java` | LLM 意图识别模块 |
| `ComplexityAnalyzer.java` | LLM 复杂度分析模块 |
| `TaskDecomposer.java` | LLM 任务分解模块 |
| `ExecutionEngine.java` | 任务执行引擎 |
| `ThinkingChainEngine.java` | 思考链引擎 |
| `ResultIntegrator.java` | 结果整合模块 |

### 1.2 数据模型

| 类 | 说明 |
|------|------|
| `IntentResult` | 意图识别结果 |
| `ComplexityLevel` | 复杂度级别 (SIMPLE/MEDIUM/COMPLEX) |
| `Task` / `TaskPlan` | 任务定义和计划 |
| `ExecutionResult` / `TaskResult` | 执行结果 |
| `ThinkingChain` / `ThinkingStep` | 思考链 |
| `Thought` / `Action` / `Observation` | 思考/动作/观察 |
| `AgentResponse` / `AgentStats` | Agent 响应和统计 |

### 1.3 处理流程

```
用户消息 → 意图识别(LLM) → 复杂度分析(LLM) → 任务分解(LLM)
    → 执行引擎(工具/LLM) → 思考链(LLM) → 结果整合(LLM) → 最终回复
```

## 二、推理锁保护

### 2.1 问题

Agent 模式调用 `LlamaHelper.generate()` 与聊天上下文 `chatSend()` 冲突，导致 native 崩溃。

### 2.2 解决方案

```java
// 所有 Agent LLM 调用前关闭上下文
boolean contextWasActive = LlamaHelper.isChatContextActive();
if (contextWasActive) {
    LlamaHelper.chatDestroy();
    Thread.sleep(100);
}

// 使用 generate 调用
String response = LlamaHelper.generate(prompt, maxTokens, temperature);
```

### 2.3 修改的文件

- `IntentRecognizer.java`
- `ComplexityAnalyzer.java`
- `TaskDecomposer.java`
- `ExecutionEngine.java`
- `ThinkingChainEngine.java`
- `ResultIntegrator.java`

## 三、UI 优化

### 3.1 系统消息布局

```xml
<!-- 固定宽度，最大化屏幕利用 -->
<LinearLayout android:layout_width="match_parent" ...>
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" ...>
        <TextView android:id="@+id/message_text" ... />
    </LinearLayout>
</LinearLayout>
```

### 3.2 Agent 模式无限等待

```java
// 无限等待 AI 服务初始化
while (true) {
    if (aiService.isInitialized()) {
        // 继续处理
        return;
    }
    Thread.sleep(1000);
    // 更新等待提示
}
```

### 3.3 快捷工具栏

```
修改前: [解释] [翻译] [总结] [代码] [🧠思考] [✍️写作] [改写] [天气] [清空]
修改后: [💬对话] [🤖Agent] [🧠思考] [✍️写作] [天气] [清空]
```

## 四、模式切换

### 4.1 移除自动模式切换

```java
// ChatModeManager.java
public ChatMode determineMode(String userMessage) {
    return currentMode;  // 直接返回当前模式，不自动切换
}

public boolean shouldAutoSwitch(String userMessage) {
    return false;  // 禁用自动切换
}
```

### 4.2 手动模式切换

```java
// AIConfig.java
private boolean agentEnabled = true;  // 启用 Agent 模式
private boolean autoModeEnabled = false;  // 禁用自动模式切换
```

## 五、聊天上下文兼容性

### 5.1 模式切换不销毁上下文

```java
// ChatOrchestrator.java
private void updateContextForMode(ChatMode mode) {
    // 使用 updateChatPrompts 更新提示词，不销毁上下文
    aiService.updateChatPrompts(globalPrompt, systemPrompt, normalPrompt);
}
```

### 5.2 Agent 数据不注入聊天上下文

```java
// UnifiedAgentEngine.java
private void sendAndProcessWithLocalModel(String message, ...) {
    // 只发送用户原始消息，不注入 Agent 内部数据
    aiService.chatSend(message, maxTokens, enableThinking, ...);
}
```

## 六、模型状态持久化

### 6.1 KV Cache 保存

```java
// ModelStateCache.java
private void saveKVCache() {
    JSONObject kvMeta = new JSONObject();
    kvMeta.put("timestamp", System.currentTimeMillis());
    kvMeta.put("valid", true);
    // 保存到 kv_cache.json
}
```

### 6.2 快速恢复

```java
private void restoreKVCache() {
    // 检查 kv_cache.json 是否存在且在 24 小时内
    kvCacheAvailable = valid && elapsed < 24h;
}
```

## 七、Token 统计修复

### 7.1 问题

流式过程中重复累加 session tokens。

### 7.2 解决方案

```java
// TokenStatsManager.java
public void updateRequestStreamingStats(int completionTokens) {
    // 流式更新：只更新 request 统计，不累加 session
    requestCompletionTokens.set(completionTokens);
    notifyStatsUpdated();
}

public void updateRequestStats(int promptTokens, int completionTokens) {
    // 最终更新：累加到 session
    sessionPromptTokens.addAndGet(promptTokens);
    sessionCompletionTokens.addAndGet(completionTokens);
}
```

## 八、GPU 状态检测修复

### 8.1 问题

`System.load()` 加载 OpenCL 后 native 层 `s_openclLoaded` 未同步。

### 8.2 解决方案

```java
// LlamaHelper.java
public static void setOpenCLLoaded(boolean loaded) {
    nativeSetOpenCLLoaded(loaded);
}

// AIService.java - preloadOpenClIfNeeded()
System.load(path);
LlamaHelper.setOpenCLLoaded(true);  // 同步 native 状态
```

## 九、其他修复

| 问题 | 修复 |
|------|------|
| 聊天历史丢失 | `coordinator.cleanup()` 移除 `chatHistory.clear()` |
| 欢迎消息重复 | 移除重复添加 "欢迎回来" 消息 |
| 主线程 I/O | `loadAIChatHistory()` 改为异步加载 |
| 数据不同步 | `clearChat()` 添加 `chatViewModel.clearChatHistory()` |
| 模型状态未持久化 | `ModelStateCache` 保存 GPU layers、thread count 等 |
| 快捷工具栏 | 移除解释/翻译/总结/改写，添加对话/Agent 入口 |

## 十、文件变更统计

| 类型 | 数量 |
|------|------|
| 新增文件 | 25 |
| 修改文件 | 40 |
| 新增代码行 | ~14,000 |
| 删除代码行 | ~1,600 |
