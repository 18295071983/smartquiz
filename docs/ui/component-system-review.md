# 内置 UI 组件审查：需要改，且有明确优先级

> 审查范围：`src/main/java/com/oilquiz/app/ai/chat/component/`（43 个文件、7,425 行、30 个 \*CardView）
> 关联：`ComponentRegistry`、`ComponentData`、`ComponentColors`、`LayoutPrimitiveCardView`、`UIComponentTypeRegistry`、`UIComponentPluginManager`
> 参考样式：`demo/ai-chat-redesign.html` 里的新版 quiz / file / tool 卡

---

## 0. 结论

**需要改，但不是"全部重写"。** 组件能力本身很全，问题集中在四件事：

1. **HTML 组件是一个没上锁的 WebView**：JS + Java 桥 + 本地文件读取 + 任意 URL，提示词注入即可被利用。这是 P0。
2. **没有统一的卡片外壳**：30 个组件各画各的圆角/内边距/标题/按钮，和聊天页的设计 token 对不上。
3. **主题体系分裂**：组件用一套 `component_*` 色板，聊天页用 `colorSurface/text_primary`；`FeedbackBar`、`AgentStatusChip` 还写死了深色模式下的白色/蓝色。
4. **题目卡不能答题**：选项是静态 TextView，答案和解析直接显示，学习场景的核心交互是缺的。

另外有两个结构性问题：**类型爆炸**（29 个内置 + 32 个 Layout 原语 + layout 树 ≈ 63 个可注册类型，命名还重叠）和**生命周期缺失**（WebView/媒体在列表回收时不销毁）。

---

## 1. 盘点

| 项 | 数字 / 事实 |
|----|-------------|
| 组件文件 | 43 个，7,425 行 |
| 卡片类 | 30 个 \*CardView |
| 内置注册 | `ComponentRegistry` 里 29 次 `register(new …)` |
| Layout 原语桥接 | `LayoutPrimitiveCardView.BRIDGED_TYPES` 32 个类型 |
| 渲染路径 | 原生卡片 / Layout 树 / Layout 原语 / HTML WebView 四条 |
| 暗色适配 | `values-night` 覆盖了 `component_*`，方向是对的 |
| 硬编码颜色 | 20 处（`AgentStatusChip` 12、`FeedbackBar` 3、`ComponentRegistry` 3、`Html` 1、`ComponentColors` 1） |
| emoji 图标 | 50 处，集中在 FileList（13）、FileCard（10）、FeedbackBar（10） |

**两套命名重叠**：`table_card` / `table`、`steps_card` / `steps`、`alert_card` / `alert`、`metric_card` / `stat`、`progress_card` / `progress_ring`、`chart` / `bar_chart`……模型很容易选错，用户看到的是两种视觉。

---

## 2. 问题清单

### P0 · 安全：HtmlCardView 没有沙箱

`HtmlCardView.java` 同时做了这些事：

- `setJavaScriptEnabled(true)`、`setDomStorageEnabled(true)`（L123–124）
- `addJavascriptInterface(new HtmlJsBridge(context), "Android")`（L135），桥暴露 `showToast` / `copy` / `openLink` / `previewImage`（L558–588）
- 加载任意 `http(s)://` / `file://` URL（L315–323）
- 对 `file://` 打开 `setAllowFileAccessFromFileURLs(true)` + `setAllowUniversalAccessFromFileURLs(true)`（L319–321）
- HTML 内容直接 `loadDataWithBaseURL("file:///android_asset/", …)`（L345）

这意味着：一段被提示词注入的 HTML 可以在卡片里执行 JS、调用剪贴板/打开链接、读取本地文件并尝试外发；`previewImage` 还会用 `AgentWorkspace.resolveExistingFile` 解析任意本地路径。

**必须改**：

- 默认关闭 JS；只有"受信任模板"（内置 HTML 模板、本地 asset）才开。
- 关闭 `setAllowFileAccessFromFileURLs` / `setAllowUniversalAccessFromFileURLs`。
- 桥只允许白名单 origin（`https://local.agent` 之类），且只保留 `copy`/`openLink` 这类低风险方法；移除任意路径解析。
- URL 只允许 `https`（或显式白名单），禁止 `file://` 直接加载用户目录。
- 限制 HTML 大小（例如 512 KB）与 WebView 数量；`onDetachedFromWindow` 时 `destroy()`。

