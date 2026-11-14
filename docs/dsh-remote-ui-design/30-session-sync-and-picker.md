# 30 · 切换会话＝强制同步 + 选择框按工作区分组（2026-11-14）

> 用户口径：「手机上拉取对话历史应当是强行同步」「切换会话时怎么落盘，请求服务端下发历史吗」
> 「手机上的会话选项与电脑上不一致，电脑上只有 6 个对话，手机上一堆」「切换会话的模态框没有好好做，
> 把 UI 显示分组」「会话标题显示改成电脑上一致的，比如工作区分组」。

## 1. 结论先行

| 问题 | 做法 |
|---|---|
| 拉取/切换电脑会话 | **切换即强制同步**：切过去就向电脑要一次整条历史，用**锚点里的 `local_session_id` 整条覆盖**本地那条会话；标题用电脑的；传完把传输游标 + 渲染游标都推到末尾 |
| 怎么落盘 | `files/conversations/<anchor.local_session_id>.json`，整文件重写（不合并旧头部、不另开随机 uuid）。旧的 `remote_dsh/import_sid_<电脑会话>` 映射降级成兜底（升级前的数据） |
| 为什么手机上"一堆" | 插件把**磁盘上所有会话**都列出来了（32 条），而桌面端侧栏只列它自己 workspace 账本里的（`storages/workspace.json`，当时 2 条）。两处都改了：插件按账本给每行打 `desktop` 标记，手机**默认只显示这一批**，其余收进「显示全部会话 · 还有 N 条（电脑上没打开的）」 |
| **电脑上正在聊的那条被藏起来** | 第一版拿"宿主进程 cwd"当当前工作区，但桌面端当前会话可能开在别的工作区（实测：当前会话在 `D:\qzq\smartquiz`，宿主 cwd 是 `profiles\desktop`，它就被折进"其他工作区"）。现在 `current_session_id` / `current_workspace` 都以**桌面账本里最近活动的那条会话**为准，并在条目上打「电脑当前」 |
| 模态框 | 换成自定义分组列表：组头（工作区短名 + 全路径 + 条数）、两行式条目（标题 / 时间 · 最后一句）、「正在跟随 / 电脑当前」标记、「其他工作区 · 还有 N 条」折叠行 |
| 标题 | 本地会话标题 = 电脑端 session title（`ConversationSession.title` 已存在时不被首条消息覆盖） |
| **本地会话越用越短** | 不是"自动裁剪保证性能"，是 bug：`onStop` / 「新建对话」调的是 2 参数重载（未加载头部=0），每次离开聊天页就把会话文件截成内存里的 50 条。已改成带上 `sessionFrontSkipped`/`aiHistoryFrontSkipped` |

## 2. 链路（切换一条电脑会话）

```
点条目
  ├─ 本地：setSyncTarget / setSyncTitle      ← 会话身份仍以电脑为准
  ├─ 电脑：POST /v2/session action=attach bind=true（@code:follow）
  ├─ 强制同步：DshHistoryImporter.importSession(target, title)
  │     ├─ **ensure 锚点**：这条电脑会话在本机还没锚点 → 让电脑签发一条
  │     │    （POST /v2/anchor action=ensure，只建账、不建电脑会话）
  │     ├─ 拉全量事件（types 过滤，snapshot_threshold 放到 100 万）
  │     ├─ 事件 → ChatMessage（DshTimeline + DshProjection，与实时同源）
  │     ├─ localId = 锚点 local_session_id → 没有才用 import_sid_ → 再没有才随机
  │     ├─ saveConversationSession(整条覆盖)     ← 标题写电脑的
  │     └─ setTransportSeq / setLastSeq = 事件最大 seq   ← 不让通道再重放一遍
  ├─ 重开通道（老通道还挂在旧会话上）
  └─ startActivity(AIChatActivity, open_session_id=localId, REORDER_TO_FRONT|SINGLE_TOP)
        → 不叠新实例；onResume 里 switchToSession()
```

