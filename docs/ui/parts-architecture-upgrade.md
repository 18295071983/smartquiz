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
- [x] feature flag：`ai_chat_prefs.parts_renderer` —— P2 建时默认 false，**P4 已于 2026-10-24 翻成出厂 true**。

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
| 旧数据崩溃 | ~~`assistantTurn == null` 全走旧路径~~（P6 起闸门不再拿它当令牌；真正的兜底是"构不出可渲 part 就回落"） |
| 表格宽度错 | 复用 `MarkdownWebView` 完成态重载 |
| 性能回退 | flag 可关；对比旧路径 |

回滚：

- 把 `ai_chat_prefs.parts_renderer` **显式写成 false**（出厂档 P4 起是 true，所以删键无效——
  产品里没有设置页入口，只能 `adb shell run-as com.oilquiz.app` 改 prefs）；只退流式就写
  `parts_streaming=false`，完成态接管照旧；
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

现状（2026-10-24 更新，原文写于 P3e 当时）：闸门 allowed 已扩到 `text`/`reasoning`/`tool_call`/`tool_result`/
`tool_group`/`data`（P3f–P3j 逐类复刻后放开），"其余 part 整条回落"在现网**已不可达**——见 §11.7。
仍要记住的机制：每类都要复刻 legacy 在 `contentHost`/`componentContainer` 里手搭的子布局，逐类真机验证后才从回落名单移出。
下列每项 = 目标 legacy 行为 + parts 侧做法 + 需要的 PartHost 能力 + 验收。

### 11.1 工具：单卡（已做）→ 组级折叠（P3h 已做）

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

### 11.3 产物 / data 卡（档位已做）→ 与工具卡同属分组挂载（P3i 已做）

- legacy：`mountRoundComponent` 里 tool_call 不参与 `ComponentDisplay` 分档；产物卡 INLINE/PEEK/HIDDEN，
  PEEK 用 `wrapProductPeek`（已复刻到 `DataPartRenderer`）。**产物挂在所属工具卡下**（§4.6）已由 P3i 落地。
- 做法（草案 → 实际实现有差别，别再照草案找 `anchorIdFor`）：草案设想由 `DataPartRenderer.anchorIdFor`
  把 data part 锚进工具卡视图；实际改走**模型层归属**——`AssistantTurn.attachOwnedProducts`
  （`AssistantTurn:367`，在 `expandInline` 之后、`groupTools` 之前）读
  `ComponentData.props` 里的 **`producerToolCallId`/`producerToolName`**（不是 `toolCallId`，与 legacy
  `productOwnerKey` 同口径），把带生产者的 data part 折进所属 `tool_call` 的 `Part.products`，
  渲染由工具卡一侧作**单写者竖排 `[工具卡]+[产物卡…]`**。原因：attached 机制要在 anchor 视图里
  `removeAllViews` 再挂子 View，会与"工具卡自身重绑"互抹（同"工具丢结果"那一类双写 bug）。
  无归属（内联/mermaid）留顶层；HIDDEN 不挂（已做）。
- 验收：天气/图表等产物卡，随工具出现时挂在卡下、独立时顶层，peek/hide 与 legacy 一致。

### 11.4 mermaid / 数学公式（P3g 已做，回落已去掉）

