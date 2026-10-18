# HANDOVER · 答题宝 AI 对话 / Agent UI 改版

> 交接时间：2026-10-19（B0、C0 均已落地；未提交、未真机验证）
> 交接对象：在 Qoder 中继续开发的 Agent
> 当前状态：**设计 + 原型完成；Track B0（安全 + 卡片外壳）与 Track C0（工具结果 5 个止血改动）均已进源码，编译/单测通过，未提交**
> 下一步：B1（quiz 可答题 + 类型收敛）→ A0 → C1 → A1 → A2 → B2；B0/C0 的真机回归可并行插队
> 必读：`docs/ui/ai-chat-redesign.md`、`docs/ui/component-system-review.md`、`docs/ui/tool-result-step0-tasks.md`、`demo/README.md`
> **先读 §4.1（B0）与 §4.2（C0）**：落地时对两份任务单的口径做了修正，别再按旧文字实现。

---

## 0. 一句话交接

聊天页、Agent 执行子 UI、Agent 控制台的方案和可交互 Demo 已经做完；
**Track B0（HtmlCardView 沙箱 + CardShell/ComponentTheme + 6 卡迁移 + 硬编码色/emoji 清理 + actions 进 footer）
与 Track C0（工具结果 5 个止血改动：去自动解读、结果限长、去成功消息、工具中文名、单测）已在源码落地**，
改动清单与偏离说明见 §4.1 / §4.2。剩下的按 **B1 → A0 → C1 → A1 → A2 → B2** 落地，
不要推翻现有代码，聊天页那条线先加 `chat_ui_v2` 开关，新旧并行。

---

## 1. 这次新增了什么（文档/原型为 untracked；B0 源码改动见 §4.1）

| 路径 | 内容 |
|------|------|
| `docs/ui/ai-chat-redesign.md` | 主方案：现状盘点、问题诊断、竞品参考、聊天页/Agent 执行/Agent 控制台设计、token、三期计划、验收 |
| `docs/ui/component-system-review.md` | 内置 UI 组件审查：HTML 安全、CardShell、主题分裂、quiz 不能答题、类型爆炸、迁移顺序 |
| `docs/ui/tool-result-ui-review.md` | 工具结果组件审查：同一结果画三遍、UI 不截断、工具名硬编码、`ToolResultCard` 目标设计 |
| `docs/ui/tool-result-modification-directions.md` | 工具结果修改方向：统一结果模型、单卡 + 详情页、Agent 时间线、运行时策略 |
| `docs/ui/tool-result-step0-tasks.md` | **C0 任务单**：5 个止血改动（自动解读/AI 气泡、限长、成功消息、工具中文名、单测），含文件/行号/验收 |
| `docs/ui/HANDOVER.md` | 本文件 |
| `demo/ai-chat-redesign.html` | 单文件可交互高保真原型（对话 / Agent / 控制台 / 空状态，三主题） |
| `demo/README.md` | Demo 打开方式、可试操作、URL 参数 |
| `demo/screenshots/*.png` | 20+ 张截图（浅色/深色/沉浸蓝、Agent 运行/完成、控制台 6 Tab、面板） |

**B0 已修改/新增 `src/` 源码与资源**（清单见 §4.1）；本次审查/任务文档仍是 untracked。仓库里原有的 22 个 modified 文件是用户之前的工作，**不要 revert、不要覆盖**。

---

## 2. 仓库与工程事实

- Android / Java 17，包名 `com.oilquiz.app`，Material Components，minSdk Android 12。
- 主分支 `main`；工作区已有用户未提交修改（约 22 个 modified + 1 个已暂存删除 `UsageInterceptingWrapper.java`），先 `git status` 确认，再动手，**不要 revert**。
- 关键文件与体量：
  - `src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java`：10,165 行
  - `src/main/java/com/oilquiz/app/ai/chat/ChatAdapter.java`：5,334 行，11 种消息类型
  - `src/main/res/layout/activity_ai_chat.xml`：683 行，8 层结构
  - `src/main/java/com/oilquiz/app/ai/chat/component/`：48 个文件、8,661 行、30 个 `*CardView` + B0 新增外壳/沙箱
  - `src/main/java/com/oilquiz/app/ui/activity/AgentManagerActivity.java`：148 行，4 Tab
  - `src/main/java/com/oilquiz/app/ai/agent/ui/AgentSessionView.java`：328 行
  - `src/main/java/com/oilquiz/app/ai/chat/component/HtmlCardView.java`：740 行（B0 后）
  - `src/main/java/com/oilquiz/app/ai/chat/component/ComponentRegistry.java`：289 行（B0 后）
