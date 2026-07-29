# Agent 工作链、思考链、执行链设计文档

> 版本: 3.0 | 基于 Hermes Agent + ReAct 架构

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         用户输入                                     │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    工作链 (Workflow Chain)                           │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐           │
│  │ 意图理解 │ → │ 任务规划 │ → │ 执行调度 │ → │ 结果整合 │           │
│  └─────────┘   └─────────┘   └─────────┘   └─────────┘           │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    思考链 (Thinking Chain)                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Thought → Action → Observation → Thought → Action → ...    │   │
│  │         (ReAct 循环，每步可观察中间结果)                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    执行链 (Execution Chain)                          │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐           │
│  │ 工具选择 │ → │ 参数准备 │ → │ 并行执行 │ → │ 结果验证 │           │
│  └─────────┘   └─────────┘   └─────────┘   └─────────┘           │
└─────────────────────────────────────────────────────────────────────┘
```

## 二、工作链 (Workflow Chain)

### 2.1 工作链状态机

```
IDLE ──┬──→ INTENT_RECOGNITION ──→ TASK_PLANNING ──→ EXECUTION_DISPATCH
       │         │                      │                   │
       │         ▼                      ▼                   ▼
       │    INTENT_FAILED          PLAN_FAILED         EXEC_FAILED
       │         │                      │                   │
       └─────────┴──────────────────────┴───────────────────┘
                              ↓
                    ERROR_RECOVERY → RETRY / FALLBACK
                              ↓
                    RESULT_INTEGRATION → RESPONSE
                              ↓
                           COMPLETED
```

### 2.2 工作链核心类

```java
/**
 * 工作链接口 - 定义完整的工作流生命周期
 */
public interface WorkflowChain {
    
    // ========== 阶段 1: 意图理解 ==========
    IntentContext understandIntent(String userInput);
    
    // ========== 阶段 2: 任务规划 ==========
    ExecutionPlan createPlan(IntentContext intent);
    
    // ========== 阶段 3: 执行调度 ==========
    List<Future<ToolResult>> dispatchExecution(ExecutionPlan plan);
    
    // ========== 阶段 4: 结果整合 ==========
    AgentResponse integrateResults(List<ToolResult> results);
    
    // ========== 生命周期回调 ==========
    void onPhaseChanged(ChainPhase phase, ChainContext context);
    void onError(ChainError error);
    void onComplete(AgentResponse response);
}
```

### 2.3 工作链阶段定义

```java
public enum ChainPhase {
    // 阶段 1: 理解
    INTENT_RECOGNITION("意图识别", "分析用户输入，理解核心需求"),
    CONTEXT_ENRICHMENT("上下文增强", "结合历史对话和用户画像丰富理解"),
    
    // 阶段 2: 规划
    TASK_DECOMPOSITION("任务分解", "将复杂任务分解为子任务"),
    TOOL_SELECTION("工具选择", "为每个子任务选择合适的工具"),
    EXECUTION_ORDER("执行排序", "确定任务执行顺序和依赖关系"),
    
    // 阶段 3: 执行
    PRE_EXECUTION_CHECK("执行前检查", "验证参数、权限、资源"),
    PARALLEL_EXECUTION("并行执行", "并发执行无依赖的任务"),
    SEQUENTIAL_EXECUTION("顺序执行", "按依赖关系串行执行"),
    
    // 阶段 4: 整合
    RESULT_VALIDATION("结果验证", "验证执行结果的正确性"),
    RESPONSE_GENERATION("响应生成", "生成最终用户响应"),
    
    // 特殊阶段
    ERROR_RECOVERY("错误恢复", "处理执行过程中的异常"),
    SELF_REFLECTION("自我反思", "评估任务完成质量");
}
```

### 2.4 工作链上下文

```java
/**
 * 工作链上下文 - 贯穿整个工作流的状态容器
 */
public class ChainContext {
    // 用户输入
    private final String userInput;
    private final String sessionId;
    
    // 意图上下文
    private IntentContext intentContext;
    
