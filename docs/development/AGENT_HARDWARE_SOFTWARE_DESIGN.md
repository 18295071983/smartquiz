# Agent 功能架构设计 - 硬件与软件分离

> 版本: 1.0 | 基于本地 LLM + 工具系统

## 一、硬件层（本地设备能力）

### 1.1 本地 LLM 推理引擎

```
┌─────────────────────────────────────────────────────────────────┐
│                    硬件层 - 本地 LLM 推理                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  llama.cpp 推理引擎                                      │   │
│  │  - GGUF 模型加载                                         │   │
│  │  - OpenCL/Vulkan GPU 加速                               │   │
│  │  - 流式 token 生成                                      │   │
│  │  - KV Cache 管理                                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  LlamaHelper JNI 接口                                   │   │
│  │  - initModel() / release()                              │   │
│  │  - generate() / generateStream()                        │   │
│  │  - chatSend() / chatContext 管理                        │   │
│  │  - GPU 层数设置 / 内存管理                               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  推理锁 (InferenceLock)                                 │   │
│  │  - ReentrantReadWriteLock                                │   │
│  │  - 防止 generate/chatSend 并发冲突                       │   │
│  │  - 30秒超时保护                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 硬件能力接口

```java
/**
 * 硬件层接口 - 本地 LLM 推理能力
 */
public interface HardwareInferenceLayer {
    
    /**
     * 推理接口 - 生成文本
     */
    String generate(String prompt, int maxTokens, float temperature);
    
    /**
     * 流式推理接口
     */
    void generateStream(String prompt, int maxTokens, TokenCallback callback);
    
    /**
     * 聊天上下文推理
     */
    void chatSend(String message, int maxTokens, boolean enableThinking, TokenCallback callback);
    
    /**
     * 获取推理锁（防止并发）
     */
    boolean acquireInferenceLock();
    void releaseInferenceLock();
    
    /**
     * 模型状态查询
     */
    boolean isModelInitialized();
    int getGPULayers();
    long getMemoryUsage();
}
```

## 二、软件层（Agent 逻辑）

### 2.1 软件层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    软件层 - Agent 逻辑                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  意图识别模块 (Intent Recognition)                       │   │
│  │  - LLM 意图识别 (主)                                     │   │
│  │  - 规则匹配 (辅)                                        │   │
│  │  - 多意图检测                                           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  复杂度分析模块 (Complexity Analysis)                    │   │
│  │  - LLM 复杂度评估                                       │   │
│  │  - 任务难度分级                                         │   │
│  │  - 推理模式选择                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  任务分解模块 (Task Decomposition)                       │   │
│  │  - LLM 任务分解                                         │   │
│  │  - 子任务规划                                           │   │
│  │  - 依赖关系分析                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  执行引擎 (Execution Engine)                             │   │
│  │  - ReAct 循环                                           │   │
│  │  - 工具调用管理                                         │   │
│  │  - 结果验证                                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  思考链引擎 (Thinking Chain Engine)                      │   │
│  │  - Thought → Action → Observation 循环                   │   │
│  │  - 自我反思                                             │   │
│  │  - 决策优化                                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  结果整合模块 (Result Integration)                       │   │
│  │  - 多工具结果合并                                       │   │
│  │  - 最终回复生成                                         │   │
│  │  - 质量评估                                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 软件层核心类

```java
/**
 * Agent 软件层 - 管理所有 Agent 逻辑
 */
public class AgentSoftwareLayer {
    
    // ========== 意图识别 ==========
    private final IntentRecognizer intentRecognizer;
    
    // ========== 复杂度分析 ==========
    private final ComplexityAnalyzer complexityAnalyzer;
    
    // ========== 任务分解 ==========
    private final TaskDecomposer taskDecomposer;
    
    // ========== 执行引擎 ==========
    private final ExecutionEngine executionEngine;
    
    // ========== 思考链引擎 ==========
    private final ThinkingChainEngine thinkingChainEngine;
    
