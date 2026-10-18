# 工具结果：修改方向与建议

> 配套审查：`docs/ui/tool-result-ui-review.md`
> 当前状态：B0 已完成 `CardShell` / `HtmlSandbox` / 6 张卡迁移；**工具结果链路仍未动**
> 目标：让"一次工具调用"只有一个结果模型、一张结果卡、一套呈现规则

---

## 0. 先给结论

不建议直接改样式。工具结果现在的问题在**模型层**：`result` 字符串、`interpretedMessage`、`formatForUi` 文本、`ToolResultCardBinder` 卡片、`presentCard` 五套数据各说各话。

建议按四个方向改，组合使用：

| 方向 | 一句话 | 解决什么 |
|------|--------|----------|
| ① 统一结果模型 | 一次调用产出 `ToolResultPresentation` | 重复渲染、字段散落、状态不统一 |
| ② 一张卡 + 详情页 | 聊天流里只放 `ToolResultCard`，完整内容进 Sheet | 噪音、长文本、层级不清 |
| ③ Agent 运行内用时间线 | 多工具调用合成一条可展开的步骤流 | Agent 多步任务刷屏 |
| ④ 运行时策略 | 模板摘要 + 按需 AI 解读 + 截断 + LRU | 慢、贵、卡、内存涨 |

推荐组合：**① + ② 作为默认聊天形态，③ 只用于 Agent 运行，④ 贯穿全链路**。

---

## 1. 方向一：统一结果模型

### 1.1 现状

- `ChatMessage.ToolCallInfo` 里有 `result`、`interpretedMessage`、`rawResult`、`presentCard`、`status`、`executionTimeMs`，但 UI 各取各的。
- `AITool.presentResult(args, result)` 已经存在，经 `onToolPresent` → `presentCard` 一路传到 UI，**但 ChatAdapter 只用了 `title`**（`ChatAdapter.java:3842`）。
- `ToolResultCardBinder` 又用一套 `switch` 把 `AIToolResult` 映射成 `ComponentData`。

### 1.2 建议

把 `presentResult` 升级成**唯一的结果声明契约**，示例：

```json
{
  "version": 1,
  "title": "天气查询",
  "subtitle": "北京 · 实时",
  "status": "success",
  "durationMs": 1230,
  "tool": "ai_weather",
  "summary": "北京 26°C 晴，湿度 60%",
  "body": {
    "type": "table_card",
    "props": {
      "title": "未来 5 天",
      "headers": ["日期", "天气", "最低", "最高"],
      "rows": [["10-13", "晴", "18°C", "28°C"]]
    }
  },
  "artifacts": [
    {"type": "image", "uri": "file:///.../chart.png", "name": "温度趋势"}
  ],
  "raw": {"preview": "…", "ref": "tr_20261019_001", "bytes": 3200},
  "actions": [
    {"id": "copy", "label": "复制结果"},
    {"id": "open", "label": "打开产物"},
    {"id": "retry", "label": "重试"}
  ]
}
```

规则：

- `summary` 是**唯一**的人读摘要；不再同时展示 `formatForUi` 全文 + `interpretedMessage`。
- `body` 是**裸内容**，不是一张完整卡片。`ToolResultCard` 用 `CardShell` 当外框，`body` 走 `ComponentRegistry.renderBody()`（新增"无壳 body"渲染路径），避免双层卡片。
- `raw` 只放预览 + `ref`，全文进 `ToolResultStore`；`ChatMessage` 不再存完整 `result`。
- `status` 用枚举：`PENDING / RUNNING / SUCCESS / PARTIAL / FAILED / TIMEOUT / CANCELLED`。
- `actions` 由工具声明，UI 只负责渲染；最多 2 个主操作 + 更多。

### 1.3 工具侧怎么落地

- `AITool.presentResult()` 默认返回 `null` → 走通用 Presenter（JSON 树/文本/文件/图片）。
- 先迁 5 个高价值工具：`ai_weather`、`webpage_reader`（/`network_search`）、`database`、`file_generator`、`python_chart`。
- `ToolResultCardBinder` 从"25 个 case 的桥接器"降级为"旧工具的 fallback"，逐步删 switch。

好处：新增工具只实现一个 `presentResult`，不用改 `ToolResultCardBinder` 的三份名单和 switch。

---

## 2. 方向二：一张卡 + 详情页

### 2.1 默认形态（聊天流）

```
┌─────────────────────────────────────────────┐
│ [icon] 天气查询        ✅ 1.2s · ai_weather  │
│ 北京 26°C 晴，湿度 60%，东南风 3 级           │
│ ┌ 未来 5 天 ──────────────────────────────┐ │
│ │ 10-13  晴  18~28°C                      │ │
│ │ 10-14  多云 17~26°C                     │ │
│ └─────────────────────────────────────────┘ │
│ ▸ 原始结果 3.2 KB              复制 / 展开  │
│ [复制结果] [打开产物] [重试]           [更多] │
└─────────────────────────────────────────────┘
```