### P0 · 主题：组件和聊天页不是一套语言

- 组件色板 `ComponentColors` 用 `component_text_primary` / `component_bg`（L18–96），聊天页用 `colorSurface` / `text_primary`；两套 token 各自维护。
- `FeedbackBarCardView` 写死 `0xE8FFFFFF`、`0x59FFFFFF`、`0xFF1565C0`（L131–136），在浅色底上基本不可读。
- `AgentStatusChipCardView` 写死 12 个颜色（L102–125），暗色模式不跟随。
- emoji 当图标：`📋`、`🔊`、`🔄`、`🔧`、`✅`、`❌`、`🧠`（FeedbackBar L111–120、AgentStatus L56–61、FileCard L60–64、FileList L86 等）。

**必须改**：组件统一走 `CardShell` + 聊天页 token；状态色只用 `success/warning/danger/brand` 四类；图标改矢量资源（或至少统一文本符号）。

### P0 · 结构：没有 CardShell

每个卡片都自己画背景：

```java
GradientDrawable gd = new GradientDrawable();
gd.setColor(ComponentColors.background(context));
gd.setCornerRadius(dp(context, 10));
gd.setStroke(dp(context, 1), ComponentColors.border(context));
```

`InfoCardView` L45–46 / L92–98、`QuizCardView` L48–51 / L112–118、`ChartCardView` L86–89 / L113–119、`DynamicCardView` L43–46…… 圆角 10、内边距 12/10、标题 14sp bold 各写一遍。结果是：

- 同一屏里卡片圆角/内边距/标题字重不一致；
- 卡片没有 `foreground` / ripple，点击反馈靠 TextView 自己；
- 组件外再包一层 actions 时（`ComponentRegistry` L162–169），按钮落在卡片背景之外，视觉上"飘"在消息流里。

**必须改**：抽 `CardShell`（容器 + header + body + footer + 状态），所有组件只填内容；`actions` 渲染进 footer，不再外层包 `LinearLayout`。

### P1 · 交互：quiz_card 不能答题

`QuizCardView` 把 options 直接渲染成 `TextView`（L71–84），没有点击、没有选中态、没有判题；答案和解析在创建时就展示（L87–107）。对答题宝来说这是核心场景，现在的卡片更像"题目预览"。

**应该改**：

- 选项改为可点击按钮（单选/多选/判断）；
- 选中后判题：正确/错误态 + 解析展开；
- 通过 `ComponentActions` 回传 `quiz_answer`（题号、选项、是否正确、耗时）；
- 支持"下一题"和"查看解析"，而不是一上来给答案。

### P1 · 交互：feedback_bar 与 Agent 状态还停在旧体系

- `FeedbackBarCardView` 是"消息操作栏"的组件化版本，但按钮 11sp、白字白边、没有图标状态同步；它和聊天页新的"3 图标 + 更多"操作行重复。
- `AgentStatusChipCardView` 是新 Agent 执行 UI 里的状态行替代品，但颜色写死、emoji 文案，和新版 Plan/工具组不兼容。

**建议**：把这两个从"卡片体系"里拿出来——`feedback_bar` 回归消息操作栏，`agent_status` 回归 Agent 执行状态行；不要作为可被模型任意调用的卡片类型。

### P1 · 结构：类型爆炸与命名重叠

`ComponentRegistry` 注册 29 个命名类型；`LayoutPrimitiveCardView` 又桥接 32 个（`table/steps/timeline/alert/stat/...`）；`LayoutCardView` 再加 layout 树；`UIComponentTypeRegistry` 还允许 Agent 现场注册新类型并持久化。总量约 63 个可注册类型。

问题：

- 同一语义多个名字（`table` vs `table_card`，`steps` vs `steps_card`，`alert` vs `alert_card`，`stat` vs `metric_card`）；
- 模型没有稳定的"类型 → 必填字段"目录，容易输出 `chart` 但给 `table` 的 props；
- 持久化的动态类型没有版本号，升级渲染器后旧数据可能渲染错。

**建议**：建立 canonical 类型表（≤ 18 个），旧名做 alias；`ChatComponent` 增加 `getSchema()` 供工具目录使用；`ComponentData` 增加 `version` 字段。

### P1 · 体验：DynamicCardView 会倒出整棵 JSON

