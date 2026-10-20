# AI 对话页 v1 / v2 两档差异盘点与合并建议

生成时间：2026-10-20　开关：`ChatUiFlags.isUiV2Enabled`（`chat_ui_prefs` / `chat_ui_v2`，**默认 false**）
用途：为"合并成一套界面"提供逐条依据。**本报告里的每个 file:line 都是我自己 grep/读码复核过的**，
不是转述注释或转述子代理。

> **落地进度（2026-10-20 夜）**：用户已拍"以 v1 为能力基线、吸收 v2 的优点合成一套"，
> S0（开关进抽屉 + 立即重建 + 默认 true）、P1（档位判据归一到 holder.v2Look）、
> S1（`ActivityTimeline` 纯逻辑 + 单测）、S2（思考与工具合成一条活动时间线，
> 轮次模式不再内联思考/工具卡）已落地，见 `docs/ui/HANDOVER.md` §4.10。
> 下文 §6 的 P0–P5 仍适用，但顺序调整为 **S0 → S1 → S2 → S3（动作/状态/模型行）→
> S4（动效）→ S5（删 v1/资源/开关）**；§3① 的混档 bug 已由 P1 修掉。

---

## 0. 结论先说

**推荐方向：以 v2 为唯一档，删掉 v1 分支**，而不是反过来。三条依据：

1. v2 不是"换皮"，它已经改了**信息架构**：日期分隔、思考段一步一行、工具段接管汇总、
   操作段图标化 + 动作面板、空状态场景卡。退回 v1 等于把这些全部扔掉。
2. 布局层已经天然支持合并：三对消息布局 + 输入栏两对**id 与 view 类型完全一致**，
   v2 只是多挂了 `day_label` 与 `chat_v2_gen_*`（详见 §4）。也就是说"换 inflate 的文件"这件事零风险。
3. 两档并存的代价已经在**变成真 bug**（见 §3 的三个问题），不是"以后可能有问题"。

但**不能直接删 v1**：现在 v2 的动作、状态、颜色有一半是"寄生"在 v1 的视图上的
（隐藏起来再 `performClick()` 转发）。合并的第一件工作是把这些寄生关系解开，不是删代码。

---

## 1. 现状规模

| 项 | 数量 |
|---|---|
| 开关读取点 | **10 处**：`ChatAdapter` 350 / 363 / 370 / 395 / 1115 / 3224，`ChatInputBar` 60，`ChatUiV2Chrome` 91 / 375，`ChatV2EmptyState` 64 |
| 开关写入点 | **1 处**：`ChatUiV2Chrome:379`，在 v2 自己 inflate 的「⋯」面板里 |
| v2 资源 | 19 色 × 深浅两档、38 dimens、64 strings、18 drawable、6 个 `_v2` 布局 |
| 主要文件 | `ChatAdapter.java` 7,004 行、`AIChatActivity.java` 11,864 行 |

**开关写入点只有 1 处、且在 v2 面板内**——这意味着今天从 v1 切到 v2 只能靠 adb 改 prefs。
合并前必须先给它一个正经的家（设置页），否则删完任一侧就变成单向门，出问题回不来。

---

## 2. 表 A：页面外壳层差异

