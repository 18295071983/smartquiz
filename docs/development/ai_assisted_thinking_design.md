# AI 辅助思考模式设计文档

> 版本: 1.0 | 更新日期: 2026-07-29

## 一、设计目标

### 1.1 核心概念

**AI 辅助思考** 是一种全新的交互模式，与现有的 Agent 模式形成互补：

| 模式 | 定位 | 交互方式 |
|------|------|----------|
| **Agent 模式** | AI 替用户做事 | 用户下达任务 → AI 分析 → 执行工具 → 返回结果 |
| **辅助思考模式** | AI 帮用户想问题 | 用户描述问题 → AI 引导思考 → 提供建议 → 用户决策 |

### 1.2 应用场景

- 🤔 **问题分析**：帮用户拆解复杂问题，找到核心要点
- 💡 **头脑风暴**：提供多角度思考方向，激发创意
- 🔍 **苏格拉底式提问**：通过层层递进的问题引导用户思考
- 🎯 **决策辅助**：分析利弊，提供决策框架
- 📝 **思维整理**：将零散想法整理成结构化思路
- 🧭 **方向导航**：在用户迷茫时提供思考路径

### 1.3 核心原则

1. **用户主导**：AI 不替用户做决策，而是提供思考辅助
2. **引导而非替代**：通过问题引导思考，而非直接给出答案
3. **多层深度**：从浅层分析到深层反思，支持渐进式思考
4. **实时互动**：支持多轮对话，逐步深入

## 二、架构设计

### 2.1 整体架构

```
用户输入
    ↓
ThinkingModeHandler（模式入口）
    ↓
ThinkingAssistantEngine（思考辅助引擎）
    ↓
┌─────────────────────────────────────────────────────────┐
│ 1. ThinkingAnalyzer    — 分析用户当前思考状态           │
│ 2. QuestionGenerator   — 生成引导性问题                 │
│ 3. PerspectiveEngine   — 提供不同思考角度               │
│ 4. ReflectionEngine    — 引导深度反思                   │
│ 5. Summarizer          — 整理和总结思考过程             │
└─────────────────────────────────────────────────────────┘
    ↓
思考辅助响应（问题/建议/总结）
```

### 2.2 与现有架构的关系

```
ChatModeManager
    ├── NORMAL          → NormalModeHandler
    ├── DEEP_THINKING   → DeepThinkingModeHandler
    ├── CREATIVE        → CreativeWritingModeHandler
    ├── AGENT           → AgentModeHandler → AgentExecutionEngine
    └── THINKING_ASSIST → ThinkingModeHandler → ThinkingAssistantEngine (新增)
```

### 2.3 核心组件

#### ThinkingAssistantEngine

思考辅助引擎，负责：
- 分析用户输入的思考状态
- 生成引导性问题
- 提供多视角分析
- 引导深度反思
- 整理思考过程

#### ThinkingAnalyzer

分析用户当前的思考状态：
- 问题清晰度
- 思考深度
- 信息完备度
- 情绪状态

#### QuestionGenerator

生成引导性问题：
- 澄清型问题：帮用户明确问题
- 拓展型问题：引导用户深入思考
- 反思型问题：促使用户反思假设
- 挑战型问题：挑战用户的固有思维

#### PerspectiveEngine

提供不同思考角度：
- 宏观/微观视角
- 短期/长期影响
- 利益相关方分析
- 正反两面分析

#### ReflectionEngine

引导深度反思：
- 假设检验
- 逻辑验证
- 遗漏检测
- 偏见识别

#### Summarizer

整理思考过程：
- 关键观点提炼
- 逻辑链条整理
- 待解决问题列表
- 下一步建议

## 三、交互流程

### 3.1 标准思考流程