`DynamicCardView` 对未知类型逐层展开 `props`（L68–73、L128–140），标题默认 `📦 type`（L51）。一旦模型传了嵌套对象，用户会看到一长串键值对，没有折叠、没有最大高度、没有"查看原始 JSON"入口。

**建议**：默认只展示 `title/content/items`，其余收进"原始数据"折叠；卡片最大高度 360dp，超出内部滚动；空数据用规范空态。

### P1 · 体验：图表/文件卡的硬限制

- `ChartCardView` 固定高度 170/190dp（L105–108），分类多时标签会挤在一起；没有"查看数据表"降级。
- `FileCardView` 操作按钮竖排"打开/分享"（L88–114），行高被撑大；文件图标是 28sp emoji（L60–64）。
- `FileListCardView` 用 `⬆` 表示上一级（L86），与矢量图标语言不一致。

### P2 · 生命周期与性能

- 每个 `html` 卡片创建一个 WebView + JS 桥 + 定时器（L118–135、L300–307），RecyclerView 回收时不 `destroy()`，长会话会累积内存。
- 图表是自绘 `View`，没有 `onSaveInstanceState`，旋转/回收后重建成本高。
- 组件 `createView` 每次全量构建，没有 `onRecycle`/`onBind` 生命周期。

### P2 · 可访问性与状态

- 没有 `contentDescription`；可点击的是 `TextView` 不是 `Button`；11sp 按钮触控高度不足 48dp。
- 没有统一的 loading / empty / error / truncated 状态；`renderFallback` 只返回一行 `⚠ 组件渲染失败: type`（`ComponentRegistry` L186–191）。
- 图表没有文本替代（TalkBack 读不到数据）。

### P2 · 样式覆盖没有边界

`ComponentRegistry.applyViewStyle` 允许模型传 `style.background` / `border` / `radius`（L197–278），任意十六进制颜色可以覆盖主题，暗色模式/对比度不可控。

**建议**：只接受 token 名（`surface` / `surfaceVariant` / `brand` / `danger`…）和受限数值，不接受任意色值；圆角/内边距设上限。

---

## 3. 目标结构

### 3.1 CardShell

```
┌──────────────────────────────────┐
│ [icon] 标题                  meta │  header 13sp/600，meta 11sp
│ 副标题（可选）                     │
├──────────────────────────────────┤
│ body：13–14sp，代码 12.5sp mono   │
│ 空态 / 错误态 / 截断态             │
├──────────────────────────────────┤
│ [主操作] [次操作]        [更多 ⋯]  │  footer，最多 2 个 + 更多
└──────────────────────────────────┘
容器：surface + 1px border + 14dp 圆角 + 14dp 内边距
状态：default / loading / empty / error / truncated
```

所有卡片共用：背景、边框、圆角、header 排版、footer 动作、状态位、最大高度（360dp，超出内部滚动）。组件只负责 body。

### 3.2 ChatComponent v2

```java
public interface ChatComponent {
    String getType();                 // canonical type
    String getDisplayName();          // 工具目录展示
    String getCategory();             // content / data / media / task / input
    JSONObject getSchema();           // 必填/可选字段，给模型看
    int getVersion();                 // 数据版本
    boolean canRender(ComponentData data);
    View createView(Context ctx, ComponentData data, ComponentHost host);
    default void onRecycle(View view) {}
}
```

`ComponentHost` 统一处理 actions、埋点、生命周期、主题；`onRecycle` 用来销毁 WebView/媒体/定时器。

### 3.3 组件处置建议

| 组件 | 处置 |
|------|------|
| `info_card` / `note_card` / `metric_card` | 保留，接 CardShell；note 作为 info 的 variant |
| `list_card` / `grid_card` | 合并为 `list`，`variant=grid` |
| `table_card` / `table` | 保留 `table` 为 canonical，`table_card` alias |
| `steps_card` / `steps` / `timeline` | 合并为 `steps` |
| `alert_card` / `alert` | 合并为 `alert` |
| `chart` / `bar_chart` / `pie_chart` / `sparkline` | 统一 `chart`，`chartType` 参数；旧名 alias |
| `file_card` / `file_list` | 合并为 `file`，`variant=single/list` |
| `code_card` / `json_viewer` / `markdown_card` | 共用 code shell；JSON 默认折叠 |
| `quiz_card` | 升级为可答题（P1 最高优先） |
| `feedback_bar` | 移出卡片体系，回归消息操作栏 |
| `agent_status` | 移出卡片体系，回归 Agent 执行状态行 |
| `html` | 仅受信任模板可用；默认沙箱（见 P0） |
| `layout` / 32 个原语 | 保留为底层 primitives，但统一命名/主题；不直接暴露给模型选型 |
| `weather_card` / `contact_card` / `progress_card` / `todo_card` / `image_grid` / `video` / `audio` / `link_card` | 保留，接 CardShell |

