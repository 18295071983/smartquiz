# 状态栏 / ChatMessage 消息布局：v1 恢复调查（2026-10-25）

> 用户问题："我之前设计的 chatmessage 的一系列 ui，之前 ai 对话页面使用 v2 时，都给改没了，
> 还能捞回来吗，我记得旧的 v1 还在。"
>
> 结论：**能捞**。分两类：
> 1. **顶部状态栏**：视图与数据管线**都还在当前代码里**，只是被 v2 外壳 `ChatUiV2Chrome` 隐藏了，
>    改几行就能恢复（S）；
> 2. **ChatMessage 消息卡系列**：v1 的独立行 UI（思考卡 / Agent 步骤卡 / 汇总卡等）确实在
>    2026-10-20 的 S5 里被删/被隐藏，素材可从 git 完整取回，但要"接回运行时"需要重接渲染，
>    工作量 M–L，且会和 parts 渲染/滚动/统计等近期改动交叉。
>
> 被删文件已归档：`docs/legacy/v1-chat-ui/`（含 README、原 10 个文件、完整恢复命令）。

---

## 1. 顶部状态栏

### 1.1 v1 是什么（现在还在布局里）

`activity_ai_chat.xml` 里这一整行"渐变胶囊"：

| 视图 id | 位置 | v1 显示内容 |
|---|---|---|
| `service_status_bar` | `activity_ai_chat.xml:32` | 状态图标 + `service_status_text`（模型/服务/就绪） |
| `tv_api_balance` | 同上行内 | 账户余额 |
| `service_status_progress` | `:102` | 生成进度条 |
| `service_status_elapsed` | `:112` | 本轮耗时 |
| `tv_token_stats` | `:122` | 输入/输出/命中率/上下文（本轮+累计） |
| `tv_gen_phase` / `tv_kv_stats` | `:142` / `:153` | 生成阶段 / KV 缓存 |
| `btn_back` | `:167` | 工具中心（抽屉）入口 |
| `remote_dsh_bar` | `:181` | 电脑（远程 DSH）连接状态 + 去配对 + 隐藏 |
| `log_container` | `:397` | 运行日志面板 |
| `session_stats_bar`（`ChatStatsBar`） | `:474` | 输入框上方统计胶囊：耗时/轮次/工具/Token/费用/上下文/任务 |

### 1.2 v2 怎么把它藏起来的

`ChatUiV2Chrome.doApply()`（`ai/chat/ui/ChatUiV2Chrome.java`）：

- `:114` `hide(service_status_bar)`，`:117-121` 连它所在的整行（含 `btn_back`）一起 `GONE`；
- `:115` `hide(remote_dsh_bar)`、`:116` `hide(log_container)`、`:124` `hide(btn_back)`、`:126` `hide(btn_history_fab)`；
- `:129-148` `session_stats_bar.setSuppressed(true)`（`ChatStatsBar` 数据照算，但不显示）；
- `:155` 把 `main_content` 底色刷成 `chat_v2_bg`，`:160` 在 index 0 插入 v2 顶栏
  `view_chat_appbar_v2.xml`（标题 + 模型副标题 + 状态点 + 新建/更多）。

### 1.3 关键结论：数据管线是活的，只是没人看

- `ServiceStatusManager:602-609`：状态文案仍写 `service_status_text`，**同时**双写 v2 副标题
  `ChatUiV2Chrome.updateSubtitle`；`:596` 双写状态点颜色。
- `AIChatActivity.updateTokenStatsUI()`：仍按 token 回调刷新 `tv_token_stats`（`R.id.tv_token_stats`）。
- `refreshNativeStateUI()`：仍刷新 `tv_gen_phase` / `tv_kv_stats`。
- `ChatStatsBar.update()`：仍按 `chatHistory` 重算，`setSuppressed(false)` 就能恢复显示。
- 「⋯」面板（`ChatUiV2Chrome.openInfoSheet`，`:282-329`）就是读这些隐藏 TextView 的当前值
  （模型 / 余额 / token / 阶段 / KV / 运行日志 / 工具中心 / 清空 / 控制台）——所以"数据没丢，只是从常驻变抽屉"。

### 1.4 恢复方案（按代价从小到大）

