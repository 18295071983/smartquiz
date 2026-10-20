# Chat Parts 架构升级与 qcoder 交接文档

- 状态：设计定稿，待实施
- 交接对象：qcoder
- 基线提交：`37f2b581`（`feat(chat-ui): 绑定前从 AssistantTurn parts 重算展示投影`）
- 基线验证：全量单测 **486 / 0 skipped / 0 failed / 0 errors**，`assembleDebug` 通过，已装机
- 文档位置：`docs/ui/parts-architecture-upgrade.md`
- 关联代码：`AssistantTurn`、`ChatMessage`、`ChatAdapter`、`AIChatActivity`、`AgentLoopEngine`、`OnlineAgentEngine`

---

## 1. 交接目标

把当前“**parts 为数据权威、content/bounds 为投影、UI 仍按投影渲染**”的过渡架构，升级为：

```text
AssistantTurn (parts + modelCalls)   ← 唯一权威
        ↓
PartRendererRegistry                 ← 按 part.type 分发
        ↓
PartViewCache / diff                ← 稳定 id、只更新变化的 part
        ↓
Android View 树（文本/思考/工具/产物）
```

完成后：

- UI 按 `parts` 渲染，不再依赖 `contentRoundBounds` 决定工具位置；
- `content` / `contentRoundBounds` 只保留为搜索、复制、旧数据兼容的投影缓存；
- 本地/在线 Agent 同构；
- 不出现工具卡重复、WebView/播放器状态丢失、全量重建闪烁。

**不是**要求每个 bind 都 `for (Part) removeAllViews`。这是本升级最重要的禁区。

---

## 2. 当前实现（qcoder 先读）

### 2.1 数据层已具备

`ChatMessage`：

```java
public List<RoundSnapshot> roundSnapshots;   // 每轮 fullText/thinking/toolIds
public AssistantTurn assistantTurn;          // canonical parts/modelCalls
public String content;                       // 兼容投影
public List<Integer> contentRoundBounds;     // 兼容投影
public List<String> contentRoundToolIds;     // 兼容投影
```

`AssistantTurn`：

```text
version
turnId
modelCalls[]
  id, round, content(fullText), reasoning, stopReason, timestamp, toolCallIds
parts[]
  reasoning / text / tool_call / tool_result / error
```

关键方法：

- `AssistantTurn.fromMessage(ChatMessage)`：从 `roundSnapshots` + tool components 构建；
- `AssistantTurn.applyToMessage(ChatMessage)`：按 parts 派生 `content` 和 bounds；
- `AssistantTurn.deriveText()`：只拼 text parts。

完成时：

```java
finalMsg.assistantTurn = AssistantTurn.fromMessage(finalMsg);
finalMsg.assistantTurn.applyToMessage(finalMsg);
```

### 2.2 UI 仍是投影渲染

`ChatAdapter.bindMessageContent()`：

```java
if (message.isCompleted() && message.assistantTurn != null) {
    message.assistantTurn.applyToMessage(message);
}
// 然后走 renderRoundAssembled / bindComponents / MarkdownWebView
```

所以：

- 数据入口已统一到 parts；
- 视觉树仍由 `contentRoundBounds` 和 `components` 驱动；
- `contentRoundBounds` 现在是 parts 派生值，不是手写真值。

### 2.3 已完成的上游修复

- 完成态使用 native/在线 `complete` 的 `fullText`，不再用流式缓冲覆盖；
- 本地/在线每轮 fullText 进入 `roundSnapshots`；
- 本地工具调用透传稳定 `toolCallId`；
- 工具结果进入 `TOOL_RESULT` part；
- 表格完成态 WebView 整篇重载，按最终宽度重排；
- 按轮次刷新 UI，生成中不逐 token 重排；
- 思考 token 回调整体切主线程；
- 语音与按轮次正文同步；
- 完成态子块错峰入场动画。

---

## 3. 为什么不能裸 `for (Part)`

必须写进验收：

```java
// 禁止
container.removeAllViews();
for (Part p : parts) container.addView(createView(p));
```

原因：

1. 每次 bind 重建 WebView/ExoPlayer/Glide 目标，状态和性能双输；
2. 折叠、滚动、播放、选中、链接状态丢失；
3. 工具卡/产物卡会与现有 `bindComponents` 双渲染；
4. 长 Markdown/表格测量抖动；
5. 无障碍顺序、跨段选择复杂化。

正确模式：

```text
parts 变化
  → 按 partId 找已有 View
  → 类型/内容指纹相同则复用
  → 只 bind 变化的 part
  → 删除消失的 part，释放资源
```

---

## 4. 目标架构

### 4.1 Part 稳定身份

给每个 part 增加稳定 id（持久化字段）：

```java
public String id;        // turnId + "-p" + seq（构建时生成，之后不变）
public String type;      // text/reasoning/tool_call/tool_result/error/data
public String toolCallId; // tool 类必填
```

规则：

- `text`/`reasoning`：`turnId + "-p" + 单调序号`；
- `tool_call`/`tool_result`：优先用真实 `toolCallId`，没有则构建时补 `local_tool_N`；
- 重排/重试后 id 不变；
- 兼容旧数据：没有 id 时按 `type + position` 派生，不写回。