- **只保留一张卡**；AI 解读变成卡内 summary，不再 `addAIMessage`。
- `raw` 默认折叠，只显示大小和前 2–3 行；点击进 BottomSheet 看全文、复制、另存。
- `body` 复用 B0 已迁移的 Info/Table/Chart/File/Steps 卡；图片/文件用卡内 grid，不再另挂"自动卡片"。
- 运行中：`● 执行中 0.8s` + 当前阶段文案 + 可取消；不要只转圈。
- 失败：`! 失败` + 错误码/可读说明 + 重试 + 查看排查。

### 2.2 详情页（BottomSheet）

- 完整原始结果（JSON/文本/日志），带搜索、复制、另存。
- 大表格/长列表：分页或内部滚动，不一次性塞 TextView。
- 产物：图片预览、文件打开、分享。
- 这是"查看全部"的唯一落点，避免在聊天流里无限展开。

### 2.3 不建议

- 不要把 `body` 做成第二张带壳卡片（双层圆角/边框）；`CardShell` 只允许一层。
- 不要把所有结果都做成"卡片"；`time_date`、`calculator` 这类一行文本结果用 **inline 摘要条** 就够，不必大卡。

---

## 3. 方向三：Agent 运行内用时间线

Agent 一轮可能调 5–10 个工具，逐个 `ToolResultCard` 会刷屏。

建议：

```
Agent 运行 · 整理错题并生成练习            5 步 · 12.4s
✓ 检索错题            1.2s   [展开结果]
● 生成相似题          3.1s   [展开结果]
○ 校验答案
○ 生成解析
○ 汇总
```

- 一个 `AgentRunCard`（复用 Plan 的时间线）承载步骤；
- 每步展开后，在该步下面渲染它的 `ToolResultCard`（compact 形态）；
- 顶部只保留一条汇总（成功/失败数、总耗时）；不额外插"✅ 工具执行完成"系统消息。
- 当前代码在 Agent 模式下已经不走独立工具卡（`AIChatActivity:9134`），所以这条线可以从"Agent 执行 UI"里自然扩展，不需要改普通聊天。

---

## 4. 方向四：运行时策略

### 4.1 摘要：模板优先，AI 按需

- 默认用工具模板/`presentResult.summary`，**不再每个工具都自动调 LLM**。
- "AI 解读"做成卡内按钮；点击才走 `ToolResultInterpreter.interpret`，结果写回 summary，缓存到 `ToolCallInfo`。
- 只对 `webpage_reader`、`smart_research`、`database` 这类复杂结果允许自动解读（可配置）。

收益：去掉每条工具 1–60s 的等待、一次额外的 AI 气泡、以及"正在AI解读结果…"系统消息。

### 4.2 截断与长内容

- summary ≤ 600 字；body 表格 ≤ 50 行、列表 ≤ 12 项，超出显示"查看全部"。
- raw preview ≤ 2 KB / 6 行；全文只在详情页。
- 所有拼字符串移出主线程绑定路径；`formatForUi` 不再直接进 TextView。

### 4.3 存储

- `ToolResultStore` 改 LRU（建议 50 条 / 10 MB），超出淘汰最旧；完整结果落盘，`get(id)` 取。
- `ChatMessage.ToolCallInfo` 只存 `presentation` + `rawRef` + `preview`，不存全量 `result`。
- 历史数据兼容：旧消息没有 `presentation` 时走旧渲染，不迁移。

### 4.4 生命周期

- `ToolResultCard` 的 WebView/媒体/大图挂到 B0 的 `ComponentLifecycle.release`。
- 详情页关闭时释放对应资源；长会话不累积。

---

## 5. 方案对比

| 方案 | 优点 | 缺点 | 适用 |
|------|------|------|------|
| A. 单卡结果（推荐） | 一致、可扫读、层级清楚 | 长表格需要详情页 | 普通聊天 / 单工具 |
| B. Agent 时间线 | 多工具不刷屏、可回看步骤 | 单条结果不如单卡突出 | Agent 运行 |
| C. 单行 + 详情 | 最省空间 | 多一次点击，弱结果不可见 | 纯文本/控制类工具 |
| D. 继续多卡片 | 改动最小 | 重复、噪音、长文本卡顿 | 不建议 |

推荐：**A 为默认，B 用于 Agent，C 用于 `time_date`/`calculator` 这类一行结果，D 淘汰。**

---

## 6. 落地顺序（建议）

### Step 0 · 快速止血（0.5–1 天）

