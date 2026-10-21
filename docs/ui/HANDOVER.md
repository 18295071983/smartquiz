# HANDOVER · 答题宝 AI 对话 / Agent UI 改版

> 交接时间：2026-10-19（B0 / C0 / B1 / A0 / demo UI 落地与接线 / A1 前三段已落地；C1、B2 各完成一部分；A1 剩余与 A2 未开始）
> 交接对象：在 Qoder 中继续开发的 Agent
> 当前状态：**v2 已过真机首轮回归（浅底页面、顶栏、输入胶囊、正文段都确认生效）；A1 第四段（日期分隔 + 操作段图标化）与思考/工具段的真机观感都还没看过；会话重复已查到第三层主因并修完（待装机复测）；JVM 单测 417 条全绿（0 跳过——更早报的"407 全绿"里有 128 条被 Robolectric 静默跳过，见 §7）**
> 下一步：切一条有工具调用的历史会话回归 A1 的思考段与工具段 + 点「新对话」看空状态首屏，再做 A1 剩余（操作段图标化、日期分隔、Binder 拆分）
> 必读：`docs/ui/ai-chat-redesign.md`、`docs/ui/component-system-review.md`、`docs/ui/tool-result-step0-tasks.md`、`demo/README.md`
> **先读 §4.1（B0）/ §4.2（C0）/ §4.3（A0）/ §4.4（demo UI 落地与接线）/ §4.5（A1 三段）**：落地时对任务单口径做了修正，别再按旧文字实现。

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



**B1 · 交互与收敛（3–5 天）— 2026-10-19 已完成**

- [x] `QuizCardView` 可答题：选项可点（单选/多选/判断）→ 本地判题 → 正确/错误态 + 解析 →
      「重做」；结果以 `quiz_answer` 回传（有 `component_id` 走该 id，否则走 `quiz_card` 通道）。
      答案与解析不再在作答前显示。
- [x] canonical 类型表 + alias：新增 `ComponentTypes`（18 个 canonical + 场景类型 + 别名表 +
      字段目录），注册表里**原生卡赢下被 Layout 原语占用的名字**，原语保留 `primitive_<名字>`；
      `ui_component(action=list_types)` 同时返回内置目录与别名。
      `ChatComponent` 补 `getCategory()/getVersion()/getSchema()/onRecycle()` 默认实现。
- [x] `DynamicCardView` 折叠 + 最大高度 + "查看原始 JSON"（默认只显示 title/content/items，
      其余字段收进限高滚动区，超 4000 字截断）。
- [x] 统一空态/错误态/截断态：`file_list` 接 CardShell 并截断超 30 条；`chart` 增加
      "查看数据表"降级（复用 `table_card` 渲染）；emoji 图标统一走新 `FileGlyph`。
- [ ] 未做：`ChatComponent.getSchema()` 目前是 `ComponentTypes` 目录的转发，各卡没有专属 schema；
      判题口径见 §9-3（本次按"本地判 + 回传"实现，可改）。

**B2 · 质量（约 1 周）— 部分完成**

- [x] `style` 只接受 token，不接受任意十六进制色；`padding`/`radius`/`border` 宽度加上限。
- [x] WebView 生命周期：B0 的 `ComponentLifecycle.release()` + `HtmlWebView.releaseComponent()`
      已覆盖"确定不再复用即销毁"；`ChatComponent.onRecycle(View)` 钩子已留（B1）。
- [ ] `ToolResultStore` 落盘（本次只做了内存限长 32 条，见 C1）。
- [ ] 无障碍：contentDescription 只在少数新控件加了，未系统覆盖 30 卡；48dp 触控未全面达标。
- [ ] 30 卡 × 3 主题截图回归（未做，需要真机/模拟器批量截图）。
- [ ] 拆分 `NativeLayoutRenderer`（240 KB）与聊天组件、统一类型注册表（未动）。

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


**C1 · 结果模型 + Presenter 化（3–5 天，C0 稳定后）— 只做了两项，其余未开始**

- [x] `ToolResultStore` 内存限长：静态列表原本只增不减（存的是原始结果 + 结果 JSON 全文），
      现按 `MAX_KEEP=32` 只保留最近若干次；`ToolResultStoreTest`(3) 钉住上限/最新条/流程内引用。
- [x] AI 解读改按需按钮 —— C0 已完成（自动触发删除、手动入口保留）。
- [ ] `ToolResultPresentation`（title/status/summary/body/raw/actions）与 `AITool.presentResult`。
- [ ] `ToolResultCard` 复用 `CardShell`；body 走 `ComponentRegistry.renderBody()`。
- [ ] `ToolResultPresenter` + 注册表；`ToolResultCardBinder`（1647 行）降级为 fallback。
- [ ] `ToolResultStore` 落盘 + `ChatMessage` 只存 preview/ref。
- [ ] Agent 运行内时间线（`AgentRunCard`）。

> 为什么只做了两项：C1 的主体是把 1647 行的 `ToolResultCardBinder` 与 1616 行的
> `ToolResultInterpreter` 里的映射迁到 Presenter 上。那需要逐工具核对输出结构（30+ 工具），
> 塞进本轮"顺手做完"只会得到一层没人验证的抽象 + 半迁移的映射表，比现状更糟。
> 建议单独排一期，从 3–5 个高频工具开始逐个迁，每迁一个跑一次真实调用。


### Track A（聊天 / Agent UI）

**A0 · 视觉收敛（1–2 天）— 2026-10-19 已完成（开关式），见 §4.3**

- [x] 语义 token：B0 的 `card_shell_*` dimens + `ComponentTheme` 已覆盖取色与尺寸；A0 复用，未再新增同义 token。
- [x] 技术信息下沉：`service_status_bar` / `remote_dsh_bar` / `log_container` 在 v2 下收起，
      模型/余额/token/生成阶段/KV/日志统一进右上「⋯」的 `BottomSheetDialog`。
- [x] 顶栏改为"标题 + 模型副标题 + 新建 + 更多"。
- [x] 输入区 3 个 chip 压成 28dp 一行。
- [x] `chat_ui_v2` 开关：`ai/chat/ui/ChatUiFlags`（SharedPreferences，**默认关**），
      面板内可切换，改完重进聊天页生效。
- [ ] 布局文件本身未重写（`activity_ai_chat.xml` 保持原样），旧控件仍在布局里只是被隐藏；
      A1 做四段消息结构时再决定是删掉这些节点还是继续隐藏。

### 4.3 A0 落地记录（做法与任务单不同）

1. **没有重写 `activity_ai_chat.xml`**，而是新增 `ai/chat/ui/ChatUiV2Chrome`，
   在 `AIChatActivity.initData()` 开头调用一次（开关关闭时直接 return，零影响）。
   原因：那个布局 683 行、被 10k 行的 Activity 按 id 绑着，重写布局等于把绑定全炸一遍；
   开关式改造能先并行验证视觉，A1 再决定要不要落到 XML。
2. **技术信息的数据源没有另起一套**：被收起的 `service_status_text` / `tv_api_balance` /
   `tv_token_stats` / `tv_gen_phase` / `tv_kv_stats` / `log_content` 仍由原逻辑刷新，
   「⋯」面板打开时直接读它们的当前值。代价是这些控件"看不见但还在跑"，
   A1 若要删控件，得同时把写入方改成写 ViewModel。
3. **「新建对话」复用历史抽屉里的 `btn_new_conversation.performClick()`**，
   没有复制一份会话切换逻辑（那条逻辑涉及会话 id 派发、历史落盘、上下文清理）。
4. **开关入口在面板里**（"启用新版界面"），改完要重进聊天页；默认关。
   adb 也可改：`run-as com.oilquiz.app` 写 `shared_prefs/chat_ui_prefs.xml` 的 `chat_ui_v2`。
5. **未验**：v2 外壳在真机上的实际观感（顶栏是否与状态栏打架、chip 28dp 是否点得动、
   面板读数是否有值）。这是 A0 目前最大的空白，也是下一步该先做的事。

### 4.4 demo UI 落地 + 接线记录（A0 的第二步，2 个 commit）

用户口径是"**先把 demo 中的 UI 实现了，再接线**"，所以拆成两个 commit：
`feat(chat-ui): demo 视觉体系落地为 v2 token / 图标 / 布局`（纯视图层，无引用点）+
`feat(chat-ui): 把 v2 布局接进聊天页（输入栏 / 顶栏 / 空状态 / 用户气泡）`。

1. **demo 的设计系统进了资源**：`chat_v2_*` 颜色（values + values-night 两档）、
   `chat_v2_*` 尺寸、14 枚 shape drawable、4 枚线性图标（sparkle/bulb/plan/globe）。
   `ic_home_plan` 是 56dp 带渐变底块的首页图标，当不了行内图标，所以另出一枚 plan。
   仍然没接 demo 的第三套蓝色主题（`[data-theme="blue"]`），也没有渐变按钮 ——
   `MaterialButton` 的 `backgroundTint` 只吃纯色，渐变得换 ImageView，先记在这。
2. **两套布局并存的口径**：输入栏和用户气泡的 v2 布局**控件 id 与旧布局逐一对应**，
   所以 `ChatInputBar` / `UserMessageViewHolder` 只按开关换 inflate 的文件，绑定逻辑零分支。
   空状态的场景卡用 v2 专属 id，**不能**复用 `chip_empty_exampleN`：
   Activity 里那几个字段声明成 `Chip`，塞进 LinearLayout 会 ClassCastException。
3. **顶栏从代码拼装改成 inflate `view_chat_appbar_v2`**，三个入口继续转发旧控件
   （历史=`btn_history`、新建=`btn_new_conversation`、模型=`btn_model_select`）。
   这些控件在 v2 下是 GONE 的，`performClick()` 照样能点，所以不必搬逻辑。
   副标题同步从"按 tag 找"改成"按 id 找 + tag 兜底"，并新增 `updateTitle()`，
   Activity 在刷新历史抽屉时顺手把当前会话标题写进顶栏。
4. **空状态是怎么"复活"的**：旧版 `updateEmptyState()` 把空状态**永远置 GONE**
   （注释写着"已移除欢迎界面"），而 `ChatAdapter#setEmptyView` 那套数据观察者
   一直没人用。v2 直接复用它做唯一写入者，`updateEmptyState()` 在 v2 下不再插手，
   避免出现两个写入者互相打回。**开关关着时仍是"永远只显示列表"。**
5. **"正在生成"提示行**由 `ChatInputBar#setGeneratingHint()` 驱动，
   挂在 `toggleStopButton()` 上（生成开始/结束各调一次，8 个调用点不用改）。
   demo 文案带秒数（"正在生成 · 0s"），秒数需要一个计时器，
   现有 `GenerationStatusBar` 没有对外暴露 elapsed，**先只做静态文案 + 呼吸点**，
   秒数留给 A1（与 think-line 的"已深度思考 N 秒"一起做，两处共用一个计时口径）。
6. **think-line / tool-line 两个布局已落地但未接**（A1 的四段结构才用到），
   现在属于"有资源无引用"，A1 直接 inflate 即可。
7. **补回了一个被 A0 吃掉的入口**：v2 把旧空状态整块收起后，里面那颗「清空」chip 就没地方点了，
   「⋯」面板加了「清空当前对话」一行，转发 `chip_clear_chat2.performClick()`
   （清空逻辑本身在 `QuickToolChipModule` 里，不重写）。
8. **真机回归（2026-10-19 13:15，`279b6c51` / 25113PN0EC，`chat_ui_v2=true`）**：
   装 APK → 冷启动 → 从首页进 AI 聊天页，logcat 无 `ChatUiV2Chrome`/`InflateException`/崩溃。
   已确认生效：v2 顶栏（汉堡 + 「AI 对话 ▾」+ 紫点副标题"AI 服务未初始化"，随服务状态刷新）、
   v2 输入胶囊（＋ / 输入消息… / 麦克风 / 品牌色圆发送）、旧头部胶囊行不再出现、
   **会话统计胶囊条已收起**（这条是真机上看出来 A0 漏掉的，见 §4.4-9）。
   仍未验：v2 空状态首屏（当前会话有历史消息，要看到它得点一次「新对话」，
   属于会改用户会话状态的动作，没替用户点）。
9. **统计胶囊条不能简单 `setVisibility(GONE)`**：`ChatStatsBar.update()` 每次刷新都会
   `setVisibility(VISIBLE)` 把自己显回来，所以给它加了 `setSuppressed(boolean)`
   ——照常算数、只是不显形，「⋯」面板仍读它的当前值。分隔线（stats bar 下面那条 1dp View）一起收。
10. **模式 chip 的 v2 配色会被打回**：`ChatUiV2Chrome.styleModeChip` 设的
    `chipBackgroundColor`/`setTextColor` 状态列表，在 `AIChatActivity#updateAgentChip`
    / `updateDeepThinkChip` 里会被 `setChipBackgroundColor(纯色)` 覆写（它们在自己的更新时机跑）。
    真机上看到的就是覆写后的样子。要彻底对齐 demo `.mode`，得让那两个方法在 v2 下走同一套 token，
    属于 A1/B2 范围，本轮没动（不影响功能，只是配色不统一）。
    chip 文案里的 🧠/🔍 emoji 同样来自那两个方法，也不在本轮范围内。

### 4.5 A1 三段 · 思考段 / 工具段 / 正文段（做法与任务单不同）

任务单写的是"新建 `item_ai_message_v2.xml` 四段结构"。**没有照做**，原因是 AI 消息根本没有布局文件：
`ChatAdapter#createAiMessageItem` 用 ~330 行代码 new 出整棵树，再塞进
`DynamicAiMessageRoot`（构造参数就是那 30 多个视图引用），全类靠字段访问、不用 `findViewById`。
所以 A1 只能一段一段换，第一段挑了**思考段**（最小、最能看出差异）：

1. `createAiMessageItem` 里思考区改成 v2 / 旧版两条分支，两条路都只交出
   `thinkingLabel` / `thinkingContent` / `thinkingDivider` 三个引用，
   后面 20 多处折叠、流式预览、高度动画的调用点**一行没改**。
2. 新增两个薄视图类，专门解决"旧逻辑只认 TextView、v2 却是一整行"的错配：
   - `ThinkLineLabel`：`setVisibility` 转发给行容器（只藏文字会剩一行孤零零的图标+箭头），
     `setOnClickListener` 也转发（demo 里整条才是按钮，旧代码只把 listener 设到文字上）。
   - `ThinkLineBody`：正文可见性就是展开态的唯一事实来源，显了就转箭头 90°，
     这样不必在 ChatAdapter 每个点击回调里补一句"顺便转一下箭头"。
3. v2 下 `thinkingDivider` 给一个**不入树**的占位 View（demo 的正文自带左侧竖线，不需要分隔线），
   保持 holder 字段非空，调用点不用加判空分支。
4. **思考段仍未真机看过**：装包当时设备掉线；15:00 重新装上后，当前活跃会话是一条
   "你好"闲聊（**既没思考也没工具调用**），所以折叠行的观感、点击展开、
   流式预览时"实时思考"是否落在这一行，仍需在真机上切到一条有思考的历史会话才能验（见 §8 下一步 1）。
5. **第二段接了 tool-line**，口径先定了三件事：
   - **计数取 `message.agentSummaryInfo.toolCallCount`**（与汇总卡同一个源），
     明细取 `message.components` 里 `type=tool_call` 的卡（生产方是 `AIChatActivity`
     的 onToolCallStart/完成回调，props 带 `toolName/status/args/result`）。
     两者不一致时（工具卡被合并/丢弃）行上仍按计数说"N 个工具"，
     明细尾部补一行"另有 M 次调用未列出明细"，不假装对得上。
   - **行接管后 `agentSummary` 文本与 `MetricCardView` 汇总卡都不再出现**
     （`renderToolLine` 返回 true 时调用方 `clearAgentSummarySlot`），
     否则同一组数字在气泡里摆两遍。步骤数/tokens/缓存命中率这些技术读数
     不在行上重复——「⋯」面板里本来就有。
   - **明细行右侧显示状态而不是耗时**：demo 每条工具有 `1.2s`，
     但 `tool_call` 卡的 props 里没有逐调用耗时（只有 `AgentSummaryInfo.totalTimeMs` 总量），
     所以先显示"执行中/已完成/失败"（成功绿、失败红、进行中性），
     要真做逐工具耗时得让生产方在 props 里补 `durationMs`。
6. **流式期间的重建**：`toolLineFp`（名称|状态|参数摘要序列）没变就复用明细视图，
   重建时先记下 `body` 的可见性再还原，用户展开着就不会每来一个 token 就被折叠回去。
7. **第三段：页面底色 + 正文段，牵出一个根本冲突**——
   demo 的整套 token（`--surface/--border/--text`）是按"纯色浅底"设计的，
   而聊天页根布局 `drawer_layout` 的 background 是首页那张深蓝壁纸（`@drawable/bg_home_full`），
   `main_content` 是 transparent。所以 v2 里"深色字压深底"的问题不是某个控件配色错，
   是**页面底没换**。这一轮：
   - `ChatUiV2Chrome` 把 `main_content` 铺成 `@color/chat_v2_bg`（壁纸留给两个抽屉）；
   - AI 侧去掉气泡（demo 的 `.ai-text` 就是坐在页面底上），正文 15sp / 行高 1.68 / `chat_v2_text`；
   - **连带必改**：`createAiMessageItem` 里操作栏、状态行、模型信息行、时间戳原本写死
     `0xE8FFFFFF/0xB3FFFFFF/0xA8FFFFFF`（注释就写着"深蓝渐变背景上用"），
     纯色浅底上会直接看不见，统一换成 token（旧版分支保持原值）；
   - 同理给 `item_thinking_message` / `item_agent_step_message` 出了 v2 变体
     （这两个布局里有硬编码 `#B3FFFFFF/#E8FFFFFF`），id 保持一致，只按开关换 inflate 的文件；
     其余 `item_*` 布局用的是 `?attr` 主题色，不用动。
   这是"换页面底"的最小闭环：**以后任何 v2 底色相关的改动，都要先检查这一串壁纸专用色**。
