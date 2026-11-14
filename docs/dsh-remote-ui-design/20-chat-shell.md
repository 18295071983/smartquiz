# DSH 远程 UI · 对话页外壳（M8）与事件接线（M9）

- 版本：v1.1（2026-11-14，随提交 `c5a24db8` 校正）
  - 审批/反问已改为**原生对话框**（不再是时间线插卡），§1.4 同步；
  - 「待审批 N」统一为「待处理 N」（计数含反问），§4.2 同步；
  - `AIChatActivity` 行号漂移 → 一律改**方法名**引用；
  - 新增 §4.6（权限行的数据源不能只靠事件）；
  - P-3 先做单点验证，见 §2 表下说明。
- 上位文档：`00-overall.md`；并列：`10-modules.md`（M1–M7）、`90-tests-acceptance.md`、`99-review-findings.md`
- 状态：**设计稿（待评审）**，本轮不改代码
- 一句话：远程能力的数据已经齐了，**缺的是外壳**——把"连了电脑的聊天页"和"用手机本地模型的聊天页"区分开，并给 DSH 专用功能一个常驻入口。

---

## 0. 结论摘要（先看这段）

调查后有四个判断，直接决定设计走向：

1. **不是"没做远程化"，是只做了一半**。抽屉五行（`ChatDrawerStatusBinder`）、远程状态条（`refreshRemoteDshBar`）、插槽、审批卡都已经按远程语义换过；**唯独对话页的三层壳——顶栏 / 空首屏 / 输入区——仍是本地模型口径**。
2. **最刺眼的一条是状态条被主动藏了**。`ChatUiV2Chrome` 在改造外壳时 `hide(R.id.remote_dsh_bar)`，而 `refreshRemoteDshBar()` 仍在每秒计算阶段、跟随会话、待审批、RTT。**算完就藏**，所以连上电脑后聊天页看不出任何"远程"。
3. **DSH 面板不是没有，是没有入口**。`/v2/jobs|goal|subagents|plan|permission|mcp|schedule` 七个区块全在 `RemoteDshInsightActivity` 里，唯一的进入方式是**点甲板插槽**（`openDockSlotPanel`）——而插槽只在对应功能活跃时才出现，且 11sp 两行截断。用户不点中那条 30dp 的行，就永远不知道这些功能存在。
4. **事件层面是"转发得多、认得少"**。插件 `EVENT_CATALOG` 登记 80+ 条、转发 40+ 条；手机 `DshTimeline` 只归约 21 条。`todo/write`、`deliverables/presented`、`workspace/changes`、`command/run|done`、`job/*`、`goal/change`、`permission/preset`、`sandbox/mode`、`team/*`、`tool-workflow/*`、`compaction/*` 全是"收到了但扔掉"。

据此拆成两件事：**M8 外壳（P0，可见、改动小）** 与 **M9 事件接线（P1/P2，内容增量）**。

---

## 1. 现状调查

### 1.1 顶栏（`view_chat_appbar_v2` + `ChatUiV2Chrome`）

| 元素 | 现状 | 证据 |
|---|---|---|
| 主标题 | 会话标题 | `ChatUiV2Chrome.updateTitle` |
| 副标题 | **抄本地模型状态条的文本**（`本地模型 · HTP0 · Qwen3.x · 就绪`） | `ChatUiV2Chrome.java:284-291` `modelSubtitle()` |
| 状态点 | 复制本地服务状态色 | `updateStatusDot` |
| 点标题/副标题 | 跳 `ModelCenterActivity`（在线模型注册+用量，**选不了本地模型**） | `ChatUiV2Chrome.java:223-225` |
| 远程状态 | 被 `hide(R.id.remote_dsh_bar)` 整条藏掉 | `ChatUiV2Chrome.java:133` |

结果：连上电脑后，顶栏照样写"本地模型…就绪"，**这句话与当前实际执行者（电脑）无关**。

### 1.2 空首屏（`ChatV2EmptyState`）

三个入口全部指向本地模型：

