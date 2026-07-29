# AI 对话系统 V2.0 设计文档

> 版本: 2.0.0 | 更新日期: 2026-07-18
> 分支: feature/agent-v3-iteration

## 一、系统架构概览

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           UI Layer (界面层)                              │
├─────────────────────────────────────────────────────────────────────────┤
│  AIChatActivity  │  ChatAdapter  │  ChatModeManager  │  InferenceProgressView │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────────┐
│                      Agent Software Layer (Agent软件层)                  │
├─────────────────────────────────────────────────────────────────────────┤
│  AgentSoftwareLayer                                                    │
│  ├─ IntentRecognizer (意图识别)                                         │
│  ├─ ComplexityAnalyzer (复杂度分析)                                     │
│  ├─ TaskDecomposer (任务分解)                                           │
│  ├─ ExecutionEngine (执行引擎)                                          │
│  ├─ ThinkingChainEngine (思考链引擎)                                    │
│  └─ ResultIntegrator (结果整合)                                         │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────────┐
│                      Chat Layer (对话层)                                 │
├─────────────────────────────────────────────────────────────────────────┤
│  AgentChatHandler  │  ChatOrchestrator  │  UnifiedAgentEngine          │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────────┐
│                      Service Layer (服务层)                              │
├─────────────────────────────────────────────────────────────────────────┤
│  AIService  │  InferenceQueue  │  StreamingInferenceManager            │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────────┐
│                      Hardware Layer (硬件层)                             │
├─────────────────────────────────────────────────────────────────────────┤
│  LlamaHelper  │  llama.cpp  │  OpenCL/GPU Acceleration                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 模块职责

| 层级 | 模块 | 职责 |
|------|------|------|
| UI Layer | AIChatActivity | 界面交互、消息显示 |
| UI Layer | ChatAdapter | 消息列表适配器 |
| UI Layer | ChatModeManager | 模式管理 |
| Agent Layer | AgentSoftwareLayer | Agent 软件层入口 |
| Agent Layer | IntentRecognizer | LLM 意图识别 |
| Agent Layer | ComplexityAnalyzer | LLM 复杂度分析 |
| Agent Layer | TaskDecomposer | LLM 任务分解 |
| Agent Layer | ExecutionEngine | 任务执行引擎 |
| Agent Layer | ThinkingChainEngine | 思考链引擎 |
| Agent Layer | ResultIntegrator | 结果整合 |
| Chat Layer | AgentChatHandler | Agent 对话处理 |
| Chat Layer | ChatOrchestrator | 对话协调器 |
| Service Layer | AIService | AI 服务主入口 |
| Service Layer | InferenceQueue | 推理队列 |
| Hardware Layer | LlamaHelper | 本地 LLM 接口 |

## 二、Agent 软件层详细设计

### 2.1 AgentSoftwareLayer

```java
/**
 * AgentSoftwareLayer - Agent 软件层主入口
 * 
 * 职责：
 * 1. 协调各软件层模块
 * 2. 管理 Agent 处理流程
 * 3. 提供统一的回调接口
 */
public class AgentSoftwareLayer {
    
    // 硬件层引用
    private final AIService aiService;
    
    // 软件层模块
    private final IntentRecognizer intentRecognizer;
    private final ComplexityAnalyzer complexityAnalyzer;
    private final TaskDecomposer taskDecomposer;
    private final ExecutionEngine executionEngine;
    private final ThinkingChainEngine thinkingChainEngine;
    private final ResultIntegrator resultIntegrator;
    
    // 处理流程
    public AgentResponse processMessage(String userMessage) {
        // 1. 意图识别 (LLM)
        IntentResult intent = intentRecognizer.recognize(userMessage);
        
        // 2. 复杂度分析 (LLM)
        ComplexityLevel complexity = complexityAnalyzer.analyze(userMessage, intent);
        
        // 3. 任务分解 (LLM)
        TaskPlan taskPlan = taskDecomposer.decompose(userMessage, intent, complexity);
        
        // 4. 执行任务 (工具/LLM)
        ExecutionResult result = executionEngine.execute(taskPlan);
        
        // 5. 思考链 (LLM)
        ThinkingChain chain = thinkingChainEngine.process(result, userMessage);
        
        // 6. 结果整合 (LLM)
        return resultIntegrator.integrate(chain, result, userMessage);
    }
}
```

### 2.2 IntentRecognizer