    // 规划上下文
    private ExecutionPlan executionPlan;
    private List<TaskNode> taskQueue;
    private Map<String, Object> sharedMemory;
    
    // 执行上下文
    private final List<ToolResult> toolResults;
    private final Map<String, Long> executionTimings;
    
    // 约束条件
    private final int maxRetries;
    private final long timeoutMs;
    private final int maxParallelTasks;
    
    // 状态
    private ChainPhase currentPhase;
    private ChainState state;
    private final List<ChainEvent> eventLog;
}
```

## 三、思考链 (Thinking Chain)

### 3.1 思考链架构 (基于 ReAct)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Thinking Chain (ReAct Loop)                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Thought 1: 我需要理解用户想要什么...                      │  │
│  │  Action 1: 调用意图识别工具                               │  │
│  │  Observation 1: 用户想要查询北京天气                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          ↓                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Thought 2: 天气查询需要调用天气API...                     │  │
│  │  Action 2: 调用天气工具 (北京)                             │  │
│  │  Observation 2: 北京今天晴，25°C                          │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          ↓                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Thought 3: 已获得天气数据，可以生成回复了                  │  │
│  │  Action 3: 生成自然语言回复                               │  │
│  │  Observation 3: 回复生成完成                              │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          ↓                                      │
│                    COMPLETED                                    │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 思考链核心类

```java
/**
 * 思考链 - 实现 ReAct 模式的推理循环
 */
public class ThinkingChain {
    
    private static final int MAX_THINKING_STEPS = 10;
    private static final long STEP_TIMEOUT_MS = 30000;
    
    private final ChainContext context;
    private final List<ThinkingStep> steps;
    private final ToolRegistry toolRegistry;
    private final LLMInterface llm;
    
    /**
     * 执行思考循环
     */
    public ThinkingResult think(String userInput) {
        steps.clear();
        int stepCount = 0;
        
        while (stepCount < MAX_THINKING_STEPS) {
            // Step 1: Thought - 让 LLM 思考下一步
            Thought thought = generateThought(userInput, steps);
            steps.add(new ThinkingStep(thought, null, null));
            
            // Step 2: Action - 决定执行什么动作
            Action action = decideAction(thought, availableTools());
            
            // Step 3: Observation - 执行动作并观察结果
            Observation observation = executeAction(action);
            steps.get(steps.size() - 1).setAction(action);
            steps.get(steps.size() - 1).setObservation(observation);
            
            // 检查是否完成
            if (thought.isComplete() || observation.isFinal()) {
                break;
            }
            
            stepCount++;
        }
        
        return new ThinkingResult(steps, buildFinalAnswer());
    }
    
    /**
     * 生成思考 (Thought)
     */
    private Thought generateThought(String input, List<ThinkingStep> history) {
        String prompt = buildThinkingPrompt(input, history);
        String response = llm.generate(prompt, 512, 0.3f);
        return parseThought(response);
    }
    
    /**
     * 决定动作 (Action)
     */
    private Action decideAction(Thought thought, List<Tool> availableTools) {
        String prompt = buildActionPrompt(thought, availableTools);
        String response = llm.generate(prompt, 256, 0.1f);
        return parseAction(response, availableTools);
    }
    
    /**
     * 执行动作并获取观察 (Observation)
     */
    private Observation executeAction(Action action) {
        if (action.getType() == ActionType.FINISH) {
            return Observation.complete(action.getResult());
        }
        
        Tool tool = toolRegistry.getTool(action.getToolName());
        if (tool == null) {
            return Observation.error("Tool not found: " + action.getToolName());
        }
        
        try {
            String result = tool.execute(action.getParameters());
            return Observation.success(result);
        } catch (Exception e) {
            return Observation.error(e.getMessage());
        }
    }
}
```

### 3.3 思考步骤数据结构

```java
/**
 * 思考步骤 - 记录 ReAct 循环中的每一步
 */