目标：**可注册类型从约 63 个收敛到 ≤ 18 个 canonical + alias**。

---

## 4. 落地顺序

### P0 · 安全与外壳（2–3 天）

- [ ] HtmlCardView 沙箱：JS 默认关、关闭 file access、桥白名单、URL 白名单、大小限制、`destroy()`
- [ ] 新增 `CardShell` + `ComponentTheme`（复用聊天页 token）
- [ ] 迁移 6 个核心卡：info / quiz / file / steps / table / chart
- [ ] 删除 FeedbackBar / AgentStatus 的硬编码色与 emoji（先改 token，再决定是否移出卡片体系）
- [ ] `actions` 渲染进 footer，不再外层包一层

### P1 · 交互与收敛（3–5 天）

- [ ] quiz_card 可答题 + 判题 + 回传
- [ ] 统一 canonical 类型表与 alias；`getSchema()` 目录
- [ ] DynamicCard 折叠 + 最大高度 + 原始 JSON
- [ ] 图表"查看数据表"降级；文件卡操作横排
- [ ] 空态 / 错误态 / 截断态统一

### P2 · 架构与质量（约 1 周）

- [ ] `ChatComponent.onRecycle` + WebView/媒体销毁
- [ ] `style` 只接受 token，拒绝任意色值
- [ ] 无障碍：contentDescription、48dp 触控、TalkBack 顺序
- [ ] 组件截图回归（浅色/深色/沉浸蓝 × 30 卡）
- [ ] 拆分 `NativeLayoutRenderer`（240 KB）与聊天组件，统一类型注册表

---

## 5. 验收清单

- [ ] HTML 组件不能读本地文件、不能外发数据；JS 默认关闭
- [ ] 所有卡片使用 CardShell，圆角/内边距/标题/按钮一致
- [ ] 组件目录里没有硬编码颜色（`0xFF…`）和 emoji 图标
- [ ] quiz_card 可以选、可以判、可以回传，不再直接显示答案
- [ ] actions 在卡片 footer 内，最多 2 个 + 更多
- [ ] 每个卡片都有空态/错误态/长内容行为
- [ ] 可注册类型 ≤ 18 个 canonical（旧名 alias 可用）
- [ ] 长会话 200 条消息滚动，WebView 数量不增长、内存不持续上涨
- [ ] 浅色/深色/沉浸蓝截图全部通过

---

## 6. 和这次聊天改版的关系

这次聊天改版已经定下了 CardShell 的视觉基线（`demo/ai-chat-redesign.html` 的 quiz / file / tool 卡）。组件系统不需要另起一套设计，直接复用：

- 圆角 14dp、边框 1px、内边距 14dp
- 标题 13sp/600、正文 13–14sp、辅助 11sp
- 操作最多 2 个 + 更多
- 颜色只走 `surface / surface-2 / border / text / brand / success / warning / danger`

先做 P0，能同时解决"安全 + 风格不统一"；P1 的 quiz 交互是产品收益最大的一项；P2 再处理类型收敛和生命周期。

---

## 7. 可操作性三档清单（可操作 / 仅本地 / 纯展示）

> 固化于 2026-10-21，逐条核到代码。用于判断"某组件点了到底有没有用、值去哪了"，
> 也用于 parts 接管（`docs/ui/parts-architecture-upgrade.md`）验收：接管不改变这三档，
> 只照搬 `ComponentRegistry.render(activityCtx, comp)` 的自包含结果。

判据：`activityCtx` 必须是真的 Activity 上下文（现网与 parts 都用 `itemView.getContext()`），
否则依赖窗口令牌的输入/弹层会静默失败（IME 不弹、`Dialog` 抛 `BadTokenException` 被 try/catch 吞）。

### 档一 · 能操作并把值回传 Agent（真往返）