8. **真机第二轮（15:00，装的是含 A1 三段的 HEAD）**：
   页面底色 + 正文段这一组**已确认生效**——首页壁纸被 `chat_v2_bg` 浅底覆盖、
   顶栏（汉堡 + 「AI 对话 ▾」+ 紫点副标题 + ＋ + ⋮ 全露出来，之前被悬浮摄像头窗挡住）、
   紫色渐变用户气泡（右下小圆角）、无气泡的 AI 正文与 Markdown 列表、
   操作行"复制/朗读/分享/重新生成/新对话"与状态行"已完成 · 121 token · 1.7s · 71.7 t/s"
   在浅底上都读得清（证明那一串壁纸专用白换 token 是必须的，否则会隐形）、
   输入胶囊与模式 chip 正常，无崩溃。
   **仍未看过**：思考折叠行与工具行——当前活跃会话是一条"你好"闲聊，
   既没思考也没工具调用（`toolCallCount=0` 时 `renderToolLine` 返回 false，
   汇总卡照旧显示，这条路径也顺带验证了"不接管时不抢戏"）。
   要验这两段得切到有工具调用的历史会话（如"一元二次方程专项练习"）。
   另外浅底上看到的仍是模型输出自带 emoji 与汇总卡里的 🍰，
   那属于 C1/B2 的"去 emoji"范围，不是这次改坏的。
   （本条的"仍未看过"已由下一条 15:06 的回归补上，按时间顺序保留。）

9. **真机第三轮（15:06–15:11，用户跑了一条"查天气"对话：有思考 + 11 次工具调用）**：
   A1 三段**全部在真机上看到并生效**——
   思考段是"星形图标 + 摘要 + ›"的一行（不再是灰底大块），
   工具段是"已运行 11 个工具 ✓ ›"，**点开后 11 行明细**：
   工具中文名（定位 / 网络搜索 / 时间日期）+ 参数摘要 JSON + 绿色"已完成"，行间有分隔线、
   箭头转成 ▼；`agentSummary` 那坨 emoji 汇总文本与汇总卡**没有再出现**（接管成功）。
   页面浅底、无气泡正文、顶栏标题「你好」（取自会话标题）+ 副标题「在线模型 · deepseek-flash · 就绪」都符合预期。
10. **这一轮查出两个真问题，已修一个**：
    - 已修：v2 思考行的文字前缀仍带 🧠/ emoji（`ChatAdapter#updateThinkingLabel` 里硬编码的
      "🧠 思考中："/"💭 思考过程："），而行左侧本来就有品牌色星形图标 → 图标重复、
      也违反"新 UI 不用 emoji"。改成 `thinkingPrefix(ctx, streaming)`：v2 走纯文字前缀，
      旧版保持原样（开关关着零影响）。
    - **未修，需要定口径**：工具行展开后是 11 行明细，而正文里那张
      `tool_call` 卡（"🔧 location ✅ address:金凤区丽银街,city:银川市,provider:network,lati…"）
      仍按轮次组装内联显示 → **同一批工具在一条消息里出现两种表示**。
      demo 的口径是"工具结果卡就是工具行的展开体"，也就是 v2 下应当**不再内联 tool_call 卡**，
      把结果摘要并进明细行（或点开明细行再看结果卡）。这会动 `renderRoundAssembled`
      的轮次组装（§4.5-5 当初刻意回避的那件事），属于 A1 第四段，先记不做。
11. **坐标点击在这台机上不可靠**：屏幕 1220x2656，我按显示图反推的缩放点同一行两次，
    第一次没展开、第二次展开了（列表还会因新消息滚动）。所以"我点开了工具明细"这句
    只在**看到展开态截图**后才算证据；后续验证优先让用户自己点，我看截图与 logcat。



**A1 · 消息重构（3–6 天）— 已开始，思考段见 §4.5**

- [x] ~~新建 `item_ai_message_v2.xml`：思考行 / 工具组 / 正文 / 操作四段~~ —— **作废，别照做**。
      AI 消息没有布局文件：整棵树是 `ChatAdapter#createAiMessageItem` 用约 330 行代码 new 出来的，
      四段只能**在代码树里一段一段换**（§4.4/§4.5/§4.8/§4.9 就是这么做的）。
      （think/tool 行的行视图已由 §4.4 做成 `view_chat_v2_think_line` / `view_chat_v2_tool_line`）
- [ ] `ChatAdapter` 拆 Binder：`UserBinder` / `AiBinder` / `SystemBinder` / `ToolBinder`；**不要一次性重写 5,302 行**，按 viewType 逐步切。
- [ ] 思考折叠 + 工具组折叠 + 流式光标。
- [ ] 操作行 3 图标 + 更多面板；末条常驻。
- [ ] 停止生成移到输入框，停止后"继续生成"。（停止按钮已在 v2 胶囊内，见 §4.4）
- [x] 空状态场景卡（demo 四张场景卡 + 最近列表已落地并接好，见 §4.4）。
- [ ] 日期分隔替代逐条时间戳。

**A2 · Agent 执行 + 控制台（4–6 天）**

- [ ] `AgentExecutionView` 拆 `AgentPlanView` + `AgentLogView`；暂停/单步/回退并入 Plan。
- [ ] 授权卡 / 工具卡 / 反思卡 / 汇总卡；授权前不渲染工具卡。
- [ ] `AgentManagerActivity` 4 → 6 Tab：概览 / 会话 / 记忆 / 工具 / 文件 / 统计。
- [ ] 记忆搜索；工具「更多」；文件私有/公共目录说明；危险操作二次确认。
- [ ] `AgentSessionView` 留在聊天流；控制台「会话」Tab 只展示列表与状态。

---

### 4.6 已知缺陷（修法已落地，真机未验）：v2 主气泡恢复首屏偶发空白

2026-10-19 16:08 用户报"WebView 主气泡 JS 被锁死，前面的会话历史只显示当前会话的主气泡"。
我抓了截图与数据，**没有复现到稳定态**（用户随后自行滚动/重进，消息就出现了）。
下面先记已排除的与机制推断，再记按该推断落地的修法（第 6 条）——**修法尚未在真机验证**：

1. **不是持久化问题**：`run-as` 拉 `files/ai_chat_history.json` 解析，8 条消息全在，
   空白那条（AI，"你好呀！😊 我是答题宝的 AI 助手…"）`content` 207 字、`components` 0、
   `contentRoundBounds` 0 → 走 `bindMessageContent` 的"纯文本 + webMarkdownEnabled"分支。
2. **也不是 B0 的沙箱把 JS 关了**：主气泡用的是 `MarkdownWebView`，它自己
   `setJavaScriptEnabled(true)` + `QZ` 桥（`onHeight`/`copy`/`previewImage`），
   与 `HtmlSandbox`/`HtmlWebView`（卡片链路）是两套；全仓没有 `setWebContentsDebuggingEnabled`，
   所以我无法隔空看页面内容。
3. **"高度塌陷为 0"这条已被代码排除**：`Bridge.onHeight` 有 `cssPx <= 0 return`，
   `applyHeightPx` 也有 `px <= 0 return`，且 2026-10-13 已把高度缓存改成
   "延迟写 + 二次校验 hash"，不会把迟到的测量缓存到新内容 hash 下。
4. **剩下的头号嫌疑**：`setContent` 在 `pageReady == true` 时走 `swapBody()`
   （`evaluateJavascript` 原地换 `document.body.innerHTML`，JS 里 `catch(e){}` 静默失败）。
   若 `pageReady` 是**上一次加载**的 `onPageFinished` 迟到置上的（holder 复用 + 短时间内连续
   `loadDataWithBaseURL`），`swapBody` 就会作用在一个不是我们想要的文档上，
   且因为 `pageReady` 已为 true，之后不会再 `loadDocument` → 该气泡永久空白，
   直到滚出屏幕触发 `resetForReuse()`（它把 `pageReady` 清 false）才自愈。
   **这与"滚一下就出来了"完全吻合。**
5. **要定性还缺的证据**（下次别凭猜改）：
   - 让它稳定复现：进一个有多条长 markdown 回复的会话，快速上下滚，看空白是否随滚动出现/消失；
   - 或在 `onPageFinished` / `swapBody` 两条路径上打一次性日志（**属于为验证改生产代码，
     需要用户同意**，且只能临时加、验完删）；
   - 或给 debug 变体开 `setWebContentsDebuggingEnabled(true)` 用 DevTools 直接看页面
     ——同样是"为验证动生产代码"，要先问。
6. **已按第 4 条落地（未真机验证）**：`MarkdownWebView` 加了"文档代号"状态机——
   `docGeneration`/`loadingGeneration`/`readyGeneration` 三者对齐才算"页面就绪"，
   `onPageFinished` 只认最后一次发出的加载；`setContent` 在**同一份文档仍在途**时不再重复
   `loadDataWithBaseURL`（这正是不就绪期每一绑都重载、互相取消导致活锁的点），
   并加 1.2s 看门狗对同一份文档最多补发一次；`resetForReuse()` 清空 `pendingHtml` 与全部代号，
   使 `setNight`/`setThemeColors` 在复用后第一次绑定前不会去加载上一条消息的文档。
   **不要**顺手改 `MAX_HEIGHT_DP`/高度缓存策略，那些与本案无关（第 3 条已排除）。



### 4.7 已修：每次退出重进多一条历史会话（会话持久化归属缺失）

用户报"你改坏了，每次恢复都会多出一个历史对话记录"。先给结论：**不是本次 UI 改版引入的**，
`git blame` 为证——`onStop` 里那句 `saveCurrentChatAsSession(copy, currentSessionId)` 是
2026-08-18（`ce699db09`），恢复分支不设 `currentSessionId` 是 2026-09-25（`965c43fb4`）。
但我今天为验证反复 `am force-stop` + 重启 + 进聊天页，等于把这个循环踩了很多遍，
**触发是我干的，代码不是我写的**，这点记在这里。

机制（一句话）：`ai_chat_history.json` 是**纯消息数组、不存会话归属**，
恢复完 `currentSessionId` 恒为 null；而 `onStop` 无条件调
`saveCurrentChatAsSession(copy, null)`，该方法在 id 为空时的行为是**新建一条 uuid 会话文件**，
且返回值被丢弃 → 每次退后台都多一条几乎相同的对话，原会话反而再也不更新（看着像分裂）。
注意：`saveHistoryAsync()` 里**本来就有**认领逻辑（`currentSessionId = session.id`），
所以只有 `onStop` 与 `saveCurrentHistory()` 两条路径漏了。

修法（三处，都不改写路径语义）：
1. `onStop`：把 `currentSessionId` 取成快照，保存后**认领返回的会话 id**（仅在仍为空时回填，
   不覆盖用户期间主动切换的会话）。
2. `saveCurrentHistory()`：同口径认领（与 `saveHistoryAsync` 一致）。
3. **恢复时认领回原会话**：新增 `adoptSessionForRestoredHistory(window)`，
   按"最后一条消息 id 相同"在既有会话里找（`ChatMessage#id` 非 transient、持久化且唯一），
   先用会话元数据 title 便宜筛、再对候选做一次"只读最后一条"的分段查询，
   不给几十个会话全量解析文件。找到就设 `currentSessionId`，
   并把"会话前端未加载条数"**返回给主线程去覆盖 `sessionFrontSkipped`**。
   这一步是关键：恢复分支原本紧跟着 `sessionFrontSkipped = 0`，
   若认领后仍清零，下一次"更新同一条会话"的保存会按 0 头部重写、**截掉会话里未加载的旧消息**。
   认不到就什么都不做（那确实是新会话）。
   `historySourceSessionId` / `aiHistoryFrontSkipped` 保持单文件口径不动：
   "加载更早消息"与单文件保存仍以单文件为准。

**遗留（用户自己处理）**：历史上已经攒出来的重复会话文件不做迁移/清理，
用户表示"我清空历史文件就行了"。**不要替他删**。
验证方式：清空后新建一次对话 → 退后台 → 回来看历史抽屉是否只有一条；
再杀进程重进 → 恢复的应是同一条会话（日志有 `恢复历史认领回原会话: <id>`）。

**第二层（入口收敛，改动本身成立，但当时不是"多一条会话"的主因）**：
logcat 里同一 pid 曾出现连续 6 次 `START ... AIChatActivity with LAUNCH_MULTIPLE`，
`AIChatActivity` 清单里是默认 `launchMode=standard`，而首页"AI 聊天"卡、AI 中心、工具箱、
通知点击都用**裸 `startActivity`** 跳它，确实会叠实例。`currentSessionId` 是实例字段，
叠实例会把"同一段对话各自保存一遍"放大，所以这一处该修。

两处代码改动（`1c02c6e9` + `09efd26b`）：
1. **入口收敛**：新增 `ai/chat/ui/ChatNavigation.openChat(Context)`，统一带
   `REORDER_TO_FRONT | SINGLE_TOP`（非 Activity 上下文再补 `NEW_TASK`）；
   首页/AI 中心/工具箱 3 个入口改走它，通知侧 3 处 `PendingIntent`（`SystemConnectTool`、
   `PythonToolManager` ×2）补 `REORDER_TO_FRONT`——它们原本只有 `SINGLE_TOP`，
   而 `SINGLE_TOP` 只在"已在栈顶"时生效，从通知进照样叠。
   `AIChatActivity` 加 `onNewIntent`：本页**不读任何 Intent extra**（全文件 0 处 `getIntent()`），
   所以复用实例时**故意不重跑历史恢复**（重跑会把用户正在看的位置和未落盘的生成中消息打掉），
   只 `scrollToBottom(true)` + 刷状态条。不改清单 `launchMode`，免得影响通知/工具箱的任务场景。
2. **`null.json` bug**：`saveCurrentChatAsSession(...)` 在 `existingSessionId` 为空、
   但要合并单文件头部时走 `writeSessionWithHead`，这条路**不经过 `saveConversationSession`**
   （全场唯一 mint id 的地方），于是文件名是字面量 `null.json`（真机 `files/conversations/` 下
   见过 728B 那份），所有"从单文件历史认领出来的新会话"挤进同一个文件，
   且返回 `session.id == null` → Activity 侧认领拿不到 id。修法：拼文件名前先 mint UUID 一行。

**第三层才是主因（装机后用户实测"还是有 3 个对话历史"，把 3 个会话文件拉下来对比才看清）**：
三个文件**内容逐字节同构**（同样 2 条消息、同样的消息 id `a4b749a3…`/`d380cb59…`），
而 logcat 显示那 3 次 `START` 带的 flag 是 `0x20020000`（= `SINGLE_TOP|REORDER_TO_FRONT`，
说明入口收敛已生效），3 次都还是新实例——因为用户是**返回键**退出的，
实例已被销毁，`REORDER_TO_FRONT` 无从复用。真正让每次恢复都新建会话的是认领比对口径错了：

`ai_chat_history.json` 里那条对话是 `USER / AI / SYSTEM("✓ 模型加载完成")` 三条，
而会话文件**只持久化 USER/AI**（保存时按 `isUserMessage()||isAIMessage()` 过滤）。
`adoptSessionForRestoredHistory` 取的是"窗口里最后一条有 id 的消息"= 那条 **SYSTEM**，
拿去比会话文件的最后一条（AI），**永远对不上** → 认不回原会话 → 每次 onStop 新建一条。
日志能对上：19:33:12 那次认不到（无 `恢复历史认领回原会话` 行），
19:33:18 那次恰好窗口末尾是 AI 消息就认到了 `3168b5eb`——**看运气**，
会话只要以系统提示/思考/工具消息收尾就必然分裂。

修在 Activity 侧（同 commit 之后另发）：认领时改为取窗口里**最后一条 USER/AI 消息**的 id，
`sessionFrontSkipped` 也按"窗口内 USER/AI 条数"（`persistedInWindow`）算，
不再拿含 SYSTEM/thinking 的窗口总条数去减（会算成负数被夹 0，后续保存按 0 头部重写截掉旧消息）。
顺带修 `ChatHistoryManager.loadMessagesFromFile`：会话对象的 `messages` 之后还有
createdAt/updatedAt/messageCount，读到 messages 就 `break` 再 `endObject()` 必抛
`IllegalStateException: Expected END_OBJECT but was NAME path $.messages`
——真机上每次分页读会话（含认领时"只读最后一条"）都刷这条错误日志；
异常被 catch 后返回值仍完整，所以是"日志脏 + 靠运气"的缺陷，改成把剩余成员读完再收尾。
回归用例见 `src/test/java/com/oilquiz/app/ai/util/ChatHistoryManagerSessionTest.java`（4 条）。

验证口径（截至 2026-10-19 19:33 那次装机）：入口侧 flag 已生效、`null.json` 已不再出现；
"多一条会话"要等第三层修好的包装机后复测——
预期：反复进出聊天页时 `files/conversations/` 只有一条 uuid 文件在被更新，
日志里每次恢复都能看到 `恢复历史认领回原会话: <id>`。


### 4.8 A1 第四段（已落地两半，真机未验）：日期分隔 + 操作段图标化

设备不在电脑跟前，这两半只做到"编译 + 单测 + 出包"，**没有目视确认**。
下一台手机回来先验这一节，再往下走 A2。

**第一半：日期分隔替代逐条时间戳**

demo 的 `.day` 是"按天在消息流里插一行居中的 今天 14:32"。Android 侧**没有**照抄成
RecyclerView 的独立条目，而是做成**本条消息行的第一个子 View**：
`chatHistory` 就是 Adapter 的数据源本体，Activity 与 ChatAdapter 各处直接按 `position`
取消息（`notifyItemRangeInserted` / `chatHistory.get(position)` 一大片），
插虚拟条目会让所有位置计算偏移——我们刚修完分页恢复 + 头部保留那条链，最不该做的就是这个。

