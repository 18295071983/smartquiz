# Agent模式本地模型路由到在线引擎说明

## 概述

修改了AI对话页面的Agent模式路由逻辑，**无论是本地模型还是在线模型，都统一使用在线引擎（OnlineAgentEngine）**。本地模型通过在线引擎的**本地回退机制**提供支持。

## 修改动机

### 原有问题
- 本地模型在Agent模式下被禁用，提示用户切换到在线模型
- 本地Agent（UnifiedAgentEngine）工具调用能力有限
- 两套引擎导致代码维护复杂

### 新方案优势
1. **统一Agent体验**：本地模型也能使用Agent模式
2. **完整功能支持**：通过在线引擎获得原生function calling能力
3. **自动降级**：在线引擎自动回退到本地模型
4. **代码简化**：统一使用一套Agent引擎
5. **透明切换**：用户无感知模型切换

## 修改内容

### 1. AgentRouter.java

#### 修改前
```java
public void execute(String message, int maxTokens, boolean enableThinking) {
    EngineType type = resolveEngineType();
    lastUsedEngine = type;

    if (type == EngineType.ONLINE) {
        // 路由到在线引擎
        executeOnline(message, maxTokens);
    } else {
        // 本地Agent已禁用
        notifyLocalAgentDisabled();
    }
}
```

#### 修改后
```java
public void execute(String message, int maxTokens, boolean enableThinking) {
    EngineType type = resolveEngineType();
    lastUsedEngine = EngineType.ONLINE; // Agent 模式强制使用在线引擎

    // Agent 模式下统一路由到在线引擎
    AILogger.i(TAG, "Agent mode: routing to OnlineAgentEngine (type=" + type + ")");
    
    // 如果是本地模型，启用在线引擎的本地回退
    if (type == EngineType.LOCAL) {
        AILogger.i(TAG, "Local model detected, enabling local fallback for online engine");
        ensureOnlineEngineCreated();
        onlineEngine.forceLocalFallback();
    } else {
        // 在线模型，确保回退状态已重置
        if (onlineEngine != null) {
            onlineEngine.resetFallbackState();
        }
    }
    
    executeOnline(message, maxTokens);
}
```

#### 新增方法
```java
/**
 * 确保在线引擎已创建
 */
private void ensureOnlineEngineCreated() {
    if (onlineEngine == null) {
        OnlineToolManager onlineToolManager = new OnlineToolManager(activity);
        onlineEngine = new OnlineAgentEngine(activity, onlineToolManager);
        if (callback != null) {
            onlineEngine.setCallback(callback);
        }
        if (progressListener != null) {
            onlineEngine.setInferenceProgressListener(progressListener);
        }
    }
}
```

### 2. AIChatActivity.java

#### 修改前
```java
if (currentMode == ChatModeManager.ChatMode.AGENT) {
    if (shouldUseOnlineModel()) {
        showOnlineAgentFriendlyGuide(message);
        processChatMessageWithAgent(message);
        return;
    }
    // 本地agent已禁用：引导用户切换到在线模型
    addSystemMessage("🚫 本地 Agent 已禁用\n\n...");
    return;
}
```

#### 修改后
```java
if (currentMode == ChatModeManager.ChatMode.AGENT) {
    forceLocalAgentOnce = false;
    
    if (shouldUseOnlineModel()) {
        // 在线模型：先显示友好引导，再走ReAct
        showOnlineAgentFriendlyGuide(message);
    } else {
        // 本地模型：显示回退提示
        addSystemMessage("🔄 当前使用本地模型，Agent功能将通过本地推理提供支持\n\n" +
            "提示：在线模型支持更丰富的工具调用和推理能力，建议切换到在线模型获得最佳体验。", 
            ChatMessage.SystemMessageType.INFO);
    }
    
    // 统一走 Agent 路由（在线引擎会自动处理本地回退）
    processChatMessageWithAgent(message);
    return;
}
```

## 工作流程

### 场景1：在线模型激活时

```
用户输入（Agent模式）
    ↓
shouldUseOnlineModel() = true
    ↓
AgentRouter.execute()
    ↓
resolveEngineType() = ONLINE
    ↓
确保回退状态已重置
onlineEngine.resetFallbackState()
    ↓
OnlineAgentEngine.execute()
    ↓
原生 function calling + 在线推理
    ↓
完整Agent体验（工具调用+推理）
```

### 场景2：本地模型激活时

```
用户输入（Agent模式）
    ↓
shouldUseOnlineModel() = false
    ↓
AgentRouter.execute()
    ↓
resolveEngineType() = LOCAL
    ↓
强制启用本地回退
onlineEngine.forceLocalFallback()
    ↓
OnlineAgentEngine.execute()
    ↓
shouldFallbackToLocal() = true
    ↓
executeWithLocalModel()
    ↓
本地模型推理（无工具调用）
    ↓
返回AI回答
```

## 功能对比