| id | 文案 | 去向 | 证据 |
|---|---|---|---|
| `chat_v2_entry_select_model` | 去选择模型 | `ModelSelectorActivity` | `ChatV2EmptyState.java:97`、该入口的点击回调在 `AIChatActivity` |
| `chat_v2_download_row` | 下载本地模型 | `ModelDownloadActivity` | `ChatV2EmptyState.java:98` |
| `chat_v2_entry_guide` | 看使用说明 | 引导弹框 | `ChatV2EmptyState.java:99` |

「最近」列的是**手机本地会话**（`ConversationSession`），不是电脑端会话（`ChatV2EmptyState.java:124-152`）。

> 顺带一笔不一致：空首屏第一行进 `ModelSelectorActivity`（能选本地模型），顶栏点标题进 `ModelCenterActivity`（选不了）。同一件事两个去处，M8 顺手收敛。

### 1.3 输入区

三个 mode chip 由本地引擎驱动：`chip_deep_think` / `chip_web_search` / `chip_local_agent`（`ChatUiV2Chrome.java:301-316` 只改样式不改交互）。远程模式下**没有任何代码把它们收起或禁用**。

### 1.4 已经远程化的部分（不要动）

| 位置 | 做法 | 证据 |
|---|---|---|
| 工具抽屉五行 | 远程时标签换成 `电脑/链路/模型/上下文/Token`，值取自 `RemoteStatusSnapshot` + `RemoteLiveStatus` | `ChatDrawerStatusBinder.java:132-246` |
| 远程状态条 | 阶段/跟随会话/待处理/排队/重连，1s 心跳 | `refreshRemoteDshBar()` |
| 甲板插槽 | `RemoteStatusSlots.publish` + `DockSlotBus`；待处理行发 `attention`（原地弹那条），只有断线/落后才发 `connect` | `refreshAttentionSlot()` → `RemoteStatusSlots.publish()` |
| 审批 / 反问 | **原生对话框**（帧到即弹，`handleChannelEvent` 的 `approval` / `question` 分支）+ 消息里留状态 + 甲板卡兜底；回传 `/session action=approval`、`/v2/answer`（2026-11-14 起；此前是时间线插卡） | `showDshApprovalDialog()` / `showDshQuestionDialog()` |
| 长按状态条 | 打开工具抽屉「状态」区 | `remoteDshBar` 长按监听 |
| 点状态条 | 进 `RemoteDshConnectActivity` | `remoteDshBar` 点击监听 → `openRemoteConnect()` |

### 1.5 DSH 面板的可达性

`RemoteDshInsightActivity` 已有七个区块：后台任务 / 目标 / 权限 / 日程 / 计划 / 子代理 / MCP（`RemoteDshInsightActivity.java:193-199`）。入口只有两个：

- 连接页的按钮（`RemoteDshConnectActivity.java:127`）
- **甲板插槽点击**（`onDockSlotClick()`）——要求恰好有 jobs/goal/schedule/subagents/mcp/permission 插槽

### 1.6 远程判定的重复

"是否远程"这一判断在 `AIChatActivity` 里写了 **6 处**同样的 `isConfigured(this) && isConnected(this)`：4028、9361、9376、9499、14727、15322。每加一个远程化点就再抄一遍——这是外壳漂移的直接原因，M8 必须先收成一个判定。

---

## 2. 问题清单

| # | 问题 | 影响 | 证据 |
|---|---|---|---|
| P-1 | 顶栏副标题恒为本地模型状态 | 用户以为在用手机模型，实际在电脑上跑 | Chrome:284-291 |
| P-2 | 点标题进 `ModelCenterActivity`，空首屏进 `ModelSelectorActivity` | 两个入口两套页面，语义不一 | Chrome:223 / 空首屏入口回调 |
| P-3 | `remote_dsh_bar` 被 hide | 每秒算出来的阶段/会话/审批全白算 | Chrome:133 |
| P-4 | 空首屏三入口全本地 | 远程用户第一步就点错 | EmptyState:96-100 |
| P-5 | 输入区本地 chip 未收起 | 点了"深度思考"对远程毫无作用 | Chrome:301-316 |
| P-6 | DSH 面板只挂甲板插槽 | 不点中 30dp 的行就发现不了 | `onDockSlotClick()` |
| P-7 | 远程判定散落 6 处 | 后续每加一处都容易漏 | `grep 'isConfigured(this)'` |