```java
/**
 * IntentRecognizer - 意图识别模块
 * 
 * 使用 LLM 识别用户意图
 * 硬件层调用: LLM.generate() 或 chatDestroy() + generate()
 */
public class IntentRecognizer {
    
    public IntentResult recognize(String userMessage) {
        // 构建意图识别 Prompt
        String prompt = buildIntentPrompt(userMessage);
        
        // 如果聊天上下文活跃，先关闭再使用 generate
        boolean contextWasActive = LlamaHelper.isChatContextActive();
        if (contextWasActive) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        // 调用 LLM 识别意图
        String response = LlamaHelper.generate(prompt, 150, 0.2f);
        
        // 解析意图
        return parseIntentResponse(response, userMessage);
    }
}
```

### 2.3 ComplexityAnalyzer

```java
/**
 * ComplexityAnalyzer - 复杂度分析模块
 * 
 * 使用 LLM 评估任务复杂度
 */
public class ComplexityAnalyzer {
    
    public ComplexityLevel analyze(String userMessage, IntentResult intent) {
        String prompt = buildComplexityPrompt(userMessage, intent);
        
        // 关闭上下文后使用 generate
        if (LlamaHelper.isChatContextActive()) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        String response = LlamaHelper.generate(prompt, 100, 0.2f);
        return parseComplexityResponse(response);
    }
}
```

### 2.4 TaskDecomposer

```java
/**
 * TaskDecomposer - 任务分解模块
 * 
 * 使用 LLM 将复杂任务分解为子任务
 */
public class TaskDecomposer {
    
    public TaskPlan decompose(String userMessage, IntentResult intent, ComplexityLevel complexity) {
        String prompt = buildDecompositionPrompt(userMessage, intent, complexity);
        
        // 关闭上下文后使用 generate
        if (LlamaHelper.isChatContextActive()) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        String response = LlamaHelper.generate(prompt, 500, 0.3f);
        List<Task> tasks = parseDecompositionResponse(response);
        
        TaskPlan plan = new TaskPlan(userMessage, intent);
        for (Task task : tasks) {
            plan.addTask(task);
        }
        return plan;
    }
}
```

### 2.5 ExecutionEngine

```java
/**
 * ExecutionEngine - 执行引擎
 * 
 * 管理工具调用和任务执行
 */
public class ExecutionEngine {
    
    private TaskResult executeWithLLM(Task task) {
        String prompt = "请完成以下任务: " + task.getDescription();
        
        // 关闭上下文后使用 generate
        if (LlamaHelper.isChatContextActive()) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        String response = LlamaHelper.generate(prompt, 500, 0.7f);
        return TaskResult.success(response);
    }
}
```

### 2.6 ThinkingChainEngine

```java
/**
 * ThinkingChainEngine - 思考链引擎
 * 
 * 驱动 Agent 的思考过程
 */
public class ThinkingChainEngine {
    
    private Thought generateThought(ExecutionResult result, ThinkingChain chain) {
        String prompt = buildThoughtPrompt(result, chain);
        
        // 关闭上下文后使用 generate
        if (LlamaHelper.isChatContextActive()) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        String response = LlamaHelper.generate(prompt, 200, 0.3f);
        return parseThoughtResponse(response);
    }
}
```

### 2.7 ResultIntegrator

```java
/**
 * ResultIntegrator - 结果整合模块
 * 
 * 整合所有结果生成最终回复
 */
public class ResultIntegrator {
    
    public AgentResponse integrate(ThinkingChain chain, ExecutionResult result, String userMessage) {
        String prompt = buildIntegrationPrompt(chain, result, userMessage);
        
        // 关闭上下文后使用 generate
        if (LlamaHelper.isChatContextActive()) {
            LlamaHelper.chatDestroy();
            Thread.sleep(100);
        }
        
        String response = LlamaHelper.generate(prompt, 1000, 0.7f);
        return new AgentResponse(response, chain, result);
    }
}
```

## 三、推理锁保护机制

### 3.1 问题

Agent 模式调用 `LlamaHelper.generate()` 与聊天上下文 `chatSend()` 冲突，导致 native 崩溃。

### 3.2 解决方案

```java
// 所有 Agent LLM 调用前关闭上下文
boolean contextWasActive = LlamaHelper.isChatContextActive();
if (contextWasActive) {
    LlamaHelper.chatDestroy();
    Thread.sleep(100);  // 等待释放
}

// 使用 generate 调用（不冲突）
String response = LlamaHelper.generate(prompt, maxTokens, temperature);

// 注意：上下文会在后续流程中由 AIService 重新创建
```