| 位置 | v1（开关关） | v2（开关开） | 合并留哪边 | 依据 |
|---|---|---|---|---|
| `activity_ai_chat.xml:8` | 根背景是首页深蓝壁纸 `bg_home_full` | `ChatUiV2Chrome:139` 用代码把 `main_content` 刷成纯色 `chat_v2_bg` | **v2**，但壁纸要一起从 XML 摘掉 | 现在两档共用一个布局文件，v2 靠运行时覆盖，等于"改颜色靠后写的人" |
| `activity_ai_chat.xml:32/181/305/397/474` | `service_status_bar` / `remote_dsh_bar` / `empty_state_view` / `log_container` / `session_stats_bar` 全部可见 | `ChatUiV2Chrome:100-132` 逐个 `GONE`，统计条走 `ChatStatsBar.setSuppressed` | **v2**（这些视图从 XML 删掉） | 它们**至今仍在 XML 里**，v2 只是藏起来；这就是"两套界面同时被 inflate"的内存与理解成本 |
| 顶栏 | 代码拼装的一行标题 + 胶囊 | `ChatUiV2Chrome:159-173` 运行时 `addView(index 0)` 塞进 `view_chat_appbar_v2` | **v2**，但入口要改成直接写在 XML 里 | 运行时插到 index 0 依赖"父容器只有一个子"这个隐含前提，很脆 |
| 历史/新建/模型入口 | 真控件 `btn_history` / `btn_new_conversation` / `btn_model_select` | v2 按钮 `performClick()` 转发给上面那些旧控件 | **保留旧控件为唯一实现**，v2 按钮改成直接调同一批 handler | 见 §5 耦合 C1：转发关系不先解开就不能删旧控件 |
| 空状态 | `empty_state_view` + `chip_empty_example1-3` + `chip_clear_chat`（`AIChatActivity:1873-1875` 接线） | `ChatV2EmptyState`（问候 + 4 场景卡 + 最近），`AIChatActivity:549-561` 把 `emptyStateView` 换根 | **v2**；场景卡是 `LinearLayout`（`chat_v2_scene_*`），与 v1 的 `Chip` **类型不同** | 删 v1 空状态块必须连着删 1873-1875 那段接线，否则 `findViewById` 返回 null 直接崩 |
| 输入栏 | `view_chat_input_bar.xml` | `view_chat_input_bar_v2.xml`（多 `chat_v2_gen_row/dot/text`） | **v2**（id 全含 v1，见 §4） | `ChatInputBar:60-75` 只是换文件 + 设方向 |
| 模式 chip 配色 | `updateAgentChip:8277` / `updateWebSearchChip:8341` / `updateDeepThinkChip:8387` 用代码设色 | `ChatUiV2Chrome:202-242` 也设一遍 | **必须只留一处**：把 Activity 那三个方法改成"读 token"，v2 的压缩样式并进 XML | 两个写入者同一属性，谁最后跑谁赢；这是 §3 问题③的成因 |
| 入口导航 | 首页/AI 中心/工具箱各自 `startActivity` | `ChatNavigation.openChat`（`REORDER_TO_FRONT\|SINGLE_TOP`） | **v2**，且通知侧三处应改用 `ChatNavigation.chatIntent()` | 我写的 `chatIntent()` **目前零调用方**，通知那三处是手抄了一遍 flag（`PythonToolManager:1202/4464`、`SystemConnectTool:183`）——重复实现，改一处忘三处 |

---

## 3. 合并顺手能修的三个真 bug（都源于"两档并存"）

① **切换开关后可能一处时间都看不到。** `bindDayLabel`（`ChatAdapter:1115`）在**绑定时**重读开关，
而时间戳的显隐（`1278` / `1312`）判的是 `holder.dayLabel != null`，那是**建视图时**定死的。
把开关从 v2 拨到 v1 而 `RecycledViewPool` 里还留着 v2 形状的 holder 时：
日期行被开关判成不显示、时间戳因为"`dayLabel` 不是 null"继续隐藏 → **这一条消息既没日期也没时间**。
同一类混档还有 `thinkingPrefix:3224`（绑定时读开关）对上 `holder.v2Look:852`（建视图时定）。

② **v2 浅底上还有深蓝壁纸专用的白描边胶囊。** `finalizeActionButton`（`ChatAdapter:930-943`）
的描边 `0x59FFFFFF` + 底色 `0x1AFFFFFF` 是**无条件**加的，
而它同时喂给 `btnStop` / `btnPause` / `btnStep` / `btnRewind`——这几颗在 v2 里是**可见的**
（生成中/Agent 控制条）。所以 v2 页面上那几颗按钮现在就是"浅底上一个几乎看不见的白胶囊"。
这不是合并才有的问题，是现在就错，只是没人报。

③ **正文 WebView 不跟 v2 配色。** `buildMarkdownThemeVars`（`ChatAdapter:3812`）取的是
`colorOnSurface` 那批 attr，不是 `chat_v2_*`；而页面底色在 v2 已被改成 `chat_v2_bg`。
也就是"页面是 v2 的皮、正文 WebView 是 v1 的色"。深色档下尤其可疑。
（这条我只核了代码取值路径，**没在真机看过**，见 §7。）

---

## 4. 表 B：消息渲染层差异 + 布局 id 核验

布局对（我自己 diff 过 id 与 view 类型）：

| v1 布局 | v2 布局 | 仅 v1 有的 id | 仅 v2 有的 id | 类型不一致 |
|---|---|---|---|---|
| `item_user_message` | `item_user_message_v2` | 无 | `day_label` | 无 |
| `item_thinking_message` | `item_thinking_message_v2` | 无 | 无 | 无 |
| `item_agent_step_message` | `item_agent_step_message_v2` | 无 | 无 | 无 |
| `view_chat_input_bar` | `view_chat_input_bar_v2` | 无 | `chat_v2_gen_row/dot/text` | 无 |

**结论：这四对可以无条件收敛到 `_v2` 文件。** 唯一要留神的是 `day_label` 在 v1 holder 里是 null，
所有用它当"档位判据"的地方（`1278` / `1312`）要改成显式常量或直接删判据。

消息树里真正只属于一档的东西：