- 新增 `ai/chat/ui/ChatDayLabel`：跨天判定 + 两套文案（`divider` 今天/昨天/M月d日 都带时分；
  `listTime` 今天只给时分）。判定比**日历日**不比"相隔 24 小时"，
  凌晨 00:30 与前一天 23:30 只差一小时但必须分到两天。
  两个方法都收显式 `now` 参数，否则用例就是"今天跑得过、明天跑就红"。
- AI 侧在 `createAiMessageItem` 里按开关建 `dayLabel` 并挂 holder（沿用
  `toolSection`/`markdownView` 那种"建完直接挂 holder"的写法，
  **不动 `DynamicAiMessageRoot` 那 36 参构造器**）；USER 侧走
  `item_user_message_v2.xml` 的 `@+id/day_label`，旧版布局没这个 id → `findViewById` 返回 null，
  关开关就是原来的样子。`bindDayLabel` 统一决定显隐，v2 下逐条时间戳收起。
- "点时间看详情"的入口没丢：挪到分隔行上（`bindTimestampClick` 复用），
  长按菜单里本来也有一项「详情」。
- 去重：`ChatV2EmptyState` 的"最近"时间原来自己抄了一份 `isSameDay` + `SimpleDateFormat`，
  改调 `ChatDayLabel.listTime`。新增 `chat_v2_today` 字符串（原来只有 `chat_v2_yesterday`，
  "今天"在历史抽屉里还是硬编码字面量——那处是旧代码，本轮没动）。

**第二半：操作段图标化（三图标 + 更多）**

demo 的 `#tpl-actions` 只有三颗图标（复制 / 重新生成 / 更多），
六个动作（朗读 / 复制 / 分享 / 有帮助 / 没帮助 / 在新对话中继续）在 `#sheet-message` 里。

- **没有新建一套动作实现**：旧版那 7 个 TextView 胶囊原样留着（v2 下整块 `GONE`、
  不删也不改绑定），`bindAIMessage` 仍把 listener 绑在它们上面；
  v2 的图标与面板条目一律转发成 `performClick()`
  （`View.performClick()` 不检查可见性，被 GONE 的旧按钮照常执行 listener）。
  这与 `ChatUiV2Chrome` 里"复用汉堡键/清空键"的既有做法一致，
  不会出现"两条路径动作口径不同"（埋点 `ComponentActions.dispatchChatAction` 也自动跟着走）。
- 新增 `ai/chat/ui/ChatV2ActionSheet`（通用"标题 + 图标文字行"底栏），
  形状与 `ChatUiV2Chrome.openInfoSheet` 一致但可复用给消息操作；条目 `Item` 只带一个
  `OnClickListener`，不接管语义。
- **状态可见性的口径改过一次**：旧版靠胶囊上的 `👍✓` / `🔊 停止` 文字表达反馈与朗读状态，
  v2 把胶囊收起后这个确认感会丢。所以改为：面板条目文案（已反馈时显示"有帮助（已反馈）"）、
  Toast 确认（`chat_v2_feedback_thanks` / `chat_v2_feedback_cleared`，取消反馈不报"已反馈"）、
  朗读条目按 `speakingMessageId` 显示"停止"。
- 图标全部复用现成 drawable（`ic_copy` / `ic_refresh` / `ic_more_vert` / `ic_speaker` /
  `ic_share` / `ic_thumb_up` / `ic_thumb_down` / `ic_add`），**没有新增图标资源**；
  配色用 `chat_v2_text*` token，无新增硬编码色。
- 每颗图标按钮都带 `contentDescription`（把文字换成图标后，TalkBack 否则念不出来）。

**装机后要看的四件事**：
1. 新日期的第一条消息上方有一行居中文案，用户气泡右下不再显示时间；
2. v2 末条 AI 消息下方只有三颗图标，旧胶囊行不出现；
3. 点「更多」出底部面板，六项齐全，点「有帮助」有 Toast 且再开面板该条显示"（已反馈）"；
4. 关掉 `chat_ui_v2` 后以上全部回到旧样子（胶囊文字 + 逐条时间戳）。

### 4.9 思考段改成"每轮一行、每行独立折叠"（2026-10-20，两档一起改，统一折叠已删除）

用户提的：现在一条消息里所有思考轮拼成一坨、由一个总开关统一折叠，要点开才读一整屏；
要改成每轮单独显示、单独折叠。

**先盘清现状（这段是关键，别重复踩）**：思考数据本来就是分轮的——
`thinkingRounds`（内容）+ `thinkingRoundIds`（THINKING 子 id，`T1-K1`…），两条都持久化。
而且**按轮折叠的机制早就有**：`addRoundThinkingBlock` 在"有工具轮次"那条路径里
每轮建独立 `roundBox`、点标题行独立翻。所以问题不是"缺功能"，是**两条路径不一致**：

- 轮次模式（`contentRoundBounds` 非空）：逐轮块，但外面还套一层消息级 `thinkingExpanded` 总开关
  → **折叠两层**。
- 普通模式：`updateThinkingContent` 把 rounds 拼成一整段 Markdown（`**第N轮思考**\n…`）
  塞进**一个** `ThinkLineBody`，一个总开关管全部 → 用户看到的"统一折叠"。

**改法（只留一条渲染路径）**：

1. **折叠语义反转**：`thinkingCollapsedRounds`（记已折叠，默认全展开）→
   `thinkingExpandedRounds`（记已展开，**默认收起**）。不反转的话，每轮独立成行之后
   一条 10 轮的消息会一次铺开 10 坨正文，比统一折叠更难读。仍是 `transient`，
   与产物卡展开集 `componentBodyExpandedIds` 同口径：运行时状态，不落盘。
2. **`buildThinkingRow(...)`** 成为唯一的"一行思考"构造器：v2 档 inflate
   `view_chat_v2_think_line`（星形 + 摘要 + 箭头 + 竖线正文），旧档"标签 + 正文"两段；
   折叠读 `thinkingExpandedRounds`，行尾耗时读新字段。
   `addRoundThinkingBlock` 改成委托它 —— 两条路径从此同一个样子。
   收起态就是收起（正文整个 GONE）：v2 的箭头由 `ThinkLineBody` 跟正文显隐联动，
   收起时还留一行摘要会让箭头永远显示成"已展开"。摘要预览只在旧档保留。
3. **删掉消息级统一折叠**：轮次模式下思考块恒挂载，`thinkingExpanded` 从此只管
   "正在进行的这一轮"那条实时行。
4. **实时行不再当"全部轮次的入口"**：`updateThinkingContent` 不再拼 rounds（只给当前轮文本），
   `updateThinkingLabel` 也不再在 `thinkingContent` 为空时回退取 `rounds[0]`
   ——那是统一折叠时代的遗产，现在会让第 1 轮文本同时出现在"第 1 轮思考"和实时行两处。
5. **指纹必须跟着改**（不做会静默失灵）：`renderRoundAssembled` 的轮次结构指纹里
   原来只有 `E/F` 总开关位；折叠变成每轮一份之后，要把**该轮展开位**编进 `K` 后面
   （`roundExpandedBit`），否则点某轮标题指纹不变 → 走增量路径 → 那一轮永远不重建。
   同理，"当前轮补一块"那段（`curThink` 与末轮不同就追加一块）删掉——实时行已经承担，
   留着就是同一段文字渲染两份。
6. **每轮耗时**：`ChatMessage.thinkingRoundDurationsMs`（与 rounds 同下标，可空/可缺位）。
   耗时只有流式过程知道（轮开始→定稿），不落盘就永久丢，所以必须进模型并随会话持久化；
   `ChatHistoryManager.deepCopyForSave` 补拷。打点：`GenerationStreamController`
   与 `AIChatActivity` 各自的 `markThinkingRoundStarted()` / `elapsedThinkingRound()`
   （在线与本地两条流式路径都有自己的一套轮次生命周期，两边都要打）。
   0 = 未知（旧文件、异常路径）→ UI 退回"第 N 轮思考"不显示秒数，不显示 0.0s。
7. 已定稿轮的行放在新容器 `thinkingRowsHost`（`bubble` 内、实时思考行之前），
   顺序天然是 第1轮 → … → 第N轮 → 正在进行的这一轮。
   **仍然不是 RecyclerView 虚拟条目**，理由与 §4.8 日期分隔同一条。

**测了什么**：`ChatMessageThinkingRoundsTest` 5 条——三条列表同下标、空轮不占位、
旧数据缺耗时/长度不齐时补位不越界、折叠默认收起且点两次回到收起、
`thinkingExpandedRounds` 必须仍是 `transient`（反射钉住，防以后有人顺手落盘）。
全量 `testDebugUnitTest` = **444 tests / 0 failed / 0 skipped**。

**真机未验**（装机时设备掉线，包已打好）：要看的是
① 多轮思考的消息里每轮一行、行尾带秒数；② 点某一行只翻那一行；
③ 流式中实时行显示"正在思考（第 N 轮）"、定稿后变成独立一行；
④ 有工具轮次的 Agent 消息不再需要"先点开总开关"；⑤ 旧会话恢复出来的消息没有秒数也不炸。

### 4.9b 同一天二次修正（真机已验）：思考段改成"一个入口 + 一步一行"，取代 §4.9 的"每轮一行"

用户对着一张真机截图说"我感觉设计的有些不对啊"，让我去查别家怎么设计。查完他是对的，
而且错得比"看着别扭"更具体。**§4.9 那一版方向对了一半**：它满足了"每轮独立折叠"，
但把"轮"当成了 UI 单位，还留了"实时行 + 每轮行"两套机制。业界口径（有出处，别凭感觉改）：

- **一个回合一个折叠容器**：assistant-ui 的 Chain of Thought 把连续的 reasoning 与 tool call
  "group into a single collapsible 'thinking' accordion"；setproduct 的 AI 聊天界面总结同口径
  （"Treat it as a collapsible section above the answer. Default collapsed."）。
- **流式与完成是同一容器的两个状态**：assistant-ui 的 `Reasoning` 检测到正在流式就自动展开、
  加 shimmer，收起时是"脑图标 + 时长"的紧凑触发器；IronClaw 的 Agent 活动流是
  working 显示"Thinking…"+elapsed 计时，完成后计时文本消失、换成静态"Worked for Ns"。
- **收起态给摘要、按时间顺序混排**：LangChain 的 reasoning 最佳实践列了
  default collapsed / show character count / truncated preview / 视觉区分 / `aria-expanded`；
  Gemini Interactions API 把 reasoning 表达成与 function call **按时间并列的 steps**；
  Claude 的 interleaved thinking 就是 `[thinking]→[tool_use]→[thinking]→…`。
- **"第 N 轮"是实现细节泄漏**：OpenCode 的 feature request 要的是收起态显示
  动态短摘要（"Searching codebase for API definitions"），不是内部循环计数。

改成了什么（`bd928505`）：

- v2 走 `updateThinkingSectionV2`：header 是整段唯一入口，文案随状态变
  ——流式"正在思考…"，完成后"已深度思考 4.6s · 2 步"；
  步骤列表住进 `view_chat_v2_think_line` 新增的 `chat_v2_think_steps`
  （与 header、实时正文同一节，不再在 `bubble` 里另建容器）。
- `buildThinkingStepRow` = 一行摘要（该段首句 truncated）+ 右对齐耗时 + 箭头，点开才出全文；
  折叠锚点仍是 `message.thinkingExpandedRounds`（按轮 id），只是**单位从"轮"换成"步"**。
- 流式期间整节自动展开，当前轮正文用**纯文本**追加（每帧跑全量 Markdown 是本地模型闪烁成因之一），
  定稿后才按 Markdown 渲染一次。
- 轮次模式（有工具边界）下 header 只做信息行：不兼任总开关、箭头一并藏起来
  ——留着箭头就是在暗示一个不存在的行为，而那正是"两层折叠"的来源。
- `formatThinkingDuration` 与 `AgentSummaryInfo#getFormattedTime` 同口径（ms / N.Ns / Nm Ns）：
  工具行、汇总卡、思考 header 三处显示"一段时间"的格式必须一致。
- **旧档（开关关着）恢复成 §4.9 之前的样子**：一个入口 + 一段拼好的推理文本。
  它本身就是业界说的"一个折叠区里面是推理文本"，只是不分步；分步先在 v2 落地，跑稳后再决定推不推。

真机已验（279b6c51，v2 开，截图 `D:/Temp/shot/v2_think_fix.png`）：
header 显示"已深度思考 4.6s · 2 步"，下面两行分别是
"用户重复问同一个问题。我刚查过。… 2.1s ›"与"Same data. Present concisely, note it's unchange 2.5s ›"，
与工具卡、正文按时间顺序混排。

**遗留（下一步）**：工具行"已运行 1 个工具"仍单独占一节，但它实际发生在第 1、2 步之间——
把工具并进同一条时间线才是完整形态，这一步没做，因为它要动 `renderToolLine` 与
`renderRoundAssembled` 的槽位计算（昨天刚在真机验过 11 行明细那条链）。

### 4.10 合并 S0–S2 落地记录（2026-10-20 夜，真机未验）

用户口径改为"以 v1 为能力基线、吸收 v2 的优点，合成一套"。因此不改写两个大文件，
按 **S0 开关 → S1 活动模型 → S2 活动时间线** 三步落地，v1 分支暂时保留作退路。

1. **S0 开关的家**：新增 `ai/chat/ui/ChatUiSetting`，抽屉按钮 `btn_chat_ui_mode`
   （旧版/新版都能打开），选中后写 pref 并**立即 `recreate()`**；生成中只 Toast、不切换。
   `ChatUiFlags` 默认值翻成 `true`。v2「⋯」面板里的 `addSwitchRow` 已删——它既是唯一写入点，
   又只在 v2 存在，是个只能关不能开的单向门。`ChatUiSetting` 与 `ChatUiFlags` 都是过渡件，
   S5 与 v1 一起删。
2. **P1 档位归一**：`UserMessageViewHolder` 补 `v2Look`（建视图时定死），
   `bindDayLabel(dayLabel, v2Look, …)`、USER/AI 时间戳、`thinkingPrefix` 全部读 holder 的档位，
   绑定期不再重读开关。日期与时间戳由同一个布尔成对决定，修掉"既没日期也没时间"。
3. **S1 `ActivityTimeline`**：纯 Java 模型，输入 `ChatMessage`、输出有序活动行
   （THINKING / TOOL / PLAN / REFLECTION / ERROR）。排序按 `contentRoundBounds` 交替，
   同一 `toolCallId` 后到状态覆盖先到；无 rounds/tools 时退回 `AgentExecutionState.blocks`。
   `ActivityTimelineTest` 10 条钉住顺序/去重/计数/旧数据锚点。
4. **S2 活动时间线**：`updateThinkingSectionV2` 改为按模型渲染行，
   header 文案："已深度思考 4.6s · 2 步 · 3 个工具" / 完成但无耗时 / 只有工具 / 失败；
   展开行指纹不重建（`activityFp`），流式只刷 header 与实时正文。
   工具行复用 `item_chat_v2_tool_row`，点行展开结果预览（`ToolResultText` 截断），
   再点开全文（`showFullToolResult`）。
   `renderRoundAssembled` 删掉思考块/工具卡内联与 header/live 写入，只挂正文段 + 产物；
   正文结构指纹相应只剩 `T`/`P`/`D`。`renderToolLine` 在 v2 下不再执行，
   `agentSummary` 文本与汇总卡由 `bindAgentStepStatus` 直接让位。
5. **未验与风险**：活动区在真机上的观感（尤其 11 次工具调用的 Agent 消息、深浅两档、
   流式展开/收起）、工具行"查看全部"、抽屉开关 recreate 都**没装过**。
   `renderRoundAssembled` 的正文段现在只由 `contentRoundBounds` 切分，若某轮正文为空、
   产物又归属不到工具，会出现"只有产物卡"的正文——需要真机拿一条多轮 Agent 消息确认。
6. **没动的**：`ToolResultCardBinder` / `ToolResultStore` / `handleAiInterpretAction`、
   B0 沙箱与卡壳、`ChatMessage` 持久化结构（只用了既有 transient 字段）。
7. **下一步（S3–S5）**：7 颗文字胶囊与 `performClick()` 转发抽成真方法 → 模型信息行下沉 →
   删 v1 else 腿/三对布局/旧资源 → 删 `ChatUiFlags`/`ChatUiSetting`。
   在 S3 之前 v2 的反馈/朗读状态仍靠被 GONE 的胶囊承载，**不要先删胶囊**。
   （S3/S4 已落地，见 §4.11；胶囊动作依赖已解除，但视图删除仍留到 S5。）

### 4.11 合并 S3–S4 落地记录（2026-10-20 夜，真机未验）

1. **动作解耦（解 C1）**：新增 `doCopy / doSpeak / doShare / doRegenerate / doNewChat /
   doLike / doDislike` 七个方法，v1 胶囊、v2 三图标、「更多」面板全部直接调；
   `forwardToLegacyAction` 已删。删除 v1 视图不再会让 v2 按钮失效，S5 的前置条件满足。
   注意 `doShare` 仍按旧口径同时派发 `Action.copy`（不是笔误，别顺手"修正"）。
2. **状态回执**：`applyFeedbackVisual` 在 v2 下额外给「更多」图标着色表示"已反馈"
   （赞/踩没有独立图标）；面板文案与 Toast 不变。`applySpeakingVisual` 仍只写 v1 胶囊文字，
   删胶囊时要把它改成空实现或接到面板状态。
3. **模型/状态行收敛**：v2 不再显示模型名/延迟/成本行，信息在"点时间戳 → 消息详情"
   卡片里（`buildDetailHtml` 本就有）；`agentStatus` 行在 v2 隐藏，状态由活动区 header 表达。
4. **动效（S4 第一刀）**：入场 30dp/200ms → **8dp/180ms**，并在
   `ValueAnimator.areAnimatorsEnabled()` 为 false 时跳过。思考高度动画是 v1 专属，
   留到 S5 与 v1 分支一起删；流式光标/三点脉冲未做。