public class ThinkingStep {
    private final int stepNumber;
    private final Thought thought;      // 思考内容
    private Action action;              // 执行动作
    private Observation observation;    // 观察结果
    private final long timestamp;
    private final long durationMs;
    
    // 是否是关键决策点
    private boolean isKeyDecision;
    
    // 子思考链 (嵌套推理)
    private List<ThinkingStep> subChain;
}

/**
 * 思考内容
 */
public class Thought {
    private final String content;        // 思考文本
    private final ThoughtType type;      // 思考类型
    private final double confidence;     // 置信度
    private final boolean isComplete;    // 是否完成
    
    public enum ThoughtType {
        ANALYSIS,      // 分析型思考
        PLANNING,      // 规划型思考
        REASONING,     // 推理型思考
        REFLECTION,    // 反思型思考
        DECISION       // 决策型思考
    }
}

/**
 * 执行动作
 */
public class Action {
    private final String toolName;       // 工具名称
    private final Map<String, Object> parameters;  // 参数
    private final ActionType type;       // 动作类型
    
    public enum ActionType {
        TOOL_CALL,    // 调用工具
        THINK,        // 继续思考
        FINISH,       // 完成任务
        ASK_USER,     // 询问用户
        RETRY         // 重试
    }
}

/**
 * 观察结果
 */
public class Observation {
    private final String content;        // 观察内容
    private final boolean success;       // 是否成功
    private final boolean isFinal;       // 是否最终结果
    
    // 结构化数据
    private final Map<String, Object> data;
}
```

### 3.4 思考链 Prompt 模板

```java
/**
 * 思考链 Prompt 构建器
 */
public class ThinkingPromptBuilder {
    
    /**
     * 构建思考 Prompt
     */
    public static String buildThoughtPrompt(String input, List<ThinkingStep> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能助手，正在思考如何解决用户的问题。\n\n");
        sb.append("【用户输入】\n").append(input).append("\n\n");
        
        if (!history.isEmpty()) {
            sb.append("【之前的思考】\n");
            for (ThinkingStep step : history) {
                sb.append("Thought: ").append(step.getThought().getContent()).append("\n");
                if (step.getObservation() != null) {
                    sb.append("Observation: ").append(step.getObservation().getContent()).append("\n");
                }
                sb.append("\n");
            }
        }
        
        sb.append("【可用工具】\n");
        sb.append("- weather: 查询天气\n");
        sb.append("- search: 搜索信息\n");
        sb.append("- calculator: 数学计算\n");
        sb.append("- translate: 翻译\n");
        sb.append("- database: 数据库查询\n\n");
        
        sb.append("【要求】\n");
        sb.append("1. 分析用户真正的需求\n");
        sb.append("2. 决定下一步应该做什么\n");
        sb.append("3. 如果需要工具，说明使用哪个工具和参数\n");
        sb.append("4. 如果已经获得足够信息，准备生成最终回复\n\n");
        sb.append("请以以下格式输出：\n");
        sb.append("Thought: [你的思考过程]\n");
        sb.append("Action: [工具名(参数)] 或 FINISH\n");
        
        return sb.toString();
    }
    
    /**
     * 构建动作决策 Prompt
     */
    public static String buildActionPrompt(Thought thought, List<Tool> availableTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据思考结果，决定下一步动作。\n\n");
        sb.append("【思考结果】\n").append(thought.getContent()).append("\n\n");
        
        sb.append("【可用工具】\n");
        for (Tool tool : availableTools) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        
        sb.append("\n【输出格式】\n");
        sb.append("TOOL: 工具名 | 参数1=值1, 参数2=值2\n");
        sb.append("或\n");
        sb.append("FINISH: 最终回答内容\n");
        
        return sb.toString();
    }
}
```

## 四、执行链 (Execution Chain)

### 4.1 执行链架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Execution Chain                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  Task Scheduler                         │   │
│  │   - 依赖分析 (DAG)                                      │   │
│  │   - 并行度控制                                           │   │
│  │   - 优先级排序                                           │   │
│  └────────────────────────────┬────────────────────────────┘   │
│                               ↓                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  Execution Pool                         │   │
│  │   ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐                  │   │
│  │   │Task1│  │Task2│  │Task3│  │Task4│  ...              │   │
│  │   └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘                  │   │
│  └──────┼────────┼────────┼────────┼──────────────────────┘   │
│         ↓        ↓        ↓        ↓                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  Tool Executor                          │   │
│  │   - 参数验证                                             │   │
│  │   - 权限检查                                             │   │
│  │   - 超时控制                                             │   │
│  │   - 重试机制                                             │   │
│  └────────────────────────────┬────────────────────────────┘   │
│                               ↓                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  Result Collector                       │   │
│  │   - 结果聚合                                             │   │
│  │   - 错误处理                                             │   │
│  │   - 质量验证                                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 执行链核心类

```java
/**
 * 执行链 - 管理工具调用的执行
 */