> **P-3 先验证再动手**：`ChatUiV2Chrome` 藏掉 `remote_dsh_bar` 的同时，`refreshRemoteDshBar()`
> 仍在每秒算阶段/跟随会话/待处理/RTT —— 这更像 v2 外壳重构的**遗留**，而不是有意取代。
> 落地时先花 10 分钟确认：临时注释掉 `hide(...)` 看远程状态条是否就是想要的样子；
> 是 → 最小修复 = 取消 hide，M8 的顶栏改造可以往后放；否 → 才按 §4.2 重做顶栏。

---

## 3. 目标与非目标

**目标**

1. 连上电脑时，对话页**三层壳全部按远程语义显示**；断开后**逐像素回退**到现状。
2. DSH 专用功能有一个**与插槽无关的常驻入口**。
3. 远程判定收敛为**一个方法**，新增远程化点只调用它。
4. 不新增轮询、不新增接口、不改消息管线、不新增 part 类型。

**非目标**

- 不改 `RemoteDshTool` / `RemoteConnection` 的语义与存储。
- 不改 `ChatAdapter` / parts / `DshProjection`（M9 只加事件归约，不碰渲染管线）。
- 不重做 `RemoteDshInsightActivity`（它是现成面板，M8 只给它加入口与首屏定位）。
- 不新增"远程专属"的第五套状态源。

---

## 4. M8 对话页外壳

### 4.1 单一判定（前置重构，约 30 行）

新建 `ai/chat/ui/ChatRemoteMode`：

```java
public final class ChatRemoteMode {
    /** 已配对 + 已连接：外壳按远程渲染 */
    public static boolean isActive(Context ctx);
    /** 已配对但未连接：顶栏给"重连"提示，其余按本地 */
    public static boolean isConfigured(Context ctx);
    /** 快照三元组：状态条 / live / 连接，取不到时返回 null 而非抛 */
    public static Snapshot snapshot(Context ctx);
}
```

- `isActive` = `RemoteDshTool.isConfigured && isConnected`，**把上面 6 处替换掉**（行为等价，纯抽取）。
- `snapshot` 只是把 `RemoteStatusMonitor.get()` / `RemoteLiveStatus.snapshot()` / `RemoteConnection.get(ctx)` 打包，**不新增缓存、不新增线程**。

判定分三态，避免"配对没连"把外壳整成远程：

| 态 | 条件 | 外壳 |
|---|---|---|
| 本地 | 未配对 | 完全现状 |
| 已配对未连 | configured && !connected | 顶栏副标题提示"电脑已断开 · 去重连"，其余本地 |
| 远程 | configured && connected | 全部远程语义 |

### 4.2 顶栏（M8-1）

**副标题**远程时改为两段拼接（现有一段空间，见 §9 决策 2）：

```
电脑 · <阶段>          ← 主段，复用 remotePhaseText(live)
跟随「<会话名>」        ← 次段，未选时"未选会话 · 点选"
```

- 阶段文案、时长、`· 待处理 N`、`· 落后 N` 全部复用 `remotePhaseText` / `buildRemoteBarSub` 的现有口径，**已抽出为静态方法**，不重写。
  （口径修正 2026-11-14：这个计数含反问，叫“待审批”是错的，状态条与甲板插槽已统一成“待处理”。）
- 状态点：`needsAttention`（待审批/排队）→ 黄；`conn.isOnline()` → 绿；否则灰。与状态条同一判据。
- 宽度仍走 `bindAdaptiveSubtitleWidth`，不新增测量逻辑。

**点击/长按**

| 手势 | 本地 | 远程 |
|---|---|---|
| 点标题 | `ModelSelectorActivity`（与空首屏统一，修 P-2） | 会话选择（见下） |
| 长按标题 | 无 | 打开 `RemoteDshInsightActivity`（**常驻入口，解决 P-6**） |
| 点副标题 | 同标题 | 同标题 |