5. **验证**：编译通过；全量单测 454 / 0 skipped / 0 failed / 0 errors；APK 重出。
   **真机未验**：v2 复制/重新生成/更多、面板六项、赞踩回执、模型行隐藏后的详情入口。
6. **S5 待办**：删 `createAiMessageItem` 的 v1 else 腿、三对旧布局与 `view_chat_input_bar.xml`、
   7 颗胶囊 + 相关 holder 字段 + `DynamicAiMessageRoot` 的构造参数、v1 思考拼接段与高度动画、
   AI `blockLabel`、`ChatUiFlags`/`ChatUiSetting`。动 `DynamicAiMessageRoot` 构造器时
   按"减参"处理，不要顺手扩参。

### 4.12 合并 S5 落地记录（2026-10-20 深夜，真机未验）：只剩一套渲染

1. **消息树只剩 v2**：`createAiMessageItem` 的 `uiV2` 与全部 else 腿已删；USER/THINKING/
   AGENT_STEP/输入栏一律 inflate `_v2`；日期分隔、无气泡正文、`chat_v2_*` 色成为唯一路径。
   `DynamicAiMessageRoot` 从 36 参减到 19 参，`AIMessageViewHolder` 同步减字段。
2. **删掉的重复渲染**：7 颗文字胶囊、AI `blockLabel`、模型信息行、Agent 执行面板/进度环/
   timeline、`toolSection` + `renderToolLine` + `buildToolRow` + `collectToolComponents`、
   v1 思考拼接段、`updateThinkingLabel`/`thinkingPrefix`、高度动画与 `thinkingAnimator`、
   `addRoundThinkingBlock`/`roundExpandedBit`、`v2Look` 档位字段。
   `updateThinkingContent` 收成"算 isStreaming → updateThinkingSectionV2"。
3. **修掉一处 S2 遗留的性能问题**：`updateThinkingSectionV2` 顶部原先无条件
   `host.removeAllViews()`，让活动行指纹永远命中不了、流式每个 token 都重建行；
   已删掉这句，指纹现在真正生效。
4. **开关删除**：`ChatUiFlags`、`ChatUiSetting`、抽屉按钮、三处 `isUiV2Enabled` 判断、
   `chat_ui_mode_*` / `chat_v2_ui_switch` / `chat_v2_restart_hint` 字符串全部移除。
   没有回退开关，回退靠 git。
5. **资源删除**：`item_user_message.xml`、`item_thinking_message.xml`、
   `item_agent_step_message.xml`、`view_chat_input_bar.xml`、`view_chat_v2_tool_line.xml`、
   `message_card_thinking.xml`、`agent_step_tag_background.xml`、`user_message_background.xml`。
   `ai_message_background` 保留（`item_inference_progress` 仍在用）。
6. **顺手修报告 §3②**：Agent 控制条改读 `chat_v2_border_strong` / `chat_v2_surface_2`，
   去掉深蓝壁纸时代的白描边；暂停/单步/回退与停止生成文案去 emoji 并走 strings。
7. **验证**：`compileDebugJavaWithJavac` + `assembleDebug` 通过；全量单测
   **454 / 0 skipped / 0 failed / 0 errors**；APK 已重出。
   **真机未验**：S2–S5 的全部视觉与交互。
8. **仍保留**：`service_status_bar` / `remote_dsh_bar` / `log_container` /
   `session_stats_bar` / `empty_state_view` 仍在布局里被隐藏，作为「⋯」面板的读数源；
   彻底删需要 `ChatShellState`（原 P3c）。旧 THINKING/TOOL_CALL/AGENT_STEP 行保留 `_v2`
   布局做旧数据兼容。

### 4.13 真机首验（2026-10-20 23:42，279b6c51）：S5 包装上后的结论

1. **装机**：MIUI 首次 `INSTALL_FAILED_USER_RESTRICTED`，原样重试一次成功；不需要放开
   `exported` 或改安装权限。
2. **确认生效**：v2 顶栏、无 AI 气泡、三图标操作段（复制/重新生成/消息操作）、状态行、
   输入胶囊。活动区 header 在滚动触发 rebind 后出现：
   `已深度思考 4.6s · 2 步 · 1 个工具`；点开后顺序为 思考 2.1s → 天气查询（已完成）→
   思考 2.5s，与轮次边界一致。
3. **真机抓到的问题（已修）**：工具行展开显示的是 `result` 里的原始 JSON，
   已让 `ActivityTimeline.formatToolResult` 优先取 `formatted_result`（其次 content/text），
   补 2 条单测；重出包 + 二次装机成功。格式化后的预览还没拿到稳定截图
   （点击后列表滚动，目标移出视口）。
4. **未验**：三图标/「更多」面板点击、暂停/单步/回退新配色、深色档、流式展开/收起、
   工具行"查看全部"。
5. **仍存在的历史数据现象**：旧会话里独立的 `AGENT_SUMMARY` 消息仍以 MetricCard + emoji
   显示（不是 AI 消息内的汇总，是单独一条消息）；本轮没动，若要一套视觉需在 A2/C1 里收掉。

### 4.14 输入区 chip 重做 + 服务状态口径（2026-10-21 凌晨，真机已验）

1. **chip 位置与外观**：从输入胶囊上方移到下方；顺序 `深度思考 / 联网 / 智能助手`；
   去 emoji 与 `ON`；图标 `ic_chat_v2_sparkle/globe/bulb`；28dp 高、无描边、
   选中 brand-soft 底 + brand-ink 字/图标。**唯一上色点**是
   `ChatUiV2Chrome#styleModeChip`，`AIChatActivity` 的
   `updateAgentChip/updateWebSearchChip/updateDeepThinkChip` 只写 checked 与文案
   （这解掉了报告 §3③ 的"两个写入者"）。
2. **字数**：`chat_v2_char_count`（`0 / 4000`）在 chip 行最右，TextWatcher 实时刷新；
   超 4000 只变红、不截断。口径常量在 `AIChatActivity#CHAT_INPUT_MAX_CHARS`。
3. **副标题口径**：根因是 `activity_ai_chat.xml` 的 `service_status_text` 默认文案
   "AI服务未初始化"，而 `ServiceStatusManager` 只写这个隐藏控件、从不更新 v2 副标题。
   现在新增 `publishStatusText()` 同时写两处；UNINITIALIZED 按真实情况显示
   「本地模型 · 待加载 / 未导入本地模型 / 本地模型 · 就绪」；在线状态
   `engineStatusLine("就绪")` 不再把模型名重复塞两次。布局默认文案改「正在检查模型…」。
4. **验证**：456 单测全绿；装机后副标题 `在线模型 · deepseek-flash · 就绪`，
   输入区三 chip + `0 / 4000` 与用户截图一致（真机截图已看）。
   未验：1.3× 字号换行、深色档、超限变红。

### 4.15 按 demo 再对齐（2026-10-21 凌晨，真机深色档已看）

对着 `demo/screenshots/phone-light.png` 逐项比，改掉五处明显差异：

1. **顶栏居中**：`view_chat_appbar_v2.xml` 从 LinearLayout 改 FrameLayout
   （左汉堡 / 中标题 / 右 + 与 ⋯），标题不再被两侧按钮挤偏。
2. **状态点按阶段上色**：`ChatUiV2Chrome.updateStatusDot()`；
   `ServiceStatusManager` 在写副标题时同步（就绪绿、错误红、加载中品牌紫、未知灰）。
3. **输入胶囊按钮态**：空输入只有麦克风、有文字/附件只有发送、生成中只有停止
   （`AIChatActivity#updateComposerActions`，对齐 demo `.shell[data-state]`）。
4. **消息状态行收起**：AI 完成/生成中不显示 token/耗时/速度，USER 不显示「已完成」；
   失败/错误/暂停保留。技术读数仍在「⋯」面板与消息详情里。
5. **隐藏历史悬浮球** `btn_history_fab`（顶栏汉堡已经是入口，悬浮球会盖正文）；
   操作图标间距 10dp → 2dp。

**仍未对齐**：demo 是「思考行 + 独立的工具卡」两段式，当前是合并的一条活动区
（S2 的选择，顺序更准确但视觉与 demo 不同）；浅色档还没在真机看过；
demo 的模型面板/会话抽屉样式也未逐项对齐。
真机验证：456 单测全绿、装机后深色档截图确认上述五项。

### 4.16 按 demo 拆回两段式（2026-10-21 凌晨，真机已看到拆分生效）

1. **结构**：新增 `view_chat_v2_tool_card.xml`，在 `createAiMessageItem` 里挂在思考行之后；
   卡头 = 工具图标 + `已运行 N 个工具` + 耗时 + ✓ + 箭头，卡体 = 工具行。
2. **数据分流**：`updateThinkingSectionV2` 把 `ActivityTimeline.items` 按 kind 分成两组：
   THINKING/PLAN/REFLECTION 进思考行，TOOL 进工具卡。思考 header 只报步数/耗时，
   工具卡 header 只报工具数/耗时，不再出现"合并一句话"。
3. **状态**：`ChatMessage#toolSectionExpanded`（transient）记工具卡展开；默认收起。
   工具行点开仍是结果预览 → 全文。
4. **指纹**：`activityFp` 只管思考行，`toolCardFp` 只管工具卡；互不触发重建。
5. **验证**：456 单测全绿、装机；真机生成中的 Agent 消息已看到
   `正在思考…` + 步骤行 + `正在运行工具…` 卡。
6. **仍未对齐**：demo 的思考行/工具卡展开动画、工具卡多工具的耗时汇总精度；
   另外真机暴露出旧 `SYSTEM` / `AGENT_SUMMARY` 消息仍以 emoji 卡片出现，
   与 demo 的 Plan/Summary 卡不是一套，属于 Agent 子 UI（A2）的缺口。

### 4.17 聊天页收尾（2026-10-21 凌晨，真机已验）

1. **重复停止按钮**：删掉 AI 气泡内的 `btnStop` 及 `DynamicAiMessageRoot`/
   `AIMessageViewHolder` 的字段与绑定；停止只留输入框右侧。
2. **附件面板**：新增 `view_chat_attach_sheet.xml`，2×3 网格（拍照/相册/文件/语音/工具/截屏），
   动作复用既有入口；相册只是 `attachFileLauncher` 限定 `image/*`；
   截屏 tile 目前提示"对智能助手说「截屏」"，没有直接拉起 MediaProjection 授权。
   新增 `ic_chat_v2_camera/gallery/screenshot` 三个矢量图标。
3. **旧 Agent 行**：`shouldHideLegacyRow()` 把 `AGENT_SUMMARY`/`AGENT_STEP` 与
   INFO/SUCCESS/TIP/TASK/PROGRESS 系统消息整行高度 0 收起，保留
   WARNING/ERROR/CONTEXT_INJECTION/SYSTEM_PROMPT；位置不变，不破坏 position 计算。
4. **验证**：456 单测全绿、装机；真机看到附件网格与干净的消息流。
5. **未做**：`已停止 · 继续生成`。仓库没有"从部分回答续写"的入口；
   要做必须新增一条生成链路（历史 + 部分 assistant 内容 + "继续"），
   不能拿 `regenerateLastMessage()` 冒充，否则会丢掉已生成内容。

### 4.18 模型面板 BottomSheet（2026-10-21 凌晨，真机已打开）

1. 新增 `ai/chat/ui/ModelSheet` + `view_chat_v2_model_sheet.xml`，
   顶栏标题点击从"跳 ModelCenterActivity"改为打开 BottomSheet：
   在线/本地分段、在线模型列表（当前项 ✓）、温度滑杆 0.1–1.5、
   上下文长度（自动/手动 N tokens）、应用。
2. 数据全部复用现有实现：在线模型 `OnlineModelManager#getModelList/setActiveModel`，
   温度/上下文 `UserModelParamsManager`（`setTemperature` 同时写决策轮/回答轮）。
   本地模型切换要重载 native 模型，面板只列名字 + 「打开模型中心」，不做假切换。
3. 面板保留「打开模型中心」入口，完整管理（增删/密钥/用量）仍在那页。
4. 验证：456 单测全绿、装机后真机打开面板，列表/温度/上下文/应用均正常渲染。
   **未点应用**（会改用户配置）。

### 4.19 ui_component 类型名归一（2026-10-21 凌晨，修「未识别的 info.card」）

1. **根因**：内置卡注册名是 `info_card`，模型传 `info.card`；`ComponentTypes.resolve`
   只做精确名/alias，不做分隔符归一，`SystemUIComponentTool#create` 的白名单判断
   `hasType("info.card")` 为 false，直接返回「组件类型不存在」。
2. **改法**：`ComponentTypes.normalize()` 把点/横线/空格统一成下划线、转小写；
   `resolve()` 精确名/alias 未命中时用归一名字再查一次，仍不认识才原样返回
   （不吞未知类型）。`SystemUIComponentTool` 写 `ComponentData` 前也归一，
   持久化里不再出现 `info.card`。
3. **覆盖**：`info.card` / `info-card` / ` Info.Card ` → `info`；
   `table.card` / `steps.card` / `bar.chart` 同理；`foo.bar` 仍原样透传。
4. **验证**：457 单测全绿（新增 1 条），装机；未在真机触发一轮 Agent 生成去复测。

### 4.20 AI 消息流收敛（2026-10-21 凌晨，真机已验）

1. **删 `bubble`**：AI 消息树原来有 `vCol` + 无背景 `bubble` 两层竖列；现在只留 `vCol` 一条主列。
2. **活动区收敛**：新增 `activitySection`，把 Agent 状态行、暂停/单步/回退、思考行、工具卡、
   推理进度全部收进同一个容器；思考与工具不再是两个固定兄弟 View。
3. **产物单列**：顺序固定为 `activitySection → contentHost → 附件 → componentContainer → 汇总`。
   `componentContainer` 从正文前挪到正文后，只兜底"无轮次"消息的产物卡；
   轮次模式仍由 `renderRoundAssembled` 把产物插在所属轮次正文后。
4. **删旧工具折叠卡**：`bindComponents` 不再渲染 `tool_call` 的
   「🔧 工具过程 N 个」折叠行；v2 的工具过程只由工具卡承担。
5. **显式卡片默认 inline**：`ui_component` 创建的卡片默认整卡展示，
   自动补挂的工具结果卡仍按 `peek` 默认档。历史数据没有 `display` 的仍按 peek。
6. **验证**：457 单测全绿、装机；真机看到思考行/工具卡/正文/peek 产物行，
   旧"工具过程 N 个"消失。
7. **仍未做**：真正的"每轮活动区"（第 N 轮工具卡跟着第 N 段正文走）。当前活动区仍是
   整条消息一个，固定在正文上方；多轮时过程与正文的时序仍不完美。

### 4.21 每轮活动区（2026-10-21 凌晨，已装机，分组单测钉住）

1. **分组**：`ChatAdapter#buildActivityGroups` 把 `ActivityTimeline.items` 按 TOOL
   切成"每轮一组"（思考0→工具0→思考1→工具1→…→末尾思考）；组顺序与
   `contentRoundBounds` 对齐，末尾只剩思考的那组单独保留。
2. **插入位置**：`renderRoundAssembled` 在每个 `contentRoundBounds[i]` 处挂该轮的
   思考行 + 工具卡，再挂该轮产物；末尾思考组挂在最后一段正文之前。工具卡/思考行
   复用原布局与行构造，没有第二套样式。
3. **展开状态**：`ChatMessage#toolSectionExpandedIds`（transient）按轮记录，
   key = 该轮首个 toolCallId；思考展开仍用全局 `thinkingExpanded`。
4. **回退**：有轮次且 `ActivityTimeline` 确有活动项时才隐藏顶部全局活动区；
   数据缺失（旧历史/异常）回退全局，避免"两边都不画"。
5. **验证**：459 单测全绿（新增 `ChatRoundActivityTest` 两条）；装机后长会话未崩溃。
   当前会话太长，逐轮活动区没有在一屏内完整做视觉核对，这是已知的验证缺口。

### 4.22 T/C 子 ID 跨会话唯一（2026-10-21 凌晨，已装机）

1. **根因**：`ChatIdDispatcher.masterCounter` 只在进程内，`reset()`（清空历史）会把它设回 1；
   子 id 带主 id 前缀，主 id 复用 → `T1-C1` 在不同会话重号。
2. **改法**：主计数器落盘 `chat_id_dispatcher.next_master`，每次 `applyMasterId()` 发号后写回
   "下一个可用值"；`seedFromTurnIds()` 抬到历史最大 `T<n>+1` 时也写回。
   `reset()` 只清 `currentMasterId` 和子序号计数，**不回收主计数器**。
3. **兼容**：`init(Context)` 可提前绑定 prefs；不调用时首次发号用
   `SmartQuizApplication.getAppContext()` 懒加载；无上下文（单测/极早启动）退化为进程内计数。
   消息级 `ChatMessage.id` 仍是 UUID。
4. **验证**：`ChatIdDispatcherTest` 两条（reset 后不重号、seed 后必须大于历史最大 T）；
   全量 462 单测全绿，装机。

### 4.23 工具结果卡归位工具卡 + 模型 display 下线（2026-10-21 凌晨，**已由 §4.24 纠正**）

1. **归位**：`bindRoundToolCard` 把该轮产物卡挂进**工具卡 body**（工具行之后），
   不再进正文 `contentHost`；工具卡展开即可看到结果卡。无轮次/无归属才退回正文尾部。
2. **模型不能控制显隐**：`ComponentDisplay.resolveMode` 删除 `props.display` /
   `props.collapsed` 分支，只保留"类型底线 → 用户默认档"；`ui_component` 工具定义
   移除 `display` 参数，`ComponentTypes` 目录不再教模型三档，`SystemUIComponentTool`
   也不再默认写 `display=inline`。
3. **用户默认档**：只作用于无轮次/无归属的兜底产物卡；工具卡内结果卡固定整卡显示，
   `ToolResultDisplaySetting` 文案同步改口径。