public class ExecutionChain {
    
    private static final int MAX_PARALLEL_TASKS = 4;
    private static final long TASK_TIMEOUT_MS = 30000;
    private static final int MAX_RETRIES = 3;
    
    private final ExecutorService executor;
    private final ToolRegistry toolRegistry;
    private final ResultCache resultCache;
    
    /**
     * 执行任务计划
     */
    public ExecutionResult execute(ExecutionPlan plan, ChainContext context) {
        // 1. 构建任务 DAG
        TaskDAG dag = buildTaskDAG(plan.getTasks());
        
        // 2. 按层级执行
        List<ExecutionLayer> layers = dag.topologicalSort();
        
        ExecutionResult result = new ExecutionResult();
        
        for (ExecutionLayer layer : layers) {
            // 并行执行同一层级的任务
            List<Future<ToolResult>> futures = new ArrayList<>();
            
            for (TaskNode task : layer.getTasks()) {
                // 检查缓存
                String cacheKey = task.getCacheKey();
                ToolResult cached = resultCache.get(cacheKey);
                if (cached != null) {
                    result.addResult(task.getId(), cached);
                    continue;
                }
                
                // 提交到线程池
                Future<ToolResult> future = executor.submit(() -> 
                    executeTask(task, context)
                );
                futures.add(future);
            }
            
            // 等待当前层级完成
            waitForLayer(futures, result, layer);
        }
        
        return result;
    }
    
    /**
     * 执行单个任务
     */
    private ToolResult executeTask(TaskNode task, ChainContext context) {
        String toolName = task.getToolName();
        Map<String, Object> params = task.getParameters();
        
        // 1. 参数验证
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return ToolResult.error("Tool not found: " + toolName);
        }
        
        ValidationResult validation = tool.validate(params);
        if (!validation.isValid()) {
            return ToolResult.error("Invalid parameters: " + validation.getMessage());
        }
        
        // 2. 权限检查
        if (!checkPermission(tool, context)) {
            return ToolResult.error("Permission denied");
        }
        
        // 3. 执行 (带重试)
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                long startTime = System.currentTimeMillis();
                String result = tool.execute(params);
                long duration = System.currentTimeMillis() - startTime;
                
                ToolResult toolResult = ToolResult.success(result, duration);
                resultCache.put(task.getCacheKey(), toolResult, 300_000); // 5分钟缓存
                
                return toolResult;
                
            } catch (TimeoutException e) {
                if (attempt == MAX_RETRIES) {
                    return ToolResult.error("Timeout after " + MAX_RETRIES + " retries");
                }
                Thread.sleep(1000 * (attempt + 1)); // 指数退避
                
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    return ToolResult.error("Execution failed: " + e.getMessage());
                }
            }
        }
        
        return ToolResult.error("Unexpected error");
    }
    
    /**
     * 等待层级完成
     */
    private void waitForLayer(List<Future<ToolResult>> futures, 
                              ExecutionResult result, 
                              ExecutionLayer layer) {
        for (int i = 0; i < futures.size(); i++) {
            try {
                ToolResult toolResult = futures.get(i).get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                result.addResult(layer.getTasks().get(i).getId(), toolResult);
                
            } catch (TimeoutException e) {
                result.addResult(layer.getTasks().get(i).getId(), 
                    ToolResult.error("Timeout"));
                    
            } catch (Exception e) {
                result.addResult(layer.getTasks().get(i).getId(), 
                    ToolResult.error("Execution error: " + e.getMessage()));
            }
        }
    }
}
```

### 4.3 任务 DAG

```java
/**
 * 任务有向无环图 - 管理任务依赖关系
 */