**会话选择复用**：`RemoteDshConnectActivity.pickSession()`（`RemoteDshConnectActivity.java:373`）已是现成的"拉 `/v2/sessions` → 弹列表 → attach"实现。给它加一个 `EXTRA_PICK_SESSION=true`：`onCreate` 末尾若带该 extra 且已连接，直接调 `pickSession()`，弹完留在原页（或 `finish()`，见 §9 决策 3）。**不复制一份列表逻辑**。

### 4.3 空首屏（M8-2）

远程时把三个入口换成远程语义，**不删本地那三行**，用可见性切换：

| 远程入口 | 去向 | 复用 |
|---|---|---|
| 选择电脑会话 | `RemoteDshConnectActivity` + `EXTRA_PICK_SESSION` | §4.2 |
| 看电脑在跑什么 | `RemoteDshInsightActivity` | 现成面板 |
| 新建电脑会话 | 现有 `btn_new_conversation` 的远程分支（`new_session`） | 已有逻辑 |

「最近」列表：远程时改列 `/v2/sessions`（`RemoteDshTool.fetchSessionsLight`，20 条），点一条 = attach 并回聊天页；本地时仍列 `ConversationSession`。

实现方式：布局新增一组 `chat_v2_remote_entry_*` id，与现有三行**并列**而不是替换——这样本地路径零改动，回归风险最小。`ChatV2EmptyState` 增加 `setMode(boolean remote)`，只切两组根的 `GONE/VISIBLE`。

### 4.4 输入区（M8-3）

远程时：

- `chip_deep_think` / `chip_web_search` / `chip_local_agent` → `GONE`（它们是本地引擎能力，对电脑无效）。
- 在 chip 行原位置放一条只读提示：`发给电脑 · 跟随「<会话名>」`；未选会话时 `未选会话 · 点这里去选`（可点 → §4.2 会话选择）。
- **高度变化必须走 `onViewportChanged` 同一入口**（`00-overall.md` 硬约束 8）；chip 行与提示行同一容器、同高 28dp，理想情况高度不变，但代码上仍按约束走通知。

发送路径不动：`isActive` 为真时现有分支已把消息发给电脑（发送前的那处 `isConfigured && isConnected` 判定）。

### 4.5 刷新出口

现在 `refreshRemoteDshBar()` 是唯一汇聚点（`RemoteConnection.Listener` → 它 → 1s tick）。M8 不新增刷新源：

```
refreshRemoteDshBar()   ← 保留，只管状态条（仍被 hide）
  └─ RemoteChrome.apply(activity, usable)   ← 新增，统一写顶栏/空首屏/输入区
```

- 一次数据取用（`ChatRemoteMode.snapshot`），三处写入，**不各拉一次网络**。
- 节流：顶栏/输入区跟随 1s tick（纯内存文本）；空首屏只在 `bind` / 切会话 / 连接状态变化时刷。
- 未连接时 `RemoteChrome.apply` 直接走本地分支，与现状一致。

### 4.6 权限行/权限插槽的数据源（2026-11-14 追加）

权限这一行**不能只靠事件**。`sandbox/mode` / `approval/policy` / `permission/preset` 都是会话事件，
桌面端一重启、或换新会话，它们不会重放 —— 真机表现是「权限」那一行整行消失（今天已踩过）。

- 连上时先向宿主补读一次：`sandboxPolicy.overrideOf(session) ?? defaultMode`、
  `approval.overrideOf(session) ?? config.policy`、`permissionPresets.current(session)`；
- 改完权限、查询 `/v2/permission` 时同样补读（插件侧 `refreshPermissionFromHost(sid)` 已实现）；
- 外壳重做（M8）时保持这个兜底，别把“插槽 = 纯事件驱动”当前提。
- 另：这台 DSH 只有 `permissionPresets.set(session, name)` 一条写路径，沙箱模式没有独立 setter ——
  手机端改权限必须走「预设」，不要再列 sandbox/policy 两个独立开关（已按此改过）。

---

## 5. M9 事件接线（P1/P2）

### 5.1 现状差集