    // ========== 结果整合 ==========
    private final ResultIntegrator resultIntegrator;
    
    // ========== 硬件层引用 ==========
    private final HardwareInferenceLayer hardwareLayer;
    
    /**
     * 处理用户消息
     */
    public AgentResponse processMessage(String userMessage) {
        // Step 1: 意图识别
        IntentResult intent = intentRecognizer.recognize(userMessage);
        
        // Step 2: 复杂度分析
        ComplexityLevel complexity = complexityAnalyzer.analyze(userMessage, intent);
        
        // Step 3: 任务分解
        TaskPlan taskPlan = taskDecomposer.decompose(userMessage, intent, complexity);
        
        // Step 4: 执行
        ExecutionResult executionResult = executionEngine.execute(taskPlan);
        
        // Step 5: 思考链驱动
        ThinkingChain chain = thinkingChainEngine.process(executionResult);
        
        // Step 6: 结果整合
        return resultIntegrator.integrate(chain, executionResult);
    }
}
```

## 三、硬件与软件交互

### 3.1 交互流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    硬件-软件交互流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  用户消息                                                       │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 意图识别                                        │   │
│  │  ├─ 调用硬件层: LLM.generate(intentPrompt)              │   │
│  │  └─ 解析意图: WEATHER, SEARCH, etc.                     │   │
│  └─────────────────────────────────────────────────────────┘   │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 复杂度分析                                      │   │
│  │  ├─ 调用硬件层: LLM.generate(complexityPrompt)          │   │
│  │  └─ 评估复杂度: SIMPLE, MEDIUM, COMPLEX                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 任务分解                                        │   │
│  │  ├─ 调用硬件层: LLM.generate(decompositionPrompt)       │   │
│  │  └─ 生成任务计划: [Task1, Task2, Task3]                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 执行引擎                                        │   │
│  │  ├─ 调用硬件层: LLM.generate(executePrompt)             │   │
│  │  ├─ 检测工具调用                                         │   │
│  │  ├─ 执行工具                                             │   │
│  │  └─ 获取结果                                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 思考链引擎                                      │   │
│  │  ├─ Thought: 分析工具结果                                │   │
│  │  ├─ Action: 决定下一步                                   │   │
│  │  └─ Observation: 观察结果                                │   │
│  └─────────────────────────────────────────────────────────┘   │
│       ↓                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  软件层: 结果整合                                        │   │
│  │  ├─ 调用硬件层: LLM.generate(finalPrompt)               │   │
│  │  └─ 生成最终回复                                         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 硬件层调用统计

| 操作 | 调用次数 | 说明 |
|------|----------|------|
| 意图识别 | 1 次 | LLM 生成意图分类 |
| 复杂度分析 | 1 次 | LLM 评估任务复杂度 |
| 任务分解 | 1 次 | LLM 生成任务计划 |
| 执行循环 | N 次 | 每个任务步骤调用 LLM |
| 思考链 | M 次 | 每次思考调用 LLM |
| 结果整合 | 1 次 | LLM 生成最终回复 |

**总 LLM 调用次数 = 3 + N + M**

## 四、详细设计

### 4.1 意图识别模块

```java
/**
 * 意图识别模块 - 使用 LLM 识别用户意图
 */
public class IntentRecognizer {
    
    private final HardwareInferenceLayer hardware;
    
    /**
     * 识别用户意图
     * 使用 LLM 进行意图分类
     */
    public IntentResult recognize(String userMessage) {
        // 构建意图识别 Prompt
        String prompt = buildIntentPrompt(userMessage);
        
        // 调用 LLM 识别意图
        String response = hardware.generate(prompt, 100, 0.2f);
        
        // 解析意图
        return parseIntentResponse(response);
    }
    