### 3.3 流程图

```
Agent 模式处理
    ↓
意图识别: chatDestroy() → generate() → 解析意图
    ↓
复杂度分析: chatDestroy() → generate() → 评估复杂度
    ↓
任务分解: chatDestroy() → generate() → 分解任务
    ↓
执行任务: chatDestroy() → generate() → 执行
    ↓
思考链: chatDestroy() → generate() → 思考
    ↓
结果整合: chatDestroy() → generate() → 最终回复
    ↓
聊天: chatSend() (上下文由 AIService 重建)
```

## 四、UI 设计

### 4.1 系统消息布局

```xml
<!-- 固定宽度，最大化屏幕利用 -->
<LinearLayout android:layout_width="match_parent" ...>
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" ...>
        <TextView android:id="@+id/message_text" ... />
    </LinearLayout>
</LinearLayout>
```

### 4.2 Agent 模式 UI

```
┌─────────────────────────────────────────────────────────────────┐
│  🤖 Agent模式已启用                                             │
│                                                                 │
│  功能特性：                                                     │
│  • 智能意图识别                                                  │
│  • 复杂任务分解                                                  │
│  • 工具调用执行                                                  │
│  • 思考链推理                                                    │
│                                                                 │
│  请发送消息开始使用。                                            │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Agent 处理过程 UI

```
┌─────────────────────────────────────────────────────────────────┐
│  🤖 Agent模式已激活，正在处理您的请求...                         │
├─────────────────────────────────────────────────────────────────┤
│  ⏳ AI服务正在初始化... (1秒)                                   │
│  ...                                                            │
│  📋 意图识别 - 正在分析用户意图...                               │
│  📋 意图识别完成 - 意图类型: WEATHER, 置信度: 95%               │
│  📋 复杂度分析 - 正在评估任务复杂度...                           │
│  📋 复杂度分析完成 - 复杂度级别: MEDIUM                         │
│  🔧 工具调用: weather                                           │
│  ✅ 工具调用完成: weather                                        │
│  💬 最终回复                                                     │
└─────────────────────────────────────────────────────────────────┘
```

## 五、模式切换设计

### 5.1 模式定义

```java
public enum ChatMode {
    NORMAL("普通", "normal", "💬"),
    DEEP_THINKING("深度思考", "deep_thinking", "🧠"),
    CREATIVE("创意写作", "creative", "✍️"),
    AGENT("Agent", "agent", "🤖");
}
```

### 5.2 模式切换流程

```
用户点击快捷按钮
    ↓
ChatModeManager.setManualMode(mode)
    ↓
switchToMode(mode) → 保存到 SharedPreferences
    ↓
updateContextForMode(mode) → 更新提示词（不销毁上下文）
    ↓
模式切换完成
```

### 5.3 上下文兼容性

```java
// 模式切换时更新提示词，不销毁上下文
private void updateContextForMode(ChatMode mode) {
    ModeContextPrompts prompts = modeManager.getContextPromptsForMode(mode);
    aiService.updateChatPrompts(prompts.globalPrompt, prompts.systemPrompt, prompts.normalPrompt);
}
```

## 六、数据流设计

### 6.1 消息处理流程

```
用户输入
    ↓
sendMessage() → processChatMessage()
    ↓
┌─────────────────────────────────────────────────────────────────┐
│  检查当前模式                                                    │
│  ├─ Agent 模式 → processChatMessageWithAgent()                  │
│  ├─ 在线模型 → processChatMessageWithOnlineModel()              │
│  └─ 其他模式 → 普通聊天                                          │
└─────────────────────────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────────────────────────┐
│  Agent 模式处理                                                  │
│  ├─ 等待 AI 服务初始化（无限等待）                               │
│  ├─ AgentChatHandler.startAgentLoop()                           │
│  ├─ AgentSoftwareLayer.processMessage()                         │
│  │   ├─ IntentRecognizer → LLM.generate()                       │
│  │   ├─ ComplexityAnalyzer → LLM.generate()                     │
│  │   ├─ TaskDecomposer → LLM.generate()                         │
│  │   ├─ ExecutionEngine → LLM.generate() / Tool.execute()       │
│  │   ├─ ThinkingChainEngine → LLM.generate()                    │
│  │   └─ ResultIntegrator → LLM.generate()                       │
│  └─ AgentResponse → UI 显示                                     │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 数据结构

