# 执行活动区 WebView 组件 PoC（2026-11-18）

> 目标：验证"把 agent 执行活动区（思考 + 工具调用）交给一个内嵌 web 组件渲染"是否可行、性能是否够。
> 本 PoC **只替换执行活动区**，消息流其余部分（正文、文件/媒体卡、审批、输入区）仍走原生。

## 1. 组成

| 层 | 文件 | 职责 |
|---|---|---|
| 页面 | `src/main/assets/agent_panel/agent_panel.html` | 自包含 HTML/CSS/JS（无外部依赖、离线）：头部（✦ + 呼吸点 + 文案 + 箭头）、折叠体（思考段 + 工具行）、流式自动展开 / 结束 700ms 自动收起、工具行点开看输出、右下角统计 |
| 原生壳 | `src/main/java/com/oilquiz/app/ai/chat/ui/AgentPanelView.java` | `loadDataWithBaseURL` 加载 assets；`update(JSONObject)` → `window.qzAgent.update(state)`；`Android.onHeight` 回传高度（设置 WebView 固定高）、`Android.onStats` 回传指标 |
| 载荷 | `src/main/java/com/oilquiz/app/ai/chat/parts/AgentPanelPayload.java` | `thinking` part → `{active,label,steps[],theme}`；与原生头共用 `ThinkingSummary` 文案口径 |
| 接线 | `ThinkingPartRenderer` + `AgentPanelWebFlag` | 开关 `agent_panel_web`（`ai_chat_prefs`，出厂**关**）：开则 thinking part 用 `AgentPanelView`，关则原生手风琴 |
| 压测页 | `src/main/java/com/oilquiz/app/infra/AgentPanelPoCActivity.java` | 本地 60 帧 × 80ms 模拟流；显示首帧耗时、注入 avg/max、页面 FPS、高度回传次数 |
| 入口 | `UiKitGalleryActivity` 第 7 节 | 「打开 PoC 页面（模拟 60 帧流）」；PoC 页里可一键把聊天页切到 WebView / 切回原生 |

## 2. 怎么验证

1. 真机：`UI 工具包` 页 → 第 7 节 → 打开 PoC；看三个数：
   - **首帧**：建 WebView 到首次 `onHeight` 的耗时（PoC 页顶部）；
   - **注入成本**：`updates / avg / max`（页面右下角，同时回传原生）；
   - **FPS**：页面 rAF 计数；
   - 高度回传次数 = 高度抖动次数（每帧都变说明内容布局不稳）。
2. 聊天页：PoC 页里点「聊天页：WebView 已开」，回到对话页看真实 turn（需 agent 跑一轮）。
3. 浏览器：`agent_panel.html?demo=1&updates=40&interval=40` 直接看交互（无 Android 桥，仅视觉）。

截图（Chromium 渲染，headless）：
`docs/ui/agent-panel-poc_shots/01_streaming.png`（流式中：主色文案 + 呼吸点 + 工具行）、
`docs/ui/agent-panel-poc_shots/02_done.png`（结束：自动收起）。

> 注意：headless 的 `--virtual-time-budget` 下 rAF/performance 计时被压缩，截图里的 `avg 0ms / fps 0`
> 不代表真机；真机数值以 PoC 页为准。

## 3. 内存与回收（2026-11-18 补）

WebView 不是普通 View：它背后是一个 Chromium 渲染侧实例（几十 MB 原生内存 + 自己的合成器）。
`MarkdownWebView` 已经是"每条文气泡一个"，执行面板再叠一层，长会话里就会先卡起来。

- **硬上限**：`AgentPanelBudget`，同进程最多 **3** 个 WebView 面板；超了 `ThinkingPartRenderer.createView`
  直接返回原生 `ReasoningShellView`（不占 WebView）。3 的依据：同屏"正在执行"的 turn 通常 ≤2。
- **两条释放路径都会走到**（`AgentPanelView implements ComponentLifecycle.Releasable`）：
  1. parts 删除该 part → `PartRenderer.releaseView` → `release()`；
  2. 换消息 / holder 回收 → `ChatAdapter.releaseAndClearComponents` 深度释放 → `ComponentLifecycle.release`
     → `releaseComponent()`。
- **释放顺序**（缺一步都会留 Chromium 残留）：
  摘 JS 桥（`Bridge` 是内部类、持有 Activity Context）→ `stopLoading()` → `loadUrl("about:blank")`
  + `clearHistory()` + `clearFormData()` → 从父容器摘除 → `destroy()`；最后监听器置空、归还额度。
  整个 `releaseComponent()` **幂等**（重复调用只生效一次，额度不会被重复扣）。
- **离屏只暂停**：`onDetachedFromWindow` → `webView.onPause()`，`onAttachedToWindow` → `onResume()`；
  列表滚动会反复 attach/detach，销毁只发生在"确定不再复用"时。
- 现状差异：`MarkdownWebView` 只 `resetForReuse()`、不 `destroy()`（历史设计：随 holder 复用），
  所以一个 holder 池里是"池大小 × 每消息 WebView 数"；面板没有沿用这种"永不销毁"，因为它是可选的、
  且数量有上限——不达标就直接退回原生。

## 4. 用多了会不会卡

会，风险点与已做的封堵：

| 风险 | 现状 |
|---|---|
| 同屏 WebView 数量翻倍 | 上限 3 + 超限回落原生；面板只随可见 holder 存在 |
| 首次创建开销（冷启 100–300ms） | 面板是 10KB 自包含页面，无三方 JS，加载成本低于 `MarkdownWebView` 的整篇 markdown 构建；首帧耗时可在 PoC 页直接读 |
| 高度抖动导致列表重排 | 面板高度由页内 `onHeight` 钉死（不靠 `wrap_content`），且只在这一帧内容变化时回传 |
| 后台 JS 占 CPU | 离屏 `onPause`；页面无轮询，只在 `update()` 时跑 |
| 释放漏掉导致内存涨 | 见第 3 节：Releasable + 幂等 + 深度释放两条路 |

真机要看的三个数（PoC 页）：首帧 ≤350ms、注入 avg ≤4ms / max ≤16ms、FPS ≥55；超了就把
`agent_panel_web` 保持关闭，原生手风琴继续用。

## 5. 已知边界（PoC 未做）

- 工具行现在只能展开**文本输出**（`result` 截断 4000 字），产物卡（图片/图表/文件）还在原生，没有进面板；
- 面板内没有长按复制、没有链接跳转（`shouldOverrideUrlLoading` 直接吞）；
- 每个 turn 一个 WebView：列表里同屏多个 running turn 时内存/初始化成本要真机量（Native 方案只有 0 个 WebView）；
- 主题：PoC 把 `--fg/--muted/--accent/--border/--surface` 五个 CSS 变量从 `ThemeColors` 注入，
  换肤/夜间跟随，但字体缩放（系统 fontScale）目前固定 13px，没有接 `sp`。

## 6. 是否该切默认（等真机数据）

建议的门槛（单 turn，3 个工具、60 帧流）：

| 指标 | 可接受 | 说明 |
|---|---|---|
| 首帧 | ≤ 350ms | 超过就会看到"卡片先空一块" |
| 注入 avg | ≤ 4ms/帧 | 80ms 一帧的流式预算里占比 <5% |
| 注入 max | ≤ 16ms | 不能丢帧 |
| FPS | ≥ 55 | 滚动时同样要测 |

达标 → 可以把 `agent_panel_web` 翻成默认；不达标 → 保留原生手风琴，只借鉴页面的交互设计（本仓库已经这么做）。