**为什么必须 ensure**：切到一条从没同步过的电脑会话时本地还没有锚点。先随机一个 uuid、
随后重开的通道握手又签发另一个 `local_session_id` —— 同一条电脑会话就裂成两条历史。
`ensure` 把"建账"提前到同步之前，两步拿到的是同一个 id（curl 实测：连续两次 ensure 返回同一个
`local_session_id`）。

## 3. A4：清历史 → 本地账 + 服务端账一起清

| 步 | 动作 | 为什么 |
|---|---|---|
| 1 | 记下 `remoteSidAtClear`（当前跟随的电脑会话） | 后面 `resetSyncState` 会把 `attached_session` 清掉 |
| 2 | `RemoteAnchorStore.clear()` + `RemoteDshTool.resetSyncState()` | 手机这半账 + 传输游标 |
| 3 | `POST /v2/anchor {action:'reset', device_id, session_id\|all:true}` | 电脑那半账**硬删**（不是软删进回收站）：下次握手 `anchorFor(create)` 重签一个新的 `local_session_id` |
| 4 | 重开通道 | 才拿得到新的 hello/anchor；不重连的话手机手里没锚点，下一次保存又退回随机 uuid |

**顺序要紧**：先 reset（HTTP 返回）再重连。反过来的话，新 `hello` 拿到的还是还没删掉的旧锚点。

## 4. 接口变化

| 端 | 变化 |
|---|---|
| 插件 | `/v2/anchor` 新增 `action=reset`：`{session_id}` 删这一条、`{all:true}` 删这台设备全部，返回 `{ok, action:'reset', scope, removed}`；没有 `session_id` 时只放行 `create` / `reset` |
| 插件 | `/v2/anchor` 新增 `action=ensure`：按（设备 × 会话）取或建锚点，返回 `{ok, action:'ensure', anchor}`（幂等，不新建电脑会话） |
| 插件 | `/sessions.json` 响应增加 `current_workspace`（宿主进程 cwd）与 `working_dir`（插件配置的建会话目录） |
| 手机 | `RemoteDshTool.fetchSessionsWithMeta()`（列表 + 元信息）、`deviceId()`、`resetAnchor()`；`fetchSessions()` 改成它的薄封装 |
| 手机 | `RemoteAnchorStore.ensureLocalSessionId()`（ensure 的客户端封装） |
| 手机 | `DshHistoryImporter.importSession()` 改为整条覆盖 + 锚点优先（ensure） + 游标推到末尾 |
| 手机 | `RemoteSessionGroups`（纯函数：分组 / 排序 / 工作区短名 / 毫秒解析）+ 7 条单测 |
| 手机 | 顶栏中间块点击：`bindCenter`/`bindCenterDefault` 返回是否真的绑上；没绑上时不清 `lastChromeKey`，下个 tick 重试（否则首帧未挂顶栏时会永远落在"默认=模型选择"） |

## 5. 为什么没走 `/ws/ctl` 的 `anchor` 帧

`28-anchor-ledger.md` 的原方案是"上报走 `/ws/ctl` 的 `anchor` 帧"。实现时改成 HTTP `action=reset`，理由：

- 隧道/免费网络下 WS 经常连不上，清历史**不能**因为通道不在线就失效；
- HTTP 幂等、可 `curl` 复验，排查成本低；
- 效果完全一样：让服务端忘掉这条锚点，下次握手重签。

`/ws/ctl` 的 `anchor` 帧留给"事件帧带 anchor"那一步再上（那时才需要按事件回报 `acked_seq`）。

## 6. 验证

| 项 | 结果 |
|---|---|
| 插件 reset（curl） | `create → reset(removed:1) → create` 拿到**新的** `local_session_id`；`reset all` 返回 `removed:1` |
| `/sessions.json` 元信息 | `current_workspace=C:\Users\…\profiles\desktop`，分组 28 / 4 |
| 单测 | `RemoteSessionGroupsTest` 7 条全绿 |
| 真机 | 见第 7 节（分组 UI / 切换同步 / 清历史重签） |

## 7. 真机验证记录（2026-11-14 17:36–17:45，2.2 debug）

选择框（2026-11-14 最终形态，顶栏中间块点开 / 连接页「切换当前电脑会话」）：