```java
// 意图识别结果
public class IntentResult {
    public String type;           // 意图类型
    public double confidence;     // 置信度
    public String entity;         // 关键实体
}

// 复杂度级别
public enum ComplexityLevel {
    SIMPLE,    // 简单任务
    MEDIUM,    // 中等任务
    COMPLEX    // 复杂任务
}

// 任务计划
public class TaskPlan {
    public List<Task> tasks;      // 任务列表
    public String originalMessage;
    public IntentResult intent;
}

// 思考步骤
public class ThinkingStep {
    public Thought thought;       // 思考内容
    public Action action;         // 执行动作
    public Observation observation; // 观察结果
}

// Agent 响应
public class AgentResponse {
    public String finalAnswer;    // 最终回复
    public ThinkingChain chain;   // 思考链
    public ExecutionResult result; // 执行结果
    public AgentStats stats;      // 统计信息
}
```

## 七、文件变更清单

### 7.1 新增文件

| 文件 | 说明 |
|------|------|
| `AgentSoftwareLayer.java` | Agent 软件层主入口 |
| `IntentRecognizer.java` | LLM 意图识别 |
| `ComplexityAnalyzer.java` | LLM 复杂度分析 |
| `TaskDecomposer.java` | LLM 任务分解 |
| `ExecutionEngine.java` | 任务执行引擎 |
| `ThinkingChainEngine.java` | 思考链引擎 |
| `ResultIntegrator.java` | 结果整合 |
| `IntentResult.java` | 意图识别结果 |
| `ComplexityLevel.java` | 复杂度级别 |
| `Task.java` / `TaskPlan.java` | 任务定义 |
| `ExecutionResult.java` / `TaskResult.java` | 执行结果 |
| `ThinkingChain.java` / `ThinkingStep.java` | 思考链 |
| `Thought.java` / `Action.java` / `Observation.java` | 思考/动作/观察 |
| `AgentResponse.java` / `AgentStats.java` | Agent 响应 |
| `EnhancedAgentHandler.java` | 增强版 Agent 处理器 |
| `CHANGELOG_V2.0.md` | V2.0 更新日志 |
| `AGENT_CHAIN_DESIGN.md` | Agent 工作链设计 |
| `AGENT_HARDWARE_SOFTWARE_DESIGN.md` | 硬件软件架构设计 |
| `OPENAI_TOOL_CALLING_DESIGN.md` | OpenAI 工具调用设计 |

### 7.2 修改文件

| 文件 | 修改内容 |
|------|----------|
| `AIChatActivity.java` | Agent 模式路由、无限等待、UI 更新 |
| `AgentChatHandler.java` | 集成 AgentSoftwareLayer |
| `ChatModeManager.java` | 移除自动模式切换 |
| `AIConfig.java` | 启用 Agent 模式 |
| `ChatOrchestrator.java` | 上下文兼容性 |
| `LlamaHelper.java` | 推理锁、setOpenCLLoaded |
| `AIService.java` | OpenCL 状态同步、GPU 验证 |
| `InferenceQueue.java` | 实际 LLM 推理 |
| `item_system_message.xml` | 固定宽度布局 |
| `activity_ai_chat.xml` | 快捷工具栏更新 |

## 八、性能优化

### 8.1 LLM 调用优化

| 优化策略 | 说明 |
|----------|------|
| 关闭上下文 | Agent 调用前关闭上下文，避免冲突 |
| 关键词兜底 | LLM 失败时使用关键词识别 |
| 结果缓存 | 相同参数的工具调用可缓存 |

### 8.2 并行执行

```java
// 无依赖的任务可并行执行
List<Future<TaskResult>> futures = tasks.stream()
    .filter(task -> task.getDependencies().isEmpty())
    .map(task -> executor.submit(() -> executeTask(task)))
    .collect(Collectors.toList());
```

## 九、测试验证

### 9.1 测试用例

| 测试场景 | 预期结果 |
|----------|----------|
| 普通对话 | 正常回复 |
| Agent 模式 - 简单任务 | 单步执行，直接回复 |
| Agent 模式 - 复杂任务 | 多步执行，工具调用 |
| Agent 模式 - AI 服务未初始化 | 无限等待，显示进度 |
| 模式切换 | 无缝切换，上下文保留 |
| 推理锁 | 不发生 native 崩溃 |

### 9.2 验证命令

```bash
# 查看日志
adb -s 279b6c51 logcat -d --pid=$(adb -s 279b6c51 shell pidof com.oilquiz.app) | Select-String -Pattern "AgentSoftwareLayer|Step:|IntentRecognizer"
```
