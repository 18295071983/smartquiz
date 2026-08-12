# 在线模型Agent本地回退机制

## 概述

在线模型Agent现已支持智能本地回退功能，当在线模型推理不可用时，自动切换到本地模型继续提供推理支持。

## 核心特性

### 1. 自动故障检测与回退

- **连续失败检测**：在线模型连续失败2次后自动切换到本地模型
- **配置缺失检测**：未配置在线模型或模型未启用时自动使用本地模型
- **内存保护**：本地内存不足时拒绝回退，避免OOM

### 2. 手动控制接口

```java
// 获取AgentRouter实例
AgentRouter router = new AgentRouter(activity, aiService, inferenceRouter, agentService);

// 启用/禁用本地回退
router.setOnlineFallbackEnabled(true/false);

// 检查是否正在使用本地回退
boolean isFallback = router.isOnlineUsingLocalFallback();

// 强制切换到本地回退（测试用）
router.forceOnlineLocalFallback();

// 重置回退状态，恢复使用在线模型
router.resetOnlineFallbackState();
```

### 3. 回退触发条件

| 条件 | 说明 | 自动恢复 |
|------|------|----------|
| 在线模型未配置 | `cfg == null` | ✅ 配置后自动恢复 |
| 在线模型已禁用 | `cfg.enabled == false` | ✅ 启用后自动恢复 |
| 连续推理失败 | `failures >= 2` | ✅ 成功后重置计数 |
| 手动强制回退 | `forceLocalFallback()` | ❌ 需手动重置 |

## 工作流程

### 正常流程

```
用户请求 → 在线模型推理 → 成功返回
```

### 回退流程

```
用户请求 → 在线模型推理 → 失败(1次)
         → 在线模型重试 → 失败(2次)
         → 自动切换本地 → 本地模型推理 → 返回结果
```

### 自动恢复流程

```
本地回退中 → 在线模型可用 → 自动重置状态 → 恢复在线推理
```

## 实现原理

### 核心类与职责

1. **OnlineAgentEngine**
   - 在线模型推理主引擎
   - 集成ALChat本地推理能力
   - 维护失败计数和回退状态
   - 实现回退判断和本地推理逻辑

2. **AgentRouter**
   - 统一路由入口
   - 提供回退控制API
   - 状态查询和管理

3. **ALChat**
   - 本地模型推理引擎
   - 提供sendMessage接口

4. **ModelMemoryManager**
   - 内存状态监控
   - 防止内存溢出

### 关键代码片段

#### 回退判断逻辑

```java
private boolean shouldFallbackToLocal(OnlineModelManager.OnlineModelConfig cfg) {
    // 1. 手动强制回退
    if (isUsingLocalFallback) return true;
    
    // 2. 本地回退未启用
    if (!localFallbackEnabled) return false;
    
    // 3. 在线模型不可用
    if (cfg == null || !cfg.enabled) return canUseLocalModel();
    
    // 4. 连续失败次数达到阈值
    if (consecutiveOnlineFailures >= MAX_ONLINE_FAILURES_BEFORE_FALLBACK) {
        return canUseLocalModel();
    }
    
    return false;
}
```

#### 本地推理执行

```java
private void executeWithLocalModel(String userMessage, int maxTokens) {
    notifyStep("本地回退", "使用本地模型推理...");
    
    // 检查内存状态
    ModelMemoryManager.MemoryState memState = memoryManager.getMemoryState();
    if (memState == ModelMemoryManager.MemoryState.CRITICAL) {
        System.gc();
        Thread.sleep(200);
    }
    
    // 构建提示词（含对话历史）
    String prompt = buildLocalPrompt(userMessage);
    
    // 调用本地模型
    String response = localChat.sendMessage(prompt, maxTokens, 0.7f, 0.9f, 40);
    
    // 清理和返回结果
    String cleaned = ToolResultInterpreter.cleanModelOutput(response);
    notifyComplete(cleaned);
}
```

## 配置选项

### 1. 启用/禁用回退

在创建OnlineAgentEngine后设置：

```java
OnlineAgentEngine engine = new OnlineAgentEngine(activity, toolManager);
engine.setLocalFallbackEnabled(true); // 默认true
```

### 2. 调整失败阈值

修改常量（需重新编译）：