```
用户: "我在考虑要不要换工作"
    ↓
ThinkingAnalyzer → 识别为"决策类问题"，清晰度中等
    ↓
QuestionGenerator → 生成澄清型问题
AI: "换工作这个想法出现多久了？是什么触发了你开始考虑这件事？"
    ↓
用户: "最近工作压力太大，已经考虑了一个月"
    ↓
PerspectiveEngine → 提供多角度分析
AI: "可以从几个角度来看待这个问题：
     1. 职业发展：现在的岗位还有成长空间吗？
     2. 生活质量：压力是否影响了你的身心健康？
     3. 财务安全：换工作的经济风险如何？"
    ↓
用户: "主要是压力问题，身体已经出现状况"
    ↓
ReflectionEngine → 引导反思
AI: "听起来压力已经影响到了身体。在你考虑的选项中，
     有没有不换工作也能缓解压力的可能性？
     比如：和领导沟通、调整工作内容、或者考虑远程工作？"
    ↓
用户: "确实没和领导沟通过"
    ↓
Summarizer → 整理思考
AI: "我们来整理一下目前的思考：
     核心问题：工作压力影响身心健康
     已考虑：换工作
     未考虑：沟通、调岗、远程
     
     建议下一步：先和领导沟通一次，了解是否有改善空间"
```

### 3.2 思考模式类型

| 类型 | 触发条件 | AI 行为 |
|------|----------|---------|
| **苏格拉底模式** | 用户问题模糊 | 连续提问，引导澄清 |
| **多角度模式** | 用户需要视野 | 提供 3-5 个不同视角 |
| **反思模式** | 用户陷入困境 | 挑战假设，引导反思 |
| **头脑风暴模式** | 用户需要创意 | 提供发散性思考方向 |
| **整理模式** | 用户思维混乱 | 整理结构化思考 |

## 四、数据模型

### 4.1 思考会话状态

```java
public class ThinkingSession {
    String sessionId;
    String userQuestion;           // 用户当前思考的问题
    ThinkingState state;           // 当前思考状态
    List<ThinkingMessage> messages; // 思考过程消息
    int depth;                     // 思考深度 (1-5)
    List<String> exploredAspects;  // 已探索的方面
    List<String> pendingQuestions; // 待思考的问题
}

public enum ThinkingState {
    EXPLORING,      // 探索阶段：澄清问题
    ANALYZING,      // 分析阶段：多角度思考
    REFLECTING,     // 反思阶段：检验假设
    SYNTHESIZING,   // 综合阶段：整理总结
    DECIDING        // 决策阶段：形成结论
}

public class ThinkingMessage {
    int step;                      // 步骤序号
    Role role;                     // USER / AI
    String content;                // 消息内容
    ThinkingType type;             // 消息类型
    String aspect;                 // 涉及的思考方面
}

public enum ThinkingType {
    QUESTION,      // 引导性问题
    SUGGESTION,    // 思考建议
    PERSPECTIVE,   // 新视角
    REFLECTION,    // 反思内容
    SUMMARY,       // 总结
    CLARIFICATION  // 澄清
}
```

### 4.2 思考上下文

```java
public class ThinkingContext {
    String originalQuestion;       // 原始问题
    String clarifiedQuestion;      // 澄清后的问题
    Map<String, Object> aspects;  // 各方面的思考进展
    List<String> keyPoints;        // 关键点
    List<String> blindSpots;      // 盲点
    List<String> nextSteps;       // 下一步建议
    ComplexityLevel complexity;    // 复杂度
    ThinkingStyle preferredStyle;  // 偏好的思考风格
}

public enum ThinkingStyle {
    SOCRATIC,       // 苏格拉底式提问
    MULTI_PERSPECTIVE, // 多角度分析
    REFLECTIVE,     // 反思式
    CREATIVE,       // 创造性
    STRUCTURED      // 结构化
}
```

## 五、核心 Prompt 设计

### 5.1 思考状态分析 Prompt

```
你是一个思考状态分析引擎，分析用户当前的思考状态。

【分析维度】
1. 问题清晰度：用户的问题是否明确？(1-5分)
2. 思考深度：用户已经深入思考了吗？(1-5分)
3. 信息完备度：用户是否有足够信息？(1-5分)
4. 情绪状态：用户的情绪如何？(焦虑/平静/兴奋/困惑)

【输出格式】
CLEARITY: <1-5>
DEPTH: <1-5>
INFORMATION: <1-5>
EMOTION: <情绪>
SUGGESTION: <建议下一步>

【用户消息】
{userMessage}
```

### 5.2 引导性问题生成 Prompt