    private String buildIntentPrompt(String userMessage) {
        return "你是一个意图识别助手。\n\n" +
               "用户消息: " + userMessage + "\n\n" +
               "请识别用户意图，输出格式:\n" +
               "INTENT: 意图类型\n" +
               "CONFIDENCE: 置信度(0-1)\n" +
               "ENTITY: 关键实体\n\n" +
               "意图类型: WEATHER, SEARCH, CALCULATOR, QUIZ, CHAT, etc.";
    }
}
```

### 4.2 复杂度分析模块

```java
/**
 * 复杂度分析模块 - 使用 LLM 评估任务复杂度
 */
public class ComplexityAnalyzer {
    
    private final HardwareInferenceLayer hardware;
    
    /**
     * 分析任务复杂度
     * 使用 LLM 评估任务难度
     */
    public ComplexityLevel analyze(String userMessage, IntentResult intent) {
        // 构建复杂度分析 Prompt
        String prompt = buildComplexityPrompt(userMessage, intent);
        
        // 调用 LLM 分析复杂度
        String response = hardware.generate(prompt, 100, 0.2f);
        
        // 解析复杂度
        return parseComplexityResponse(response);
    }
    
    private String buildComplexityPrompt(String userMessage, IntentResult intent) {
        return "你是一个任务复杂度分析助手。\n\n" +
               "用户消息: " + userMessage + "\n" +
               "识别意图: " + intent.type + "\n\n" +
               "请分析任务复杂度，输出格式:\n" +
               "LEVEL: SIMPLE/MEDIUM/COMPLEX\n" +
               "REASON: 分析理由\n" +
               "STEPS: 预估步骤数\n\n" +
               "判断标准:\n" +
               "- SIMPLE: 单步任务，直接回答\n" +
               "- MEDIUM: 需要1-2个工具调用\n" +
               "- COMPLEX: 需要多个工具调用或复杂推理";
    }
}
```

### 4.3 任务分解模块

```java
/**
 * 任务分解模块 - 使用 LLM 分解复杂任务
 */
public class TaskDecomposer {
    
    private final HardwareInferenceLayer hardware;
    
    /**
     * 分解任务
     * 使用 LLM 将复杂任务分解为子任务
     */
    public TaskPlan decompose(String userMessage, IntentResult intent, ComplexityLevel complexity) {
        // 简单任务不需要分解
        if (complexity == ComplexityLevel.SIMPLE) {
            return TaskPlan.simple(userMessage, intent);
        }
        
        // 构建任务分解 Prompt
        String prompt = buildDecompositionPrompt(userMessage, intent, complexity);
        
        // 调用 LLM 分解任务
        String response = hardware.generate(prompt, 300, 0.3f);
        
        // 解析任务计划
        return parseDecompositionResponse(response);
    }
    
    private String buildDecompositionPrompt(String userMessage, IntentResult intent, 
                                            ComplexityLevel complexity) {
        return "你是一个任务分解助手。\n\n" +
               "用户消息: " + userMessage + "\n" +
               "识别意图: " + intent.type + "\n" +
               "复杂度: " + complexity + "\n\n" +
               "请将任务分解为可执行的子任务，输出格式:\n" +
               "TASKS:\n" +
               "1. [子任务1] - 工具: 工具名, 参数: {...}\n" +
               "2. [子任务2] - 工具: 工具名, 参数: {...}\n" +
               "...\n\n" +
               "可用工具: weather, search, calculator, database, file, translate";
    }
}
```

### 4.4 执行引擎

```java
/**
 * 执行引擎 - 执行任务计划
 */
public class ExecutionEngine {
    
    private final HardwareInferenceLayer hardware;
    private final ToolRegistry toolRegistry;
    
    /**
     * 执行任务计划
     */
    public ExecutionResult execute(TaskPlan taskPlan) {
        ExecutionResult result = new ExecutionResult();
        
        for (Task task : taskPlan.getTasks()) {
            // 检查依赖
            if (!checkDependencies(task, result)) {
                result.addError("任务依赖未满足: " + task.getId());
                continue;
            }
            
            // 执行任务
            TaskResult taskResult = executeTask(task);
            result.addTaskResult(task.getId(), taskResult);
            
            // 检查是否需要停止
            if (taskResult.isError() && task.isCritical()) {
                result.setError("关键任务失败: " + task.getId());
                break;
            }
        }
        
        return result;
    }
    