4. **指纹**：轮次产物不再占 contentHost 子 View，但仍以 `Q` 段进指纹；
   数据缺组时回退正文尾部，避免产物丢失。
5. **验证**：462 单测全绿，装机；当前会话为空，工具卡内结果卡的最终视觉尚未真机复测。

### 4.24 纠正：模型可控制卡片显隐，结果卡仍在工具卡内（2026-10-21 凌晨）

1. **口径纠正**：工具和内置卡片由模型管理；模型通过工具调用参数
   `display=inline|peek|hidden` 控制是否显示卡片。§4.23 的"模型不能控制"作废。
2. **恢复链路**：`ui_component` 工具定义恢复 `display` 参数；`ComponentTypes` 目录恢复三档；
   `SystemUIComponentTool` 恢复顶层转发与默认 `inline`；自动补挂卡也读工具参数 `display`。
3. **档位优先级**：模型 `display` → 历史 `collapsed` → 类型底线 → 用户默认档。
4. **位置不变**：结果卡仍挂在 `chat_v2_tool_card_products`（工具卡内、不随工具行折叠），
   `inline` 整卡、`peek` 摘要、`hidden` 不挂载。
5. **修复**：`view_chat_v2_tool_card` 根默认 `GONE`，逐轮挂载时显式 `setVisibility(VISIBLE)`，
   否则整张工具卡不显示。
6. **验证**：462 单测全绿（恢复模型声明/collapsed 断言），装机。

### 4.25 修轮次边界去重导致的"产物堆末尾"（2026-10-21 凌晨）

1. **现场**：一条 AI 消息 6 个 `tool_call`，`contentRoundBounds` 只有 `[0]`。
   `onToolCallStart` 对相同正文 offset 去重，导致只有第 1 个工具有轮次槽位；
   其余工具的产物落到 `tailProds`，全部挂到最后一段正文后面。
2. **修法**：一个工具调用追加一条 bound，不再因 offset 相同而跳过；
   旧历史在 `ChatAdapter.ensureRoundBounds()` 里按 tool_call 数用最后一个 offset 补齐，
   渲染前修复、下次保存落盘。
3. **验证**：新增 `ChatRoundActivityTest#repairsMissingBoundsForToolsAtSameOffset`；
   全量 463 单测全绿，装机。真机 6 工具消息已按
   「思考行 → 工具卡 → 该轮搜索结果」逐轮显示，正文末尾不再堆卡。

### 4.26 contentRoundBounds 全面加固（2026-10-21 凌晨）

1. **纯逻辑收口**：新增 `ai/chat/RoundBounds`，统一处理：
   - 数量 = `tool_call` 数；
   - 值单调不减、夹在 `[0, content.length()]`；
   - 新增 `ChatMessage#contentRoundToolIds`（与 bounds 等长，记录每轮 toolCallId）。
2. **内容改写重映射**：AI 完成时剥离嵌入 thinking 标签会删除一段正文并 trim，
   `remapAfterRemoval()` 按删除区间/前导空白把旧 bounds 映射到新 content，避免卡片落错段。
3. **生产/加载/保存**：`onToolCallStart` 同步写 bound + toolCallId；
   `ChatHistoryManager` 在 load、session load、save deep copy 三处 normalize。
4. **按身份配对**：`ActivityTimeline` 优先用 `contentRoundToolIds` 找该轮工具；
   `ChatAdapter` 的产物归属键也优先用配对后的 toolCallId；旧数据缺 id 才退回下标。
5. **验证**：`RoundBoundsTest` 4 条；`ActivityTimelineTest` 新增"按 id 配对而非组件顺序"；
   全量 468 单测全绿，装机。

### 4.27 恢复/渲染配对回归 + 工具卡视觉包裹（2026-10-21 凌晨）

1. **数据抽查**：当前历史 T8/T9/T10/T11 的 bounds 数、`contentRoundToolIds`、
   `tool_call.toolCallId`、产物 `producerToolCallId` 全部一一对应
   （T8 9/9/9，T9 4/4/4，T10 5/5/5，T11 1/1/1）。
2. **补回归**：`savedRoundToolIdsSurviveReloadAndStillPairTools` 保存"组件顺序 a,b、
   边界身份 b,a"的消息，恢复后断言 ids 原样且 ActivityTimeline 仍按 b,a 配对。
3. **视觉**：`chat_v2_tool_card_products` 加 `chat_v2_tool_body_bg`，
   模型控制显隐的结果卡在视觉上归属这张工具卡，不再像独立漂卡。
4. **验证**：469 单测全绿，装机。

### 4.28 原生媒体/文件组件接回（2026-10-21 凌晨）

1. **已有原生实现**：`ImageGridCardView`（Glide/PhotoView 全屏）、`VideoCardView`（Media3
   ExoPlayer）、`AudioCardView`（ExoPlayer）、`FileCardView`（FileRenderActivity 原生预览）、
   `FileListCardView`，均已在 `ComponentRegistry` 注册。
2. **补工具暴露**：`ui_component` 的 component_type/props 说明补 `video`/`audio`
   及 `{url,title,autoPlay,loop,speed}` / `{url,title,artist,loop,speed}`。
3. **补映射**：`media_toolkit` 多产物按扩展名分流 image_grid/video/audio/file；
   百炼文生视频、`video_to_player` 下载视频改挂 `video` 原生播放卡。
4. **测试**：`ToolResultCardBinderMediaTest` 三条；全量 472 单测全绿，装机。

### 4.29 app_card（2026-10-21 凌晨）

1. 新增 `AppCardView`（type=`app_card`）：页面名 + 结果文案 + 「打开」；
   `target` 类名 / `package` / `uri` 三种打开方式，`params` 可重开注入 extras。
2. `ComponentRegistry` 注册；`ComponentDisplay` 列入 always-inline，保证按钮可点。
3. `AppOperationTool` 结果补 `page`；`ToolResultCardBinder` 新增 `app_operation → app_card`。
4. **验证**：473 单测全绿，装机。

### 4.30 动态工具结果渲染器（2026-10-21 凌晨）

1. **之前只有**：`ToolResultCardBinder` 静态按工具名映射 + `DynamicCardView` 对未知
   component 类型的兜底；任意工具结果没有通用的"结果 → 卡片"路径。
2. **新增** `DynamicToolResultRenderer`：只在静态 binder 空时调用，按形状选：
   媒体→image_grid/video/audio/file；headers+rows→table；items→list；metrics→metric；
   html→html；url→link；data→json_viewer；其余标量对象→info_card；纯文本不造卡。
3. **接入**：`AIToolManager.collectAutoUiCards` 的静态 binder 后；模型 `display` 参数
   继续覆盖动态卡。
4. **验证**：`DynamicToolResultRendererTest` 5 条；全量 478 单测全绿，`assembleDebug` 通过。
   设备 `279b6c51` 当前未连接，未安装。

### 4.31 发送链路 ID 先行 + 滚动时序（2026-10-21 凌晨）

1. **附件发送分支**（`sendMessage` 附件、`sendMessageWithAttachments`）原来直接
   `createUserMessage`，没有 turnId/subId；新增 `newUserMessageForTurn()` 统一先发
   `applyMasterId/applySubId(USER)`，再插列表。
2. **AI 侧**：`addAIMessage`（缓存/远程直连）补 `turnId + Tn-A1`；Agent 路径 AI 占位消息
   原来只写 turnId，补 `subId`。
3. **TOOL 子 id** 改显式 `applySubId(TOOL, currentTurnId)`，不依赖隐式 currentMasterId。
4. **滚动**：`scrollToBottom()` 的 `lastPosition` 从 post 之前移到 post 内取；
   用户消息先触发滚动、AI 占位消息紧接插入时，滚动会落到最新一条而不是旧末条。
5. **验证**：478 单测全绿，装机；未主动发新消息做真机复测。

### 4.32 网页链接统一 App 内跳转（2026-10-21 凌晨）

1. `MarkdownLinkRouter` 正文 http(s) 从系统浏览器改为
   `ComponentActions.openLink` → `WebViewActivity`。
2. `LinkCardView`、`LinkOpener`、`AppCardView` 的 web URI、`ChatInputManager` 附件 URL
   同样走应用内 WebView；file/content/tel 等仍按原逻辑。
3. `ComponentActions.openLink` 是唯一 http(s) 入口；`HtmlCardView` 的卡片内/安全策略不动。
4. **验证**：478 单测全绿，装机。

### 4.33 启动恢复 × 实时发送竞态（2026-10-21 凌晨）

1. **现场**：T22 USER「写个流程图」无 AI；T23「继续」有 AI；T22 的流式日志已启动。
2. **根因**：恢复线程用 `chatHistory.clear(); addAll(historyToLoad)` 覆盖内存。
   用户在恢复完成前发送时，USER 已落盘进快照，AI 占位还没落盘 → 被旧快照抹掉。
3. **改法**：`mergeLoadedHistory()` 以磁盘历史为底，把快照没有的本地新消息按序补上；
   单文件恢复与会话分页恢复都走它。
4. **验证**：478 单测全绿，装机；旧 T22 孤儿不会自动补 AI，新发送受 merge 保护。

### 4.34 USER/AI 成对展示 + 模型面板重复层（2026-10-21 凌晨）

1. **成对不变式**：`ChatTurnPairing.insertMissingReplies()` 给缺 AI 的 USER 补
   `ERROR` AI 占位（「未生成回复。点重新生成重试。」）。
2. **接入点**：历史加载两条路径、`newUserMessageForTurn()` 开新回合前、
   `sendMessage` 后 1.5s 兜底；生成中/已有 AI 时跳过。
3. **重试**：占位有 subId；ChatAdapter 对 ERROR/FAILED 也显示操作段；
   `regenerateMessage()` 复用原 USER turn，不再复制 USER。
4. **模型面板**：`ModelSheet` 静态 showingSheet 防叠层；renderOnline/renderLocal
   入口 removeAllViews；「打开模型中心」底部间距 12dp；默认 tab 跟随 active
   （本地模型时打开「本地」）；本地列表过滤 `.mmproj` 投影文件。
5. **验证**：482 单测全绿，装机；真机确认 T32 占位、单组模型面板、本地列表无
   mmproj；T32 重试后已落成 `T32-A2` 完整回复。

### 4.35 Markdown 换行丢失：修上游完成内容来源（2026-10-21 凌晨）

1. **现象**：本地回复 `### **木星**#### **基本信息**- ...||------|` 全粘一行，
   Markdown 渲染成裸文本。
2. **根因**：`completeGeneration()` 完成时优先取 `currentStreamingContent`
   （UI 流式缓冲，换行在 token 路径丢失），而不是 native `complete` 的 `fullText`。
   诊断日志实测 `chatJson complete nl=11`，证明权威全文有换行。
3. **改法**：两个 complete 分支都改为 `fullText` 非空优先，流式缓冲兜底；
   删除下游 `MarkdownTextRepair` 临时方案。
4. **验证**：新增「介绍下冥王星」`T34-A1` 落盘 52 个换行，结构完整；482 单测全绿，装机。

### 4.36 UI 按轮次刷新（2026-10-21 凌晨）

1. `ROUND_BATCH_RENDER=true`：生成中 `safeUpdateMessage()` 直接 return，
   不再逐 token notify；状态条/思考区仍给反馈。
2. `onToolCallStart` 调 `flushRoundContentToMessage()`：本轮正文一次性刷进消息，
   再记录 `contentRoundBounds`/插工具卡。
3. 最终 `completeGeneration` 用 native `fullText` 整条刷新并落库。
4. **代价**：无打字机；无工具单轮在完成时一次显示。
5. **验证**：482 单测全绿，装机。

### 4.37 思考 token 线程修复（2026-10-21 凌晨）

1. **现场**：日志 `onJson parse error: Only the original thread that created a view
   hierarchy can touch its views ... pool-23-thread-1`，随后 `genResult null`，
   回答只剩半截，退出重进才恢复。
2. **根因**：本地 Agent `onThinkingToken` 在推理线程回调，`setAgentStepStatus` /
   `chatHistory` / `startThinkingBuffer` 未切主线程；Android view 校验抛异常，
   `AgentLoopEngine.onJson` 捕获后中止本轮。
3. **修法**：`onThinkingToken` 整体 `runOnUiThread` 后再执行所有 UI/状态操作。
4. **验证**：482 单测全绿，装机。

### 4.38 语音与按轮次正文同步（2026-10-21 凌晨）

1. **现象**：语音先出现，文字在轮次边界/完成才显示。
2. **根因**：`feedStreamingTts` 每 token 朗读，而正文被 `ROUND_BATCH_RENDER` 延后。
3. **修法**：`ROUND_BATCH_RENDER` 下不逐 token 喂 TTS；`finishAutoSpeak` 在完成后
   整条朗读。
4. **验证**：482 单测全绿，装机。

### 4.39 完成态内容编排动画（2026-10-21 凌晨）

1. `ChatAdapter.scheduleContentReveal()`：完成态对 `contentHost` 子块做
   220ms 淡入 + 6dp 上移，35ms 错峰，最多 6 块。
2. AI 消息不再在占位插入时整条滑入；用户/系统消息保留方向性入场。
3. 按 message id 去重；仅 120s 内完成的消息播放；尊重系统动画开关。
4. **验证**：482 单测全绿，装机。

### 4.40 权威边界收口（2026-10-21 凌晨）

1. `onTextOutput` 仅在 `!ROUND_BATCH_RENDER` 时写 `msg.content`；轮次渲染下 token
   预览不得直接改最终消息。
2. 轮次渲染只有两个提交点：
   - `flushRoundContentToMessage()`（工具调用边界，一次刷本轮正文）；
   - `completeGeneration()`（native `fullText` 最终快照）。
3. 非轮次路径保持原逻辑。
4. **验证**：482 单测全绿，装机。

### 4.41 Markdown 表格完成态重排（2026-10-21 凌晨）

1. **现象**：WebView 表格流式期间按旧宽度算列宽，完成后 `swapBody` 不重排，
   退出重进才正常。
2. **修法**：`MarkdownWebView.setContent()` 在 `wasStreaming && !streaming` 且
   `bodyHtml.contains("<table")` 时整篇 `loadDocument()` 一次；其余仍原地 swapBody。
3. **验证**：482 单测全绿，装机。

### 4.42 每轮 fullText 快照（2026-10-21 凌晨）

1. `ChatMessage.RoundSnapshot` + `roundSnapshots`：每轮独立 `content/thinking/timestamp/toolCallIds`。
2. 回调链：`AgentLoopEngine.onRoundComplete` → `AgentSoftwareLayer` → `AgentChatHandler`
   → `AgentCallbackImpl` → `ChatMessage.addRoundSnapshot`。
3. 工具边界把 `toolCallId` 挂到最近快照；`clone` / `deepCopyForSave` 补拷。
4. **边界**：最终 `ChatMessage.content` 仍是 finalAnswer 权威；快照是逐轮留档/组装源，
   不直接替换 UI 正文。在线 Agent 逐轮接入待补。
5. **验证**：482 单测全绿，装机。

### 4.43 本地 Agent 工具调用 id（2026-10-21 凌晨）

1. **问题**：`LoopCallback` 只有 `toolName`，start/complete 传空 id，UI 按工具名猜匹配；
   并行或同名工具会错配，这是疏漏。
2. **修法**：
   - `LoopCallback` 增加带 `toolCallId` 的默认重载；
   - `AgentLoopEngine` 用 `tc.id` 调用；
   - `AgentSoftwareLayer` → `AgentChatHandler` 透传真实 id；
   - UI 按 id 精确匹配 running/success 卡片；
   - `RoundSnapshot.toolCallIds` 可记录本地 id。
3. **验证**：482 单测全绿，装机。

### 4.44 AssistantTurn parts 模型（2026-10-21 凌晨）

1. `AssistantTurn`：`modelCalls[]`（每轮 fullText/reasoning/stopReason/toolIds）+ 有序
   `parts[]`（reasoning/text/tool_call/tool_result/error）。
2. `ChatMessage.assistantTurn` canonical；`content` 保留兼容投影。
3. 完成时从 `roundSnapshots` 构建；旧消息回退单 call+text；`clone/deepCopy` 深拷贝。
4. **未完成**：UI 仍读 content；在线 Agent 逐轮快照未接。
5. **验证**：`AssistantTurnTest` 3 条；485 单测全绿，装机。

### 4.45 AssistantTurn 二期：在线快照 + 工具 parts（2026-10-21 凌晨）

1. `AgentCallback.onRoundComplete` + `OnlineAgentEngine` 每轮回调 → AgentChatHandler
   → Activity，本地/在线统一 `roundSnapshots`。
2. `AssistantTurn.fromMessage` 从 tool_call 组件回填 name/args/status/result，
   按序补 `TOOL_RESULT` part。
3. 仍未做：content 派生、UI parts 渲染、移除 contentRoundBounds。
4. **验证**：485 单测全绿，装机。

### 4.46 AssistantTurn 三期：content/bounds 派生（2026-10-21 凌晨）

1. `AssistantTurn.applyToMessage`：按 parts TEXT 拼 content，按 TOOL_CALL 位置生成
   `contentRoundBounds`/`contentRoundToolIds`。
2. 完成时 `fromMessage` → `applyToMessage`，content 成为 parts 投影；实时预览的
   bounds 只是临时值，提交时覆盖。
3. UI 仍读 content+bounds，但不再手写最终位置；旧数据/流式继续兼容。
4. **验证**：486 单测全绿，装机。

### 4.47 UI 绑定前重算 parts 投影（2026-10-21 凌晨）

1. `ChatAdapter.bindMessageContent` 对完成态且有 `assistantTurn` 的消息先
   `applyToMessage`，再进入现有渲染。
2. UI 每次绑定拿到的 content/bounds/toolIds 都是 parts 的即时投影，历史旧字段
   不会与 canonical turn 漂移。
3. 视觉树仍复用现有渲染器，避免一次性重写 `renderRoundAssembled`/`bindComponents`
   带来的回流风险。
4. **验证**：486 单测全绿，装机。