- 构建：`gradlew.bat assembleDebug`。`build.gradle` 有**二进制资源预检**：`liblo-native-code.so`（约 171 MB）、`asr/model.int8.onnx`（约 228 MB）缺失会直接失败。先确认这些文件在，再构建；不要为了跑构建去删/替换它们。
- 仓库里有大量 `build/`、`_archive/` 产物，**不要清理**。

---

## 3. 已确认的设计决策（不要重新讨论）

### 3.1 聊天页

- 一屏只留 3 层：顶栏 / 对话流 / 输入胶囊。
- 技术信息（模型、tokens、费用、上下文、KV、日志）全部下沉到 BottomSheet。
- AI 消息固定四段：思考（一行折叠）→ 工具（一组折叠）→ 正文 → 操作（末条 3 图标 + 更多）。
- 停止生成固定在输入框；停止后提供"继续生成"。
- 空状态用 4 张学习场景卡，不用 emoji + 示例 chip。
- 三套主题：浅色 / 深色 / 沉浸蓝（保留现有深蓝品牌）。

### 3.2 Agent 执行子 UI

- 6 块：任务计划 / 授权 / 工具 / 日志 / 反思 / 汇总。
- **授权前不渲染工具卡**；授权卡写清"读什么、为什么、读多久"。
- 暂停 / 单步 / 回退 / 停止收进计划卡；日志默认折叠。

### 3.3 Agent 控制台

- 6 Tab：概览 / 会话 / 记忆 / 工具 / 文件 / 统计。
- 入口统一聊天右上 `⋯`；不再从工具抽屉/设置页各放一个。
- 危险操作二次确认；记忆可搜索；文件区分私有/公共目录。

### 3.4 组件系统

- 统一 `CardShell`：surface + 1px border + 14dp 圆角 + 14dp 内边距；header 13sp/600；footer 最多 2 个动作 + 更多。
- `quiz_card` 必须可答题（选项可点、判题、回传），不能一上来显示答案。
- `html` 默认沙箱：JS 关、禁止 file access、桥白名单、URL 白名单。
- 类型收敛到 ≤18 个 canonical + alias；旧名保留兼容。
- 不再用 emoji 当图标；颜色只走 token。

---

## 4. 未完成 / 下一步任务

> B0 已完成。推荐顺序：**C0（工具结果止血）→ B1 → A0 → C1 → A1 → A2 → B2**；如果更想先看到聊天页效果，可以把 A0 提前，C0 与 A0 互不阻塞。

### Track B（组件，B0 已完成；B1/B2 待做）

**B0 · 安全 + 外壳（2–3 天）— 2026-10-19 已完成，见 §4.1 落地记录**

- [x] `HtmlCardView` 沙箱：
  - JS 默认关闭；仅受信任模板（内置 asset / 固定模板）可开启。
  - `setAllowFileAccessFromFileURLs(false)`、`setAllowUniversalAccessFromFileURLs(false)`。
  - Java 桥只保留 `copy` / `openLink`，按 origin 白名单；移除任意本地路径解析。
  - URL 只允许 `https`（+ 显式白名单）；限制 HTML 大小（512 KB）。
  - `onDetachedFromWindow` 销毁 WebView；避免每卡一个常驻 WebView。（**口径已改，见 §4.1-2**）
- [x] 新增 `CardShell` + `ComponentTheme`（复用 `?attr/colorSurface`、`colorOutline`、`textColorPrimary` 等）。
- [x] 迁移 6 个核心卡：`InfoCardView`、`QuizCardView`、`FileCardView`、`StepsCardView`、`TableCardView`、`ChartCardView`。
- [x] 清掉 `FeedbackBarCardView` / `AgentStatusChipCardView` 的硬编码色和 emoji。
- [x] `ComponentRegistry` 的 actions 渲染进卡片 footer，不再外层包 `LinearLayout`。

