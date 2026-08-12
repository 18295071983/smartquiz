# 在线模型Agent本地回退功能测试指南

## 功能概述

在线模型Agent现已支持在在线模型推理不可用时自动回退到本地模型，确保推理支持的连续性。

## 核心特性

1. **自动故障检测**：连续失败2次自动切换到本地模型
2. **内存保护**：回退前检查内存状态，防止OOM
3. **透明切换**：用户无需感知模型切换过程
4. **自动恢复**：在线模型恢复后可手动重置状态
5. **对话历史压缩**：本地推理仅保留最近3轮对话，优化性能

## 测试场景

### 场景1：在线模型不可用时的自动回退

**测试步骤**：
1. 打开应用，进入Agent对话界面
2. 关闭网络连接或配置一个无效的在线模型API Key
3. 发送消息："你好，请介绍一下自己"
4. 观察日志输出

**预期结果**：
```
OnlineAgentEngine: Online model unavailable (config null or disabled)
OnlineAgentEngine: Using local fallback (auto)
OnlineAgentEngine: Local model inference success, len=xxx
```

UI应显示：
- 步骤提示："本地回退 - 使用本地模型推理..."
- 正常返回AI回答

### 场景2：在线模型连续失败后的回退

**测试步骤**：
1. 配置一个会超时的在线模型（如设置极短的超时时间）
2. 连续发送2条消息，触发在线推理失败
3. 发送第3条消息

**预期结果**：
```
OnlineAgentEngine: Online model failure #1
OnlineAgentEngine: Online model failure #2
OnlineAgentEngine: Online model failed 2 times, switched to local fallback
OnlineAgentEngine: Using local fallback (auto)
```

### 场景3：手动强制回退

**测试代码**：
```java
// 在Activity或测试类中
AgentRouter router = new AgentRouter(this, localEngine, onlineEngine);

// 强制切换到本地回退
router.forceOnlineLocalFallback();

// 发送消息，应使用本地模型
sendMessage("测试消息");

// 验证回退状态
boolean isFallback = router.isOnlineUsingLocalFallback();
Log.d("Test", "Is using fallback: " + isFallback);
```

**预期结果**：
- 立即使用本地模型推理
- `isFallback` 返回 `true`

### 场景4：重置回退状态

**测试代码**：
```java
AgentRouter router = new AgentRouter(this, localEngine, onlineEngine);

// 先触发回退
router.forceOnlineLocalFallback();
sendMessage("消息1"); // 使用本地模型

// 重置状态
router.resetOnlineFallbackState();

// 恢复在线模型（需确保在线配置正确）
sendMessage("消息2"); // 应使用在线模型
```

**预期结果**：
```
OnlineAgentEngine: Reset fallback state, will use online model
```

### 场景5：禁用回退功能

**测试代码**：
```java
AgentRouter router = new AgentRouter(this, localEngine, onlineEngine);

// 禁用回退
router.setOnlineFallbackEnabled(false);

// 使在线模型不可用
// 发送消息
```

**预期结果**：
- 不触发本地回退
- 返回在线模型错误提示

## API 使用示例

### 1. 检查回退状态

```java
AgentRouter router = ...;
boolean isUsingFallback = router.isOnlineUsingLocalFallback();

if (isUsingFallback) {
    Log.i("Agent", "当前使用本地回退模型");
} else {
    Log.i("Agent", "当前使用在线模型");
}
```

### 2. 控制回退功能

```java
// 启用回退（默认启用）
router.setOnlineFallbackEnabled(true);

// 禁用回退
router.setOnlineFallbackEnabled(false);
```

### 3. 手动触发和重置

```java
// 强制切换到本地回退
router.forceOnlineLocalFallback();

// 执行测试...

// 重置回退状态，恢复使用在线模型
router.resetOnlineFallbackState();
```

## 日志分析

### 正常在线推理日志

```
OnlineAgentEngine: Agent mode: TAKEOVER (maxIterations=30)
OnlineAgentEngine: Stream started
OnlineAgentEngine: Token usage: prompt=1200 completion=350 total=1550
OnlineAgentEngine: Local fallback not triggered
```

### 回退触发日志

```
OnlineAgentEngine: Online model failure #1
OnlineAgentEngine: Online model failure #2
OnlineAgentEngine: Online model failed 2 times, switched to local fallback
OnlineAgentEngine: Using local fallback (auto)
OnlineAgentEngine: Local model inference success, len=256
```

### 内存不足日志

```
OnlineAgentEngine: Memory insufficient for local model
OnlineAgentEngine: Local model OOM
OnlineAgentEngine: Emergency memory cleanup
```

## 故障排查

### 问题1：回退未触发

**检查项**：
1. 回退功能是否启用？`router.isOnlineFallbackEnabled()`
2. 本地模型是否已初始化？`localChat.isInitialized()`
3. 内存状态是否充足？`memoryManager.getMemoryState()`
4. 查看日志中的失败计数

### 问题2：回退后内存不足

**解决方案**：
1. 在回退前手动清理对话历史
2. 减少本地推理的 maxTokens 参数
3. 调用 `System.gc()` 主动释放内存

### 问题3：在线恢复后仍使用本地模型

**解决方案**：
```java
// 手动重置回退状态
router.resetOnlineFallbackState();
```

## 性能指标

| 指标 | 在线模型 | 本地回退模型 |
|------|---------|------------|
| 首字延迟 | 500-2000ms | 100-300ms |
| 推理速度 | 20-50 tokens/s | 5-15 tokens/s |
| 内存占用 | 网络请求 | 1-2GB |
| 上下文长度 | 8K-32K tokens | 2K-4K tokens |
| 工具调用 | 完整支持 | 不支持 |

## 最佳实践

1. **监控回退频率**：频繁回退说明在线服务不稳定，应检查网络或API配置
2. **合理设置失败阈值**：默认2次，可根据网络状况调整（1-5次）
3. **内存预警**：在低内存设备上提前禁用回退或限制对话长度
4. **用户提示**：回退时在UI显示提示，让用户了解当前使用的模型
5. **日志记录**：生产环境记录回退事件，用于后续分析

## 扩展功能建议

1. **自动重试机制**：本地推理失败后自动重试在线模型
2. **混合推理**：简单问题用本地模型，复杂问题用在线模型
3. **模型预热**：在后台预加载本地模型，减少回退延迟
4. **智能路由**：根据问题类型自动选择模型
5. **回退统计面板**：在设置中显示回退次数和成功率

## 相关文件

- `OnlineAgentEngine.java` - 核心实现
- `AgentRouter.java` - 统一控制接口
- `ONLINE_AGENT_LOCAL_FALLBACK.md` - 详细文档
