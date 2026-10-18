# 工具结果组件审查：一个结果，被画了三遍

> 审查范围：`ToolCallCardView`、`item_tool_call_message.xml`、`ChatAdapter` 的 TOOL_CALL 绑定、`ToolResultInterpreter`、`ToolResultCardBinder`、`ToolResultStore`、`ChatMessage.ToolCallInfo`
> 关联：`AIChatActivity` 的工具执行/解读链路、`AIToolManager.collectAutoUiCards`
> 结论：**不是样式小问题，是"结果模型"没有定义清楚。**
>
> 2026-10-19 更新：Qoder 已完成 B0（`HtmlSandbox` / `CardShell` / 6 个核心卡迁移）。**B0 没有覆盖工具结果链路**——本审查里的问题仍然成立，且 `ToolResultCard` 应该直接复用 B0 的 `CardShell` / `ComponentTheme` / `ComponentLifecycle`，不要再造一套外壳。

---

## 0. 结论

同一个工具结果，当前最多会以 **4 种形态**同时出现：

1. 工具卡里的 **原始结果文本**（`toolCallInfo.result`，可能几万字）；
2. 工具卡顶部的 **AI 解读摘要**（`interpretedMessage`）；
3. 单独一条 **AI 主气泡**（`addAIMessage(content)`）；
4. 外加 `ToolResultCardBinder` 自动挂的 **结构化卡片**（表格/天气/文件/图片…）。

再加上每条工具都有的"✅ 工具执行完成""💡 正在AI解读结果…"系统消息，用户看到的是：**同一条结果，重复、冗长、没有层级**。

另外三个硬问题：

- **UI 不截断**：`formatForUi` 明确"不做长度截断"，几十 KB 文本直接塞进 TextView，长结果会撑高列表、卡顿甚至 ANR。
- **工具名/图标是猜的**：`formatToolDisplayName` 只映射了 9 个工具，其余直接 `python_execute` → "python execute"；状态用 emoji。
- **映射是硬编码 switch**：`ToolResultCardBinder` 1,647 行、25 个 case + 3 个工具名单，新增工具要改多处；没命中的工具只有原始文本，没有结构化卡。

---

## 1. 现状：工具结果到底怎么显示的

### 1.1 工具卡（`item_tool_call_message.xml` + `ChatAdapter.bindToolCallMessage`）

- 头部：emoji 图标 + `toolDisplayName` + 状态文本 + 耗时；点击展开/折叠。
- 参数：优先 `json_viewer` 组件，失败回退等宽 `TextView`。
- 结果：`tool_result` TextView，`scrollbars="vertical"`，但**没有 maxHeight**；内容来自 `buildResultWithInterpretChip`。
- 结果区文本 = `💡 interpretedMessage` + 分隔 + `result`（原始模板文本），两者都完整拼接。
- 失败：结果区顶部挂"重试该工具"按钮（这是现有做得好的地方）。

### 1.2 自动卡片（`ToolResultCardBinder`）

- `AIToolManager.collectAutoUiCards` 在工具执行完调用，把结果映射成 `weather_card / table_card / list_card / info_card / file_card / image_grid / …`，交给 `ComponentCollector` 挂到 AI 消息。
- 一次调用最多 2 张卡；表格最多 50 行、列表最多 12 项、天气最多 5 天。
- 25 个工具名走 `dispatch` switch；其余只有 `ARTIFACT_SCAN_TOOLS` 白名单里的工具会做文件路径探测。
- 工具自己已经 `withComponent` 的（`network_search` / `file_reader` / `image_gen` …）不再补卡。

### 1.3 AI 解读（`ToolResultInterpreter`）

- 每个成功工具都会触发 `interpret()`：先在线模型（60s 超时），失败再本地模型，最后回退模板。
- `formatForUi` 只做模板文本，**不截断**；注释明确写着"UI 展示不做任何长度截断"。
- 解读完成：`writeInterpretedToMessage` 写进工具卡，**同时** `addAIMessage(content)` 再加一条 AI 气泡；`injectInterpretToLocalContext` 还会写进本地上下文。

### 1.4 存储（`ToolResultStore`）

