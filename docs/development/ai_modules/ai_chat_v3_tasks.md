# AI助手界面 v3.0 任务分解文档

> 版本: 3.0 | 更新日期: 2026-07-18 | 总任务数: 26 | 预计周期: 11天

---

## 1. 里程碑概览

| 里程碑 | 任务数 | 预计时间 | 依赖 |
|--------|--------|---------|------|
| M1: 引擎修复与集成 | 5 | 2天 | 无 |
| M2: ChatAdapter功能实现 | 7 | 3天 | M1 |
| M3: 附件处理实现 | 5 | 2天 | M2 |
| M4: 消息队列与流式生成 | 5 | 2天 | M1, M2 |
| M5: 测试与优化 | 4 | 2天 | M1-M4 |

---

## 2. 详细任务分解

### M1: 引擎修复与集成

#### 任务1.1: DeepThinkingEngine集成

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建 DeepThinkingModeHandler 类
- [ ] 实现与ChatOrchestrator的集成
- [ ] 思考步骤能正确广播到UI
- [ ] 测试深度思考模式完整流程

**依赖**: 无

**技术要点**:
- 调用顺序: startThinking → analyzeProblemDecomposition → gatherKnowledge → evaluateHypotheses → logicalReasoning → verifySolution → synthesizeAnswer
- 每步发送THINKING_STEP事件
- 最终发送MESSAGE_COMPLETED

---

#### 任务1.2: CreativeWritingEngine集成

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建 CreativeWritingModeHandler 类
- [ ] 实现与ChatOrchestrator的集成
- [ ] 创作阶段能正确广播到UI
- [ ] 测试创意写作模式完整流程

**依赖**: 无

**技术要点**:
- 调用顺序: extractTheme → generateOutline → 分段撰写 → 润色总结
- 分段撰写时发送TOKEN_APPENDED事件
- 大纲和撰写进度发送THINKING_STEP事件

---

#### 任务1.3: ChatModeManager完善

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 完善模式切换逻辑（生成中延迟切换）
- [ ] 实现pendingMode机制
- [ ] 添加模式切换回调
- [ ] 测试模式切换功能

**依赖**: 无

**技术要点**:
- isGenerating状态追踪
- setGeneratingState方法完善
- applyModeChange方法确保线程安全

---

#### 任务1.4: ChatOrchestrator实现

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建ChatOrchestrator类
- [ ] 实现sendMessage方法
- [ ] 实现switchMode方法
- [ ] 实现cancelGeneration方法
- [ ] 实现模式处理器映射

**依赖**: 1.1, 1.2, 1.3

**技术要点**:
- 统一入口，协调所有组件
- ModeHandler接口设计
- 消息ID生成

---

### M2: ChatAdapter功能实现

#### 任务2.1: DiffUtil实现

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建ChatMessageDiffCallback类
- [ ] 实现areItemsTheSame
- [ ] 实现areContentsTheSame
- [ ] 实现getChangePayload
- [ ] 测试列表更新性能

**依赖**: 无

**技术要点**:
- 比较ID判断是否同一项
- 比较内容判断是否需要更新
- 生成Payload用于局部更新

---

#### 任务2.2: 流式更新优化

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现appendToken方法
- [ ] 完善updateMessageContent方法
- [ ] 确保payload更新只影响相关View
- [ ] 测试流式更新无闪烁

**依赖**: 2.1

**技术要点**:
- 使用PAYLOAD_CONTENT_UPDATE
- 仅更新TextView，不重绘整个ViewHolder
- 确保在主线程执行

---

#### 任务2.3: 思考过程可视化

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现ThinkingMessageViewHolder完善
- [ ] 显示思考步骤列表
- [ ] 显示当前步骤进度
- [ ] 显示置信度和推理深度
- [ ] 支持折叠/展开

**依赖**: 无

**技术要点**:
- RecyclerView嵌套或LinearLayout
- 进度条显示
- 动态添加步骤