### 4.48 架构升级交接（2026-10-21 凌晨）

1. 新增 `docs/ui/parts-architecture-upgrade.md`：Parts 渲染器目标架构、
   `PartRenderer`/Registry/`TurnPartsHost` 草案、P0–P5 迁移、测试矩阵、风险/回滚。
2. 新增 `docs/ui/qcoder-handoff.md`：qcoder 交接卡，第一任务为
   `AssistantTurn.Part` 稳定 id + fingerprint，禁止裸 `for(Part)` 全量重建。
3. 当前只写文档，UI 代码未动；基线 486 单测全绿。

### 4.49 轮次头「正在思考」残留（2026-10-21 凌晨）

1. **根因**：`renderRoundAssembled` 结构指纹未包含 `GENERATING → COMPLETED`；
   完成时走增量路径，只更新正文，不重建活动组头部，退出重进才恢复。
2. **修法**：指纹加 `S0/S1` 状态位，完成时强制重建轮次头。
3. **验证**：486 单测全绿，装机。

### 4.50 「正在思考」真正根因：fallback 文案（2026-10-21 凌晨）

1. `thinkingHeaderText()`：`streaming=false` 且 `thinkingSteps==0` 时仍回落
   `chat_v2_thinking_now`。
2. 本地 Agent 有 `thinkingContent` 但无结构化 steps，因此完成态一直显示「正在思考」。
3. 修法：非流式无 steps 返回 `chat_v2_thought_done`（「已深度思考」）；
   4.49 的 S0/S1 指纹修复保留，负责轮次活动组重建。
4. **验证**：496 单测全绿，装机。

### 4.51 parts P1：Part 稳定 id + 内容指纹（2026-10-21）

1. `AssistantTurn.Part` 的 id 由 `fromMessage` 统一并稳定化：reasoning/text →
   `{callId}:reasoning` / `{callId}:text`；tool_call → `{callId}:tool:{toolCallId}`
   （键于真实 toolCallId，前序工具增删不再让 id 漂移）；tool_result 由 call id 派生 `...:result`。
2. 新增 `Part.fingerprint()`：FNV-1a 64 位对内容字段（type/text/toolCallId/toolName/args/
   result/status）哈希，**不含 id/timestamp**，null≡空串；供渲染层判"同 id 内容是否变化"。
   另加 `Part.toolResult(call, result, ts)` 工厂集中结果 part 的 id 派生。
3. 修 `fillToolPartsFromComponents`：遍历 `turn.parts` 时插入 result part 触发的
   `ConcurrentModificationException`（改遍历防御性副本）。
4. 不改 ChatAdapter，不删 contentRoundBounds。**验证**：AssistantTurnTest 14 条全绿。

### 4.52 parts P2：PartRenderer 体系 + Text/Reasoning + flag（2026-10-21）

1. 新建包 `com.oilquiz.app.ai.chat.parts`：`PartRenderer`/`PartHost` 接口、
   `PartRendererRegistry`（按 type 解析，P2 注册 text/reasoning/error，tool/data 留 P3）、
   `TurnPartsHost` 增量协调引擎——`viewById` + `fingerprintById`，复用同 id、指纹相同不重绑、
   删除必 `releaseView`、末尾按 parts 顺序对齐子视图，杜绝文档 §3 禁止的裸 `for(Part) removeAllViews`。
   并支持 §4.6 工具卡归属所需的 **attached part**：`PartRenderer.anchorIdFor(part, ordered)` 返回
   anchor 的 part id 时，该 part 不建独立视图、`bindView` 写到 anchor 视图上；anchor 重建→强制重绑，
   anchor 缺失→跳过并清理残留指纹（P3 的 tool_result/data renderer 直接复用此能力，不会改出双卡）。
2. `TextPartRenderer` 复用 `MarkdownWebView`（web 开）/`MarkdownRenderer.render`+TextView（原生）；
   `ReasoningPartRenderer` 复用同一 markdown 原语 + 折叠行。think line 的 header 文案/逐轮展开态
   长在 ChatAdapter 私有方法里，留 P3 由 ChatAdapter 实现 `PartHost` 时透出，避免与旧 think line 双份展示。
3. feature flag `ai_chat_prefs/parts_renderer` 默认 **false**，沿用 `LayoutCanvasConstraints.enabled`
   读法；**P2 未接线 ChatAdapter，flag=false 完全走旧路径，现网零行为变化**。
4. **验证**：parts 包 23 条单测（引擎 8 / registry 4 / flag 3 / text+reasoning 2 / coverage·error·attach 6），
   全量 `--offline testDebugUnitTest` 519 全绿、无回归。真机端到端"flag=true 与旧 UI 一致"待 P3 接线后再验
   （手机不在时无法做，属 P3 范畴）。
5. **下一步（P3）**：ChatAdapter 实现 `PartHost`；注册 `ToolCallPartRenderer`/
   `ToolResultPartRenderer`/`DataPartRenderer`；parts 模式下 `bindComponents` 跳过 tool_call；
   flag=true 时 AI 消息树走 `TurnPartsHost`。

### 4.53 parts P2.5：渲染器功能补全（2026-10-21）

1. **text 原生**改走 `PartHost.renderMarkdown`（宿主后台渲染原语：renderInto 同源，含 LinkMovementMethod +
   Glide 异步图片 + 主题），修 `setText(Spanned)` 丢链接/丢图 + 主线程解析；**text web** 补
   `setNight(dark)`/`setThemeColors(markdownThemeVars)`/`setContent(streaming)`。
2. **reasoning/error** 正文统一走 `PartHost.renderMarkdown`；**error** 加语义状态色（ThemeColors + R.color.error）。
3. **工具卡**：`ToolCallPartRenderer` 复用 `ComponentRegistry.render("tool_call")`（卡片能力全继承，零复制）；
   `ToolResultPartRenderer` 按 `toolCallId` 经 `anchorIdFor` 挂回所属卡、合并 result 原地重渲染，不建第二张卡（§4.6）。
   工具卡**以组件内联通道为准**（AI 气泡内联工具就是 `bindComponents`→`ComponentRegistry` 这条；消息级
   `VIEW_TYPE_TOOL_CALL`/枚举 `ToolCallInfo` 是独立列表项的另一形态，不对应 parts 单树）。
   `ToolCardProps.normalizeStatus` 把 parts 的 `done/completed` 归一到内联卡认的 `success/failed/running`
   （快照路径写 `done`，不归一会被 `ToolCallCardView` 落到 default 显"⏳ 执行中"）。
4. `PartHost` 扩展 `streaming()/dark()/markdownThemeVars()/renderMarkdown()`。
5. **data 暂无 renderer**：Part 模型尚无 data 类型/源（组件仍走 msg.components→bindComponents），不造无源轮子；
   `renderMarkdown/dark/markdownThemeVars` 的真实实现随 P3 由 ChatAdapter 落地，视觉一致端到端验证届时做（手机在手）。
6. **验证**：parts 包 30 条（含 status 归一 3），全量 526 全绿、无回归；flag 默认 false、未接 ChatAdapter，现网零变化。

### 4.54 parts：per-part `state` 对齐 AI SDK 流式（2026-10-21）

1. 业界（AI SDK UIMessage）流式是 **part 级 `state:'streaming'|'done'`**，不是消息级布尔。
2. `AssistantTurn.Part` 加 `state`+`isStreaming()`（null≡done），`fingerprint()` 计入 state
   → 正文不变、running→done 收尾也能重绑（补全 markdown）；`copy()` 带上 state。
3. `TextPartRenderer` web 用 `part.isStreaming()`；**删 `PartHost.streaming()`**（消息级信号，已弃）。
4. 修多段 text part 被整条消息级 streaming 误套的 bug。`state` 的真实写入随 P3 在线引擎落到尾段 part。
5. **验证**：新增 3 条（fingerprint 随 state / isStreaming / 引擎 done 收尾重绑），全量 529 全绿、无回归。

### 4.55 parts P2.6：审批 part + cancelled 态（2026-10-21）

1. 对标开源 agent 面板（`cdesktop`/`clawpanel` 均有 approval「确认」块）与 AI SDK approval 态。
2. `Part` 加 `TYPE_APPROVAL`（toolName=权限名/text=说明/status=pending|approved|denied/toolCallId 关联）
   + `approval()` 工厂 + `isApproval*()`；加 `STATE_CANCELLED`（计入 fingerprint，中断收尾可重绑）。
3. `PartHost.onApprovalDecision(partId, toolCallId, approved)` = 第一个**块级动作**回调通道。
4. `ApprovalPartRenderer`：pending→「允许/拒绝」按钮回调宿主；已决→收起、显示"已允许/已拒绝"、ThemeColors 上色、无 emoji。
5. 生产者（现有 Agent 权限请求 → approval part）与 ChatAdapter 接线在 P3；未接、flag 默认 false，现网零变化。
6. **验证**：新增 5 条（canRender/按钮点击回调/已决收起/注册解析/cancelled 指纹），全量 534 全绿、无回归。

### 4.56 parts P2.7：data/产物 part（2026-10-21）

1. 数据审计定取舍：#2 执行结果（`ToolCallInfo` 只有 result 串，无 stdout/stderr/exit 分字段）、
   #5 citations（无结构化源）、#4 plan（ThinkingStep 已在 reasoning 通道）都**缺生产者**，不做（免死 renderer）。
   唯 **data** 有真实源：`msg.components` 非 tool_call 组件 + `ToolCallInfo.presentCard`。
2. `Part` 加 `TYPE_DATA` + `transient ComponentData data` + `data()` 工厂；`copy()` 浅带 data。
3. `fromMessage` 每个非 tool_call 组件产一个 data part（id `data:{componentId}`，缺则序号，稳定）；
   内容摘要写进 `args` 参与指纹（**同类型不同内容的两卡必须指纹相异，否则引擎误跳过第二张绑定**）。
4. `DataPartRenderer` 复用 `ComponentRegistry.render`；注册进 createDefault。`applyToMessage` 不读 data → 投影不变。
5. 未接 ChatAdapter、flag 默认 false；产物卡"挂所属工具卡下"与 PEEK/HIDDEN 档位归属留 P3。
6. **验证**：新增 3 条，全量 537 全绿、无回归（fromMessage 在用路径，特意跑全量）。

### 4.57 parts P3a：消费端机器（未接 ChatAdapter）（2026-10-21）

1. P3 拆两步，本次只做不碰在用绑定、JVM 可验的部分；flag=false 时 ChatAdapter 不构造这些类 → 现网不变。
2. `ChatPartsHost implements PartHost`（放 `com.oilquiz.app.ai.chat` 包内以复用 ChatAdapter 包级静态 `renderInto`）：
   renderMarkdown→renderInto、dark/markdownThemeVars→ThemeColors、审批/尺寸经 `Callback`。按 holder 复用刷新。
3. `PartsTurnBinder`：封装 `TurnPartsHost`，输入 (容器, registry, host) + `bind(ChatMessage)`；
   parts 源优先 `message.assistantTurn` 否则 `fromMessage`；`clear()` 供 holder 回收。仅依赖抽象，可脱 ChatAdapter 测。
4. **P3b（待做，需手机）**：在 `ChatAdapter.bindMessageContent` 的 `isCompleted && assistantTurn!=null` seam(:3510)
   加 flag 门控改走 binder + `bindComponents` 跳 tool_call + holder 生命周期 + 真机视觉/不串卡验证。
5. **验证**：新增 5 条，全量 542 全绿、无回归。

### 4.58 parts P3b：ChatAdapter 接线（flag 门控，首次改 ChatAdapter）（2026-10-21）

1. `bindMessageContent` 在 `applyToMessage` 后加 flag 门控：`PartsRendererFlag.enabled && isCompleted && assistantTurn!=null`
   → `bindWithParts` 渲染并 return；否则旧路径字节级不变（flag=false 默认根本不进新代码）。
2. `bindWithParts`：holder 独占 `partsContainer`（`TurnPartsHost` 唯一写入者，旧渲染不碰）；隐藏 messageText/markdownView；
   惰性建 `ChatPartsHost`+`PartsTurnBinder`，每绑刷 message/width/web。旧路径 `ensurePlainTextHostViews` 的 removeAllViews
   会摘走 partsContainer，但引擎视图随容器存活，`getParent()!=contentHost` 时回接即恢复复用。
3. `AIMessageViewHolder` 加 partsHost/partsContainer/partsBinder 三字段（flag=false 恒 null，未动构造器）。审批回调暂空（无生产者）。
4. **未做，需真机（用户一次性测）**：flag=true 视觉一致 / 并行同名工具不串卡 / 滚动回收旋转 / contentHost 内 legacy 子 View 与
   partsContainer 顺序 / data 卡 PEEK/HIDDEN 档位归属（parts 路径未复刻 bindComponents 语义）。
5. **自审修复**（接线后复查，非设备相关）：① 工具卡 result 丢——`ToolCallPartRenderer.bindView` 改 `mergeCall` 增量合并保留 result
   （result 在独立 tool_result part，整体重建会在 call 后续重绑时抹掉结果）+ 回归单测；② 流式→完成切换：parts 接管时
   `contentHost.removeAllViews()` 只留 partsContainer + 清 `componentContainer`，去掉旧渲染残留子 View；③ 核实
   `AIChatActivity` 完成时 `msg.assistantTurn = fromMessage(msg)`（:6699/:6903），gate 非死代码；④ 内联
   ` ```component``` ` 标记（`ComponentContentSplitter` 从 content 现场解析、**不进 msg.components**）parts 的 data 源看不到会漏渲
   → gate 加 `!containsComponent(content)` 让这类消息暂回落 legacy；"content 切成 text+data part"留后续。
6. **验证**：`assembleDebug` 通过；全量 543 全绿、无回归；flag=false 现网不变。

### 4.59 parts P3c：内联 component 拆成 text+data 交错（撤 4.58-④ 护栏）（2026-10-21）

1. 新增 `AssistantTurn.expandInline(turn)`（public）+ `fromMessageForParts(msg)`：含 ` ```component``` ` 标记的 text part
   就地展开成交错 text 段 + data part（data 引用解析的 ComponentData、id 唯一、args 写内容摘要参与指纹）。
2. **不污染现网**：`PartsTurnBinder` 对 `msg.assistantTurn` 先 `.copy()` 再 expandInline（无 turn 才 fromMessage）；
   `fromMessage`/`applyToMessage` 不改 → flag=false 现网字节级不变。
3. 撤掉 4.58-④ 的 `!containsComponent` 护栏：parts 现能忠实渲染内联卡，含标记消息也走 parts。
4. **验证**：+2 单测，全量 545 全绿、`assembleDebug` 通过、无回归。仍待真机验 flag=true 内联卡渲染。

### 4.60 parts P3d：data 卡档位对齐（2026-10-21）

1. `DataPartRenderer` 复用公共 `ComponentDisplay`（resolve/mounts/peekLabel）+ `ChatMessage.isComponentBodyExpanded/toggle`，
   与 legacy `mountRoundComponent`/`wrapProductPeek` 同口径：HIDDEN 不挂、PEEK 摘要行可点展开（key=part.id）、INLINE 整卡。
2. 删/换卡调 `ComponentLifecycle.release` 深释放嵌套 WebView。**未改 legacy 挂载** → flag=false 不受影响。
3. 工具组"已运行N个工具/全部完成折一行"（`buildActivityGroups`）结构性折叠，与一 part 一视图模型冲突，**未盲移**，留决策。
4. 真机本次已连：开 flag=true、重载历史仍 legacy（assistantTurn 不落盘、gate 不满足，预期，P5 补），无崩溃/parts 异常；
   uiautomator 读不到 WebView 内文本，data 卡档位差异需目视确认。
5. **验证**：+1 单测（HIDDEN/INLINE），全量 546 全绿、`assembleDebug` 通过、无回归。

### 4.61 parts P3e：能力覆盖才接管（修 flag=true 子布局失效）（2026-10-21）

1. 真机反馈：flag=true 时 parts 接管把未复刻的子布局清掉了（"原生子布局都失效"）。根因：gate 太宽，
   parts 只忠实覆盖 text/reasoning，但 `bindWithParts` 先 `contentHost.removeAllViews()`。
2. 收紧：`bindWithParts` 在动 contentHost 前判覆盖——`canRenderAll` + 只允许 text/reasoning +
   text 无 mermaid/公式；任一不满足 → return false、容器不动 → 整条回落 legacy。含工具/data 的消息现在走 legacy。
3. `PartsTurnBinder.bind(AssistantTurn)` 复用判定时的 turn，避免二次构建。
4. 逐类放开路线：data 卡(已做档位)/工具卡(单卡已做，组折叠待做)/mermaid/公式 各自复刻并真机确认后，
   再把这些类型移出"回落名单"。
5. **验证**：+1 单测；真机 flag=true 进聊天页无崩溃、纯文本正常；全量 547 全绿、`assembleDebug` 通过。

### 4.62 parts P3f：接工具/思考耗时数据 + 接管扩到 tool/data（2026-10-21）

1. `Part` 加 `durationMs`（计入 fingerprint/copy）。`fromMessage`：reasoning 接 `thinkingRoundDurationMs(round)`、
   tool_call 接组件 props `durationMs`/`executionTimeMs`。`ReasoningPartRenderer` header 显示"已深度思考 N 秒"。
2. `bindWithParts` allowed 扩到 `tool_call/tool_result/data`：agent 消息现走 parts；含 mermaid/公式 或
   error/approval 仍整条回落 legacy。工具组级折叠(§11.1)未做 → 先出单卡，真机确认差异。
3. **验证**：全量 547 全绿、`assembleDebug` 通过；flag=false 不变；flag=true 需真机验收工具/产物/思考卡呈现。

### 4.63 parts P3g：mermaid/公式 → html data part（§11.4，去回落）（2026-10-21）

1. `expandInline(base, dark)`：text part 含 ` ```component``` ` 用 ComponentContentSplitter；否则含 mermaid/`$$`
   用 `MermaidMathSplitter.split(text,dark)`，切成交错 text+html data part（同 legacy renderMermaidMath 的组件源）。