| 特性 | 在线模型 + 在线引擎 | 本地模型 + 在线引擎（回退） |
|------|-------------------|------------------------|
| **路由** | OnlineAgentEngine | OnlineAgentEngine |
| **工具调用** | ✅ 原生 function calling | ❌ 不支持（回退时） |
| **推理能力** | 强（云端大模型） | 中等（端侧小模型） |
| **推理速度** | 20-50 tok/s | 5-15 tok/s |
| **首字延迟** | 500-2000ms | 100-300ms |
| **网络需求** | 需要联网 | 离线可用 |
| **内存占用** | 网络请求 | 1-2GB |
| **提示词** | 完整工具定义 | 简化提示词 |
| **对话历史** | 完整保留 | 压缩（最近3轮） |
| **流式输出** | ✅ 支持 | ✅ 支持 |
| **思考链** | ✅ reasoning_content | ❌ 不支持 |

## 用户体验

### 在线模型时
- 显示"☁️ 在线模型"
- 显示友好引导（根据问题推荐工具）
- 完整的工具调用和推理能力
- 实时思考链展示

### 本地模型时
- 显示"📱 本地模型"
- 显示回退提示（建议切换到在线模型）
- 自动使用本地推理
- 无工具调用，纯文本推理
- 更快响应，离线可用

## 日志输出

### 在线模型日志
```
AgentRouter: Agent mode: routing to OnlineAgentEngine (type=ONLINE)
OnlineAgentEngine: Agent mode: TAKEOVER (maxIterations=30)
OnlineAgentEngine: Tool definitions: count=8
OnlineAgentEngine: Stream started
OnlineAgentEngine: Token usage: prompt=1200 completion=350 total=1550
```

### 本地模型日志
```
AgentRouter: Agent mode: routing to OnlineAgentEngine (type=LOCAL)
AgentRouter: Local model detected, enabling local fallback for online engine
OnlineAgentEngine: Forced switch to local fallback
OnlineAgentEngine: Using local fallback (manual)
OnlineAgentEngine: Local model inference success, len=256
```

## 手动控制API

```java
AgentRouter router = new AgentRouter(activity, aiService, inferenceRouter, agentService);

// 检查是否使用本地回退
boolean isFallback = router.isOnlineUsingLocalFallback();

// 手动强制本地回退（测试用）
router.forceOnlineLocalFallback();

// 重置回退状态（恢复在线模型）
router.resetOnlineFallbackState();

// 禁用回退功能
router.setOnlineFallbackEnabled(false);
```

## 注意事项

### 1. 本地回退限制
- **不支持工具调用**：本地模型无法使用搜索、天气、数据库等工具
- **推理质量差异**：本地模型推理能力弱于在线大模型
- **内存需求**：需要1-2GB可用内存
- **模型初始化**：本地模型必须已下载并加载

### 2. 自动回退触发条件
- 在线模型配置为null或disabled
- 在线推理连续失败2次
- 手动强制回退

### 3. 性能优化
- 对话历史压缩（仅保留最近3轮）
- 内存状态检查防止OOM
- 失败计数自动重置

## 测试场景

### 测试1：本地模型Agent模式
1. 打开应用，选择本地模型
2. 切换到Agent模式
3. 发送消息："今天天气怎么样？"
4. 预期：显示回退提示，使用本地推理回答（无法调用天气工具）

### 测试2：在线模型Agent模式
1. 选择在线模型
2. 切换到Agent模式
3. 发送消息："今天天气怎么样？"
4. 预期：显示友好引导，调用天气工具，返回结果

### 测试3：在线模型故障回退
1. 配置无效的在线模型API Key
2. 发送消息（Agent模式）
3. 连续失败2次后
4. 预期：自动回退到本地模型

### 测试4：手动控制回退
```java
// 测试代码
router.forceOnlineLocalFallback();
router.execute("测试", 2048); // 应使用本地模型
router.resetOnlineFallbackState();
router.execute("测试", 2048); // 应使用在线模型
```

## 优势总结

1. **统一架构**：所有Agent请求都通过在线引擎
2. **自动降级**：在线不可用时自动使用本地
3. **用户体验**：本地模型也能使用Agent模式
4. **代码维护**：简化了路由逻辑
5. **灵活性**：支持手动控制回退
6. **透明度**：日志完整记录路由过程

## 后续优化建议

1. **智能路由**：根据问题复杂度自动选择模型
2. **模型预热**：后台预加载本地模型
3. **回退统计**：记录回退事件用于分析
4. **混合推理**：简单问题用本地，复杂问题用在线
5. **UI优化**：在设置中显示当前使用的引擎和回退状态

## 相关文件

- `AgentRouter.java` - 路由逻辑修改
- `AIChatActivity.java` - UI提示修改
- `OnlineAgentEngine.java` - 本地回退实现
- `ONLINE_AGENT_LOCAL_FALLBACK.md` - 回退机制详细文档
- `ONLINE_AGENT_LOCAL_FALLBACK_TEST.md` - 测试指南

## 实现时间

2026-08-10