```
选择电脑对话
当前工作区 · qzq/smartquiz（2）
  D:\qzq\smartquiz
  移除引用并阅读交接文档  [电脑当前]
  项目 Prompt 组装方式
profiles/desktop（1） · 正在跟随
  修复 ask 功能仿审批实现  [正在跟随]    1 小时前 · …
显示全部会话 · 还有 29 条（电脑上没打开的）
关闭
```

- 头一行「（2）」= 桌面账本里的会话数，和电脑侧栏一致；
- 「正在跟随」那条即使不在桌面账本里也强制显示（否则用户看不到自己在跟哪条）；
- 其余 29 条（历史项目 / CLI 遗留 / 手机新建的空会话）收在「显示全部会话」里。

更早那轮的记录：

- 切到「项目 Prompt 组装方式」：`ensure` 给了 `local_session_id=851f6f44ca405e43`，
  本地写出同名会话文件（标题=电脑标题），跟随会话切过去，聊天页被拉回前台打开它；
- 再切回「修复 ask 功能仿审批实现」：**复用** `4872c3db9cc887ee.json`（同一文件被更新，
  没有新增文件），跟随会话切回来；
- 清空全部历史（17:40:26）：本地 2 条会话进回收站、锚点被 `reset`；重连后 hello 重签
  `local_session_id` 由 `94a27de2e65bac63` → `4872c3db9cc887ee`（A4 全链路跑通）。

## 8. 本地会话体积：现状（2026-11-14 查）

| 项 | 值 | 说明 |
|---|---|---|
| `MAX_SESSION_FILE_BYTES` | **64MB** | 只是"极端超大文件"兜底，真机从来没触发过（当前最大的本地会话 8.5MB） |
| `MIN_SESSION_MESSAGES` | 30 | 触发上面那条兜底时，至少保留 30 条 |
| `HISTORY_PAGE_SIZE` | 50 | 进会话只渲染最后一页；更早的按"加载更早消息"翻页 |
| 会话是否拆成多个文件 | **不拆** | 一条会话就是一个 JSON；"多个文件"是另外几套：单文件历史 `ai_chat_history.json`、按会话的引擎历史 `online_agent_history_<会话>_<模型>.json` / `local_chat_history_…`、回收站快照 |
| 那为什么文件只有 ~50 条 | 三个来源，都在 2026-11-14 修了 | ① `onStop` / 「新建对话」传了"未加载头部=0" → 每次退出把文件截成内存窗口（实测：电脑端 1 万+ 事件，本地只剩 51 条）；② 锚点绑定后页面手里只有 1~2 条也会覆盖整条会话（919KB → 11KB）；③ 即便传了非 0，那个计数**可能是旧的**（会话被切换/强制同步在背后重写过），一次保存把 322 条写成 54 条（11MB → 955KB）。最终修法：保存前按**消息 id** 与文件对齐 —— 文件里不在本次窗口里的消息全保留、与窗口重复的从截断处停住（日志 `按文件对齐后=N（防止截断/重复）`），且只在算出来更大时才覆盖调用方传的值。单测：`savingWithUnloadedHeadKeepsTheWholeSession`、`savingSmallerWindowDoesNotShrinkSession`、`staleHeadCountNeitherDropsNorDuplicatesMessages` |

真机复核（最终一轮）：强制同步后 `4872c3db` = 322 条 / 8.5MB → 退出一次 8,497,959 →
8,498,790 字节（条数不变），日志 `调用方头部=0，按文件对齐后=272`。

结论：**不需要调大 64MB**（本来就没碰到）；要修的是"保存时把头部丢了"这一类 bug。
真要把会话拆成多文件（按 8MB 分段），是另一个工程量：读侧/搜索/回收站/单测全要跟着改，暂不做。

## 9. 历史抽屉里也能切电脑会话（2026-11-14 收尾）

用户口径：「为什么历史抽屉不能多加一份。我还得到 dsh 远程界面切去」。

做法：聊天页历史抽屉最上面加一组 **「电脑上的对话 · N 条」**。