```
你是一个思考引导引擎，根据用户的思考状态生成引导性问题。

【问题类型】
- CLARIFICATION: 澄清型问题（用户问题模糊时使用）
  例："你说的'XX'具体是指什么？"
- EXPANSION: 拓展型问题（引导深入思考）
  例："除了XX，还有哪些因素可能影响这个问题？"
- REFLECTION: 反思型问题（检验假设）
  例："如果你的假设不成立，会发生什么？"
- CHALLENGE: 挑战型问题（打破思维定式）
  例："有没有完全不同的解决思路？"

【规则】
1. 一次只问一个问题
2. 问题要具体，不要太宽泛
3. 问题要引导思考，而非测试知识
4. 根据用户回答动态调整

【当前思考上下文】
{thinkingContext}

【输出格式】
QUESTION_TYPE: <类型>
QUESTION: <问题内容>
REASON: <为什么问这个问题>
```

### 5.3 多角度分析 Prompt

```
你是一个多视角分析引擎，为用户的问题提供不同的思考角度。

【分析框架】
1. 宏观视角：从大局/长远看
2. 微观视角：从细节/当下看
3. 利益相关者：不同角色的看法
4. 时间维度：短期/中期/长期影响
5. 正反两面：支持与反对的理由

【规则】
1. 每个视角给出具体的思考方向
2. 用提问方式呈现，而非直接给答案
3. 标注哪个视角最值得优先探索

【当前问题】
{question}

【已探索的视角】
{exploredAspects}

【输出格式】
PERSPECTIVE_1: <视角名称>
  DIRECTION: <思考方向>
  QUESTION: <引导问题>
  
PERSPECTIVE_2: <视角名称>
  DIRECTION: <思考方向>
  QUESTION: <引导问题>
  
RECOMMENDED: <推荐优先探索的视角>
```

## 六、与现有模式的对比

| 特性 | Agent 模式 | 深度思考模式 | 辅助思考模式 |
|------|-----------|-------------|-------------|
| **目标** | 完成任务 | 深度分析 | 辅助思考 |
| **交互** | AI 主导执行 | AI 展示推理 | 用户主导思考 |
| **输出** | 执行结果 | 分析报告 | 问题/建议/总结 |
| **工具调用** | 大量使用 | 可能使用 | 不使用 |
| **token 消耗** | 高（多轮+工具） | 中（多轮推理） | 低（单轮引导） |
| **适用场景** | 任务执行 | 复杂分析 | 思考辅助 |

## 七、实现计划

### Phase 1: 核心引擎（本阶段）

1. 创建 `ThinkingAssistantEngine.java`
2. 创建 `ThinkingModeHandler.java`
3. 扩展 `ChatModeManager.ChatMode` 枚举
4. 实现基础思考引导功能

### Phase 2: UI 优化

1. 思考过程可视化展示
2. 思考进度指示器
3. 思考方向选择器
4. 思考历史回溯

### Phase 3: 高级功能

1. 思考风格选择
2. 思考模板（决策、创意、分析）
3. 思考成果导出
4. 多人协作思考

## 八、文件结构

```
src/main/java/com/oilquiz/app/ai/agent/software/thinking/
├── ThinkingAssistantEngine.java    # 思考辅助引擎主类
├── ThinkingAnalyzer.java           # 思考状态分析器
├── QuestionGenerator.java           # 引导问题生成器
├── PerspectiveEngine.java           # 多角度分析引擎
├── ReflectionEngine.java            # 反思引擎
├── Summarizer.java                  # 思考整理器
├── model/
│   ├── ThinkingSession.java         # 思考会话
│   ├── ThinkingContext.java         # 思考上下文
│   ├── ThinkingMessage.java         # 思考消息
│   ├── ThinkingState.java           # 思考状态枚举
│   ├── ThinkingType.java            # 思考类型枚举
│   └── ThinkingStyle.java           # 思考风格枚举
└── prompt/
    ├── AnalyzePrompt.java           # 分析 Prompt 构建
    ├── QuestionPrompt.java          # 问题 Prompt 构建
    ├── PerspectivePrompt.java       # 多角度 Prompt 构建
    └── SummaryPrompt.java           # 总结 Prompt 构建
```