| 方案 | 做法 | 影响 / 风险 | 工作量 |
|---|---|---|---|
| **A1 原样恢复状态行** | `doApply` 不再隐藏 `service_status_bar` 所在行（和/或 `session_stats_bar`、`remote_dsh_bar`）；v2 顶栏保留或针对位置调整 | 会出现"v2 顶栏 + v1 状态胶囊"两套头，需要配色/圆角在浅底上过一眼；`status_bar_background` 用的是主题 attr（`colorSurface`/`colorOutline`），大概率不用改 | S |
| **A2 合并进 v2 顶栏（推荐）** | 保留 v2 顶栏，把关键读数塞进已有槽位：副标题（模型·状态）已在；`view_chat_input_bar_v2` 的生成行（`chat_v2_gen_text`，由 `ChatInputBar` 维护）扩成"阶段 · token · 耗时"；余额/KV/日志继续留在「⋯」 | 不新增第二条状态栏，符合 v2 IA；要动 `ChatInputBar` 生成行文案与 `ServiceStatusManager` 写入点 | S–M |
| **A3 若"状态栏"指消息状态行** | 恢复气泡下"已完成 · N token · 1.7s · 71.7 t/s" | 见 §2.3 的 `updateMessageStatus` 门控 | S |

---

## 2. ChatMessage 消息布局系列

### 2.1 v1 的"一条消息一种卡"

| 类型 | v1 布局/实现 | 现在（v2 + parts） |
|---|---|---|
| USER | `item_user_message.xml`（`:32` 的渐变气泡 + 时间戳） | `item_user_message_v2.xml`（同 id，换 `chat_v2_bubble_user`/token 色）；时间戳收进日期行 |
| AI | 一直是 **代码树**（`ChatAdapter#createAiMessageItem`，历史无 `item_ai_message.xml`） | 同代码树，v2 换 token 色 + 图标动作行 |
| THINKING | `item_thinking_message.xml`（灰卡 + 进度条 + 可展开正文） | `item_thinking_message_v2.xml`；parts 接管后改气泡内一行折叠行（`view_chat_v2_think_line`） |
| AGENT_STEP | `item_agent_step_message.xml`（图标+标签+内容+步骤号卡片） | `item_agent_step_message_v2.xml` 仍存在，但 **`shouldHideLegacyRow()` 直接隐藏**（`ChatAdapter:965-985`） |
| AGENT_SUMMARY | `item_agent_summary_message.xml`（汇总卡） | 同样被 `shouldHideLegacyRow()` 隐藏 |
| TOOL_CALL / TOOL_RESULT | `item_tool_call_message.xml` | 布局仍在；parts 接管后由 `parts/ToolCallPartRenderer`/工具行渲染，legacy 卡让位 |
| AGENT_REFLECTION | `item_agent_reflection_message.xml` | 仍可见 |
| SYSTEM（INFO/SUCCESS/TIP/TASK/PROGRESS） | `item_system_message.xml` | `shouldHideLegacyRow()` 隐藏 |
| 输入栏 | `view_chat_input_bar.xml` | `view_chat_input_bar_v2.xml` |

### 2.2 为什么"都被改没了"

两件事叠加：

1. **S5（2026-10-20，提交 `7b9c6843`）**：把 v1/v2 开关 `ChatUiFlags` 和 `if (uiV2) … else v1 …` 分支整体删掉
   （`ChatAdapter` **-1359 行 / +130 行**），只留 v2；被删文件：`item_user_message.xml`、
   `item_thinking_message.xml`、`item_agent_step_message.xml`、`view_chat_input_bar.xml`、
   `view_chat_v2_tool_line.xml`、3 个 drawable、`ChatUiFlags`、`ChatUiSetting`。
2. **parts 渲染器接管（2026-10-21 起，`parts_renderer` 默认 true）**：思考块/工具/组件统一由
   `parts/*` 渲进 AI 气泡（一条折叠时间线），legacy 的思考行、工具卡、`agentSummary` 文本必须让位
   （`ChatAdapter:911`、`:1226-1231`、`:3642`）；AGENT_STEP / AGENT_SUMMARY / 系统提示行由
   `shouldHideLegacyRow()` 一律隐藏。

所以现在不是"布局文件丢了"（大部分 `item_*` 还在），而是**"每种一张卡"的信息架构被折叠成了一条线**，
加上 v1 的 3 个卡布局被删。

### 2.3 消息级"状态栏"（状态行/时间戳）也一起收了