| 组件 / 机制 | 交互 | 回传链路（证据） |
|---|---|---|
| `ui_component` / `layout` 树卡 | 卡内 input/选择/开关 + 提交按钮 | `LayoutCardView.java:68` 注册 `registerInputCollector(cid, ()->collectValues(refs))`；按钮 `callback` 点击 `ComponentActions.perform:262` 合并 `collectInputs` → `notifyResult:164` → `PythonToolManager.notifyChatComponentResult:330` → `setComponentResult:4784` 唤醒 `SystemUIComponentTool.get_result:291`（`interactive`/`registerComponentCallback` 见 `SystemUIComponentTool.java:630/636`） |
| `QuizCardView` | 点选项答题 | `QuizCardView.java:131` 点击 → `dispatchResult` 提交 `quiz_answer`（`:244`）走 `chat_ui_actions` 通道 |
| `FeedbackBarCardView` | 赞 / 踩 | `FeedbackBarCardView.java:99` → `dispatchResult` 到 `chat_ui_actions`（`:101`） |
| 任意卡的底栏 `props.actions` | callback / refresh / link / copy | `ComponentActions.renderActions:199`→`perform:249`/`execute:287`；`callback` 回传值、`refresh` 回传 `"refresh"`、`link`(`:310`)/`copy`(`:290`) 本地 |

### 档二 · 仅本地交互（点了动，但不向 Agent 回传）

`VideoCardView`（ExoPlayer 起停/进度，`:111`）、`AudioCardView`（播放+`SeekBar`，`:199/:212`）、
`ImageGridCardView`（点开大图 `Dialog`，`:223/:323`）、`ChartCardView`（"查看数据表"本地 `AlertDialog`，`:111/:147`）、
`FileCardView`（预览 `:104`/分享 `:254`/图弹层 `:196`）、`FileListCardView`（行跳转 `:153`）、
`LinkCardView`（整卡开 URL `:58`）、`ContactCardView`（拨号/短信/邮件/地图 `startActivity:150`）、
`AppCardView`（再次进入 `startActivity:104`）、`HtmlCardView`（WebView 导航/返回 `:153`、复制链接 `:621`、图弹层 `:718`）、
`MarkdownCardView`（`LinkMovementMethod` 内联链接可点 `:68`）、`ListCardView`（行 `openLink:132`）。
（`VideoCardView`/`AudioCardView` 的播放器在 `onDetachedFromWindow` 自毁——重挂同一路径即死卡，须"渲一次挂一次"。）

### 档三 · 看着像可操作、实则纯展示（易误判，重点）

- 聊天流里的**内联 layout 原语** `toggle / stepper / slider_range / tag_input / checkbox_group / radio_group / search_bar / calendar`：
  `LayoutPrimitiveCardView.createView` 把 viewRefs 传成 `new HashMap<>()` 即丢弃（`LayoutPrimitiveCardView.java:72-73`），
  **既不注册收集器、也无提交动作**——点了不回收。类注释自认"本卡片主要用于展示类，交互控件请走 ui_component 弹窗/插件链路"（`:19-20`）。
  要可操作：改用 `layout` 树卡（档一）或 `ui_component`。
- `TodoCardView`：`✅/⬜` 是无监听 TextView、整行无 click（`TodoCardView.java:74-91`）；勾选态只由 Agent 重发组件驱动。
- `NativeLayoutRenderer` 里 `breadcrumb` 的 `crumbTv.setOnClickListener` 是**空 body**（`:3144`）、`notice` 的"操作"标签只 `setTag` 存进 viewRefs、无 click listener（`:2241/:2244`）、
  `accordion`/`carousel` 只改本地展开/翻页、不 collectValues、不注册收集器。名字像交互，实为本地/静态。

### 全 App 既有两处边界（非 parts 引入）

1. `ContactCardView` 的 dial/sms/email/map 调 `startActivity` **没加** `FLAG_ACTIVITY_NEW_TASK`（`:150/158/167/175`）——
   非 Activity 上下文会抛错落到"无法打开"Toast。现网/parts 都传 Activity，故不触发；但它是与其余卡（都正确加 NEW_TASK）不一致的孤例。
2. parts 的 `TurnPartsHost` 仅在**某卡自身指纹变化**时才重渲该卡；用户在卡内输入未提交时，别的 part 更新不会重建这张卡 → 输入态天然保留，
   不需旧 `componentSegmentViews` 那套段缓存来防重建。