```java
private static final int MAX_ONLINE_FAILURES_BEFORE_FALLBACK = 2; // 默认2次
```

### 3. UI集成示例

在AIChatActivity中显示回退状态：

```java
// 检查回退状态
if (agentRouter.isOnlineUsingLocalFallback()) {
    showStatus("当前使用本地模型（在线不可用）");
} else {
    showStatus("在线模型");
}

// 手动切换（调试用）
menu.addItem("强制本地回退", () -> {
    agentRouter.forceOnlineLocalFallback();
    showToast("已切换到本地模型");
});

// 重置状态
menu.addItem("恢复在线模型", () -> {
    agentRouter.resetOnlineFallbackState();
    showToast("已恢复在线模型");
});
```

## 最佳实践

### 1. 监控与日志

回退机制会自动记录关键日志：

```
W/OnlineAgentEngine: Online model failure #1
W/OnlineAgentEngine: Online model failure #2
E/OnlineAgentEngine: Online model failed 2 times, switched to local fallback
W/OnlineAgentEngine: Online model unavailable, falling back to local model
I/OnlineAgentEngine: Local model inference success, len=256
```

### 2. 用户体验优化

- **透明切换**：用户无需感知模型切换
- **状态提示**：在UI上显示当前使用的模型类型
- **自动恢复**：在线恢复后自动切回在线模型

### 3. 性能优化

- **内存检查**：回退前检查内存状态，避免OOM
- **历史压缩**：本地推理仅保留最近3轮对话，节省token
- **参数调优**：本地推理使用`temperature=0.7, topP=0.9`

## 故障排查

### 问题1：回退未触发

**可能原因**：
- 本地回退被禁用：`setLocalFallbackEnabled(false)`
- 本地模型未初始化：检查模型加载状态
- 内存不足：`MemoryState.OUT_OF_MEMORY`

**解决方案**：
```java
// 检查状态
Log.d("Debug", "fallbackEnabled=" + engine.localFallbackEnabled);
Log.d("Debug", "localInitialized=" + localChat.isInitialized());
Log.d("Debug", "memoryState=" + memoryManager.getMemoryState());
```

### 问题2：回退后无法恢复在线

**可能原因**：
- 手动强制回退未重置：调用`resetFallbackState()`
- 在线模型仍不可用：检查网络和配置

**解决方案**：
```java
// 重置回退状态
agentRouter.resetOnlineFallbackState();

// 检查在线模型配置
OnlineModelManager.OnlineModelConfig cfg = onlineModelManager.getActiveModel();
Log.d("Debug", "config=" + cfg + ", enabled=" + (cfg != null ? cfg.enabled : false));
```

### 问题3：本地推理失败

**可能原因**：
- 提示词过长：检查`buildLocalPrompt()`输出
- 模型参数不当：调整temperature/topP
- 内存泄漏：监控内存使用

**解决方案**：
```java
// 查看日志
adb logcat | grep "Local model"

// 强制GC
System.gc();
```

## 扩展功能

### 1. 自定义回退策略

继承OnlineAgentEngine重写回退逻辑：

```java
public class CustomOnlineEngine extends OnlineAgentEngine {
    @Override
    protected boolean shouldFallbackToLocal(Config cfg) {
        // 自定义回退条件
        boolean customCondition = checkCustomCondition();
        return customCondition || super.shouldFallbackToLocal(cfg);
    }
}
```

### 2. 回退事件监听

添加回退事件回调：

```java
public interface FallbackListener {
    void onFallbackTriggered(String reason);
    void onOnlineRestored();
}

// 使用
engine.setFallbackListener(new FallbackListener() {
    @Override
    public void onFallbackTriggered(String reason) {
        Analytics.log("fallback", reason);
    }
    
    @Override
    public void onOnlineRestored() {
        Analytics.log("online_restored", "");
    }
});
```

## 版本历史

- **v1.0** (2026-08-10)
  - 初始实现
  - 支持自动回退和手动控制
  - 集成内存保护机制
  - 完整的日志和状态管理

## 未来计划

- [ ] 支持回退优先级配置（在线优先/本地优先）
- [ ] 添加回退统计和分析
- [ ] 支持多本地模型回退策略
- [ ] 实现回退成本优化（API费用vs本地电量）
