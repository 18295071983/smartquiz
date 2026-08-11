# Agent模式本地模型路由 - 快速使用指南

## 功能说明

现在，AI对话页面的Agent模式会**自动使用在线引擎**，无论配置的是在线模型还是本地模型。

- **在线模型**：完整Agent功能（工具调用+推理）
- **本地模型**：通过回退机制提供推理支持（无工具调用）

## 使用方式

### 方式1：使用在线模型（推荐）

1. 打开应用
2. 点击左上角菜单 → **模型设置**
3. 选择在线模型（如通义千问、DeepSeek等）
4. 切换到 **Agent模式**
5. 发送消息

**效果**：
- ✅ 完整工具调用能力
- ✅ 推理链展示
- ✅ 联网搜索、天气查询等
- ✅ 最强推理能力

### 方式2：使用本地模型

1. 选择本地模型
2. 切换到 **Agent模式**
3. 发送消息

**效果**：
- ⚠️ 显示提示："当前使用本地模型，Agent功能将通过本地推理提供支持"
- ✅ 自动使用本地推理
- ❌ 无工具调用
- ✅ 离线可用，响应更快

## 对比体验

| 功能 | 在线模型 | 本地模型 |
|------|---------|---------|
| 工具调用 | ✅ 支持 | ❌ 不支持 |
| 推理能力 | 强 | 中等 |
| 响应速度 | 中等 | 快 |
| 联网需求 | 需要 | 不需要 |
| 内存占用 | 低 | 高（1-2GB） |

## 测试示例

### 示例1：天气查询（需使用在线模型）

**输入**：`今天北京天气怎么样？`

**在线模型回答**：
```
[调用天气工具]
今天北京晴，气温25℃，空气质量良好...
```

**本地模型回答**：
```
（无法调用天气工具，只能基于训练数据回答）
我无法获取实时天气信息，建议查看天气应用...
```

### 示例2：通用问答（两者都支持）

**输入**：`解释一下量子力学`

**在线模型**：调用搜索工具，获取最新资料，生成详细回答
**本地模型**：基于本地知识生成回答，速度更快

## 自动回退机制

当在线模型遇到问题时，会**自动回退**到本地模型：

1. 在线模型配置无效
2. 在线推理超时
3. 连续失败2次

**表现**：
- 日志显示：`Online model failed, switching to local fallback`
- 自动使用本地推理
- 用户无感知

## 手动控制（开发者）

```java
// 获取路由器
AgentRouter router = new AgentRouter(activity, aiService, inferenceRouter, agentService);

// 强制使用本地回退（测试用）
router.forceOnlineLocalFallback();

// 重置状态（恢复在线模型）
router.resetOnlineFallbackState();

// 检查是否使用回退
boolean isFallback = router.isOnlineUsingLocalFallback();
```

## 注意事项

1. **本地模型限制**
   - 不支持工具调用
   - 推理质量可能不如在线模型
   - 需要足够内存（1-2GB）

2. **推荐使用场景**
   - 在线模型：需要工具调用、高质量推理
   - 本地模型：离线环境、简单问答、快速响应

3. **性能建议**
   - 长期使用推荐配置在线模型
   - 本地模型作为备用或特定场景使用

## 日志查看

开启日志后可以看到路由过程：

**在线模型**：
```
AgentRouter: Agent mode: routing to OnlineAgentEngine (type=ONLINE)
OnlineAgentEngine: Agent mode: TAKEOVER
```

**本地模型**：
```
AgentRouter: Agent mode: routing to OnlineAgentEngine (type=LOCAL)
AgentRouter: Local model detected, enabling local fallback
OnlineAgentEngine: Using local fallback (manual)
```

## 常见问题

### Q: 为什么本地模型也用在线引擎？
A: 统一架构，简化代码，本地模型通过回退机制提供支持。

### Q: 本地模型能使用工具吗？
A: 不能，本地回退时仅支持纯文本推理。

### Q: 如何获得最佳体验？
A: 配置在线模型并使用Agent模式。

### Q: 离线时能用Agent吗？
A: 能，自动使用本地模型回退，但无工具调用。

## 详细文档

- 完整实现说明：`AGENT_LOCAL_MODEL_ONLINE_ROUTING.md`
- 回退机制文档：`ONLINE_AGENT_LOCAL_FALLBACK.md`
- 测试指南：`ONLINE_AGENT_LOCAL_FALLBACK_TEST.md`