- 静态 `List<ToolExecution>`，每次保存 `result` 对象 + `resultJson` 字符串（双份）。
- 没有条数上限、没有 TTL、没有按大小淘汰；只在新建对话/结束 Agent 时 `clear()`。
- `ChatMessage.ToolCallInfo.result` 也会持久化完整结果字符串。

---

## 2. 问题清单

### P0 · 同一个结果画三遍

- `AIChatActivity` L2522 `updateToolCallResult(..., resultStr)`：工具卡里放完整模板文本。
- L2545 `writeInterpretedToMessage`：AI 解读再写进工具卡顶部。
- L2549 `addAIMessage(content)`：**又加一条独立 AI 气泡**。
- `ToolResultCardBinder`：再挂 1–2 张结构化卡。
- L2523/L2532：每条工具还额外插"✅ 工具执行完成""💡 正在AI解读结果…"系统消息。

结果：用户看到的是"工具卡 + 系统消息 + AI 气泡 + 结构化卡"四层，信息重复，真正的结果反而被淹没。

**改法**：一条工具调用 = **一张结果卡**。AI 解读只作为卡内"摘要"区，不再新增 AI 气泡；系统消息只保留失败/需要用户操作的情况。这张卡直接复用 B0 的 `CardShell`（`State` + body + footer + notice），不要另起一套。

### P0 · UI 不截断，长结果直接进 TextView

- `ToolResultInterpreter` L32–34 注释："UI 展示…不做截断保护，信息完整输出"。
- `formatForUi` 可能返回几十 KB（在线输入硬切上限 200,000 字符，见 L266）。
- `item_tool_call_message.xml` 的 `tool_result` 无 `maxHeight`，`buildResultWithInterpretChip` 把"解读 + 原始结果"全量拼接（L4142–4163）。
- `ChatMessage.ToolCallInfo.result` 持久化完整字符串，历史恢复时再次绑定。

**改法**：结果卡只渲染"摘要 + 前 6 行/2 KB"；完整结果进 BottomSheet，带"复制全部/另存"；`ToolResultStore` 用 id 引用，卡内不存全文。

### P0 · 工具名/状态是"猜"的

`ChatMessage.ToolCallInfo`：

- `formatToolDisplayName` 只映射 9 个（L490–503），其余 `name.replace("_", " ")` → 用户看到 `python execute`、`ocr recognize`、`ai weather`。
- `getToolIcon` 按子串猜 emoji（L506–517），`toolIcon` 直接进 UI。
- `getStatusText` 返回 `⏳ 等待执行 / 🔄 执行中... / ✅ 执行完成 / ❌ 执行失败`（L480–488）。

而 `ToolResultCardBinder` 的 dispatch 用的是归一化后的 `ai_weather`，显示名却用的是原始 `get_weather`——**显示名、图标、卡片映射三套名字**。

**改法**：建 `ToolMetaRegistry`（toolName → 中文名 + 矢量图标 + 分类 + 结果呈现器），所有 UI 只读这一份；状态用文字 + 语义色，不用 emoji。

### P0 · 硬编码 switch + 三份工具名单

`ToolResultCardBinder`（1,647 行）：

- `dispatch` 的 25 个 `case`（L226–276）；
- `SELF_COMPONENT_TOOLS`（L78–81）、`SKIP_TOOLS`（L84–89）、`ARTIFACT_SCAN_TOOLS`（L96–100）三份名单；
- `MAX_CARDS_PER_CALL=2`、`MAX_TABLE_ROWS=50`、`MAX_LIST_ITEMS=12`。

新增一个工具，要同时改 switch + 名单 + 可能还要加 parser；漏改就没有结构化结果，只剩原始文本。

**改法**：`ToolResultPresenter` 接口 + 注册表；每个工具（或工具类别）注册一个 presenter；通用 presenter 兜底 JSON 树/文件/文本。名单只保留"是否已自带组件"这一条元数据。

### P1 · 自动解读过度，慢且贵

- 每个成功工具都走 `interpret()`：在线模型 60s 超时，失败再本地模型。
- 解读期间插"💡 正在AI解读结果…"系统消息；无模型时再改成"ℹ️ 无可用AI模型解读…"。
- 对 `time_date`、`calculator`、`file_list` 这类结果，LLM 解读没有增量价值，却增加延迟和 token。