- legacy：`renderMermaidMath`（现位于 `ChatAdapter:3003`，原文档写的 `:3527` 已漂移）把 ` ```mermaid ` 与 `$$..$$`
  交 WebView(html) 专门渲染。
- parts 做法（已落地）：`expandInline(base, dark)` 里 text 段含 component 围栏标记时用 `ComponentContentSplitter`；
  否则命中 `MermaidMathSplitter.containsStructure` 时用 `MermaidMathSplitter.split(text, dark)` 切成**交错 text+html data part**
  （与 legacy 同一组件源），交 `ComponentRegistry` 的 html 卡渲 mermaid.js / katex。**不再整条回落**。
- 前置（已核）：mermaid/katex 不是独立注册组件，是 `MermaidMathSplitter` 直接产 **html 组件**、由 `HtmlCardView` 渲。
- 验收：含流程图/公式的回答在 parts 下正常出图，不再整条回落。

### 11.5 围栏代码（web 关时的 CodeCardView）

- legacy web 开：`MarkdownWebView` 自带 highlight.js（parts 走同一 WebView → 已一致）。
  web 关：legacy 用 `renderWithCodeCards`/CodeCardView；parts 原生走 `renderInto`（Markwon+Prism4j 高亮，内联代码块）。
- 决策：parts 原生代码块用 renderInto 的高亮即可，不强求 CodeCardView 卡壳；若目视不一致再补 data part 化。

### 11.6 流式（本文原写"保持现状"，已由 §11.11 接管）

- 原边界：流式（未完成）走 legacy，parts 只接管已完成消息。**2026-10-24 这条已经作废**，见 §11.11；
  仍保留的设计判断是"`part.state=streaming` 由产出侧写、渲染器按 state 收尾"。

### 11.7 审批 / error part（无生产者；且这两类内容在本工程根本不以 part 形态存在）

- 无 part 生产者前不进 allowed 名单；审批源接入（在线引擎产出 approval part）后再单独做交互卡 + 回调。
- **2026-10-24 核实（更正早前"命中就整条回落 legacy"的说法）**：`fromMessageForParts` 只产
  text/reasoning/tool_call/tool_result/tool_group/data 六种，生产代码里**没有任何写者**造 error/approval part
  （`Part.approval(...)`、`TYPE_ERROR` 仅出现在测试与各自渲染器的 `canRender`）。
  于是 `bindWithParts` 里那句 `if (!allowed) return false` 目前是**不可达的防御分支**，不是现网会走到的回落路径。
- 这两类内容现在的真实通道（都不受 parts 接管影响，翻 P4 也不改变其显示）：
  - error：① 独立错误消息行 `ChatMessage.createErrorMessage()` → `MessageType.ERROR` → `VIEW_TYPE_ERROR`
    → `ErrorMessageViewHolder`/`bindErrorMessage`（另一个 ViewHolder，`bindMessageContent` 只对
    `AIMessageViewHolder` 调用）；调用方 `AIChatActivity:9834`、`AIChatCoordinator:210`、`AIChatViewModel:461`、
    `AgentDebugActivity:632`。② AI 消息自身失败/取消：`ChatAdapter.failMessage`/`cancelMessage`
    置 `MessageStatus.FAILED` + `errorDetail`，展示在 holder 的**状态行**与 v2 **操作段**
    （失败才留状态行、ERROR/FAILED 也显示"重新生成"，`ChatAdapter:1177-1199`）——接管只换 `contentHost`，
    这两处按设计继续由 legacy 写。
  - approval：① `permission_manager(action=request_and_wait, permission=…)` 是一次**普通工具调用**，
    弹 Android 系统权限对话框，聊天流里就是工具卡（已接管）；② `ui_component` 的 `get_result` 阻塞等用户点
    组件按钮（`OnlineAgentEngine:851-854` 给它单独长超时），走 data part（已接管）；html 卡 `submit` 同族。
- 因此 `ApprovalPartRenderer` + `PartHost.onApprovalDecision` 是为"审批块进气泡（同意/拒绝按钮）"这种**尚未决定要做**
  的形态预留；`ChatAdapter:3551-3555` 的 `onApprovalDecision` 实现是空占位，`PartHost` 里也**没有** §11.2 设想的
  `requestInterpret/canInterpret`。按"有渲染器无生产者"记为死链保留，不是接管缺陷。

### 11.8 放开顺序与接线（“接”留最后）

1. ~~data 独立卡（11.3 顶层部分）→ 真机对齐 → allowed 加 `data`~~（已做：P3d/P3f）；
2. ~~工具组折叠（11.1）→ 真机 → 加 `tool_call/tool_result`~~（已做：P3h，组内为 legacy 紧凑行）；
3. ~~产物归工具卡（11.3 归属）→ 真机~~（已做：P3i，改走模型层 `attachOwnedProducts`）；
4. ~~mermaid/公式（11.4）→ 真机 → 去掉 `MermaidMathSplitter` 回落~~（已做：P3g，切成 html data part）；
5. ~~全绿后 P4：`parts_renderer` 默认 true~~（**已做，2026-10-24 用户指令"出厂仍是 false，也打开"**，
   `PartsRendererFlag.DEFAULT_ENABLED=true`，`PartsStreamingFlag` 出厂同样 true）；
   P5 剩余未做：新消息不再写 `contentRoundBounds`、删 legacy 分支（旧数据补 `assistantTurn` 已做，见 §11.9）。
- 每步只扩 `ChatAdapter.bindWithParts` 的 allowed 判定（覆盖不了仍整条回落，见 P3e），单步可回退。
  注：闸门保留是为了"将来真加 error/approval 生产者时不会被半吊子渲染吃掉"，**当前不可达**（见 §11.7）。

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

### 11.11 流式接管（P6，2026-10-24 已做，子开关 `parts_streaming` 出厂 true）

先纠正一个把人带偏的前提：**本工程的"流式"不是逐 token 重绘**。`AIChatActivity.ROUND_BATCH_RENDER=true`
（:345）让 `safeUpdateMessage()` 直接 return（:7620），正文只在**轮次边界**（工具调用前
`flushRoundContentToMessage` :7602）刷进 `msg.content`；逐 token 刷新的只有思考行
（`scheduleThinkingRefresh` 120ms 节流）和状态栏统计。所以"流式接管"**不需要**给
`PartsTurnBinder` 造一条"按 part id 就地更新"的通道——`TurnPartsHost.bind()` 每次全量喂进去，
增量成本由它的 id + 指纹早退吸收（:183-188）。真正要解决的只有四件事：

1. **顺序真值换源**（产出侧，`AssistantTurn.fromMessageForStreaming`）。`fromMessage` 首选
   `roundSnapshots`，而快照是 `onRoundComplete` 才追加的，`msg.content` 却已经在边界被 flush 成
   当前累积正文——生成中用快照分支重建，**正在写的这一轮**（还没有快照）整段不见，观感是"正文永远
   落后一轮"。流式这条改按 `contentRoundBounds` 切片（共 `nBounds+1` 段，最后一段即 live 轮），
   live 轮的思考取 `msg.thinkingContent` 并按同 id 覆盖（不新增，否则思考翻倍）。
   id 稳定是前提：callId = `turnId + 轮号`（轮号只增不改），工具 part 以 `toolCallId` 为键，
   组 id 取组内首个工具 id → 逐 tick 只有 live 段指纹变，前面各段一律不重绑。
   无边界时也要把 `thinkingRounds` 里**已定稿**的各轮思考各铺一条（legacy 思考段是"一个入口 + 一步一行"，
   只渲 live 那条就是回归），并把最后一条定稿当 live 快照交给同一个 id 覆盖，避免重复。
2. **容器只装一次**。`bindWithParts` 开头那段 `releaseAndClearComponents(contentHost)` +
   重挂 `partsContainer` 是给**首次**接管用的（legacy 可能已塞过正文/组件）。流式每 tick 都重进就会
   把上一 tick 建好的图表/播放器连根释放，复用白做 → 新增 `AIMessageViewHolder.partsTakenOverForId`，
   只在换消息（或 holder 回收、或开关被关）时重装。配套地，`bindAIMessage` 里那句
   "每次绑定无条件复位 `partsTookOver`" 改成"同一条消息且开关仍开就延续"，否则轮次边界那次全量绑定
   会先把标记复位、legacy 两个写者照常跑、接管再重装容器 —— 每过一轮闪一次。
3. **legacy 的两个并行写者在流式路径上也要让路**。§4.79 只防了全量绑定那条，而流式的
   `PAYLOAD_THINKING_UPDATE`（每 token 节流）与 `PAYLOAD_CONTENT_UPDATE` 都会直接调
   `updateThinkingContent` → 与 parts 的 reasoning part 双份，两处都补了 `partsTookOver` 判定。
4. **闸门不再拿 `assistantTurn` 当令牌**（这是本轮唯一的既有设计变更）。渲染每次都是现场重建，
   落盘 turn 从来不是渲染输入；以"生产者跑过了"当闸门会造出一类新倒退——停止/出错那六处终态
   （`onGenerationStopped` / `onGenerationError` / `handleGenerationError` 及各自 `uiDetached` 分支）
   都没人补 turn，于是流式期被接管、一落到终态就判回 legacy，用户看到"点停止那一刻整条重排"。
   逐个终态补 turn 等于再开一个写者，所以修到真值本身：能构建出可渲 part 就接管，空 parts 照旧回落。
   副作用（主动报）：`parts_renderer=true` 时接管面比原来宽，包含以前没有 turn 而留在 legacy 的完成态消息。
   唯一**故意没放宽**的是 `OutputRouter.onError`（:1538）——它把 `content` 覆盖成"错误: …"，
   此时补 turn 会让快照分支用 parts 反投影把那句错误冲掉，所以那条维持 legacy。

开关：新 `PartsStreamingFlag`＝`ai_chat_prefs/parts_streaming`，是 `parts_renderer` 的**子开关**
（父开关关则整条不接管，本开关自然无效），出厂默认 true —— "修好就打开"是用户 2026-10-24 的明确要求。
单独一个键的理由：流式换了正文顺序真值、又要在生成期让 legacy 思考行/组件容器让路，出问题时要能
单独退回"只有完成态进 parts"，而不必连整条 parts 一起关。产品里仍**没有**设置页入口（与
`parts_renderer` 一样只能改 prefs）。

已证（JVM）：`AssistantTurnStreamingTest` 12 条 —— live 轮不掉段 / 完成态生产者的丢弃行为被钉住（
谁把闸门改回复用 `fromMessageForParts` 立刻变红）/ 已完成轮的 id 与指纹逐 tick 不漂 /
轮号十进制前缀碰撞（`R1` 是 `R11` 的前缀，误匹配会把已完成轮标成流式）/ 定稿思考与 live 不重复 /
running 工具的产物在流式期就折进所属工具卡。全量 **792 tests / 0 skipped / 0 failures / 0 errors**，
`assembleDebug` 通过。

未证（真机，JVM 证不了）：① 逐 tick 观感——是否还有闪烁/跳动/滚动位置被抢；② 未闭合的
` ```component``` ` / mermaid 围栏在流式期被 `expandInline` 切成半截 data part 再消失时，
子段 id（`:p{seq}`）重排是否造成可见抖动；③ 本地引擎（非在线 Agent）的流式消息形状是否始终
拿得到 `contentRoundBounds`；④ 停止/出错那两条终态切换是否还重排一次。装新包后按
[[parts-restore-device-verification-deferred]] 的前置走（`parts_renderer` 设备上已是 true，
`parts_streaming` 无键即默认 true）。