### 4.2 PartRenderer 接口

建议包：`com.oilquiz.app.ai.chat.parts`

```java
public interface PartRenderer {
    boolean canRender(Part part);
    View createView(Context ctx, ViewGroup parent);
    void bindView(View view, Part part, PartHost host);
    void releaseView(View view);
}
```

`PartHost` 需要提供：

- `availableWidth`；
- 当前 `ChatMessage`；
- 点击/长按/复制/链接路由回调；
- 子组件挂载/释放（给 tool/data renderer 用）；
- 滚动宿主（用于 WebView 高度变化后 requestLayout）。

### 4.3 Registry

```java
public final class PartRendererRegistry {
    void register(String type, PartRenderer renderer);
    PartRenderer resolve(Part part);
}
```

默认注册顺序：

| type | renderer | 复用现有 |
|---|---|---|
| `text` | `TextPartRenderer` | `MarkdownRenderer` / `MarkdownWebView` |
| `reasoning` | `ReasoningPartRenderer` | 现有 think line / `ThinkingStepRow` |
| `tool_call` | `ToolCallPartRenderer` | `ToolCallCardView` / `ComponentRegistry` |
| `tool_result` | `ToolResultPartRenderer` | 工具卡内 result 区，不单独建卡 |
| `error` | `ErrorPartRenderer` | 现有错误行 |
| `data` | `DataPartRenderer` | `ComponentRegistry` |

### 4.4 PartViewCache

`TurnPartsHost`（可以是 `LinearLayout` 子类或 ChatAdapter 内部管理器）：

```java
Map<String, View> viewById;
Map<String, String> fingerprintById;
```

bind 流程：

```text
for each part in order:
    view = viewById.get(part.id)
    if view == null or type changed:
        create + add at correct index
    fingerprint = type|textHash|status|resultHash
    if fingerprint changed:
        renderer.bindView(view, part, host)
    mark seen
remove/release unseen views
```

要求：

- 不能每次 `removeAllViews`；
- 不能对相同 fingerprint 重复 bind；
- 删除 view 必须 `releaseView`；
- 动画只在 part 首次出现/完成态首次绑定时播放，滚动回滚不重播。

### 4.5 文本 part 的边界

- 一个 `text` part 对应一个 Markdown 渲染器实例（WebView 或 TextView）；
- 同一条 AI 消息多个 text part 时，不使用一个巨大 WebView 拼接，避免局部更新拖累全文；
- 表格/代码/Mermaid 的宽度问题沿用 `MarkdownWebView` 的完成态重载逻辑；
- WebView 数量上限沿用 `setMaxRecycledViews` 策略，`releaseView` 必须 `destroy`/清缓存。

### 4.6 工具卡归属

**唯一 owner 原则**：

- `tool_call` part 渲染工具卡；
- `tool_result` part 只把 result 写进对应工具卡，不新建卡；
- 产物卡（`data` / withComponent）挂在所属工具卡下，不重复走 `bindComponents`；
- 同一 `toolCallId` 只能有一个工具卡 View。

切换开关：

```java
PartHost.partsOwnToolCards = true; // parts 渲染器接管
```

为 true 时，`bindComponents` 必须跳过 `tool_call` 类型组件，避免双卡。
为 false 时走旧路径。

### 4.7 流式与完成

- 流式：只更新当前 `text` part 的局部文本；不重建其它 part；
- 完成：用 `fullText` 更新最后一个 text part，再落库；
- `tool_call`：start 插入 part，complete 更新同 id 的 status/result；
- `reasoning`：流式只更新当前 reasoning part；
- 不把 token 写入 `ChatMessage.content`。

---

## 5. 迁移阶段（qcoder 按顺序做）

### P0：冻结基线

- [ ] 从 `main` 拉分支 `feat/chat-parts-renderer`；
- [ ] 跑 `.\gradlew.bat --offline testDebugUnitTest`，确认 486 绿；
- [ ] 不改 `AssistantTurn` 现有字段语义，只加不改。

### P1：Part 身份与指纹

- [ ] `AssistantTurn.Part` 增加 `id`；
- [ ] `fromMessage` 为所有 part 生成稳定 id；
- [ ] 增加 `fingerprint()`（type + text/status/result 的 hash）；
- [ ] 单测：同一消息重复 `fromMessage` 的 id 稳定；重排后 id 不变。

验收：`AssistantTurnTest` 新增 3 条。

### P2：PartRenderer + Text/Reasoning

- [ ] 新建 `parts` 包与 `PartRenderer`/`Registry`/`PartHost`；
- [ ] `TextPartRenderer` 复用 Markdown 渲染；
- [ ] `ReasoningPartRenderer` 复用 think line；
- [ ] `TurnPartsHost` 支持 viewById + fingerprint；
- [ ] feature flag：`ai_chat_prefs.parts_renderer` 默认 false。

验收：纯文本、标题、列表、表格、代码块在 flag=true 时与旧 UI 一致；
flag=false 完全走旧路径。