### 4.1 B0 落地记录（对本文原口径的 4 处修正 + 1 处补充）

> 实现时核对代码后偏离了原文，都是"原文会打脸"的地方，后续 Track 别再照抄旧口径。

1. **JS 开关按"内容来源"分四档，不是全局开关**（`HtmlSandbox.Trust`）。
   `MermaidMathSplitter` 生成的 mermaid/katex 卡片是**必须执行脚本**的内置模板
   （`js/mermaid.min.js`、`css/katex.min.css` 走 `file:///android_asset/` 相对引用），
   一刀切关 JS 会把聊天里的流程图/公式全变成空白卡。
   信任来源只认代码：新增 `ComponentData.internalTemplate`（**transient，不进持久化**），
   模型在自己的 component JSON 里写 `"internalTemplate":true` 无效
   （`HtmlSandboxTest.modelCannotForgeTrustedFlag` 钉住）；改造前的历史数据靠模板指纹兜底。
   内置模板档：JS 开 + 无桥 + 无文件访问 + mermaid `securityLevel` 由 `loose` 改 `strict`。
2. **销毁时机不是 `onDetachedFromWindow`**。
   `ChatAdapter` 对组件段做了指纹缓存（`AIMessageViewHolder#componentSegmentViews`，
   见 `renderMermaidMath` 与组件标记分支：`removeAllViews()` 后把缓存里的**同一个 View**
   再 add 回来），detach 即 destroy 会把复用中的 WebView 打死 → 空白卡。
   改为 `ComponentLifecycle.release(View)`，在"确定不再复用"的两处缓存替换点和
   `contentHost`/`componentContainer` 重建点显式释放；detach 只 `onPause()`。
3. **默认档（模型内联 HTML）高度是近似值**。
   自适应高度原本靠 `evaluateJavascript` 读 `scrollHeight`，该档 JS 已关，
   退回 `WebView.getContentHeight()`；个别页面可能比实际内容高一点（封顶 360dp 内滚）。
4. **模型内联 HTML 里的 `Android.copy/openLink` 按钮不再可用**。
   这是沙箱的必然结果（无桥）。替代路径两条：交互按钮走 `props.actions`
   （原生 footer），需要脚本的报表让 Agent 落成 HTML 文件走 `LOCAL_FILE` 档（桥可用、路径受限）。
   如果后面要给用户"信任此内容"的显式开关，属于新能力，另立任务，别默认放开。

5. **最严档先净化再渲染**：模型给的 HTML 字符串进 `HtmlSandbox.sanitizeInline()`
   （jsoup 真解析器，剥 `script/iframe/object/embed` + 全部 `on*` 属性 + `javascript:`/`data:text/html`
   协议 + refresh meta，**保留结构与内联 style**），然后才是 JS 关 + CSP。
   净化失败走 fail-closed（只留纯文本），不原样放行。
   这条是 B0 里唯一"实现踩出来的坑"：遍历 `Element.attributes()` 时直接 `removeAttr`
   会抛 `ConcurrentModificationException`，被兜底吞掉后**所有模型 HTML 卡片会整片变空**——
   单测 `inlineSanitizerStripsExecutionVectorsButKeepsStyling` 就是为了钉住它。

另外两条顺带修的：`renderFallback` 从裸文本 `⚠ 组件渲染失败` 升级为统一错误态 `CardShell`；
`ChatAdapter` 里两处同文案的降级占位也改走 `renderFallback`
（`strings_migrated.xml` 的 `h_ce08c6dd`/`h_56e45313` 因此不再被引用，暂留不清理）。
`SimpleWebViewActivity`（全屏本地 HTML 预览）与 html 卡片是同一个洞，已把它
`setAllowFileAccessFromFileURLs/setAllowUniversalAccessFromFileURLs` 从 `true` 收回 `false`
（`setAllowFileAccess(true)` 保留，报表自身与同目录相对资源照常加载）；
它复用的正是收紧后的 `HtmlCardView.HtmlJsBridge`。项目里另有
`webview/security/WebViewSecurityConfig`，`HtmlSandbox.configure` 现在先调它再打信任档补丁，
安全口径只维护一处。