| 位置 | 内容 | 合并处理 |
|---|---|---|
| `ChatAdapter:419-433` | `dayLabel` 仅 v2 建 | 留 v2 |
| `:436-444` | AI 侧 `blockLabel` **两档都建、两档都永久 GONE** | 直接删（连带 holder 字段与 `1315` 那句 GONE） |
| `:463-471` | v1 给 `bubble` 套 `ai_message_background` + 12/10dp；v2 不套皮 | 留 v2；但 `ai_message_background` **不能删**，`item_inference_progress.xml` 还在用 |
| `:552-615` | v1 思考区是 label/content/divider 三段裸视图；v2 inflate `view_chat_v2_think_line`（含 `_steps` 容器与 `_chev` 箭头），`thinkingDivider` 变成**不入树的占位 View** | 留 v2；占位不能删——约 20 处 `thinkingDivider.setVisibility` 调用点不判空 |
| `:621-627` + `:2006-2080` | 工具行 `toolSection` 仅 v2；`renderToolLine` 接管汇总并**顺带**压掉 `agentSummary` 文本与汇总卡 | 留 v2；但"返回值同时兼任压汇总"这个双重契约要拆开写（耦合 C3） |
| `:712-717`、`:777-779` | 操作行/元信息色 `uiV2 ? token : 0xE8FFFFFF/0xB3FFFFFF` | 留 token 分支，删字面量 |
| `:733-760` + `:1438-1471` + `:1481-1521` | v2 图标操作行 + 动作面板，**动作全部 `performClick()` 转发给 v1 那 7 颗胶囊** | 留 v2 外观，但见耦合 C1：转发必须先落地成独立 handler |
| `:3024-3166` | v1 思考段：把 rounds 拼成一坨 + `expand/collapseThinkingContent` 高度动画 + `showDivider`；v2 走 `updateThinkingSectionV2` 一步一行 | 留 v2；连带 `thinkingAnimator` 字段与 `chat_thinking_*` 里只被 v1 用的字符串 |
| `:2483-2508`、`:2950-2961` | 轮次模式的 header 文案与逐步行**两档共用同一段代码**，且用的是 `chat_v2_*` 字符串 | 这不是差异，是"v1 已经在偷用 v2 的文案"——合并后自然统一 |

---

## 5. 硬耦合：不能拆开做的事（顺序错了就静默失灵）

- **C1 v1 那 7 颗胶囊是 v2 的动作后端。** v2 只是把它们 `GONE`，图标和面板条目全靠
  `performClick()` 转发（`1468-1471`），`applyFeedbackVisual` / `applySpeakingVisual`
  还在往这些看不见的胶囊上 `setText`。**先删 v1 绑定块 = v2 所有按钮和反馈/朗读状态一起失效。**
  必须先把动作抽成 `onCopy/onSpeak/onLike/...` 方法，v1 胶囊和 v2 图标都调它，然后才能删胶囊。
- **C2 三个档位信号并存**：`holder.v2Look`（建时）、`dayLabel/v2ActionRow/toolSection == null`（用 null 当档位）、
  绑定时重读开关（`1115` / `3224`）。合并的**第一步**就是让这三者归零——只要还剩两个，§3① 那类 bug 就会复发。
- **C3 `renderToolLine` 的返回值兼任"压掉汇总"开关**（`1542-1547`），而它自己现在也可能返回 true 却把行隐藏
  （轮次模式）。拆成两个语义：`shouldSuppressAgentSummary(message)` 与 `renderToolLine(...)`。
- **C4 `thinkingDivider` 占位 + `thinkingStepsHost` 可能为 null**：`updateThinkingSectionV2` 在 `2745` 判了空、
  却在 `2773/2777` 直接解引用，今天安全只是因为"`v2Look` 为真 ⇒ 容器非空"。合并后这条隐含前提消失，
  要么保证非空，要么补判空。
- **C5 `ThinkLineLabel` 重写 `setVisibility`/`setOnClickListener` 去改父行**（`ui/ThinkLineLabel.java:33-52`），
  所以约 20 处"只给 label 设可见性"的老调用点实际在驱动整行 + 箭头。删 v1 分支时这些调用点逐个都要看。
- **C6 有些 v1 字符串被非档位代码用着**：`h_71505bcf`/`h_1d8b034e`（`3053-3063`）、
  `chat_thinking_process/expanded/collapsed/streaming_expand`（`578`、`3198-3210`、`6203`），
  后者被 `ThinkingMessageViewHolder.bind` 读——**不随 v1 分支一起可删**。

---

## 6. 建议的落地顺序（每步都能编译、能装、能回退）