public class TaskDAG {
    
    private final Map<String, TaskNode> nodes;
    private final Map<String, Set<String>> edges; // 依赖关系
    
    /**
     * 添加任务节点
     */
    public void addNode(TaskNode node) {
        nodes.put(node.getId(), node);
        edges.put(node.getId(), new HashSet<>());
    }
    
    /**
     * 添加依赖关系
     */
    public void addDependency(String taskId, String dependsOn) {
        edges.get(taskId).add(dependsOn);
    }
    
    /**
     * 拓扑排序 - 确定执行顺序
     */
    public List<ExecutionLayer> topologicalSort() {
        List<ExecutionLayer> layers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Map<String, Integer> inDegree = calculateInDegree();
        
        while (visited.size() < nodes.size()) {
            ExecutionLayer layer = new ExecutionLayer();
            
            // 找出入度为0的节点
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0 && !visited.contains(entry.getKey())) {
                    layer.addTask(nodes.get(entry.getKey()));
                    visited.add(entry.getKey());
                }
            }
            
            if (layer.isEmpty()) {
                throw new IllegalStateException("Cycle detected in task dependencies");
            }
            
            // 更新入度
            for (String taskId : layer.getTaskIds()) {
                for (String dependent : getDependents(taskId)) {
                    inDegree.merge(dependent, -1, Integer::sum);
                }
            }
            
            layers.add(layer);
        }
        
        return layers;
    }
    
    /**
     * 检测是否可以并行执行
     */
    public boolean canParallel(List<TaskNode> tasks) {
        // 同一层级的任务可以并行执行
        Set<String> taskIds = tasks.stream()
            .map(TaskNode::getId)
            .collect(Collectors.toSet());
        
        for (TaskNode task : tasks) {
            for (String dep : edges.get(task.getId())) {
                if (taskIds.contains(dep)) {
                    return false; // 有循环依赖
                }
            }
        }
        return true;
    }
}
```

### 4.4 执行计划

```java
/**
 * 执行计划 - 定义任务执行策略
 */
public class ExecutionPlan {
    
    private final List<TaskNode> tasks;
    private final ExecutionStrategy strategy;
    private final Map<String, Object> metadata;
    
    public enum ExecutionStrategy {
        SEQUENTIAL,     // 顺序执行
        PARALLEL,       // 并行执行
        PIPELINE,       // 流水线执行
        ADAPTIVE        // 自适应 (根据依赖自动决定)
    }
    
    /**
     * 任务节点
     */
    public static class TaskNode {
        private final String id;
        private final String toolName;
        private final Map<String, Object> parameters;
        private final List<String> dependencies;
        private final int priority;
        private final long timeoutMs;
        private final int maxRetries;
        