**B0 新增文件**

| 文件 | 作用 |
|------|------|
| `ai/chat/component/HtmlSandbox.java` | 信任档判定 + WebView 收紧配置 + CSP 注入 + 工作区路径白名单（纯逻辑，可单测） |
| `ai/chat/component/HtmlWebView.java` | 记住信任档/桥对象，提供 `releaseComponent()`；detach 只 `onPause()` |
| `ai/chat/component/ComponentLifecycle.java` | `Releasable` 接口 + `release(View)` 树遍历（B1 的 `onRecycle` 走这条） |
| `ai/chat/component/CardShell.java` | 统一外壳：header/body/notice/footer + 状态位 + 「更多」溢出 |
| `ai/chat/component/ComponentTheme.java` | 组件 token 单一出口：先解析主题 attr，失败退回 `component_*` 资源 |
| `res/drawable/ic_file*.xml`、`ic_speaker/ic_share/ic_thumb_up/ic_thumb_down/ic_stop/ic_circle_outline.xml` | 替代文件类型/操作栏 emoji 的矢量图标 |
| `src/test/.../HtmlSandboxTest.java` | 17 条沙箱口径用例（恶意 HTML 注入与净化、伪造 trusted、截断、协议白名单、路径解码） |

**B0 改动文件**：`HtmlCardView`（也一并接了 CardShell，共 7 张卡）、`MermaidMathSplitter`、
`ComponentData`（加 transient 标记）、
`ComponentRegistry`（footer 分流 + 错误态兜底 + 描边取 token）、`ComponentActions`
（抽出 `actionRunner/perform`，两条按钮链路共用一套语义）、
`InfoCardView`、`QuizCardView`、`FileCardView`、`StepsCardView`、`TableCardView`、`ChartCardView`、
`FeedbackBarCardView`、`AgentStatusChipCardView`、`ChatAdapter`（仅组件缓存释放与降级占位 4 处）、
`SimpleWebViewActivity`（收回跨源文件访问）、
`res/values/{colors,dimens,strings}.xml`。

**验证（2026-10-19）**
- `gradlew --offline testDebugUnitTest --tests "*HtmlSandboxTest*"` → 17 passed
- 全量 `testDebugUnitTest` → 失败仍是**存量 25 个 / 同 7 个类分布**
  （见记忆 [[unit-test-baseline-failures]]），本次改动**没有新增失败**
- `gradlew --offline assembleDebug` → 通过，`build/outputs/apk/debug/答题宝-debug-2.0.apk`

**未验（B0 真机回归，可与 C0 并行）**：真机上 7 张迁移卡的外观与滚动表现、mermaid/公式仍渲得出、
工作区 HTML 报表（`file://` 档）的桥按钮仍可点、内联 HTML 卡片高度近似是否可接受。
安装 900MB 包要用后台跑（见 [[adb-verification-hooks]]）。



**B1 · 交互与收敛（3–5 天）**

- [ ] `QuizCardView` 可答题：单选/多选/判断 → 判题 → 正确/错误态 + 解析 → `ComponentActions` 回传 `quiz_answer`。
- [ ] canonical 类型表（≤18）+ alias；`ChatComponent` 增加 `getSchema()` / `getVersion()` / `onRecycle()`。
- [ ] `DynamicCardView` 折叠 + 最大高度 + "查看原始 JSON"。
- [ ] 统一空态 / 错误态 / 截断态；图表提供"查看数据表"降级。

**B2 · 质量（约 1 周）**

- [ ] WebView / 媒体生命周期；长会话不增长。
- [ ] `style` 只接受 token，不接受任意十六进制色。
- [ ] 无障碍：contentDescription、48dp 触控、TalkBack 顺序。
- [ ] 30 卡 × 3 主题截图回归。
- [ ] 拆分 `NativeLayoutRenderer`（240 KB）与聊天组件，统一类型注册表。

### Track C（工具结果，C0 先做）