2. `fromMessageForParts(msg,dark)`；`bindWithParts` 传 isNightMode，接管 allowed 含 text/reasoning/tool_call/tool_result/data
   （mermaid 已是 data，不再回落）。DataPartRenderer 经 ComponentRegistry 的 html 卡渲 mermaid.js/katex。
3. 现 flag=true 覆盖文本/思考/工具卡/产物卡/mermaid/公式；仅 error/approval（无生产者）回落。工具组折叠/产物归属未做（单卡）。
4. **验证**：+1 单测（mermaid→html data part、text 无残留围栏），全量 548 全绿、`assembleDebug` 通过。

### 4.64 parts P3h：工具组折叠卡（§11.1，最后一个未复刻子布局）（2026-10-21）

1. `AssistantTurn.groupTools(base)`（`fromMessageForParts` 内、expandInline 之后）：① 每条 `tool_result` 按
   `toolCallId` 并入其 `tool_call`（result/status/durationMs），删独立 result part（孤儿丢弃）；② 连续 ≥2 个
   `tool_call` 折成 `tool_group`（成员存 `Part.group`），单个工具留独立卡。`Part.fingerprint()` 计入成员指纹→组内变化即重绑。
2. `ToolGroupPartRenderer`：折叠头"🔧 已运行 N 个工具"（含 running→"⏳ N 个工具执行中"），点击展开逐成员卡；
   成员卡复用 `ComponentRegistry` 的 `tool_call` 卡（同源），展开态走 `PartHost.toolGroupExpanded/toggleToolGroup`（存 `ChatMessage`）。
3. `createDefault()` 注册组渲染器；`bindWithParts` allowed 扩 `TYPE_TOOL_GROUP`。现 flag=true 覆盖文本/思考/单卡/组折叠/产物/mermaid/公式，仅 error/approval 回落。
4. **验证**：+3 单测（折叠并入 / 单工具不折叠 / 组指纹随成员变化），`--offline` 定向 parts+turn 全绿（AssistantTurn 25、parts 42，0 skip/fail/err）。待真机：组卡折叠交互。

### 4.65 parts P3i：产物归所属工具卡下（§11.3，模型层归属 + 单写者渲染）（2026-10-21）

1. 模型：`Part` 加 `transient List<ComponentData> products`（计入 `fingerprint()` 的 `contentDigest` + `copy()`）；
   `attachOwnedProducts(base)`（expandInline 之后、groupTools 之前）按 `producerToolCallId`（缺则 `producerToolName`，同 legacy `productOwnerKey`）
   把 data part 折进所属 `tool_call.products` 并移顶层；无归属（内联/mermaid）保留顶层；归属先于折叠→组成员也带产物。
2. 渲染改**单写者**（规避 anchor 卡 removeAllViews 抹掉产物的双写 bug）：抽 `ProductCards`（HIDDEN/PEEK/INLINE）供
   `DataPartRenderer`/工具卡/组卡共用；`ToolCallPartRenderer` 宿主改竖排 LinearLayout `[工具卡]+[产物卡…]`（重绑 `ComponentLifecycle` 深度释放）；
   `ToolGroupPartRenderer` 成员行 `[成员卡]+[其产物卡…]`，随组折叠。
3. flag=false 现网不变；接管闸门无需改（无归属 data 仍 allowed）。现 flag=true 覆盖文本/思考/单卡(+产物)/组折叠(+成员产物)/产物/mermaid/公式，仅 error/approval 回落。
4. **验证**：+4 单测（折入移顶层 / 无归属+名字退化 / 产物变→工具指纹变 / 工具卡渲产物在卡下·裸工具不碰产品工厂），全量 555 全绿、`assembleDebug` 通过。待真机：产物视觉/peek 展开/组内归属。

### 4.66 parts P3j：内置组件体系接管核对 + 修 WebView 释放泄漏（2026-10-21）

1. 核对"内置组件/工具卡/内置卡片/原生 JS 桥/工作区/原生控件"是否接通：结论**已接通、无需另造渲染器**。
   这些渲染器本就是 `ComponentRegistry` 的插件（Chart/Weather/AppCardView 导航/HtmlCardView=JS 桥/
   LayoutCardView·LayoutPrimitiveCardView=原生控件/Video·Audio 原生播放器/FeedbackBar chip），`render(activityCtx,comp)`
   自包含挂 style+actions；parts 的 `DataPartRenderer`/`ProductCards`/`ToolCallPartRenderer` 全委派到它；
   `ChatPartsHost.context()`=`itemView.getContext()`（Activity）→ 依赖 Activity 的卡片不降级。
2. 新增单测：12 类内置组件经 `fromMessageForParts` 均成 data part、`canRenderAll` 全覆盖（不丢任一内置类型）。
3. 修真实泄漏：`bindWithParts` 原裸 `removeAllViews` 清 contentHost/componentContainer，切换接管前旧流式的
   mermaid/公式 WebView 不被 `ComponentLifecycle.release` → 渲染器进程随滚动泄漏；改用既有 `releaseAndClearComponents`。
4. 工具卡"重试/编辑参数"属独立 `ToolCallViewHolder`（非 AI 消息 parts 范围）；`ToolCallCardView` 已显示名称/参数/状态/结果/查看全部/耗时。"AI 深度解读" chip 仍按 §11.2 有意延后（无 parts 生产者）。
5. **验证**：+1 单测，全量 556 全绿、`assembleDebug` 通过。待真机：内置卡片交互可用性与不泄漏。
6. 可操作组件清单：接管不改变交互能力，各组件"能操作/仅本地/纯展示"三档逐条 file:line 见 `component-system-review.md §7`
   （真往返回传 = `layout`/`ui_component` 树卡 + quiz + feedback + `props.actions` 底栏；内联 layout 原语 `LayoutPrimitiveCardView:72-73` 丢 viewRefs、纯展示；parts 只在卡自身指纹变化才重渲→未提交输入天然保留）。

### 4.67 fix(component)：目录字段/短名与卡片真实读取对齐（"掉原始 JSON"根因）（2026-10-21）

1. 现象根因：卡片 `canRender=false` → `ComponentRegistry.render:174` 静默落 `DynamicCardView` 通配兜底（贴原始 JSON、无报错）。
   `ComponentTypes.SCHEMA`（模型目录，`getSchema`/`list_types` 都从它生成）教的字段名与卡片真读字段对不上。既有 `ComponentNameIntegrityTest` 只查名字不查字段。
2. 修一（字段）：`progress`→`progress`（非 percent）、`metric`→`metrics[]`（非 items）、`contact`→`value`（非 name/phone）、`weather`→`temp`（非 city/data）。
   修二（下架实现）：`SystemUIComponentTool.getParameterDescriptions` component_type/props 去掉 `note_card`/`grid_card`（实现已删、alias 保留、历史可渲）。
   修三（短名）：新增 `ComponentTypes.SCENE_TARGET`(weather/contact/todo/file_list/layout) 并在 `registerBuiltin` 占短名 key（目录教短名但实现报 `*_card`，模型用短名会掉兜底）；`isScene` 改读它；`file_list` 补进目录。
3. 新测 `ComponentFieldIntegrityTest`(+4)：目录教的必填必须 `canRender==true` + 反向锁死旧错字段 + 场景卡在目录 + 已删实现不售卖（旧名仍可渲）。json 卡本体（字符串 JSON 美化 + CardShell）单开。
4. **验证**：全量 560 全绿（+4，0 skip/fail/err）、`assembleDebug` 通过；改到共享 `ComponentRegistry`/`ComponentTypes`（additive）。待真机：短名 weather/todo/contact 出正卡。

### 4.68 fix(component)：json 卡适配（字符串 JSON 美化 + CardShell 统一外壳）（2026-10-21）

上一批单开的 json 卡本体（§2 P0"组件和聊天页不是一套语言"残留实例）。

1. 字符串 JSON 也美化：旧 `prettyPrint` 只认 `JSONObject`/`JSONArray`，字符串（工具常把 JSON 序列化后返回）走
   `String.valueOf` 原样吐 → 一整行转义文本，看着像渲染失败。现按首尾 `{}`/`[]` 试探解析后缩进 2 空格；
   解析失败或纯文本原样显示，不吞内容、不崩。
2. 外壳接 `CardShell`：删自绘 LinearLayout/背景/私有 MaxHeightScrollView，复用 `CardShell.MaxHeightScrollView`
   （`props.maxHeight` dp 仍生效，缺省 200）+ `ComponentTheme` token（`metaTextSize`/`textSecondary`）；
   空数据 `State.EMPTY`，表头 meta 显示行数。写法对齐 `DynamicCardView` 原始 JSON 折叠区。
3. 目录 `json` 必填说明补"或字符串形态的 JSON 也会美化"，模型可直接塞序列化结果。
4. **验证**：新增 `JsonViewerCardTest` 8 项（走真 `ComponentRegistry.render`：字符串对象/数组美化、对象数组不回归、
   纯文本原样、坏 JSON 留原文、根 View 是 CardShell、空白不崩、短名 json 命中），全量 568 全绿（+8）、`assembleDebug` 通过。
   待真机：json 卡视觉与长内容滚动观感。

### 4.69 docs(html桥)：本地 HTML 卡回传 Agent 设计（**待批准，未开工**）（2026-10-21）

1. 设计文档 `docs/ui/html-card-agent-roundtrip-design.md`。核实纠正两处口径：信任档是**四档**
   （`INTERNAL_TEMPLATE`/`LOCAL_FILE`/`REMOTE_PAGE`/`INLINE_UNTRUSTED`），桥只在 `LOCAL_FILE` 挂（`HtmlSandbox:444`），
   mermaid/katex 属 `INTERNAL_TEMPLATE`＝**无桥**；回传地址是 `props.actions[].component_id`（工具注入，`:630-653`），
   **不是 `ComponentData.id`**（那是 COMPONENT 子 id）。
2. 缺口与方案：桥现仅 `showToast/copy/openLink/previewImage`，无通往 `dispatchResult`→`get_result` 的方法
   （富桥 `app/webview/js/*` 亦零命中）。拟加 `HtmlJsBridge.submit(payload)`：native 绑死本卡 id、payload 不透明 +16KB、
   只转投既有 `ComponentActions.dispatchResult`；取址抽 `ComponentActions.resolveComponentId` 统一口径；
   **不为 `INLINE_UNTRUSTED` 开任何回传**；建议 prefs 开关默认 false。
3. 接管侧**零改动**（html 卡走同一个自包含 `ComponentRegistry.render`，Activity ctx 已具备）。
4. 文档含安全评估（写明"静态值→运行时任意串"属能力增量）、JVM/真机两级验证计划、回滚、4 个待用户点头的决策项。**未获批准前不要开工**。

### 4.70 refactor(component)：抽出回传取址 `resolveComponentId`（html submit 第 1 步）（2026-10-21）

设计文档 §3.1/§3.3 的第一步，**纯重构、行为零变化**，先把口径收成一个家：

1. 取址逻辑原先只活在 `LayoutCardView` 渲染里（`props.actions[].component_id` 第一个非空 → 注册输入收集器）；
   抽成 `ComponentActions.resolveComponentId(ComponentData)` 与 `(JSONObject)` 重载，`LayoutCardView` 改调它。
2. 钉死口径：回传地址是 `actions[].component_id`（工具仅在 `props.has("actions")` 时生成并注入），
   **不是 `ComponentData.id`**（COMPONENT 子 id `T1-C1`，只用于会话内定位/更新）；无 actions/全空/actions 非数组 → null。
3. **验证**：新增 `ComponentActionsResolveIdTest` 5 项；component 包 53 项全绿（0 skipped/fail/error）。
4. 下一步（第 2 步）：`HtmlJsBridge.submit(payload)` + prefs 开关 `html_component_submit` 默认 false；
   第 3 步：模型可见文案。**真机项**：JS↔native 端到端、`get_result` 唤醒、内滚不抢手势。

### 4.71 feat(component)：`HtmlJsBridge.submit`——html 卡页面内回传 Agent（开关默认 false）（2026-10-21）

设计文档 §3.2 的第 2 步。把 `html` 卡从"仅本地交互"接进"真往返"那一档，**不新造通道、不扩权限**。

1. `HtmlJsBridge` 加 `@JavascriptInterface submit(String payload)`：只转投既有 `ComponentActions.dispatchResult`
   →按 id 回调/全局兜底→`PythonToolManager`→`ui_component(get_result)` 同一回收口；成功 Toast「已提交」。
2. **id 由 native 绑定**：新 `HtmlJsBridge(context, componentId)`（留旧单参构造），id 取 `resolveComponentId(data)`；
   **JS 不能指定 id**（否则任意页面可往别的组件结果槽写）。id 为空＝非交互卡（创建时没带 `actions`）→ 静默 no-op。
3. **payload 不透明 + 16KB 上限**（`HtmlCardView.MAX_SUBMIT_CHARS`）：不解析/不执行/不进 openLink，超限 Toast 拒。
4. **`HtmlSubmitFlag`＝`ai_chat_prefs/html_component_submit` 默认 false**：关掉时不接地址，四个既有桥方法一字未动，
   现网逐字节同今天；挂载档位也没扩大（只 `LOCAL_FILE` 有桥，`INLINE_UNTRUSTED` JS 关、`INTERNAL_TEMPLATE`/`REMOTE_PAGE` 无桥）。
   parts 接管侧零改动（html 卡仍走自包含 `ComponentRegistry.render`）。
5. **验证**：`HtmlBridgeSubmitTest` 8 项（真回调注册表：恰好派发一次 + payload 原样、纯值形状可通、
   null/空 id 与旧构造不派发、空 payload 不派发、超限拒且等于上限放行、四档 allowsBridge 只 LOCAL_FILE 真、
   开关默认 false 可读写、交互卡才有地址）；`HtmlSandboxTest` 17 项仍绿。**待真机**：JS↔native 端到端、
   `get_result` 唤醒、长报表内滚不抢手势、flag=true 下 inline/peek 两档均可提交。

### 4.72 docs(html桥)：submit 落地状态回写 + 第 3 步（模型文案）主动推迟（2026-10-21）

1. 设计文档状态改为"第 1、2 步已落地（`99e77714` 抽 `resolveComponentId`、`349e3b85` `submit`+`HtmlSubmitFlag` 默认 false），第 3 步推迟"。
   决策按文档推荐落定：id 来源＝方案 A、payload 16 KB、开关默认 false（用户"你开始把"＝按推荐走）。
2. **第 4 条决策（写进模型可见说明）作废并推迟**，理由：`@Param(props)` 是静态文本、无法跟着开关变；
   开关默认 false 时 `submit` 静默 no-op，此时教模型"页面内可 `Android.submit` 回传"是在提示词里画死路
   （对齐 [[no-renderers-without-producer]]）。等真机验收 + 开关翻默认 true 后再补 `ComponentTypes.SCHEMA` html 行与工具 props 说明。
3. 顺带核实：`src/test/resources/golden/{assist,takeover}.txt` **没有内联** props 字段表
   （`chartType/maxHeight/json_viewer` 零命中），所以改工具文案不会动 golden；golden 只由 `OnlinePromptBuilderAssemblyTest` 比。

### 4.73 fix(component)：字段守卫改为"从目录派生"，并再纠 3 条必填/可选错配（2026-10-21）

1. 上一批 `ComponentFieldIntegrityTest` 用手抄 fixture，弱点明显：目录再写错**新**字段名它不会红。
   新增 `catalogTaughtRequiredFields_aloneSatisfyCardGates`——解析目录"必填"文本 → 自动造 props → 要求 `canRender` 为真；
   目录项解析不出字段名也判失败（防止某条目失去守卫）。
2. 解析必须做两件事，否则在校验现实中不存在的形状：先抹掉 `[{...}]` 内层（内层逗号会把 `data` 当必填字段）、
   每段只取第一个英文词（`bar|line|pie` 是枚举值）；集合字段给**非空**数组（`series.length()>0` 才是闸）。
3. 派生守卫当场抓到 3 条我上一轮漏掉的真错配（都是"照目录填必填→canRender=false→静默掉原始 JSON"）：
   `info` 必填/可选写反（卡片要 `items` 不是 `title`）；`table` 的 `rows` 被误列可选（`TableCardView:46-48` 双要求）；
   `chart` 的 `series` 被误列可选（`ChartCardView:47-50` 要 chartType 非空且 series 非空）。均已改正。
4. **验证**：全量 582 全绿（+1，0 skip/fail/err）、`assembleDebug` 通过；golden 未内联 props 表，
   `OnlinePromptBuilderAssemblyTest` 7 项仍绿。这条守卫的价值=将来目录再漂移会自动红。

### 4.74 fix(component)：饼图不出图——图表别名 props 形状归一（真机抓到）（2026-10-21）

用户报"piechart 错误"，截图坐实：模型按工具说明传 `pie_chart` + `data:[{name,value}]`，
出来的却是"收起原始 JSON"文本卡，模型还回答"饼图已经在上面了"。

1. 根因＝**名字归一了、参数没归一**：`pie_chart/bar_chart/line_chart/sparkline` 被 canonical 归一到
   `ChartCardView`（原生赢名字），但本卡只读 `chartType`+`series`，工具说明教的参数仍是 layout 原语那套 `data`
   → `canRender=false` → `ComponentRegistry.render:174` 静默落 `DynamicCardView`。属 §2 P0"两套命名重叠"真咬到用户的一例。
2. 修在渲染器侧（历史会话存量也得能渲）：`ChartCardView.normalize(type, props)`——chartType 缺省按别名补
   （pie/bar/line|sparkline）；series 缺省而有 `data:[{name,value}]` 则拆成 categories+单 series；
   `data:[数值…]` 直接成单 series；显式 series 优先。`canRender` 与 `createView` 同走归一，口径不分叉。