    /**
     * 执行单个任务
     */
    private TaskResult executeTask(Task task) {
        // 检查是否需要工具调用
        if (task.needsTool()) {
            return executeWithTool(task);
        } else {
            return executeWithLLM(task);
        }
    }
    
    /**
     * 使用工具执行任务
     */
    private TaskResult executeWithTool(Task task) {
        Tool tool = toolRegistry.getTool(task.getToolName());
        if (tool == null) {
            return TaskResult.error("工具不存在: " + task.getToolName());
        }
        
        try {
            String result = tool.execute(task.getParameters());
            return TaskResult.success(result);
        } catch (Exception e) {
            return TaskResult.error("工具执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 使用 LLM 执行任务
     */
    private TaskResult executeWithLLM(Task task) {
        String prompt = task.getPrompt();
        String response = hardware.generate(prompt, 500, 0.7f);
        return TaskResult.success(response);
    }
}
```

### 4.5 思考链引擎

```java
/**
 * 思考链引擎 - 驱动 Agent 的思考过程
 */
public class ThinkingChainEngine {
    
    private final HardwareInferenceLayer hardware;
    private static final int MAX_THINKING_STEPS = 10;
    
    /**
     * 处理执行结果，生成思考链
     */
    public ThinkingChain process(ExecutionResult executionResult) {
        ThinkingChain chain = new ThinkingChain();
        int stepCount = 0;
        
        while (stepCount < MAX_THINKING_STEPS) {
            // Thought: 分析当前状态
            Thought thought = generateThought(executionResult, chain);
            chain.addStep(new ThinkingStep(thought));
            
            // Action: 决定下一步
            Action action = decideAction(thought, executionResult);
            
            // Observation: 执行动作并观察
            Observation observation = executeAction(action);
            chain.getLastStep().setAction(action);
            chain.getLastStep().setObservation(observation);
            
            // 检查是否完成
            if (thought.isComplete() || observation.isFinal()) {
                break;
            }
            
            stepCount++;
        }
        
        return chain;
    }
    
    /**
     * 生成思考
     */
    private Thought generateThought(ExecutionResult result, ThinkingChain chain) {
        String prompt = buildThoughtPrompt(result, chain);
        String response = hardware.generate(prompt, 200, 0.3f);
        return parseThoughtResponse(response);
    }
    
    /**
     * 决定动作
     */
    private Action decideAction(Thought thought, ExecutionResult result) {
        String prompt = buildActionPrompt(thought, result);
        String response = hardware.generate(prompt, 100, 0.1f);
        return parseActionResponse(response);
    }
}
```

### 4.6 结果整合模块

```java
/**
 * 结果整合模块 - 整合所有结果生成最终回复
 */
public class ResultIntegrator {
    
    private final HardwareInferenceLayer hardware;
    
    /**
     * 整合结果生成最终回复
     */
    public AgentResponse integrate(ThinkingChain chain, ExecutionResult executionResult) {
        // 构建整合 Prompt
        String prompt = buildIntegrationPrompt(chain, executionResult);
        
        // 调用 LLM 生成最终回复
        String response = hardware.generate(prompt, 1000, 0.7f);
        
        // 构建响应
        return new AgentResponse(response, chain, executionResult);
    }
    
    private String buildIntegrationPrompt(ThinkingChain chain, ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能助手，请根据以下信息生成最终回复。\n\n");
        
        // 思考链
        sb.append("【思考过程】\n");
        for (ThinkingStep step : chain.getSteps()) {
            sb.append("Thought: ").append(step.getThought()).append("\n");
            if (step.getObservation() != null) {
                sb.append("Observation: ").append(step.getObservation()).append("\n");
            }
        }
        
        // 执行结果
        sb.append("\n【执行结果】\n");
        for (Map.Entry<String, TaskResult> entry : result.getTaskResults().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue().getResult()).append("\n");
        }
        
        sb.append("\n请基于以上信息，生成清晰、完整、有帮助的回复。");
        
        return sb.toString();
    }
}
```

## 五、数据流设计

### 5.1 数据结构

```java
/**
 * 意图识别结果
 */
public class IntentResult {
    public String type;           // 意图类型
    public double confidence;     // 置信度
    public String entity;         // 关键实体
    public Map<String, Object> params;  // 参数
}

/**
 * 复杂度级别
 */
public enum ComplexityLevel {
    SIMPLE,    // 简单任务
    MEDIUM,    // 中等任务
    COMPLEX    // 复杂任务
}

/**
 * 任务计划
 */
public class TaskPlan {
    public List<Task> tasks;      // 任务列表
    public String originalMessage; // 原始消息
    public IntentResult intent;   // 意图
}

/**
 * 任务
 */
public class Task {
    public String id;             // 任务ID
    public String description;    // 任务描述
    public String toolName;       // 工具名称
    public Map<String, Object> params;  // 工具参数
    public List<String> dependencies;   // 依赖任务
    public boolean isCritical;    // 是否关键任务
}

/**
 * 思考步骤
 */
public class ThinkingStep {
    public Thought thought;       // 思考内容
    public Action action;         // 执行动作
    public Observation observation; // 观察结果
    public boolean isComplete;    // 是否完成
}

/**
 * Agent 响应
 */
public class AgentResponse {
    public String finalAnswer;    // 最终回复
    public ThinkingChain chain;   // 思考链
    public ExecutionResult result; // 执行结果
    public AgentStats stats;      // 统计信息
}
```

## 六、性能优化

### 6.1 LLM 调用优化

| 优化策略 | 说明 |
|----------|------|
| Prompt 缓存 | 相同意图的 Prompt 可缓存 |
| 批量推理 | 多个子任务批量调用 LLM |
| 提前终止 | 满足条件时提前结束思考链 |
| 结果缓存 | 相同参数的工具调用可缓存 |

### 6.2 并行执行

```java
/**
 * 并行执行无依赖的任务
 */
public List<TaskResult> executeParallel(List<Task> tasks) {
    List<Future<TaskResult>> futures = tasks.stream()
        .filter(task -> task.getDependencies().isEmpty())
        .map(task -> executor.submit(() -> executeTask(task)))
        .collect(Collectors.toList());
    
    return futures.stream()
        .map(f -> {
            try {
                return f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                return TaskResult.error("Timeout");
            }
        })
        .collect(Collectors.toList());
}
```

## 七、与现有系统集成

### 7.1 集成点

```
现有组件                     新增组件
─────────────────────────────────────────
AIService (硬件层)        →   HardwareInferenceLayer (接口)
SmartIntentRecognizer     →   IntentRecognizer (软件层)
AgentChatHandler          →   AgentSoftwareLayer (软件层)
UnifiedAgentEngine        →   ThinkingChainEngine (软件层)
AIToolManager             →   ToolRegistry (软件层)
```

### 7.2 数据流

```
用户消息
    ↓
AIChatActivity.processChatMessageWithAgent()
    ↓
AgentChatHandler.startAgentLoop()
    ↓
UnifiedAgentEngine.execute()
    ↓
┌─────────────────────────────────────────┐
│  软件层处理                              │
│  IntentRecognizer → ComplexityAnalyzer  │
│  → TaskDecomposer → ExecutionEngine     │
│  → ThinkingChainEngine → ResultIntegrator│
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  硬件层调用                              │
│  LLM.generate() / LLM.chatSend()       │
└─────────────────────────────────────────┘
    ↓
AgentResponse
    ↓
UI 显示
```