> 完整任务单：`docs/ui/tool-result-step0-tasks.md`（含每个任务的文件/行号/代码骨架/验收）
> 审查：`docs/ui/tool-result-ui-review.md`；方向：`docs/ui/tool-result-modification-directions.md`
> **C0 不依赖 B1，也不要动 B0 的 `CardShell` / `HtmlSandbox` / `HtmlWebView` / `ComponentLifecycle`。**

**C0 · 5 个止血改动（0.5–1 天）— 2026-10-19 已完成，见 §4.2 落地记录**

- [x] **T1 关自动解读 + 删 AI 气泡**：`AIChatActivity` 三处（主链路 / 聚合流程 / 重试成功）删掉
      `ToolResultInterpreter.interpret(...)` 与"💡 正在AI解读结果..."；保留 `handleAiInterpretAction` 手动入口。
      成功工具只更新工具卡，然后 `onComplete.run()` / `executeCompositeFlow(flow, stepIndex + 1)`。
- [x] **T2 结果限长 + 查看全部**：新增 `ai/chat/util/ToolResultText`（8 行 / 2,000 字 + `…`）；
      `ChatAdapter.buildResultWithInterpretChip` 走预览，超长追加"查看全部（N 字）"可点击 Span，
      `showFullToolResult` 弹 `ScrollView + TextView`，支持复制。
- [x] **T3 删逐工具成功消息**：三处 `addSystemMessage("✅ …")` 已删；保留流程级 L2998 与失败消息。
- [x] **T4 工具中文名 + 状态去 emoji**：新增 `ai/chat/ToolMetaRegistry`（按 `AITool#getName()` 实际注册名 + 历史别名）；
      `ChatMessage.formatToolDisplayName` 委托它；`getStatusText` 改纯文本；
      `ChatAdapter.bindToolCallMessage` 按 success/error/secondary 设色（`toolStatusColor`）。
- [x] **T5 单测 + 回归**：`ToolResultTextTest`(6)、`ToolMetaRegistryTest`(5) 全绿；
      `HtmlSandboxTest`(17) 仍全绿；全量单测失败仍是存量 25 个 / 同 7 个类；`assembleDebug` 通过。

### 4.2 C0 落地记录（对任务单的 4 处补充）

1. **`ToolExecutionOrchestrator` 是一份没有调用点的副本**。
   `ai/agent/ToolExecutionOrchestrator` 里有第四份"执行 → 自动解读 → 成功消息"逻辑
   （L104/L109/L114），但全仓库没有任何 `new ToolExecutionOrchestrator(...)`，`Host` 也没人实现
   （进度消息位置写死 `-1`，"正在解读"提示根本关不掉）。任务单的 T1/T3 行号只覆盖 `AIChatActivity`。
   本次**没有改它**（改了也验不了，且它不参与运行），只在类注释里写明"接线前必须同步去掉自动解读"。
   要么删掉，要么接线后统一改——等你拍。
2. **T1 保留了"结果回灌本地模型上下文"**。
   原来 `injectInterpretToLocalContext(...)` 只在自动解读成功时调用；本地模型的多轮历史由
   native KV 侧维护，不注入的话用户接着追问就看不到这次工具结果。
   现在改成用 `formatForUi` 的模板文本注入（**不产生任何气泡、不多等一次 LLM**），
   在线模型仍走 `chatHistory` 重建，不受影响。
3. **T2 顺带覆盖了旧 `TOOL_RESULT` 消息**。
   `bindToolCallMessage` 的兼容分支（`info == null`，直接显示 `message.content`）此前也是整段进
   TextView，同一类卡顿；已按同一口径改成预览 + 查看全部。
4. **工具卡文案去掉三处 emoji**（`💡 ` 前缀、`h_129d0a0f` = "📋 原始结果："、
   `🔍 点击使用AI深度解读此结果`），改走 `strings.xml` 的 `tool_result_*`。
   注意：`toolIcon` 仍是 emoji（要改布局 `TextView → ImageView`，按任务单留给 B1）；
   历史消息里已持久化的 `toolDisplayName`（旧兜底产出的 "python execute"）不会自动变，
   新消息才是中文名——要连旧消息一起刷，得在反序列化后重算 `toolDisplayName`，属 C1 范畴。