`DshTimeline` 归约 21 类（`turn/*`、`step/*`、`assistant/message`、`tool/call|result`、`approval*`、`question*`、`stream/*`、`session/title`、`plan/mode`、`subagent/activity`、`schedule/changed`）。插件转发 40+ 类，差集里按价值分三档：

| 档 | 事件 | 现状 | 建议落点 |
|---|---|---|---|
| **A 高频、用户能感知** | `todo/write` | 丢 | 待办卡（`TodoCardView` 已有） |
| | `command/run`、`command/done` | 丢 | 命令卡（带退出码/耗时） |
| | `job/registered`、`job/settled` | 只进插槽 | 插槽 + 卡片角标（完成态留痕） |
| | `goal/change` | 只进插槽 | 目标卡 |
| | `deliverables/presented` | 丢 | 产物卡（`FileCardView`） |
| | `workspace/changes` | 丢 | 变更卡（文件数 + 首个路径） |
| **B 状态类** | `permission/preset`、`sandbox/mode`、`approval/policy` | 只进插槽 | 权限行（抽屉五行已有位） |
| | `agent-preset/selected`、`team/mode`、`plan/mode`(已有) | 部分丢 | 状态行 |
| **C 暂不接** | `team/task`、`tool-workflow/*`、`tool/ptc-dispatch*`、`compaction/*` | 丢 | 先只记日志，不进时间线（噪声大） |

### 5.2 约束（沿用 `00-overall.md` 硬约束）

- **不新增 part 类型**（5 个必改点，漏一个整条消息回落 legacy）。A 档走 `ComponentData` → `DataPartRenderer` → `DynamicCardView` 兜底那条现成链路。
- 组件 id 以**所属消息 turnId** 为前缀（B6/B14 的老坑）。
- props 指纹要**按 key 排序**再算（否则服务端换序就多一次重绑、闪一下）。
- 未知事件仍按"丢弃 + 打点"走，不静默。

### 5.3 顺序

先 A 档 6 类（一个 PR 一类，各自带 fixture 单测），B 档并进 `M8-4.4` 的抽屉/顶栏状态行，C 档只补 `EVENT_CATALOG` 注释与日志分组。

---

## 6. 改动面

| 文件 | 改动 | 量级 |
|---|---|---|
| `ai/chat/ui/ChatRemoteMode.java` | 新建（三态判定 + snapshot 打包） | +60 行 |
| `ai/chat/ui/ChatUiV2Chrome.java` | 顶栏远程分支、长按、点击改向、`updateSubtitle` 支持两段 | ~80 行 |
| `ai/chat/ui/ChatV2EmptyState.java` | `setMode(remote)` + 远程三条目 + 远程最近 | ~70 行 |
| `ai/chat/ui/RemoteChrome.java` | 新建（统一写三处）或并入 Chrome | +50 行 |
| `res/layout/view_chat_appbar_v2.xml` | 副标题第二段 | 小 |
| `res/layout/view_chat_empty_v2.xml` | 远程入口组 | ~40 行 |
| `AIChatActivity.java` | 6 处判定改调 `ChatRemoteMode`；`refreshRemoteDshBar` 末尾加一次 `RemoteChrome.apply`；chips 显隐 | ~40 行 |
| `RemoteDshConnectActivity.java` | `EXTRA_PICK_SESSION` 直达会话选择 | ~15 行 |
| `strings.xml` | 远程文案若干 | 若干 |

**不改**：`ChatAdapter`、`parts/*`、`DshProjection`、`DshTimeline`（M9 才动）、插件 `index.js`。

---

## 7. 验收