- [ ] 删掉工具结果的 `addAIMessage(content)`（`AIChatActivity:2549` 及 3149/3286），只保留工具卡内 summary。
- [ ] 工具卡结果区限长：前 6 行 / 2 KB，加"查看全部"入口。
- [ ] 去掉每条工具的"✅ 工具执行完成"系统消息，状态回到卡片。
- [ ] 关掉自动 AI 解读（或只对 3 个复杂工具开启）。

这四步不依赖新架构，立刻解决"重复 + 卡顿 + 慢"。

### Step 1 · 结果模型（2–3 天）

- [ ] 新增 `ToolResultPresentation`（title/subtitle/status/summary/body/raw/actions）。
- [ ] `AITool.presentResult` 按 schema 输出；`AIChatActivity` 存到 `ToolCallInfo.presentation`。
- [ ] 新增 `ToolResultCard`（`CardShell` + body + raw + footer）。
- [ ] `ChatAdapter` 新增 `VIEW_TYPE_TOOL_RESULT`（或 TOOL_CALL 内切换），带 `tool_result_v2` 开关。
- [ ] 先迁 3 个工具：`ai_weather`、`file_generator`、`python_chart`。

### Step 2 · Presenter 化（3–5 天）

- [ ] `ToolResultPresenter` + 注册表；`ToolResultCardBinder` 变 fallback。
- [ ] `ToolMetaRegistry`：所有工具的中文名/图标/分类。
- [ ] 通用 Presenter：JSON/文本/文件/图片；任何工具都有结果卡。
- [ ] 详情页 BottomSheet：原始结果、产物、复制/另存。
- [ ] `ToolResultStore` LRU + 落盘。

### Step 3 · Agent 时间线（3–4 天）

- [ ] `AgentRunCard` 复用 Plan 时间线；每步展开 `ToolResultCard`（compact）。
- [ ] 汇总只保留一条；去掉逐步系统消息。
- [ ] 多产物（5 图/5 文件）一张卡内 grid/list。

---

## 7. 文件级改动清单

| 文件 | 建议 |
|------|------|
| `ai/tool/AITool.java` | `presentResult` 文档写清 schema；返回值约定 |
| `ai/chat/ChatMessage.java` | `ToolCallInfo` 增加 `presentation`（transient 或 JSON）；保留旧字段兼容 |
| `ai/chat/component/ToolResultCard.java`（新增） | 基于 `CardShell` 的结果卡 |
| `ai/chat/component/ToolResultPresentation.java`（新增） | 结果模型 + 解析/校验 |
| `ai/chat/component/ToolMetaRegistry.java`（新增） | toolName → 名称/图标/分类 |
| `ai/chat/component/ToolResultPresenter.java`（新增） | supports/present |
| `ai/chat/component/presenters/*`（新增） | 每个工具/工具族的 presenter |
| `ai/agent/ToolResultCardBinder.java` | 降级为 fallback；删 switch 与三份名单 |
| `ai/agent/ToolResultInterpreter.java` | 保留模板；AI 解读改按需；UI 不再直接吃全量文本 |
| `ai/agent/ToolResultStore.java` | LRU + 落盘 + `get(id)` |
| `ai/chat/ChatAdapter.java` | 新 viewType / 新 Binder；旧 TOOL_CALL 分支保留回退 |
| `ui/activity/AIChatActivity.java` | 去掉 `addAIMessage`、系统消息；接 `presentation` |
| `ai/tool/AIToolManager.java` | `collectAutoUiCards` 先读 `presentResult`，再 fallback |
| `res/values/strings.xml` | 工具中文名/状态/错误文案 |

---

## 8. 风险与兼容

1. **双层卡片**：`body` 必须是"无壳内容"，否则 `CardShell` 套 `CardShell`。需要给 `ComponentRegistry` 加 `renderBody()`（或在 `ChatComponent` 加 `createBody`）。
2. **旧历史**：旧 `ToolCallInfo` 没有 `presentation`，渲染时走旧路径；不要做一次性数据迁移。
3. **Agent 模式双通道**：Agent 模式当前不插独立工具卡（`AIChatActivity:9134`），新时间线要接在 Agent UI 上，不能和普通聊天共用同一套插入逻辑。
4. **工具输出不稳定**：`presentResult` 可能返回缺字段；必须 schema 校验 + fallback，不能让工具报错。
5. **截断争议**：用户明确说过 UI 不要截断（`ToolResultInterpreter` 顶部注释）；这次要改成"摘要截断 + 详情页全文"，需要和用户确认口径，避免又被改回去。

---

## 9. 最小验收

- [ ] 一条工具调用只出现一张结果卡；没有重复 AI 气泡/系统消息
- [ ] 200 KB 结果首屏 < 16ms，原始内容只在详情页
- [ ] 所有工具都有中文名 + 矢量图标
- [ ] 失败卡有错误码 + 重试
- [ ] 多产物一张卡完整展示
- [ ] `ToolResultStore` 有上限，长会话内存稳定
- [ ] 旧历史消息仍能正常渲染