---

#### 任务2.4: 推理状态显示

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现InferenceProgressView
- [ ] 显示推理阶段
- [ ] 显示token统计
- [ ] 显示生成速度
- [ ] 显示GPU/CPU状态
- [ ] 显示进度条

**依赖**: 无

**技术要点**:
- 绑定到AIMessageViewHolder
- 监听InferenceProgress变化
- 实时更新统计信息

---

#### 任务2.5: 模式切换UI

**优先级**: 中
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现模式选择器
- [ ] 显示当前模式
- [ ] 生成中切换提示
- [ ] 切换动画

**依赖**: 1.3

**技术要点**:
- PopupWindow或BottomSheet
- 模式图标和说明
- pending状态显示

---

#### 任务2.6: Compose兼容性修复

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 修复流式生成布局闪烁
- [ ] 确保RecyclerView与Compose共存
- [ ] 测试滚动性能 ≥60fps
- [ ] 测试快速滚动无崩溃

**依赖**: 2.1, 2.2

**技术要点**:
- 使用Payload而非完整重绘
- 确保notifyItemChanged参数正确
- 检查布局文件冲突

---

#### 任务2.7: AI服务信息显示

**优先级**: 中
**预计时间**: 0.5天
**验收标准**:
- [ ] 显示当前模型信息
- [ ] 显示GPU层数
- [ ] 显示内存使用
- [ ] 显示推理模式（GPU/CPU）

**依赖**: 无

**技术要点**:
- 从AIService获取状态
- 实时更新显示

---

### M3: 附件处理实现

#### 任务3.1: Attachment数据结构完善

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 添加thumbnail字段
- [ ] 添加duration字段
- [ ] 添加mimeType完善
- [ ] 添加状态字段（上传中、成功、失败）

**依赖**: 无

**技术要点**:
- 扩展ChatMessage.Attachment类
- 保持向后兼容

---

#### 任务3.2: AttachmentViewHolder实现

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建AttachmentViewHolder类
- [ ] 实现附件图标显示
- [ ] 实现附件名称和大小显示
- [ ] 实现图片缩略图显示
- [ ] 实现上传进度显示

**依赖**: 3.1

**技术要点**:
- 根据类型显示不同布局
- 使用Glide/Coil加载图片
- 进度条动画

---

#### 任务3.3: 附件专用渲染器

**优先级**: 中
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现AttachmentRenderer接口
- [ ] 实现ImageAttachmentRenderer
- [ ] 实现DocumentAttachmentRenderer
- [ ] 实现AudioAttachmentRenderer
- [ ] 实现VideoAttachmentRenderer

**依赖**: 3.2

**技术要点**:
- 策略模式
- 根据MIME类型选择渲染器

---

#### 任务3.4: 附件操作逻辑

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现预览功能
- [ ] 实现保存功能
- [ ] 实现分享功能
- [ ] 实现删除功能
- [ ] 实现长按菜单

**依赖**: 3.2

**技术要点**:
- 使用系统Intent
- ContentProvider权限处理
- 文件操作权限

---

#### 任务3.5: 多附件Grid布局

**优先级**: 中
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现2x2 Grid布局
- [ ] 支持1-4个附件
- [ ] 点击放大预览
- [ ] 支持更多附件折叠

**依赖**: 3.2, 3.4

**技术要点**:
- GridLayoutManager或自定义Layout
- 动态计算列数

---

### M4: 消息队列与流式生成

#### 任务4.1: StreamingEvent定义

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建StreamingEvent类
- [ ] 定义所有事件类型
- [ ] 实现事件数据封装
- [ ] 实现事件序列化

**依赖**: 无

**技术要点**:
- Type枚举定义
- 数据泛型或Object
- 时间戳记录

---

#### 任务4.2: StreamingBroadcaster实现

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建StreamingBroadcaster类
- [ ] 实现subscribe方法
- [ ] 实现subscribeGlobal方法
- [ ] 实现broadcast方法
- [ ] 实现unsubscribe方法
- [ ] 测试多订阅者