### P3：Tool/Data Renderer

- [ ] `ToolCallPartRenderer` 复用 `ToolCallCardView`，按 `toolCallId` 更新；
- [ ] `ToolResultPartRenderer` 只更新已有工具卡；
- [ ] `DataPartRenderer` 复用 `ComponentRegistry`；
- [ ] parts 模式下 `ChatAdapter.bindComponents` 跳过 tool_call 组件；
- [ ] 并行同名工具、两轮同名工具不串卡。

验收：多工具串行/并行、工具失败、产物卡、折叠展开、重试都正常。

### P4：切换默认

- [ ] flag 默认 true；
- [ ] 旧 `contentRoundBounds` 渲染只保留给 legacy（无 `assistantTurn`）；
- [ ] 滚动、回滚、旋转、会话恢复、搜索、复制、导出全过；
- [ ] 性能：长消息/多工具不出现明显掉帧或内存上涨。

### P5：删除旧路径

- [ ] `contentRoundBounds` / `contentRoundToolIds` 从新消息写入路径移除；
- [ ] `renderRoundAssembled` 只保留旧数据迁移入口，或彻底删除；
- [ ] `ChatHistoryManager` 版本迁移：旧数据补 `assistantTurn`；
- [ ] 更新 `HANDOVER.md` 和本文件状态。

---

## 6. 测试矩阵

### 数据

- [ ] 单轮纯文本
- [ ] 多轮思考 + 最终回答
- [ ] 单工具/多工具/并行同名工具
- [ ] 工具失败/超时/取消
- [ ] 产物卡（list_card/image_grid/table_card）
- [ ] 在线/本地同构
- [ ] 旧数据无 `assistantTurn` 回退

### UI

- [ ] 流式更新不闪烁、不跳滚动
- [ ] 完成态动画只播一次
- [ ] 工具卡 running → success/failed 同卡更新
- [ ] 折叠/展开状态不丢
- [ ] WebView 表格/代码/Mermaid 宽度正确
- [ ] 图片/视频/音频不重复创建
- [ ] 复制/导出/搜索仍可用
- [ ] TalkBack 顺序合理
- [ ] 深色模式/字号缩放

### 性能

- [ ] 2000 字 Markdown 滚动帧率
- [ ] 10 个工具卡连续更新
- [ ] 反复绑定同一消息不重复创建 View
- [ ] WebView 缓存上限不超

---

## 7. 风险与回滚

| 风险 | 处理 |
|---|---|
| 工具卡双渲染 | `partsOwnToolCards` + 单测断言组件数 |
| WebView 泄漏 | `releaseView` 必须 destroy；缓存上限 |
| 状态丢失 | viewById 复用；不 removeAllViews |
| 旧数据崩溃 | `assistantTurn == null` 全走旧路径 |
| 表格宽度错 | 复用 `MarkdownWebView` 完成态重载 |
| 性能回退 | flag 可关；对比旧路径 |

回滚：

- 关闭 `ai_chat_prefs.parts_renderer`；
- 保留 `content/bounds` 投影；
- 不删旧字段，直到 P5 验收通过。

---

## 8. 给 qcoder 的开工清单

```powershell
cd D:\qzq\smartquiz
git status --short
git switch -c feat/chat-parts-renderer
.\gradlew.bat --offline testDebugUnitTest
```

第一条任务：

> 只做 P1：给 `AssistantTurn.Part` 加稳定 id 和 fingerprint，补单测。
> 不改 ChatAdapter，不改 UI，不删 `contentRoundBounds`。

提交规则：

- 每个 Phase 一个提交；
- 不 `git add -A`；
- 不碰 `build/`、`_archive/`、`libs/`；
- 不删除旧字段，直到 P5；
- 每次提交更新 `CHANGELOG.md` 和 `HANDOVER.md`。

完成后必须报告：

- 改了哪些文件；
- 测试数量/失败数；
- flag 开/关两种路径的验证结果；
- 是否还有双渲染/状态丢失；
- 下一步建议。

---

## 9. Definition of Done

- `parts_renderer=true` 时，AI 消息视觉树由 `AssistantTurn.parts` 驱动；
- `content`/`bounds` 只作为投影缓存；
- 无工具卡重复、无全量重建闪烁、无 WebView/播放器状态丢失；
- 本地/在线一致；
- 旧数据可回退；
- 全量单测 ≥486 全绿，`assembleDebug` 通过；
- `HANDOVER.md` 更新到可交接状态。

---

## 10. 参考（业界）

- Vercel AI SDK `UIMessage.parts`：
  https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message
- assistant-ui `MessagePrimitive.Parts` / `GroupedParts` / stable id：
  https://www.assistant-ui.com/docs/primitives/message
- OpenAI Responses streaming events（item/content part 粒度）：
  https://mintlify.wiki/openai/openai-python/api/responses/stream
- Anthropic Messages content blocks：
  https://platform.claude.com/docs/en/api/messages

核心结论：**parts 是数据权威；渲染用类型注册表 + 稳定 key + 增量复用，不是裸 for。**