        // 缓存键
        public String getCacheKey() {
            return toolName + ":" + parameters.hashCode();
        }
    }
}
```

## 五、完整流程示例

### 5.1 用户输入: "帮我查一下北京今天的天气，如果下雨就提醒我带伞"

```
┌─────────────────────────────────────────────────────────────────┐
│                    工作链执行流程                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Phase 1: 意图理解                                              │
│  ├─ 意图识别: WEATHER (天气查询)                                │
│  ├─ 实体提取: 北京, 今天                                        │
│  └─ 条件判断: 如果下雨 → 提醒带伞                               │
│                                                                 │
│  Phase 2: 任务规划                                              │
│  ├─ Task 1: 查询北京天气 (无依赖)                               │
│  ├─ Task 2: 判断是否下雨 (依赖 Task 1)                          │
│  └─ Task 3: 生成提醒 (依赖 Task 2)                              │
│                                                                 │
│  Phase 3: 执行                                                  │
│  ├─ Layer 1: [Task 1] → 调用天气API                            │
│  ├─ Layer 2: [Task 2] → 逻辑判断                               │
│  └─ Layer 3: [Task 3] → 生成回复                               │
│                                                                 │
│  Phase 4: 结果整合                                              │
│  └─ 输出: "北京今天多云，气温25°C，不会下雨，不需要带伞"          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    思考链执行流程                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Thought 1: 用户想查询北京今天的天气，并根据天气决定是否带伞。    │
│             我需要先获取天气数据。                                │
│  Action 1: weather(北京)                                        │
│  Observation 1: 北京今天：多云，25°C，湿度60%，无降水            │
│                                                                 │
│  Thought 2: 天气数据显示不会下雨。用户说如果下雨就提醒带伞，     │
│             现在不下雨，所以不需要提醒带伞。                      │
│  Action 2: FINISH                                              │
│  Observation 2: 准备生成最终回复                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 复杂任务示例: "分析我的学习数据，找出薄弱环节，生成针对性练习题"

```
┌─────────────────────────────────────────────────────────────────┐
│                    任务分解                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Task 1: 获取学习历史数据 (database)                            │
│  Task 2: 分析答题正确率 (calculator + analysis)                 │
│  Task 3: 识别薄弱知识点 (analysis)                              │
│  Task 4: 生成针对性题目 (quiz_generator)                        │
│  Task 5: 格式化输出 (formatter)                                 │
│                                                                 │
│  依赖关系:                                                      │
│  Task 1 → Task 2 → Task 3 → Task 4 → Task 5                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    思考链过程                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Thought 1: 用户想要分析学习数据并生成练习题。                   │
│             我需要先获取用户的学习历史记录。                      │
│  Action 1: database(query="学习记录", userId=current)           │
│  Observation 1: 获取到 500 条答题记录                           │
│                                                                 │
│  Thought 2: 有了答题数据，我需要计算各知识点的正确率。           │
│             数学正确率85%，英语正确率60%，物理正确率45%。         │
│  Action 2: calculator(formula="按知识点分组统计正确率")          │
│  Observation 2: 物理是最薄弱的科目，正确率仅45%                 │
│                                                                 │
│  Thought 3: 物理是薄弱环节，我需要生成针对性的物理练习题。       │
│             用户最近在学力学和电学。                              │
│  Action 3: quiz_generator(subject="物理", topics=["力学","电学"],│
│                           difficulty="medium", count=5)         │
│  Observation 3: 生成了5道物理练习题                             │
│                                                                 │
│  Thought 4: 已经获得了分析结果和练习题，可以生成最终报告了。     │
│  Action 4: FINISH                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 六、实现要点

### 6.1 关键设计原则

| 原则 | 说明 |
|------|------|
| **可观测性** | 每一步思考和执行都可追踪 |
| **可恢复性** | 任务失败时可重试或回退 |
| **并行性** | 无依赖的任务可并行执行 |
| **缓存性** | 相同请求结果可缓存复用 |
| **自适应性** | 根据执行结果动态调整策略 |

### 6.2 与现有架构的集成

```
现有组件                     新增组件
─────────────────────────────────────────
SmartIntentRecognizer    →   ThinkingChain (思考链)
TaskDecompositionManager →   ExecutionChain (执行链)
AgentChatHandler         →   WorkflowChain (工作链)
AIToolManager            →   TaskDAG (任务图)
```

### 6.3 性能优化

1. **思考链缓存**: 相同意图的思考过程可缓存
2. **工具结果缓存**: 相同参数的工具调用可缓存
3. **并行执行**: 无依赖的任务并行执行
4. **提前终止**: 满足条件时提前结束思考循环
5. **流式输出**: 思考过程实时展示给用户