**依赖**: 4.1

**技术要点**:
- ConcurrentHashMap存储订阅者
- CopyOnWriteArrayList保证线程安全
- 按messageId路由

---

#### 任务4.3: MessageQueue实现

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 创建MessageQueue类
- [ ] 实现submitMessage方法
- [ ] 实现cancelMessage方法
- [ ] 实现cancelAll方法
- [ ] 实现getTaskStatus方法
- [ ] 基于InferenceQueue增强

**依赖**: 无

**技术要点**:
- 优先级队列
- 并发控制
- 状态追踪

---

#### 任务4.4: 管道式流式生成

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 实现消息ID生成规则
- [ ] 实现事件流完整链路
- [ ] 实现发送接收端唯一ID关联
- [ ] 测试多消息并发
- [ ] 测试取消功能

**依赖**: 4.1, 4.2, 4.3

**技术要点**:
- ID格式: msg_{timestamp}_{random}_{sequence}
- 事件按ID路由
- 发送端和接收端通过ID关联

---

#### 任务4.5: ChatAdapter订阅集成

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] ChatAdapter订阅流式事件
- [ ] 实现TOKEN_APPENDED处理
- [ ] 实现STATUS_CHANGED处理
- [ ] 实现THINKING_STEP处理
- [ ] 实现MESSAGE_COMPLETED处理
- [ ] 测试事件完整流程

**依赖**: 4.1, 4.2

**技术要点**:
- 全局订阅所有消息
- 根据messageId找到对应位置
- 局部更新UI

---

### M5: 测试与优化

#### 任务5.1: 单元测试

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] ChatOrchestrator测试
- [ ] StreamingBroadcaster测试
- [ ] MessageQueue测试
- [ ] ChatModeManager测试
- [ ] 测试覆盖率 ≥60%

**依赖**: M1-M4

**技术要点**:
- JUnit 4
- Mockito
- Robolectric

---

#### 任务5.2: 集成测试

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 端到端流程测试
- [ ] 模式切换测试
- [ ] 流式生成测试
- [ ] 错误处理测试
- [ ] 取消功能测试

**依赖**: 5.1

**技术要点**:
- Android Instrumented测试
- 模拟AIService

---

#### 任务5.3: 性能测试

**优先级**: 中
**预计时间**: 0.5天
**验收标准**:
- [ ] 滚动帧率测试 ≥60fps
- [ ] 流式更新延迟测试 <16ms
- [ ] 内存占用测试 <100MB
- [ ] 模式切换响应时间 <100ms

**依赖**: 5.2

**技术要点**:
- Systrace
- Memory Profiler
- 自定义性能监控

---

#### 任务5.4: Bug修复与优化

**优先级**: 高
**预计时间**: 0.5天
**验收标准**:
- [ ] 修复所有测试发现的bug
- [ ] 性能瓶颈优化
- [ ] 内存泄漏修复
- [ ] 用户体验微调

**依赖**: 5.3

**技术要点**:
- 基于测试结果修复
- 渐进式优化

---

## 3. 任务依赖关系图

```
M1: 引擎修复与集成
├── 1.1 DeepThinkingEngine集成 ──┐
├── 1.2 CreativeWritingEngine集成 ─┤
├── 1.3 ChatModeManager完善 ──────┤
└── 1.4 ChatOrchestrator实现 ──────┘
                                    │
                                    ▼
M2: ChatAdapter功能实现
├── 2.1 DiffUtil实现
├── 2.2 流式更新优化
├── 2.3 思考过程可视化
├── 2.4 推理状态显示
├── 2.5 模式切换UI
├── 2.6 Compose兼容性修复
└── 2.7 AI服务信息显示
                                    │
                                    ▼
M3: 附件处理实现
├── 3.1 Attachment数据结构完善
├── 3.2 AttachmentViewHolder实现
├── 3.3 附件专用渲染器
├── 3.4 附件操作逻辑
└── 3.5 多附件Grid布局
                                    │
                                    ▼
M4: 消息队列与流式生成
├── 4.1 StreamingEvent定义
├── 4.2 StreamingBroadcaster实现
├── 4.3 MessageQueue实现
├── 4.4 管道式流式生成
└── 4.5 ChatAdapter订阅集成
                                    │
                                    ▼
M5: 测试与优化
├── 5.1 单元测试
├── 5.2 集成测试
├── 5.3 性能测试
└── 5.4 Bug修复与优化
```

