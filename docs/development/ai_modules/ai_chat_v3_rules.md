# AI助手界面 v3.0 开发规则约束文档

> 版本: 3.0 | 更新日期: 2026-07-18 | 适用于: AI Chat v3.0 迭代开发

---

## 1. 代码规范约束

### 1.1 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| **类名** | 大驼峰命名法 | `ChatOrchestrator`, `StreamingBroadcaster` |
| **接口名** | 大驼峰，以I开头或直接描述 | `ModeHandler`, `IStreamingSubscriber` |
| **方法名** | 小驼峰命名法 | `sendMessage()`, `handleEvent()` |
| **常量名** | 全大写，下划线分隔 | `DEFAULT_STREAM_INTERVAL`, `MESSAGE_ID_PREFIX` |
| **变量名** | 小驼峰命名法 | `messageId`, `currentMode` |
| **成员变量** | 小驼峰，可加m前缀（可选） | `mMessageQueue`, `broadcaster` |
| **布尔变量** | is/has/can开头 | `isGenerating`, `hasAttachment` |
| **TAG常量** | 类名或缩写 | `"ChatOrchestrator"`, `"DeepThinking"` |

### 1.2 文件结构规范

```
src/main/java/com/oilquiz/app/ai/chat/
├── ChatOrchestrator.java          # 协调器（新增）
├── StreamingBroadcaster.java      # 流式广播器（新增）
├── MessageQueue.java              # 消息队列（增强）
├── ChatModeManager.java           # 模式管理器（增强）
├── ChatAdapter.java               # 聊天适配器（修复增强）
├── mode/
│   ├── ModeHandler.java           # 模式处理器接口
│   ├── NormalModeHandler.java     # 普通模式处理器
│   ├── DeepThinkingModeHandler.java  # 深度思考模式处理器（新增）
│   ├── CreativeWritingModeHandler.java # 创意写作模式处理器（新增）
│   └── AgentModeHandler.java      # Agent模式处理器
├── engine/
│   ├── DeepThinkingEngine.java    # 深度思考引擎（现有）
│   └── CreativeWritingEngine.java # 创意写作引擎（现有）
├── event/
│   ├── StreamingEvent.java        # 流式事件（新增）
│   └── StreamingSubscriber.java   # 订阅者接口（新增）
├── attachment/
│   ├── AttachmentRenderer.java    # 附件渲染器（新增）
│   ├── AttachmentViewHolder.java  # 附件ViewHolder（新增）
│   └── AttachmentOperation.java   # 附件操作接口（新增）
└── model/
    ├── ChatMessage.java           # 聊天消息（扩展）
    └── InferenceProgress.java     # 推理进度（扩展）
```

### 1.3 代码格式约束

- 缩进使用4个空格，不使用Tab
- 每行最多120个字符
- 大括号使用K&R风格
- 方法之间空一行
- 导入按包分组，顺序：Android包 → 第三方包 → 本地包

---

## 2. 架构约束

### 2.1 分层架构

```
┌─────────────────────────────────────────────┐
│                  UI层                       │
│  ChatAdapter, ViewHolder, Activity/Fragment │
└──────────────────┬──────────────────────────┘
                   │ 事件回调
┌──────────────────▼──────────────────────────┐
│                协调层                        │
│       ChatOrchestrator                       │
└──────────────────┬──────────────────────────┘
                   │ 统一调度
        ┌──────────┼──────────┐
        ▼          ▼          ▼
┌─────────────┐ ┌─────────┐ ┌──────────────┐
│  Engine层   │ │ Queue层 │ │ Broadcast层  │
│ DeepThinking│ │MessageQ │ │StreamingBr...│
│ CreativeWrit│ │         │ │              │
└──────┬──────┘ └────┬────┘ └──────┬───────┘
       │             │             │
       └─────────────┼─────────────┘
                     ▼
            ┌────────────────┐
            │   Service层    │
            │   AIService    │
            └────────────────┘
```

### 2.2 依赖注入约束

- 使用构造函数注入，避免静态依赖
- 接口抽象，具体实现可替换
- 单例使用双重检查锁定或枚举

### 2.3 线程约束

| 组件 | 线程要求 |
|------|---------|
| ChatOrchestrator | 可在任意线程调用，内部线程安全 |
| StreamingBroadcaster | 线程安全，支持多线程订阅和广播 |
| MessageQueue | 线程安全，使用BlockingQueue |
| ChatAdapter | UI操作必须在主线程 |
| Engine层 | 后台线程执行，通过回调通知UI |

---

## 3. 数据流约束

### 3.1 单向数据流

```
用户输入
   ↓
ChatOrchestrator (唯一入口)
   ↓
MessageQueue (排队)
   ↓
ModeHandler (处理)
   ↓
StreamingBroadcaster (广播)
   ↓
ChatAdapter (UI更新)
```

### 3.2 消息ID约束

- 所有消息必须有唯一ID
- ID格式：`msg_{timestamp}_{random}_{sequence}`
- ID在消息创建时生成，生命周期内不变
- 流式更新必须携带messageId进行路由

### 3.3 事件类型约束

| 事件类型 | 触发时机 | 必须字段 |
|---------|---------|---------|
| MESSAGE_CREATED | 消息创建时 | messageId, initialContent |
| TOKEN_APPENDED | 每生成一个token | messageId, token |
| STATUS_CHANGED | 状态变更 | messageId, newStatus |
| THINKING_UPDATE | 思考步骤更新 | messageId, thinkingStep |
| MESSAGE_COMPLETED | 消息完成 | messageId, finalContent |
| MESSAGE_FAILED | 消息失败 | messageId, error |
| MESSAGE_CANCELLED | 消息取消 | messageId |