**C1 · 结果模型 + Presenter 化（3–5 天，C0 稳定后）**

- [ ] `ToolResultPresentation`（title/status/summary/body/raw/actions）；`AITool.presentResult` 按 schema 输出，复用现有 `onToolPresent → presentCard` 通道。
- [ ] `ToolResultCard` 复用 `CardShell`；body 走 `ComponentRegistry.renderBody()`（避免双层卡片）。
- [ ] `ToolResultPresenter` + 注册表；`ToolResultCardBinder` 降级为旧工具 fallback，逐步删 switch/三份名单。
- [ ] AI 解读改按需按钮；`ToolResultStore` LRU + 落盘；`ChatMessage` 只存 preview/ref。
- [ ] Agent 运行内用时间线（`AgentRunCard`），每步展开 compact 结果卡。

### Track A（聊天 / Agent UI）

**A0 · 视觉收敛（1–2 天）**

- [ ] 新增语义 token（`colors.xml` / `dimens.xml` / `styles.xml`）。
- [ ] 重写 `activity_ai_chat.xml`：删除 3 行状态胶囊、`remote_dsh_bar`、`ChatStatsBar`、日志面板。
- [ ] 顶栏改为"标题 + 模型副标题 + 新建 + 更多"。
- [ ] 输入区 3 个 chip 压成一行 28dp；统计/模型进 BottomSheet。
- [ ] 加 `chat_ui_v2` 开关（SharedPreferences 或现有 `AppConfigManager`），默认关。

**A1 · 消息重构（3–6 天）**

- [ ] 新建 `item_ai_message_v2.xml`：思考行 / 工具组 / 正文 / 操作四段。
- [ ] `ChatAdapter` 拆 Binder：`UserBinder` / `AiBinder` / `SystemBinder` / `ToolBinder`；**不要一次性重写 5,302 行**，按 viewType 逐步切。
- [ ] 思考折叠 + 工具组折叠 + 流式光标。
- [ ] 操作行 3 图标 + 更多面板；末条常驻。
- [ ] 停止生成移到输入框，停止后"继续生成"。
- [ ] 空状态场景卡；日期分隔替代逐条时间戳。

**A2 · Agent 执行 + 控制台（4–6 天）**

- [ ] `AgentExecutionView` 拆 `AgentPlanView` + `AgentLogView`；暂停/单步/回退并入 Plan。
- [ ] 授权卡 / 工具卡 / 反思卡 / 汇总卡；授权前不渲染工具卡。
- [ ] `AgentManagerActivity` 4 → 6 Tab：概览 / 会话 / 记忆 / 工具 / 文件 / 统计。
- [ ] 记忆搜索；工具「更多」；文件私有/公共目录说明；危险操作二次确认。
- [ ] `AgentSessionView` 留在聊天流；控制台「会话」Tab 只展示列表与状态。

---

## 5. 关键文件 / 改动地图