| # | 用例 | 期望 |
|---|---|---|
| 1 | 未配对打开对话页 | 与当前版本逐项一致（本地模型副标题、三入口、chips 全在） |
| 2 | 已配对未连 | 顶栏副标题「电脑已断开 · 去重连」，其余本地 |
| 3 | 已连接、有跟随会话 | 副标题「电脑 · 生成中 0:12｜跟随「xxx」」；点标题进会话选择；长按进洞察 |
| 4 | 已连接、未选会话 | 副标题「电脑 · 空闲｜未选会话 · 点选」 |
| 5 | 远程时空首屏 | 三条远程入口可点且到位；「最近」是电脑端会话 |
| 6 | 远程时输入区 | 三个本地 chip 不可见；提示行显示跟随会话 |
| 7 | 连接中断开 | 1 个 tick（≤1s）内回落到本地口径，无残留"生成中" |
| 8 | 甲板显隐 | 聊天区贴底不裁切（`onViewportChanged` 已走） |
| 9 | 单测 | `ChatRemoteModeTest`（三态）、`RemoteSubtitleTest`（拼接与截断）、`ChatV2EmptyStateModeTest`（可见性切换） |

---

## 8. 分步与回滚

| 步 | 内容 | 可独立验收 | 回滚 |
|---|---|---|---|
| P0-1 | `ChatRemoteMode` + 6 处替换 | 行为等价，全量回归 | 纯抽取，revert 无副作用 |
| P0-2 | 顶栏副标题 + 状态点 + 长按洞察 | 用例 3/4/7 | 还原 `updateSubtitle` 调用 |
| P0-3 | 点标题改向 + `EXTRA_PICK_SESSION` | 用例 3 | 还原点击目标 |
| P0-4 | 空首屏远程组 | 用例 5 | 隐藏远程组即恢复 |
| P0-5 | 输入区 chips 显隐 + 提示行 | 用例 6/8 | 恢复可见 |

每步一个 commit，`feat(remote):` 前缀。P0-1 必须先合，否则后面三处又会各写一遍判定。

---

## 9. 待评审决策

1. **空首屏「最近」要不要在远程时被电脑会话替换？** 替换更连贯，但会盖掉"我手机上刚才聊的那条"。备选：两组都留，标题分别为「电脑上最近」「手机上最近」。
2. **顶栏副标题只够一段**：优先「阶段」（能看出电脑在忙什么）还是「会话名」（能看出在跟哪条对话）？本文倾向阶段 + 次段会话名，窄屏可能要截断其中一个。
3. **点标题后弹完会话选择**：留在连接页（可以顺便看链路状态）还是 `finish()` 回聊天页（少一跳）？本文倾向回聊天页，连接页由长按/抽屉进。
4. **输入区要不要保留一个「切换电脑会话」chip**？保留 = 多一个常驻入口；不保留 = 只靠顶栏，界面更干净。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 三态判定抖动（连接状态短时 false） | 断开要持续 >3s 才切成"已断开"文案，避免一帧闪回本地 |
| 顶栏文案比本地长，压到左右图标 | 复用 `bindAdaptiveSubtitleWidth`；两段各自 ellipsize |
| 空首屏多拉一次 `/v2/sessions` | 仅在远程且消息为 0 时拉，15s 内复用连接页缓存 |
| 远程组与本地组同时可见 | `setMode` 内互斥设置，单测钉死 |
| `refreshRemoteDshBar` 里做太多事（已是 90 行） | 只加**一行**调用 `RemoteChrome.apply`，逻辑在新类 |
| 外壳重做了，权限行仍然是空的 | 权限不止插槽：沿用 `refreshPermissionFromHost` 在连上/改完/查询时从宿主补读（§4.6）；外壳别假设“插槽 = 纯事件驱动” |
| 审批/反问既有对话框又有甲板卡，两套入口打架 | 对话框是打断式入口、甲板卡是兜底；状态只有一份（`msg.approvals` / `msg.questions`），两边都改这同一份，别各自存一份 |
| M9 加事件导致整条消息回落 legacy | 只写 `ComponentData`，不新增 part 类型；每类事件带 fixture 单测 |

---

## 附：与既有文档的关系

- `00-overall.md` §7 的 14 条硬约束继续生效，本文新增两条：**"远程判定只允许一个入口"**、**"外壳三处写入只允许一个出口"**。
- `10-modules.md` 的 M1–M7 不动；本文是 M8（外壳）/ M9（事件），M9 与 M6（消息转换）相邻但只加归约分支。
- `99-review-findings.md` 里已列的 B1–B17 仍未修的，本文不重复，M9 落地时按那份清单逐条对账。
