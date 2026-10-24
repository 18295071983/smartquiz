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
AssistantTurn (parts 为准；modelCalls 只留每轮元信息)   ← 唯一权威
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
public AssistantTurn assistantTurn;          // canonical parts（modelCalls 全文不落库，见 §11.10）
public String content;                       // 兼容投影
public List<Integer> contentRoundBounds;     // 兼容投影
public List<String> contentRoundToolIds;     // 兼容投影
```

`AssistantTurn`：

```text
version
turnId
modelCalls[]
  id, round, stopReason, timestamp, toolCallIds
  content(fullText), reasoning        ← transient：只在内存，不落库（§11.10）
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

---

## 11. 完整接管剩余设计（P3f 起，逐类复刻→逐类放开→最后接线）

现状：接管闸门只允许 `text`/`reasoning`，其余 part 的消息整条回落 legacy（P3e）。要"全量接管"，
每类都要复刻 legacy 在 `contentHost`/`componentContainer` 里手搭的子布局，逐类真机验证后才从回落名单移出。
下列每项 = 目标 legacy 行为 + parts 侧做法 + 需要的 PartHost 能力 + 验收。

### 11.1 工具：单卡（已做）→ 组级折叠（待做）

- legacy 目标：`buildActivityGroups` 把一轮内连续 TOOL 过程聚成"已运行 N 个工具"一行，
  全部完成后**默认折叠**、点击展开列每个工具+结果；执行中保持实时展开（`ChatAdapter:1906/3401`）。
- parts 做法（复用现有 attached 机制，不引入第二套）：
  - `ToolCallPartRenderer.anchorIdFor`：同一"折叠组"内非首个 tool_call → 锚到该组首个 tool_call 的
    组卡 id（新增一个 **GroupCard** 由组首 part 建，其余 tool_call 用 `bindView` 往组卡追加一行）。
  - 组卡容器需要"折叠头(已运行N个工具) + 展开体(逐工具行)"，展开态记在 `ChatMessage`（新增
    `toolGroupExpandedIds` 或复用 `isThinkingRoundExpanded` 同款 keyed 存）。
  - 判"整组完成才折叠"：`anchorIdFor` 需要看到全序 parts（已具备），据组内是否有 running/pending 决定折叠。
- PartHost 新增：`boolean toolGroupExpanded(String groupId)` / `toggleToolGroup(String groupId)`；
  结果行限长/「查看全部」走 `ToolCallCardView`/`ComponentRegistry` 既有能力（单卡已复用）。
- 验收：多工具串行/并行/两轮同名 → 折叠成一行、点开展示每个、结果对上、不串卡；与 legacy 截图一致。

### 11.2 工具结果 AI 深度解读覆盖层（待做，可延后）

- legacy：`buildResultWithInterpretChip`（`ChatAdapter:4349`）——结果预览 + 「查看全部」+ 「AI 深度解读」chip，
  依赖 `ToolCallInfo.canInterpret/interpretedMessage/interpretationDone`。
- parts 做法：解读是**交互+异步**行为，属工具卡的扩展。短期：parts 工具卡只到"结果限长+查看全部"
  （`ToolCallCardView` 已给 summarize 预览），解读 chip 暂不接管（含未解读标记的消息可仍走 parts，仅少一个 chip）
  或**列入回落**直到做齐。决策：解读 chip 依赖模型可用性 + 点击回调，放最后；先保证不崩、不重复卡。
- PartHost 新增：`void requestInterpret(String toolCallId)`；`boolean canInterpret(...)`。

### 11.3 产物 / data 卡（档位已做）→ 与工具卡同属分组挂载（待做）

- legacy：`mountRoundComponent` 里 tool_call 不参与 `ComponentDisplay` 分档；产物卡 INLINE/PEEK/HIDDEN，
  PEEK 用 `wrapProductPeek`（已复刻到 `DataPartRenderer`）。**产物挂在所属工具卡下**（§4.6）尚未做：
  data part 若其 ComponentData 关联某 toolCallId → `anchorIdFor` 锚到该工具组卡，而非独立顶层。
- 做法：`DataPartRenderer.anchorIdFor`：`comp.props.optString("toolCallId")` 命中某 tool_call → 返回其组卡 id。
  无归属 → 独立 data part（顶层）。HIDDEN 不挂（已做）。
- 验收：天气/图表等产物卡，随工具出现时挂在卡下、独立时顶层，peek/hide 与 legacy 一致。

### 11.4 mermaid / 数学公式（当前回落，待做）