| 区域 | 文件 | 动作 |
|------|------|------|
| 聊天页布局 | `res/layout/activity_ai_chat.xml` | 重写，删状态胶囊/连接条/统计条/日志 |
| 输入区 | `res/layout/view_chat_input_bar.xml`、`ai/chat/input/ChatInputBar.java` | 胶囊输入 + 停止按钮 |
| 用户消息 | `res/layout/item_user_message.xml` | 去头像/时间戳 |
| AI 消息 | 新增 `res/layout/item_ai_message_v2.xml` | 四段结构 |
| Adapter | `ai/chat/ChatAdapter.java` | 拆 Binder；AI 构造器重写 |
| 统计 | `ai/chat/ui/ChatStatsBar.java` | 改为 `SessionInfoSheet` |
| 工具抽屉 | `res/layout/view_tool_drawer.xml` | 改为 `ToolCenterSheet` |
| Agent 执行 | `ai/chat/AgentExecutionView.java` | 拆 `AgentPlanView` + `AgentLogView` |
| Agent 消息 | `item_agent_step_message.xml`、`item_tool_call_message.xml`、`item_agent_summary_message.xml`、`item_agent_reflection_message.xml` | 合并进四段/六块 |
| Agent 控制台 | `ui/activity/AgentManagerActivity.java`、`res/layout/activity_agent_manager.xml` | 4 → 6 Tab |
| 记忆 | `ai/agent/ui/AgentMemoryView.java` | 搜索、统一卡片、删除确认 |
| 工具 | `ai/agent/ui/AgentToolView.java` | 动态工具 + 组件注册 + 更多菜单 |
| 文件 | `ai/agent/ui/AgentWorkspaceView.java` | 存储占比 + 权限 + 长期/临时 |
| 统计 | `ai/agent/ui/AgentStatsView.java` | 指标 + Top 工具 + 组合 + 清理 |
| 组件注册 | `ai/chat/component/ComponentRegistry.java` | CardShell、actions 进 footer、fallback |
| 工具卡（C0 已完成） | `ai/chat/ChatAdapter.java`（TOOL_CALL 绑定）、`res/layout/item_tool_call_message.xml`、`ai/chat/component/ToolCallCardView.java` | 已做：结果限长 + 查看全部、状态色、状态去 emoji；C1 再合并为 `ToolResultCard` |
| 工具结果（C0 已完成 / C1 待做） | `AIChatActivity.java`、`ai/agent/ToolResultInterpreter.java`、`ai/agent/ToolResultCardBinder.java`、`ai/agent/ToolResultStore.java`、`ChatMessage.ToolCallInfo` | C0 已去自动解读/AI 气泡/成功消息；C1 做 Presenter + LRU（Binder 映射与 Store 本次未动） |
| 结果模型（C0 前两个已完成） | 新增 `ToolResultText`、`ToolMetaRegistry`（已完成）、`ToolResultPresentation`、`ToolResultCard`、`ToolResultPresenter`（C1） | — |
| HTML 组件 | `ai/chat/component/HtmlCardView.java` | 沙箱（B0 已完成，别重做） |
| 反馈栏 | `ai/chat/component/FeedbackBarCardView.java` | 去硬编码色/emoji，或移出卡片体系 |
| Agent 状态 | `ai/chat/component/AgentStatusChipCardView.java` | 去硬编码色/emoji，或移出卡片体系 |
| 主题 | `res/values/colors.xml`、`dimens.xml`、`styles.xml`、`values-night/` | 新 token；组件与聊天页共用 |

---

## 6. 约束与坑

1. **不要整体重写 `ChatAdapter` / `AIChatActivity`**。先加开关，按 viewType 逐步切。
2. **不要改 `ChatMessage` 的持久化结构**；`ComponentData` 由 `ChatHistoryManager` 的 TypeAdapter 持久化，类型/字段变更必须兼容旧数据（alias）。
3. **颜色**：禁止新增 `0xFF…` / `#…` 硬编码；用 `ThemeColors`、`?attr/colorSurface`、`R.color.*`。
4. **emoji**：新 UI 不用 emoji 图标；用 `res/drawable/ic_*.xml` 矢量图。清理字符串里的 emoji（`chat_speak` = `🔊 朗读` 等）。
5. **HTML 安全**：B0 已完成沙箱（`HtmlSandbox` / `HtmlWebView`）；不要再开 JS / file access；改动 Html 相关必须跑 `HtmlSandboxTest` 的 17 条用例。
6. **构建资源**：`build.gradle` 预检会因缺少大二进制文件失败；不要删除 `viewer/src/main/jniLibs/...`、`src/main/assets/asr/...`。
7. **Git**：仓库已有 22 个用户修改文件 + B0 的未提交改动；只提交你改的文件，不要 `git add -A` 覆盖。
8. **不要动 `build/`、`_archive/`、`libs/`**。
9. **文案**：中文文案走 `strings.xml`；仓库里还有 `strings_migrated.xml`（102 KB），先确认引用的是哪一个，别重复加。

---

## 7. 验收 / 测试