- `ChatAdapter.bindUserMessage()` `:1171-1175`：`timestampText` 填好时间后仍 `GONE`（时间交给日期行）；
- `:1180-1184`：USER 完成态不显示"已完成"；
- `:1222-1223`：AI 逐条时间戳同理收起；
- `updateMessageStatus()` `:2977-2983`：`quietStatus`（COMPLETED/GENERATING/IN_PROGRESS）下
  强制隐藏 `statusIcon`/`statusText`——v1 那行"已完成 · 121 token · 1.7s · 71.7 t/s"就是这里收掉的
  （2026-10-21 的 demo 对齐决定）。

### 2.4 已捞回的素材

- `docs/legacy/v1-chat-ui/`：S5 删除的 10 个文件（布局/drawable/开关类）+ 恢复命令；
- 完整 v1 `ChatAdapter.java`（7004 行）与 v1 版 `AIChatActivity` 在 `7b9c6843^`：
  `git show 7b9c6843^:src/main/java/com/oilquiz/app/ai/chat/ChatAdapter.java`
- 逐项差异依据：`docs/ui/v1-v2-merge-report.md`（含 §3 三个 v1/v2 并存的真 bug，别原样倒回去）。

### 2.5 恢复方案

| 方案 | 做法 | 影响 / 风险 | 工作量 |
|---|---|---|---|
| **B1 样式回接（推荐先做）** | 不恢复"卡行"架构，只把 v1 的设计元素并进 v2：用户气泡（v1 渐变/圆角/内边距）、思考卡（灰底 `message_card_thinking` + 进度条）、步骤卡（`agent_step_tag_background`）等视觉并入现有 v2 布局/parts renderer | 视觉接近 v1，但不改列表结构；对滚动锚点/统计/parts 无影响；注意 v1 布局里 `#B3FFFFFF` 等白字在浅底不可见，必须换 token | M |
| **B2 结构回接** | 取消 `shouldHideLegacyRow()`、恢复 v1 独立行 + `ChatUiFlags`/`ChatUiSetting` 开关，回到"每种一张卡" | 与 parts 渲染、思考流式、2s 统计隐藏、滚动锚点全交叉；等于把 S5 + 5 天迭代回退重接；回归面大 | L（建议独立立项） |
| **B3 只回接重点卡** | 例如只恢复"思考卡"和"Agent 步骤卡"两种独立行，其余保持 v2 折叠 | 折中；仍要处理 parts 接管让位与列表高度变化对贴底/锚点的影响 | M–L |

另：有调试退路可先看"parts 之前的 v2 legacy 渲染"——把 prefs
`ai_chat_prefs/parts_renderer` 显式写 false（无设置页入口），可见 pre-parts 的思考行/工具卡路径；
但这仍是 v2 皮，不是 v1。

---

## 3. 风险清单（真正动手前）

1. **顶部 insets/键盘**：刚做完的"insets 驱动贴底 + 滚动锚点"假设了头部高度稳定；恢复状态栏后
   头部高度变化，需要重跑键盘/阅读位置回归（`docs/scroll_shots/06~09` 那套）。
2. **配置与暗色**：v1 布局里大量 `#B3FFFFFF/#E8FFFFFF` 是深蓝壁纸专用；v2 是浅底 token，
   直接换布局会白字不可见 —— 样式回接必须用 `chat_v2_*` token。
3. **parts 接管让位**：B2/B3 必须明确"parts 接管时 legacy 行隐藏"的边界，否则思考/工具双份。
4. **统计块 2s 自动隐藏**：属于 AI 消息内 meta；恢复 v1 消息 meta 行时要和它统一口径，避免两套统计。
5. **测试基线**：当前全量 949 / 0 fail；状态栏/消息结构改动都要跑全量 + 真机回归，
   重点：上滑阅读不被拽底、键盘开关、回底、thinking 流式。

---

## 4. 建议的下一步（等拍板）

- **P1（先做，S–M）**：状态栏回接，推荐 A2——v2 顶栏副标题补"模型 · 状态 · 本轮 token"，
  生成行（`chat_v2_gen_text`）补"阶段 · 耗时 · token"，「⋯」保留余额/KV/日志。
  想更接近 v1 就选 A1，直接恢复那条胶囊（配色在浅底上过一眼）。
- **P2（M）**：消息样式回接 B1——把 v1 的用户气泡/思考卡/步骤卡视觉并回 v2，不改列表结构。
- **P3（L，另立项）**：B2/B3 结构回接（独立卡行 + 开关），需要单独立项+回归计划。

> 本报告只做调查，未改任何运行时代码（`docs/legacy/v1-chat-ui` 为素材归档）。