**改法**：默认只展示模板摘要；"AI 解读"改为**按需按钮**（用户点才调模型），或只对 `webpage_reader`、`smart_research`、`database` 这类复杂结果自动解读。

### P1 · 结果卡没有"来源"和"状态"

- `ToolResultCardBinder` 生成的是通用 `weather_card/table_card/...`，卡片上看不出是哪个工具产出的。
- 一次调用最多 2 张卡，多余产物直接丢弃；没有"查看全部"。
- 失败只把 `errorMessage` 塞进结果文本，没有错误码、排查建议、重试以外的动作。

**改法**：结果卡 header 带工具名/图标/耗时/状态；多产物用 1 张卡内的 grid/list，不截断到 2 张；失败卡带错误码 + 重试 + "查看排查"。

### P1 · `ToolCallCardView` 是另一套实现

- `component/ToolCallCardView` 是单行 `🔧 toolName ✅ summary`（50 字截断），注册为 `tool_call`；
- 实际聊天流用的是 `item_tool_call_message.xml` + `ChatAdapter` 的完整工具卡；
- 模型如果输出 `tool_call` 组件，会得到第三种视觉。

**改法**：删除/废弃 `ToolCallCardView`，只保留一套工具卡；或把它改成结果卡的壳，复用 `ToolResultCard`。

### P1 · `ToolResultStore` 无上限

- `static List<ToolExecution>`（L39），`save` 同时存 `result` 和 `resultJson`（L51–63）；
- 没有条数/字节上限，没有 TTL；
- 长会话、大结果（网页、表格、日志）会持续占内存，且 `resolveRef` 需要全量保留。

**改法**：LRU（如 50 条 / 10 MB），超出淘汰最旧；完整结果落盘按 id 取；`resolveRef` 只依赖最近 N 条。

### P2 · 参数/结果的阅读体验

- 参数默认折叠在 `json_viewer` 里（好），但结果区是等宽 12sp 的原始文本，长行不换行/无语法高亮。
- 结果区没有"复制结果""打开产物""另存"；参数区没有"复制 JSON"之外的复用。
- 错误信息直接是异常字符串/工具返回的英文；没有本地化。

### P2 · 可访问性与性能

- 头部是 `LinearLayout` 可点击，不是 `Button`；`▶/▼` 是文本箭头，无 contentDescription。
- 结果 TextView 全量文本，绑定在 RecyclerView 主线程；`formatForUi`/`buildResultWithInterpretChip` 都在绑定路径上做字符串拼接。
- `ChatAdapter` 对工具消息 `notifyItemChanged` 全量刷新，长结果反复测量。

---

## 3. 目标：一张 `ToolResultCard`

```
┌─────────────────────────────────────────────┐
│ [icon] 天气查询        ✅ 成功 · 1.2s · ai_weather │
├─────────────────────────────────────────────┤
│ 北京 26°C 晴，湿度 60%，东南风 3 级            │  summary（1–3 行）
│                                             │
│ ┌ 未来 5 天 ──────────────────────────────┐ │  structured
│ │ 10-13  晴  18~28°C                      │ │  （复用 CardShell）
│ │ 10-14  多云 17~26°C                     │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ ▸ 原始结果（JSON · 3.2 KB）      复制 / 展开 │  raw（默认折叠）
├─────────────────────────────────────────────┤
│ [复制结果] [打开产物] [重试]           [更多] │  footer（≤2+更多）
└─────────────────────────────────────────────┘
```

规则：

- **一条工具调用只渲染一张卡**；AI 解读是卡内 summary，不再新增 AI 气泡。
- **状态**：pending / running / success / partial / failed / timeout / cancelled；running 显示已耗时，不只转圈。
- **summary**：模板摘要 ≤ 600 字；LLM 解读按需触发，结果替换/补充 summary，不新开气泡。
- **structured**：复用 CardShell 的 table/chart/list/file/image 等；多产物一张卡内展示，不砍到 2 张。
- **raw**：默认折叠；只放前 2 KB / 6 行；完整内容进 BottomSheet，可复制/另存。
- **error**：错误码 + 可读信息 + 重试 + 排查建议；不把堆栈直接当正文。
- **actions**：复制结果、打开产物、重试、查看原始、反馈；最多 2 个主操作 + 更多。