- 手动矩阵：浅色 / 深色 / 沉浸蓝 × 字体缩放 1.3× × 小屏（360dp）。
- 长会话：200 条消息滚动/流式更新，WebView 数量不增长。
- Agent：等待授权 → 允许一次 → 工具执行 → 完成；拒绝 → 跳过。
- 控制台：6 Tab 切换；记忆搜索/删除；文件授权；危险操作二次确认。
- 组件：quiz 答题 → 判题 → 回传；空/错/超长内容；HTML 注入用例。
- 工具结果（C0）：成功/失败/200KB 输出/手动 AI 解读/聚合流程/历史恢复，明细见 `docs/ui/tool-result-step0-tasks.md` §T5。
- 构建：`gradlew.bat assembleDebug`（确认二进制资源存在）。
- 截图：`demo/screenshots/` 是设计基线，可作为实现对照。

---

## 8. 给 Qoder 的启动提示（可直接粘贴）

```text
你在继续 D:\qzq\smartquiz 的 AI 对话 / Agent UI 改版。先读：
- docs/ui/HANDOVER.md
- docs/ui/tool-result-step0-tasks.md
- docs/ui/tool-result-ui-review.md
- docs/ui/tool-result-modification-directions.md
- docs/ui/ai-chat-redesign.md
- docs/ui/component-system-review.md
- demo/README.md

现状：
- B0（HtmlSandbox + CardShell + 6 卡迁移）已完成并编译/单测通过，不要重做；
  也不要动 CardShell / HtmlSandbox / HtmlWebView / ComponentLifecycle。
- C0（工具结果 5 个止血改动）已完成，见 §4.2；不要重做，也不要"顺手"接线
  ToolExecutionOrchestrator（它是无调用点的副本）。
- 仓库已有未提交修改（含 B0/C0），不要 revert / 不要 git add -A。

下一步：做 Track B1（组件交互与收敛），严格按 docs/ui/component-system-review.md §4 P1：
1) QuizCardView 可答题：选项可点 → 判题 → 正确/错误态 + 解析 → ComponentActions 回传 quiz_answer；
2) canonical 类型表（≤18）+ alias；ChatComponent 增 getSchema()/getVersion()/onRecycle()；
3) DynamicCardView 折叠 + 最大高度（用 CardShell.enableBodyScroll）+ "查看原始 JSON"；
4) 统一空态 / 错误态 / 截断态；图表提供"查看数据表"降级。
判题在本地做还是回传模型判（§9-3）先问用户再动手。

B0/C0 的真机回归仍未做（安装 + 卡片外观 + 工具卡限长 + 手动解读入口），可与 B1 并行插队。

约束：
- 不整体重写 ChatAdapter / AIChatActivity；不改 ChatMessage 持久化结构。
- 新 UI 不用 emoji 图标、不新增硬编码颜色；中文文案走 strings.xml。
- 构建前先确认 liblo-native-code.so / asr/model.int8.onnx 存在。
- 动 HTML 相关必须跑 HtmlSandboxTest；全量单测存量失败 25 个 / 7 个类，别算到自己头上。
```

---

## 9. 开放问题（落地前和用户确认）

1. 默认主题：中性浅色（推荐）还是保留现有深蓝渐变作为默认？
2. Agent 控制台「会话」Tab 要不要展示正在运行的实时会话，还是只展示历史？
3. quiz 判题在本地做，还是回传 Agent 由模型判？
4. 文件"公共目录权限"的具体范围（Downloads / 用户选择目录 / 全盘）？
5. 是否接受把 `feedback_bar` / `agent_status` 移出可被模型调用的卡片类型（推荐）？
   —— B0 只把它们的颜色/图标接了 token，**注册状态原样保留**，移出与否仍等你拍。

---

## 10. 参考

- 主方案：`docs/ui/ai-chat-redesign.md`
- 组件审查：`docs/ui/component-system-review.md`
- 工具结果审查：`docs/ui/tool-result-ui-review.md`
- 工具结果修改方向：`docs/ui/tool-result-modification-directions.md`
- 工具结果 Step 0 任务单：`docs/ui/tool-result-step0-tasks.md`
- Demo：`demo/ai-chat-redesign.html`（`start` 直接打开）
- Demo 参数：`demo/README.md`
- 设计基线截图：`demo/screenshots/`