3. **只读不改**：归一返回**副本**，不原地写回 `data.props`（会被持久化，写回＝改用户存档）。
4. **验证**：`ChartAliasShapeTest` 7 项（真机那条必须 canRender、别名推图型、纯数值成 series、显式 series 不回归、
   两形状皆无仍判不可渲、不改源 props、**走真注册表渲出的视图含 `ChartView`**）；全量 589 全绿、`assembleDebug` 通过、已装机待复验。

### 4.75 fix(component)：别名形状矩阵——timeline / stat 也会掉 JSON（饼图同族排查）（2026-10-21）

1. 手段：`ChartAliasShapeMatrixTest` 把**工具说明对每个名字教的字面形状**照抄成 props（34 个），逐个要求 `canRender` 通过。
   这比"改一张卡测一张卡"强在：它按模型的视角扫全族，一次列出所有"照说明做就失败"的名字。
2. 抓到并修 2 条真 bug（与饼图同一根因：名字被 canonical 抢去指原生卡，**参数形状没跟着换**）：
   `timeline`→`StepsCardView` 只读 `steps[]` 而说明教 `items[]`；`stat`→`MetricCardView` 只读 `metrics[]` 而说明教 `label/value`。
   修法同饼图：`StepsCardView.normalize` / `MetricCardView.normalize` 在渲染器侧归一（历史存量也要能渲），
   `canRender` 与 `createView` 同走，**返回副本不写回 `data.props`**。
3. 两个**假阳性已排除**，别重复查：`empty`/`notice` 没有原生对应卡、也不是 alias，由原语渲是设计如此；
   `line_chart` **能出图**，只是 B1-2"原生赢名字"漏了它 → 与同族长得不一样，属一致性欠账，**改动会换观感，留给用户拍**
   （统一办法：`ComponentTypes.ALIAS` 加 `line_chart→chart`）。
4. **验证**：全量 591 全绿（+2）、`assembleDebug` 通过、已装机。真机待复验：让模型出 `timeline`/`stat` 卡看是否还掉 JSON。

### 4.76 fix(parts)：真机 flag=true 首验——思考正文空（bindView 前视图未入树）（2026-10-21）

真机开 `parts_renderer=true` 的第一轮实测（会话「看一下后天的油价」，无障碍树 48 条）：
接管把该轮子布局**全渲出来了**——4×`▸ 已深度思考 N 秒`、**2×`🔧 已运行 2 个工具`（P3h 组折叠成立）**、
3 张单工具卡带 `✅` 结果、2 张 PEEK 产物行（`当前位置 · 3 项`/`搜索结果 · 6 条 · 6 项`）、
末尾 `table_card` 出真表格（含 CardShell 的 `共 4 行`），logcat 无 FATAL。

用户报的缺陷与修法：
1. **「深度思考点开是空的」**。诊断先排数据再动代码：header 无「· N 步」→ 确是 parts 在渲；
   `durationMs` 只在 `snap.thinking` 非空分支赋值 → 正文有数据；空在应用环节：
   `TurnPartsHost` 先 `bindView` 后 `reconcileOrder` 才 addView，异步 markdown 的 TextView 当时未 attach，
   被 `ChatAdapter:3254-3256` 的代次清理丢掉结果，且指纹未变不再重绑 → 永久空白无报错。
2. 修：根因＝**新建视图先入树再 bindView**；自愈＝展开时正文为空则重发 `renderMarkdown`。
   新增探针测试断言 `bindView` 时 `getParent()!=null`。（想做"撤修复看变红"的变异验证被安全策略拦下，未绕过，
   故该测试失败面是时序推演、非实测反证。）
3. 同屏另一现象**未归因**：`table_card`/`sparkline` 创建成功后模型仍用 Markdown 表格做对比（表在手机上横向截断）。
   属模型选型问题，不是渲染器 bug，待单独看。
4. **A/B 未做成**：翻回 false 后我两次滑动落在不同消息上，"48 vs 17 条"是滚动位置差异，不能当内容对比；
   消息操作行（复制/重新生成/⋯）在 parts 下是否仍在**仍未证**。设备由用户持有时不要抢点击。

### 4.77 fix(parts)：组卡折叠不再吞产物（P3i 回归，真机用户报）（2026-10-21）

1. 现象：`🔧 已运行 N 个工具` 折叠后，附在工具下的图/表卡也一起不见了，必须点开组才看得到。
2. 查实为**我 P3i 引入的回归**：legacy 组卡有两个容器——工具行 `chat_v2_tool_card_body`（随折叠 GONE，
   `ChatAdapter:2040-2051`）与**独立的**产物容器 `chat_v2_tool_card_products`（`:2057`，写在 `if (expanded)` 之外），
   即 legacy 折叠只藏工具行、产物常显；我把产物塞进了折叠体。
3. 修：`ToolGroupPartRenderer` 三段式 `header / body(折叠) / products(常显)`，成员行进 body、成员产物进 products；
   重绑与 `releaseView` 都清两段（产物含 WebView，须 `ComponentLifecycle` 深度释放）。单工具卡本就不折叠，不受影响。
4. 测试：`groupProductsStayVisibleWhenCollapsed`——折叠档下产物到根每级 VISIBLE，**且成员工具行仍不可见**
   （防"把折叠删掉"式假修）。`FakeFactory` 增记返回 View。全量 593 全绿（+1）、`assembleDebug` 通过。
5. 同轮另一条已查实**未修、待拍**：parts 组内成员渲成整张 `ToolCallCardView`，而 legacy 是紧凑行
   `item_chat_v2_tool_row`（`buildToolActivityRow:2525/2531`）→ 用户看到的"和以前不一样"。
   选型：复用旧行布局（需把 `buildToolActivityRow` 依赖的 `ActivityTimeline.Item`+inflater 路径接进 parts）
   vs 接受卡片长相。

---

## 5. 关键文件 / 改动地图

| 区域 | 文件 | 动作 |
|------|------|------|
| 聊天页布局 | `res/layout/activity_ai_chat.xml` | 重写，删状态胶囊/连接条/统计条/日志 |
| 输入区 | `res/layout/view_chat_input_bar.xml`、`ai/chat/input/ChatInputBar.java` | 胶囊输入 + 停止按钮 |
| 用户消息 | `res/layout/item_user_message.xml` | 去头像/时间戳 |
| AI 消息 | `ai/chat/ChatAdapter.java#createAiMessageItem`（**代码树，无布局文件**） | 四段在代码里逐段替换；新增视图走"挂 holder 字段"，不扩 36 参构造器 |
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
- **JVM 单测现状（2026-10-19 二次修正）**：`testDebugUnitTest` = **411 条，0 失败，0 跳过**。
  这里必须记一件难看的事：**同一天早些时候我报的"407 条全绿"是假的**。
  当时 `build.gradle` 没开 `testOptions.unitTests.includeAndroidResources`，
  Robolectric 跑在 legacy resources mode（P 之后不受支持），
  于是**所有 Robolectric 类被整类跳过**——Gradle 把 skip 记成既不是 failure 也不是 pass，
  11 个类 128 条用例一条没跑。我是加了 `ChatHistoryManagerSessionTest` 之后
  去看 `build/test-results/*.xml` 才发现 `skipped="4"`，顺手统计出全仓 128 条被跳过。
  **教训：判断"测试过了"要连 `skipped` 一起看，光看 failures=0 会被骗。**
  开齐配置后真跑的 132 条里 18 条红，逐条查完的结论：
  1. **配置层（必改）**：`includeAndroidResources = true`；
     `robolectric.properties` 加 `application=android.app.Application`
     ——否则 Robolectric 按清单实例化 `SmartQuizApplication`，它 onCreate 里
     `new AndroidPlatform()` 会 `System.loadLibrary("crypto_chaquopy")`，桌面 JVM 必炸；
     删掉 `PreviewRenderBridgeTest` 的 `@Config(sdk = P)`（minSdk=31，28 档位解析 ap_ 直接抛
     "Requires newer sdk version #31"）。
  2. **测试环境不像真机（10 条，改测试）**：`ThemeColors.get(Context, resId)` 对主题语义色
     走 `?attr/...` 解析，解析不到**按设计返回 0**；Robolectric 给的 Application 上下文没套清单主题，
     于是 `ColorResourceProviderTest` / `AppResourceManagerTest` / `ResourceIntegrationTest`
     成片"color should not be 0"。**没有为了让断言通过去改生产口径**，
     而是新增 `src/test/java/com/oilquiz/app/testsupport/ThemedAppContext.java`
     把 `R.style.Theme_OilQuizApp` 显式套到应用上下文（真机上每个 Activity 都带这个主题）。
     注意必须套在应用上下文本身：`ColorResourceProvider` 构造时会 `getApplicationContext()`，
     包一层 `ContextThemeWrapper` 会被脱掉。
  3. **真缺陷（改生产端，2 处）**：
     `TemplateManager` 的 8 个 `createScene*Template()` 只 `templates.add(...)`、
     **不写 `templateMap`**，而 `getTemplateById()` 只查 map（`getDefaultTemplates()` 查 list）——
     首次运行/迁移补建模板那条路上，列表看得见、按 id 取不到，`ExportManager` 拿不到模板就回退。
     修法：`createDefaultTemplates()` 与 `migrateTemplatesIfNeeded()` 补建后各调一次 `updateTemplateMap()`。
     另一处：`scene_mistake`（错题回顾版）描述写着"包含错误次数"，字段列表却漏了
     `incorrectCount`，而 FieldMapper/ExportUtils/MarkdownExporter 全都支持它（表头"答错次数"）→ 补进字段。
  4. **测试自己漏了开关（1 条，改测试）**：`includeDifficulty` 默认 false，
     JSON 与 CSV/Excel/Markdown 同口径都不输出难度；用例没打开开关就断言"难度应为文字"。
     补 `config.includeDifficulty = true`（生产口径本来就是对的）。
     另在 `ExportTemplateTest#setup` 里清 `files/templates/`：Robolectric 的 sandbox 会复用 filesDir，
     而默认模板是"文件不存在才创建"的，不清就永远看不到改过的默认模板。
  5. 更早一轮（本轮之前）修红的三类根因仍然成立：
     `StreamingUpdateManagerTest`（回调走 `mainHandler.post()`，改 Robolectric + idle）、
     `AIToolManagerTest`（"测试环境没注册工具"的前提早已不成立）、
     `OnlinePromptBuilderAssemblyTest` + golden（提示词瘦身后没重新生成）；
     以及唯一一条生产端修复 `AIServiceState.setCurrentStage(...)` 少夹 0..100（`3609a2a8`）。
- **改提示词后怎么更新 golden**：见 `OnlinePromptBuilderAssemblyTest` 类注释——
  先看 diff 确认措辞是想要的，再重新生成，**不许为了让测试变绿直接覆盖 golden**；
  另有 `uiComponentGuidanceStaysSlim` 钉住"UI 组件长规则不再回系统提示词"。
- **跑测试别把输出接进 `tail`**：Gradle 的失败会被管道吞掉（后台任务通知也会报 exit 0）。
  怀疑"没真跑"时加 `--rerun`，并核对 `build/test-results/testDebugUnitTest/*.xml` 的时间戳。
- 截图：`demo/screenshots/` 是设计基线，可作为实现对照。

---

## 8. 给 Qoder 的启动提示（可直接粘贴）

```text
你在继续 D:\qzq\smartquiz 的 AI 对话 / Agent UI 改版。先读：
- docs/ui/HANDOVER.md（**先读 §4.1 / §4.2 / §4.3 / §4.4 / §4.5 的落地修正口径**，别照抄 §3/§4 的旧文字）
- docs/ui/tool-result-step0-tasks.md
- docs/ui/ai-chat-redesign.md
- docs/ui/component-system-review.md
- demo/README.md 与 demo/ai-chat-redesign.html（视觉验收基线）

现状（已提交，工作区干净）：
- B0（HTML 沙箱 + CardShell + 7 卡）、C0（工具结果止血）、B1（quiz 可答题 + 类型收敛 + 兜底卡折叠）、
  A0（聊天页 v2 外壳，开关 chat_ui_v2 默认关）、demo 视觉体系落地为 chat_v2_* token/图标/9 个 v2 布局
  并完成接线（输入胶囊、顶栏、空状态、用户气泡、正在生成提示行）、A1 前三段
  （思考段折叠行 / 工具段接管 Agent 汇总 / 正文段去气泡 + 页面底色 token 化）。
- 真机已确认：浅底页面（chat_v2_bg 覆盖首页壁纸）、v2 顶栏（汉堡 + 标题▾ + 紫点副标题 + ＋ + ）、
  紫色渐变用户气泡、无气泡的 AI 正文与 Markdown、操作行/状态行在浅底上可读、
  输入胶囊、旧头部胶囊行与会话统计胶囊条已收起、无崩溃。
- 真机**未看过的**：带思考/带工具调用的消息（思考折叠行、"已运行 N 个工具"行 + 点开明细）、
  v2 空状态首屏（要点一次「新对话」才会出现）、深色档。
- JVM 单测 **417 条全绿、0 跳过**（2026-10-19 一天内三轮：修 25 条红 → 发现 Robolectric 因没开
  `includeAndroidResources` 被整类跳过 128 条 → 开齐后逐条查修 18 条，明细见 §7。
  **报"绿"之前必须核 tests/skipped/failures/errors 四个数**，`failures=0` 会被跳过骗）。
- "每次退后台/重进多一条历史会话"查到第三层主因并修完（§4.7）：会话文件只持久化 USER/AI，
  而恢复窗口末尾常是 SYSTEM 消息，认领比对必须取"窗口里最后一条 USER/AI"的 id。
- A1 第四段两半已落地但**真机没看过**（§4.8）：日期分隔做成"消息行自己的第一个子 View"
  （不是 RecyclerView 虚拟条目）、操作段换成三图标 + 更多面板。
- 不要动 CardShell / HtmlSandbox / HtmlWebView / ComponentLifecycle / ComponentTypes 的既有口径；
  不要"顺手"接线 ToolExecutionOrchestrator（无调用点的副本）。

下一步（按顺序）：
1) **装机回归（设备 279b6c51 回到电脑跟前第一件事；APK 已在 `build/outputs/apk/debug/`）**，四组一起看：
   a. 会话不再重复：反复进出聊天页，`files/conversations/` 只有一条 uuid 文件在被更新、
      没有 `null.json`，日志每次恢复都有 `恢复历史认领回原会话: <id>`（§4.7）；
   b. 主气泡不再空白：进页面首屏就有正文，logcat 无 `measure failed`（§4.6，仍未验）；
   c. A1 第四段：新日期第一条上方一行居中日期、用户气泡右下不再显示时间；
      末条 AI 只有三颗图标、点「更多」出六项面板、点「有帮助」有 Toast（§4.8）；
   d. 切一条有工具调用的历史会话确认思考段/工具段，点「新对话」看 v2 空状态，顺手看深色档。
2) A1 剩余：**"正在生成 · Ns"的秒数**（与 think-line 共用一个计时口径）、
   **ChatAdapter 按 viewType 拆 Binder**。**注意 AI 消息没有布局文件**：
   是 `createAiMessageItem` 用 ~330 行代码 new 出整棵树（`DynamicAiMessageRoot` 36 参构造器），
   只能一段一段换，不要整体重写；新加的视图沿用"建完直接挂 holder"（`toolSection`/`markdownView`/
   `dayLabel`/`v2ActionRow` 都是这样），别去扩构造器参数。
3) A2 Agent 执行子 UI + 控制台 6 Tab。
4) C1 剩余（ToolResultPresentation/Presenter/落盘/时间线）与 B2 剩余（无障碍、截图回归、
   拆 NativeLayoutRenderer）各自独立排期。

约束：
- 不整体重写 ChatAdapter / AIChatActivity；不改 ChatMessage 持久化结构。
- 新 UI 不用 emoji 图标、不新增硬编码颜色；中文文案走 strings.xml（注意还有 strings_migrated.xml）。
- **v2 页面底色已是 chat_v2_bg 纯色**：任何底色相关改动，先查那一串"深蓝壁纸专用"的
  0xE8FFFFFF / 0xB3FFFFFF / #E8FFFFFF（createAiMessageItem 与若干 item_*.xml），
  浅底上它们会直接看不见；需要时出 *_v2 布局变体，控件 id 保持一致，只按开关换 inflate 的文件。
- Chip 字段类型不能换（Activity 里按 Chip 声明），v2 场景卡因此用独立 id。
- 模式 chip 的 v2 配色会被 AIChatActivity#updateAgentChip / updateDeepThinkChip 覆写，
  要彻底对齐 demo .mode 得改那两个方法（见 §4.4-10）。
- 构建前先确认 liblo-native-code.so / asr/model.int8.onnx 存在。
- 动 HTML 相关必须跑 HtmlSandboxTest；动类型名必须跑 ComponentTypesTest；
  改在线提示词要先看 diff 再重新生成 golden（见 OnlinePromptBuilderAssemblyTest 类注释）。
- 验证：adb install / gradle test 的输出**别接进 tail**（管道吞失败、后台通知照样 exit 0）；
  写 prefs 前先 am force-stop；AIChatActivity 是 exported=false，进它只能起首页再点首页"AI 聊天"卡
  （实测坐标 (610,2053)，屏 1220x2656），不要为验证放开 exported；
  该设备 279b6c51 会反复从 adb 掉线，装不上就说明"编译+单测已过、真机未验"，别连续重试耗轮次。
```

---

## 9. 开放问题（落地前和用户确认）

1. 默认主题：中性浅色（推荐）还是保留现有深蓝渐变作为默认？
2. Agent 控制台「会话」Tab 要不要展示正在运行的实时会话，还是只展示历史？
3. quiz 判题在本地做，还是回传 Agent 由模型判？
   —— B1 先按**本地判题 + 结果回传**实现（即时反馈，不等模型；Agent 可用
   `get_result` 拿到 `quiz_answer` 继续讲评）。要改成"模型判"只需把
   `QuizCardView` 的提交动作换成回传后等模型下发正确态。
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