---

## 4. 性能约束

### 4.1 UI性能约束

| 指标 | 阈值 | 约束规则 |
|------|------|---------|
| 滚动帧率 | ≥60fps | 禁止在onBindViewHolder中执行耗时操作 |
| 流式更新间隔 | 50-200ms | 使用防抖机制，避免频繁notify |
| ViewHolder创建 | <16ms | 禁止在创建时加载大图或解析JSON |
| DiffUtil计算 | <5ms | 限制同时计算的消息数量 |

### 4.2 内存约束

| 指标 | 阈值 | 约束规则 |
|------|------|---------|
| 聊天界面内存 | <100MB | 限制历史消息数，分页加载 |
| 单个消息大小 | <100KB | 超长文本截断或分块 |
| 附件缓存 | <50MB | LRU缓存，定期清理 |

### 4.3 流式更新优化约束

- 使用`notifyItemChanged(position, payload)`进行局部更新
- 禁止使用`notifyDataSetChanged()`
- 使用`DiffUtil.ItemCallback`计算差异
- 流式更新节流：50ms内只更新一次

---

## 5. 错误处理约束

### 5.1 异常处理原则

- 所有异步操作必须有异常捕获
- 异常必须记录日志（ERROR级别）
- 用户可见错误必须有友好提示
- 可恢复错误自动重试

### 5.2 错误状态码

| 错误码 | 分类 | 处理方式 |
|--------|------|---------|
| ERR_001 | 消息ID生成失败 | 重试或使用备用ID |
| ERR_002 | 事件路由失败 | 记录日志，静默忽略 |
| ERR_003 | 模式切换失败 | 回退到当前模式，提示用户 |
| ERR_004 | 引擎执行失败 | 显示错误消息，支持重试 |
| ERR_005 | 推理超时 | 取消当前任务，提示重试 |
| ERR_006 | 附件加载失败 | 显示占位图，不影响其他功能 |

### 5.3 日志约束

```java
// ✅ 正确
try {
    // 操作
} catch (Exception e) {
    Log.e(TAG, "Error processing message: " + messageId, e);
    broadcastError(messageId, "处理失败，请重试");
}

// ❌ 错误（静默失败）
try {
    // 操作
} catch (Exception e) {
    // 什么都不做
}

// ❌ 错误（泄露敏感信息）
Log.e(TAG, "User API Key: " + apiKey);
```

---

## 6. 测试约束

### 6.1 单元测试覆盖

| 组件 | 覆盖率要求 | 测试重点 |
|------|-----------|---------|
| StreamingBroadcaster | ≥80% | 订阅/取消订阅、事件广播、线程安全 |
| MessageQueue | ≥80% | 入队/出队、优先级、并发控制 |
| ChatModeManager | ≥70% | 模式切换、pendingMode、自动识别 |
| ModeHandler | ≥60% | 消息处理、事件发送 |

### 6.2 集成测试

- 端到端测试：用户发送消息 → AI回复显示
- 模式切换测试：生成中切换模式
- 并发测试：同时发送多条消息
- 取消测试：生成中取消任务

### 6.3 性能测试

- 滚动性能测试：100条消息滚动
- 流式更新测试：连续生成1000个token
- 内存测试：长时间使用不泄漏

---

## 7. 兼容性约束

### 7.1 Android版本

- 最低支持：Android 12 (API 31)
- 目标版本：Android 14 (API 34)
- 使用AndroidX库，不使用Support库

### 7.2 架构约束

- 使用Java 8+特性
- 使用协程/Executor处理异步
- ViewModel + LiveData/Flow

---

## 8. 变更约束

### 8.1 代码审查要求

- 所有代码变更必须经过代码审查
- 审查要点：命名规范、架构约束、性能、错误处理
- 至少1人审查通过才能合并

### 8.2 破坏性变更

- 禁止修改现有公共API签名
- 禁止修改现有数据模型字段类型
- 如需变更，使用@Deprecated标记，至少保留3个版本

### 8.3 版本管理

- 功能分支：`feature/ai-chat-v3-*`
- 修复分支：`fix/ai-chat-v3-*`
- 提交信息：`[AI-CHAT-v3] 简要描述`

---

## 9. 验收清单

开发完成后，必须满足以下所有检查项：

### 9.1 代码规范检查

- [ ] 命名符合规范
- [ ] 代码格式正确
- [ ] 无未使用的导入
- [ ] 无TODO遗留（除非有明确原因）

### 9.2 架构检查

- [ ] 依赖注入正确
- [ ] 线程安全
- [ ] 无循环依赖
- [ ] 符合分层架构

### 9.3 功能检查

- [ ] 深度思考模式正常工作
- [ ] 创意写作模式正常工作
- [ ] 模式切换功能正常
- [ ] 流式生成无闪烁
- [ ] 附件显示正常

### 9.4 性能检查

- [ ] 滚动帧率≥60fps
- [ ] 无内存泄漏
- [ ] 流式更新流畅

### 9.5 测试检查

- [ ] 单元测试通过
- [ ] 集成测试通过
- [ ] 性能测试达标

---

## 10. 参考资料

- [Android官方代码风格指南](https://source.android.com/docs/core/architecture/hidl/code-style)
- [Oracle Java代码规范](https://www.oracle.com/java/technologies/javase/codeconventions-contents.html)
- [Android性能优化指南](https://developer.android.com/topic/performance)
- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