> B0 之后：容器、圆角、header/footer、错误/空/截断 notice、动作溢出都应走 `CardShell` + `ComponentTheme`；完整结果的 BottomSheet 可用 `CardShell` 的 body 内部滚动，生命周期挂到 `ComponentLifecycle.release`。
>
> B0 迁移了 Info / Quiz / File / Steps / Table / Chart 这 6 张卡，但 `ToolResultCardBinder` 还会产出 `weather_card` / `list_card` / `image_grid` / `file_list` / `video` / `audio` / `metric_card` / `todo_card` / `progress_card` / `link_card`——这些仍是旧外壳。工具结果卡会出现"有的新、有的旧"的混排。

### 3.1 架构

```java
interface ToolResultPresenter {
    boolean supports(String toolName, AIToolResult result);
    ToolResultModel present(Context ctx, String toolName, AIToolResult result,
                            Map<String, Object> params);
}

class ToolResultModel {
    String title;          // 中文名
    String icon;           // 矢量资源名
    Status status;
    String summary;        // 模板/按需 LLM
    ComponentData body;    // 可选：table/chart/file...
    String rawPreview;     // ≤ 2 KB
    String rawRef;         // ToolResultStore id
    List<Action> actions;
}
```

- `ToolMetaRegistry`：`toolName → displayName / icon / category / presenter`。
- `ToolResultPresenter` 注册表：每个工具或工具族一个；通用 presenter 兜底 JSON/文本/产物。
- `ToolResultStore`：LRU + 落盘，`get(id)` 供 BottomSheet。
- `ToolResultSizePolicy`：summary 600 字、rawPreview 2 KB、表 50 行/列表 12 项，超出给"查看全部"。

---

## 4. 落地顺序

### P0 · 去重 + 限长 + 元数据（2–3 天）

- [ ] 删除"工具结果 AI 主气泡"分支：`addAIMessage(content)` 不再调用；解读只写 `interpretedMessage`。
- [ ] 结果区限长：只渲染 summary + 前 6 行/2 KB；完整结果进 BottomSheet。
- [ ] `ToolMetaRegistry`：补全所有工具的显示名/图标；状态改文字 + 语义色。
- [ ] 失败卡：错误码 + 重试 + 排查建议；不再把原始异常当正文。
- [ ] 移除每条工具的"✅ 工具执行完成"系统消息（改卡片状态）。

### P1 · Presenter 化 + 按需解读（3–5 天）

- [ ] `ToolResultPresenter` + 注册表，替换 `ToolResultCardBinder.dispatch` switch。
- [ ] 通用 presenter：JSON 树/文本/文件/图片兜底，任何工具都有结果卡。
- [ ] AI 解读改按需按钮；只对复杂工具自动解读。
- [ ] 多产物一张卡（image_grid/file_list），不限制 2 张。
- [ ] `ToolResultStore` LRU + 落盘；`ChatMessage` 只存 `resultRef` + preview。

### P2 · 体验与质量（约 1 周）

- [ ] 运行中耗时/阶段；长任务支持 partial 结果。
- [ ] 结果卡 footer 动作：复制/打开/另存/重试/反馈。
- [ ] 错误本地化；参数/结果语法高亮与换行。
- [ ] 无障碍：Button 语义、contentDescription、TalkBack。
- [ ] 工具结果卡截图回归（成功/失败/长文本/多产物）。

---

## 5. 验收清单

- [ ] 一条工具调用只出现一张结果卡；没有重复的 AI 气泡/系统消息
- [ ] 任何结果（含 200 KB 文本/500 行表格）首屏绑定 < 16ms，不卡顿
- [ ] 原始结果默认折叠，完整内容可在 BottomSheet 查看/复制
- [ ] 所有工具都有中文显示名和矢量图标，没有 `python execute` 这类原名
- [ ] 失败卡有错误码、可读说明、重试入口
- [ ] 多产物（5 张图/5 个文件）在一张卡内完整展示，不丢
- [ ] `ToolResultStore` 有上限，长会话内存不持续增长
- [ ] `ToolCallCardView` / `item_tool_call_message` / binder 三套实现收敛成一套