---

## 4. 每日任务分配

### Day 1
- [ ] 1.1 DeepThinkingEngine集成
- [ ] 1.2 CreativeWritingEngine集成

### Day 2
- [ ] 1.3 ChatModeManager完善
- [ ] 1.4 ChatOrchestrator实现

### Day 3
- [ ] 2.1 DiffUtil实现
- [ ] 2.2 流式更新优化
- [ ] 2.3 思考过程可视化

### Day 4
- [ ] 2.4 推理状态显示
- [ ] 2.5 模式切换UI
- [ ] 2.6 Compose兼容性修复

### Day 5
- [ ] 2.7 AI服务信息显示
- [ ] 3.1 Attachment数据结构完善
- [ ] 3.2 AttachmentViewHolder实现

### Day 6
- [ ] 3.3 附件专用渲染器
- [ ] 3.4 附件操作逻辑
- [ ] 3.5 多附件Grid布局

### Day 7
- [ ] 4.1 StreamingEvent定义
- [ ] 4.2 StreamingBroadcaster实现
- [ ] 4.3 MessageQueue实现

### Day 8
- [ ] 4.4 管道式流式生成
- [ ] 4.5 ChatAdapter订阅集成

### Day 9
- [ ] 5.1 单元测试
- [ ] 5.2 集成测试

### Day 10
- [ ] 5.3 性能测试
- [ ] 5.4 Bug修复与优化

### Day 11
- [ ] 回归测试
- [ ] 最终验收
- [ ] 文档更新

---

## 5. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| 引擎集成复杂 | 延期1天 | 先集成普通模式，再扩展其他模式 |
| Compose兼容性问题 | 延期2天 | 优先修复核心流式更新，其他功能降级 |
| 附件类型过多 | 延期1天 | 分阶段实现：图片→文档→音视频 |
| 性能不达标 | 延期1天 | 增加性能监控，提前发现瓶颈 |
| 测试发现bug多 | 延期1天 | 增加测试覆盖，提前集成测试 |

---

## 6. 关键交付物

| 交付物 | 对应任务 | 验收人 |
|--------|---------|--------|
| 深度思考模式可用 | 1.1, 1.4 | 产品/测试 |
| 创意写作模式可用 | 1.2, 1.4 | 产品/测试 |
| 模式切换流畅 | 1.3, 2.5 | 产品/测试 |
| 流式生成无闪烁 | 2.2, 2.6 | 开发/测试 |
| 推理状态显示完整 | 2.4, 2.7 | 产品/测试 |
| 附件功能完整 | 3.1-3.5 | 产品/测试 |
| 消息队列稳定 | 4.1-4.5 | 开发/测试 |
| 性能达标 | 5.3 | 开发/测试 |
| 无严重bug | 5.1-5.4 | 测试 |

---

## 7. 变更管理

### 变更请求流程
1. 提交变更请求 → 评估影响 → 更新任务列表 → 执行变更 → 验证

### 延期处理
- 单个任务延期 ≤0.5天：吸收到缓冲时间
- 单个任务延期 >0.5天：评估优先级，调整后续任务
- 里程碑延期：重新规划，通知相关方

### 优先级调整
- 发现阻塞问题：立即提升优先级
- 用户需求变更：评估后调整