```
┌ 电脑上的对话 · 2 条 ─────────────┐
│ 🖥 移除引用并阅读交接文档   电脑当前 · 正在跟随 │
│ 🖥 项目 Prompt 组装方式      电脑 · 7 小时前    │
├ 今天 ─────────────────────────┤
│ 💬 修复 ask 功能仿审批实现   322 条            │
└───────────────────────────────┘
```

- 口径与选择框默认一致：**桌面账本里的会话 + 正在跟随那条**（跟随的即使不在账本里也要显示）；
- 点一条 = `RemoteSessionSwitcher`：写跟随 → 电脑绑 `@code:follow` → 重开通道 →
  **强制同步整条历史**（锚点 id、标题用电脑的）→ 进度框 → `switchToSession` 打开；
- 没连电脑时组头变成「电脑上的对话（未连接）」，点它直接进「远程连接（电脑）」页；
- 选择框与抽屉**共用一份执行逻辑**（`ai/tool/RemoteSessionSwitcher`），不再两套写法；
- 性能：拉这一组会读电脑端 100 条会话的头/尾，比较重 —— **只在打开抽屉时拉一次**，
  保存/切换这类被动刷新直接用上次结果（`refreshHistoryDrawer(withRemote)`）。

## 10. 历史里的 DSH 系统通知气泡（2026-11-14）

用户口径：「拉取消息同步消息时没有经过我的消息转换器。有些历史消息显示有问题。」

现状：历史导入其实**已经**走 `DshTimeline + DshProjection`（和实时同一套转换器），
但**入口过滤**不一致，导致"系统消息"被当成了"用户提问"：

| 路径 | 代码 | 规则 |
|---|---|---|
| 实时镜像 | `AIChatActivity.mirrorUserMessage` | `source.kind` 为空或 `user`；空文本跳过；10 分钟内同文本去重 |
| 历史导入（旧） | `DshHistoryImporter.buildMessages` | 见到 `user/message` 就当用户气泡 |

DSH 把后台任务通知（`background job pwsh-… finished [status: completed…]`）、重试回执、
系统提示都记成 `user/message`，所以同步出来的历史里全是这种"用户提问"，标题也会被取成任务名
（和之前那条 `background job pwsh-d4ad4c1b2b…` 是同一个根因）。

改动：

1. `DshHistoryImporter.isHumanUserMessage(JSONObject)`：历史与实时**共用一处判定**；
2. `buildMessages`：非人类消息不建气泡，也不当轮次边界；相邻重复用户消息只留一条；
3. `mirrorUserMessage` 改为调用同一个判定；
4. 落盘旧数据的清理：
   - `ChatMessage.isDshSystemNotice()`（`USER` + 以 `background job ` / `[background job` /
     `<task-notification` 开头）；
   - `ChatHistoryManager.stripDshNoticeRows()`：**加载会话时**就过滤（加载即正确）；
   - `ChatHistoryManager.cleanDshNoticeRowsOnce()`：启动时一次性删掉落盘的那批
     （prefs `dsh_notice_rows_cleaned_v1`，幂等）。

真机实测（修复前各会话里的这类气泡）：`1e0cf6e1` 14 条、`66106cac` 31 条、
`1fef1ee1` 12 条、`4872c3db` 0 条。

## 11. 遗留


1. **旧的随机 id 本地会话不会自动合并**：修复前产生的 `conversations/<uuid>.json` 仍在历史里，
   与锚点那条并存。要么手删（进回收站可恢复），要么以后再做一个"孤儿会话清理"。
2. **桌面账本的边界**：手机新建的空会话（`session-<hex>`，如 583f…）不在 `workspace.json` 里，
   桌面侧栏看不到、手机上也只能在「显示全部会话」里找到。要彻底一致，得让插件建会话时
   顺手把它登记进 workspace 账本（写 `storages/workspace.json` 风险较高，未做）。
3. **"最近活动"≈桌面当前会话**是启发式：多开/后台跑任务时，最新写入的可能不是桌面前台那条。
   真要精确，得让桌面端把"当前选中的会话"报给插件。
4. 事件帧仍不带 `anchor`；`acked_seq` 回传还没做。
5. 切换时的全量同步是**同步等待**（长会话几秒），进度框能看，但没有取消按钮；
   抽屉里切也一样（非阻塞，但用户要等这几秒）。