| 阶段 | 做什么 | 为什么先做 | 验收 |
|---|---|---|---|
| **P0** | 把 `chat_ui_v2` 开关从 v2 面板搬进真正的设置页（或先保留面板但同时在设置页加一项） | 现在它是单向门：唯一的写入点在 v2 自己 inflate 的面板里 | 从 v1 界面能开、从 v2 界面能关 |
| **P1** | 消灭三个档位信号：`v2Look` 保留为唯一判据，`bindDayLabel:1115` 与 `thinkingPrefix:3224` 改读 holder 上的档位；时间戳显隐不再用 `dayLabel != null` 当档位 | 修 §3① 的混档 bug，且不动任何外观 | 开关来回拨 + 复用池里留旧 holder，时间/日期不会双双消失 |
| **P2** | 动作解耦：把 7 个动作抽成方法，v1 胶囊与 v2 图标/面板都调它；`applyFeedbackVisual/applySpeakingVisual` 改成同时驱动"胶囊文字"和"图标态/面板文案" | 解开 C1，后面才谈删 v1 | 复制/朗读/分享/反馈/新对话在 v2 上逐条点通 |
| **P3** | 外壳收敛：把 v2 顶栏从"运行时 addView"改成 XML 里就摆着；旧 `service_status_bar`/`remote_dsh_bar`/`log_container`/`session_stats_bar`/`empty_state_view` 从 XML 删除并清掉 `ChatUiV2Chrome` 里对应的 hide 与 `AIChatActivity:1873-1875` 接线；模式 chip 配色只留 Activity 一处并改读 token | 修 §3③ 的双写入者；也让"两套界面同时被 inflate"消失 | 深浅两档 + 360dp 小屏截图回归 |
| **P4** | 消息层收敛：`ChatAdapter` 删掉所有 `if (uiV2) … else …` 的 else 腿，三对布局只留 `_v2`，删 AI 侧 `blockLabel`、v1 思考拼接段与 `expand/collapseThinkingContent` | 前面都解耦了，这步才是纯删 | 411→当前 444 条单测 + 真机跑一条多轮 Agent 消息 |
| **P5** | 清资源：`item_user_message.xml`/`item_thinking_message.xml`/`item_agent_step_message.xml`/`view_chat_input_bar.xml`、`message_card_thinking`、`agent_step_tag_background`、`user_message_background`、只被 v1 用的字符串 | 死资源留着会误导（今天已经误导过一次：`item_message_ai.xml`） | `grep` 零引用 + 编译过 |

`ai_message_background` 与 `bg_home_full` **不在可删清单**：前者被 `item_inference_progress.xml` 用着，
后者是首页背景，只是聊天页不该再拿它当页面底。

---

## 7. 未验与风险（别当成已知）

- §3② 的白描边、§3③ 的 WebView 配色，我只核了代码取值路径，**没在真机看过**。
- v2 至今没验过的项：主气泡空白修法（HANDOVER §4.6）、日期分隔与操作段图标化（§4.8 部分已验）、
  思考段重复两遍的修复与 header 信息行（§4.9b 之后改的，包还没装上）。合并前这些最好先落定，
  否则"合并后坏了"和"本来就坏"分不清。
- 子代理本轮报错过两处，已自行复核纠正：① 说 `blockLabel` "两档都在用、不是 legacy-only"——
  实际是 **AI 侧** `blockLabel` 两档都 GONE（可删），它把 **USER 侧** `block_label`（语音标签，活的）混进来了；
  ② 说 ChatAdapter 约 5,900 行——实际 7,004 行（我给的 brief 也错，两边都要以 grep 为准）。
- 工作量我没有硬估。按阶段看，P1/P2 是"改判据与抽方法"，风险最高但视觉零变化；
  P3/P4 才是删代码，删之前必须先把 P1/P2 落稳，否则就是拿 11,864 行的 Activity 赌。

---

## 8. 需要你拍的三件事

1. **合并方向**确认是"v2 为唯一档"吗？（我推荐是；若你更在意风险，也可以只做到 P3，
   把消息层的 v1 分支留到 A1 收尾再删。）
2. **P0 开关的家**：搬进"设置 → 界面"，还是干脆不留开关（合并即终态，回退靠 git）？
   我推荐前者留一个过渡期开关，但**默认值同时翻成 true**，让 v2 成为实际默认。
3. **模式 chip 配色**（§3③）：v2 那套压缩样式（`ChatUiV2Chrome:202-242`）和 v1 的
   `updateAgentChip/updateDeepThinkChip` 现在长得不一样，合并时保留哪一套？
   我推荐留 v2 的紧凑样式，把 Activity 那三个方法的色值改成读 `chat_v2_*` token，
   这样"两个写入者"归一，也顺手解掉 §4.4 里记的那条已知覆写冲突。