- legacy：`renderMermaidMath`（`ChatAdapter:3527` 分支）把 ` ```mermaid ` 与 `$$..$$` 交 WebView(html) 专门渲染。
- parts 做法：`expandInline` 里，text 段命中 `MermaidMathSplitter.containsStructure` 时，**不再回落**，
  而是切成 `data` part（type=chart? 用现成 html/mermaid 组件），交 `ComponentRegistry` 的 Html/Mermaid 卡渲染。
  即把 mermaid/math 归一到"内联 data part"，与 §11.3 同一渲染器。
- 前置：确认 `ComponentRegistry` 有 mermaid/katex 组件（有 `HtmlCardView`；mermaid 是否独立组件需核）。
- 验收：含流程图/公式的回答在 parts 下正常出图，不再整条回落。

### 11.5 围栏代码（web 关时的 CodeCardView）

- legacy web 开：`MarkdownWebView` 自带 highlight.js（parts 走同一 WebView → 已一致）。
  web 关：legacy 用 `renderWithCodeCards`/CodeCardView；parts 原生走 `renderInto`（Markwon+Prism4j 高亮，内联代码块）。
- 决策：parts 原生代码块用 renderInto 的高亮即可，不强求 CodeCardView 卡壳；若目视不一致再补 data part 化。

### 11.6 流式（保持现状）

- 流式（未完成）仍走 legacy 旧路径，不进 parts；parts 只接管已完成消息。§11 全部完成后仍维持此边界
  （AI SDK 也是"定稿结构 + 局部流式"分离）。`part.state=streaming/cancelled` 留给在线引擎在需要时写。

### 11.7 审批 / error part（无生产者，暂不参与接管）

- 无 part 生产者前不进 allowed 名单；审批源接入（在线引擎产出 approval part）后再单独做交互卡 + 回调。

### 11.8 放开顺序与接线（“接”留最后）

1. data 独立卡（11.3 顶层部分）→ 真机对齐 → allowed 加 `data`；
2. 工具组折叠（11.1）→ 真机 → 加 `tool_call/tool_result`；
3. 产物归工具卡（11.3 归属）→ 真机；
4. mermaid/公式（11.4）→ 真机 → 去掉 `MermaidMathSplitter` 回落；
5. 全绿后 P4：`parts_renderer` 默认 true；P5：新消息不再写 `contentRoundBounds`、旧数据补 `assistantTurn`、删 legacy 分支。
- 每步只扩 `ChatAdapter.bindWithParts` 的 allowed 判定（覆盖不了仍整条回落，见 P3e），单步可回退。

### 11.9 历史恢复的 parts 归一（P5 第一步，2026-10-24 已做）

恢复口径在今天是**分裂**的：`assistantTurn` 虽然落盘（非 transient，`deepCopyForSave` 显式补拷），
但只有 2026-10-21 之后的新消息才有；而 `bindWithParts` 渲的又不是我读出来的那份——它每次绑定都
`fromMessageForParts(message)` 从 `roundSnapshots`/`content`/`components` 现建。于是同一条消息有两套
part 顺序：落盘那份参与 `applyToMessage` 投影，重建那份参与渲染，两者一旦不一致就表现为
"退出重进后思考/正文/工具/产物的时序和实时不一样"。

收口做法（**只有一个顺序生产者**）：

- `AssistantTurn.fromMessage` 补齐旧数据形态：无 `roundSnapshots` 但有 `contentRoundBounds` 时，
  按 legacy `renderRoundAssembled` 的同一规则切正文（第 i 段 = `content[bound[i-1], bound[i])`，
  第 i 个工具跟在段 i 之后），思考取 `thinkingRounds[i]`。实时提交与历史恢复从此走同一个方法、
  同一套入参，顺序不再有两个来源。
- `fillToolPartsFromComponents` 加"按序就地归位"一趟：边界里没记 `toolCallId` 的极旧数据，
  原先一律 `补到末尾`，多轮工具的卡片会整体沉底；现按出现顺序与无 id 的工具组件配对回填，
  配不上组件的空壳 part 直接摘除（否则会渲一张空白工具行）。兜底的末尾追加保留，但时间戳
  改取消息时间（原 `System.currentTimeMillis()` 让每次重建的 part 时间都不同）。
- `AssistantTurnRestore`（新）挂在加载出口，与 `RoundBounds.normalizeAll` 同一时机：
  `loadAIChatHistory` / `loadAgentChatHistory` / `loadConversationSession` / 分段 `readArrayInto`。
  它把每条已完成 AI 消息的 `assistantTurn` 换成 `fromMessage` 的结果 —— 旧数据由此补上 turn
  （parts 闸门得以接管，新旧同口径），新数据由此丢弃旧版本落盘的错位顺序。
- 两条护栏：重建不出任何 part 的消息**不回填**（否则 `canRenderAll(空)` 为真，会接管并渲出空气泡）；
  落盘 turn 若含 `fromMessage` 造不出的 part（error/approval，§11.7），保留落盘原样不覆盖。
- `AssistantTurn.textPartsAreSlices`（新，落盘字段）标记"text parts 是 content 的连续切片"。
  此时 content/bounds 才是真值，`applyToMessage` 必须原样跳过——切片里"只剩空白"的段不进 parts，
  而 `"\n\n"` 分隔规则又会凭空加行，一来一回每次重开历史正文都在漂移。`ChatAdapter` 的接线因此**不用改**。
- 热更新合并（`pollHistoryForHotReload`）在同步终态时一并接上文件里已归一的 `assistantTurn`，
  免得界面那条停在"无 turn"状态被闸门判回 legacy，与重进后看到的不一。


### 11.10 落盘形状去重：ModelCall 全文改 transient（2026-10-24）

§11.9 之后按真机视角核了一遍"历史文件到底长什么样"，用一条"3 轮 × 400 字正文 + 3 个各带 500 字结果
的工具"的消息实测：

| 字段 | 字节 |
|---|---|
| 整条消息 JSON（改前） | 13224 |
| `assistantTurn` | 6859（其中 `parts` 4654、`modelCalls` 2128） |
| `roundSnapshots` | 2038 |
| `components` | 1816 |
| `content` | 1204 |

同一段正文在文件里有**四份**：`content`（兼容投影）、`parts[].text`（顺序真值）、
`modelCalls[].content`、`roundSnapshots[].content`（每轮全量）。思考同样四份，工具结果两份
（`components.props.result` + `parts[].result`）。其中 `modelCalls` 全仓**零消费者**——除 `copy()`
与单测外没有任何代码读它，渲染、上下文、恢复都不碰；`fromMessage` 要每轮全文读的是 `roundSnapshots`。
所以那一份是纯复制，占整条消息的 16%。这个比例直接连着裁剪闸门：单文件超 64MB 就砍最早的 1/4 条、
最多 4 轮（`trimBySize`），重复正文等于把用户历史往刀下推。

处理：`ModelCall.content` / `.reasoning` 标 `transient`，`modelCalls` 只留
`id/round/stopReason/timestamp/toolCallIds` 这些 parts 没有的元信息；内存里全文照旧可得
（`fromMessage` 仍然填），只是不再落盘。实测同一条消息 **13224 → 11316（−14.4%）**，
turn 本身 **6859 → 4963（−27.7%）**，正文份数 4 → 3。

两条回归把它钉住：`AssistantTurnRestoreTest.modelCallFullTextIsNotPersistedButMetadataIs`
（turn 的 JSON 里正文/思考各只出现一次）与 `ChatHistoryManagerSessionTest.persistedFileCarriesBodyTextOnlyTwice`
（真文件里正文只两份——那条夹具是**切片口径、没有 roundSnapshots**，所以是两份而不是三份，
别把这条断言当成"全项目正文只两份"）。A/B 过：把两个 `transient` 摘掉，恰好这两条变红
（真文件那条报"实际=3"），放回即全绿。

旧文件兼容：Gson 读未知成员会跳过，所以改版前落盘的 `modelCalls.content/reasoning` 不会报错，
也只是没人读。

**真机数据实测（2026-10-24 20:29 用户刚产生的会话，12 条 / 139321B，由本轮改动之前的 19:35 包写入）**：

| 组成 | 字节 | 占整文件 |
|---|---|---|
| `assistantTurn` | 67500 | 48.4%（其中 `parts` 53555、`modelCalls` 13591 = 9.8%） |
| `components` | 39087 | 28.1%（卡片与工具结果本体，不是重复） |
| `roundSnapshots` | 12573 | 9.0% |
| `content` | 2967 | 2.1% |

把这份真文件放进 Robolectric 的 `filesDir/conversations/` 走**真加载出口**
（`loadConversationSession`）跑了一遍：6 条 AI 消息恢复后的 parts 顺序与落盘**逐条一致**——包括一条
30 个 part 的长 Agent 回答、一条边界全为 `[0,0,0,0,0]`（工具都挂在正文之前）的形状；
`content` 零漂移。再用新代码重落盘，139361 → 129077B（−7.4%），正文份数 4 → 3。

**没动的重复，另拍**：剩下三份正文里，`roundSnapshots` 与 `parts[].text` 是同一信息的两种表达，
理论上能再收一层——但 `fromMessage` 的每轮全文就是从 `roundSnapshots` 读的，动它等于改"谁是每轮权威"，
是设计取向不是纯去重。工具结果两份同理（legacy 渲染仍以组件为源，要连 `Part.products` 的
transient 口径一起想）。
