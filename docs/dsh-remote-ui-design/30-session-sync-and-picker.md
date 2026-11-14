# 30 · 切换会话＝强制同步 + 选择框按工作区分组（2026-11-14）

> 用户口径：「手机上拉取对话历史应当是强行同步」「切换会话时怎么落盘，请求服务端下发历史吗」
> 「手机上的会话选项与电脑上不一致，电脑上只有 6 个对话，手机上一堆」「切换会话的模态框没有好好做，
> 把 UI 显示分组」「会话标题显示改成电脑上一致的，比如工作区分组」。

## 1. 结论先行

| 问题 | 做法 |
|---|---|
| 拉取/切换电脑会话 | **切换即强制同步**：切过去就向电脑要一次整条历史，用**锚点里的 `local_session_id` 整条覆盖**本地那条会话；标题用电脑的；传完把传输游标 + 渲染游标都推到末尾 |
| 怎么落盘 | `files/conversations/<anchor.local_session_id>.json`，整文件重写（不合并旧头部、不另开随机 uuid）。旧的 `remote_dsh/import_sid_<电脑会话>` 映射降级成兜底（升级前的数据） |
| 为什么手机上"一堆" | 插件把**所有工作区**的会话都列出来了（28 条 `D:\qzq\smartquiz` + 4 条 DSH 配置目录）；桌面上看的是"当前工作区"。选择框改成按 `cwd` 分组、当前工作区置顶且默认只展开它 |
| **电脑上正在聊的那条被藏起来** | 第一版拿"宿主进程 cwd"当当前工作区，但桌面端当前会话可能开在别的工作区（实测：当前会话在 `D:\qzq\smartquiz`，宿主 cwd 是 `profiles\desktop`，它就被折进"其他工作区"）。现在 `current_session_id` / `current_workspace` 都以**最近活动的那条会话**为准，并在条目上打「电脑当前」 |
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

选择框（顶栏中间块点开 / 连接页「切换当前电脑会话」）：

```
选择电脑对话
当前工作区 · profiles/desktop（4）
  修复 ask 功能仿审批实现  [正在跟随]   32 分钟前 · 还有一份手机端页面改造的文档找出来
  Greeting                             2 小时前 · 你好
  你在干嘛                              6 小时前 · 坏了
  你是谁的询问
其他工作区 · 还有 28 条
关闭
```

- 切到「项目 Prompt 组装方式」：`ensure` 给了 `local_session_id=851f6f44ca405e43`，
  本地写出同名会话文件（标题=电脑标题），跟随会话切过去，聊天页被拉回前台打开它；
- 再切回「修复 ask 功能仿审批实现」：**复用** `4872c3db9cc887ee.json`（同一文件被更新，
  没有新增文件），跟随会话切回来；
- 清空全部历史（17:40:26）：本地 2 条会话进回收站、锚点被 `reset`；重连后 hello 重签
  `local_session_id` 由 `94a27de2e65bac63` → `4872c3db9cc887ee`（A4 全链路跑通）。

## 8. 本地会话体积：现状（2026-11-14 查）

| 项 | 值 | 说明 |
|---|---|---|
| `MAX_SESSION_FILE_BYTES` | **64MB** | 只是"极端超大文件"兜底，真机从来没触发过（当前最大的本地会话 0.9MB） |
| `MIN_SESSION_MESSAGES` | 30 | 触发上面那条兜底时，至少保留 30 条 |
| `HISTORY_PAGE_SIZE` | 50 | 进会话只渲染最后一页；更早的按"加载更早消息"翻页 |
| 会话是否拆成多个文件 | **不拆** | 一条会话就是一个 JSON；"多个文件"是另外几套：单文件历史 `ai_chat_history.json`、按会话的引擎历史 `online_agent_history_<会话>_<模型>.json` / `local_chat_history_…`、回收站快照 |
| 那为什么文件只有 ~50 条 | 两个来源，都在 2026-11-14 修了 | ① `onStop` / 「新建对话」传了"未加载头部=0" → 每次退出把文件截成内存窗口（真机实测：电脑端 1 万+ 事件，本地文件只剩 51 条）；② 锚点绑定后，页面手里只有 1~2 条也会按锚点 id 覆盖整条会话（实测 919KB → 11KB）。修法：两处保存点带上 `sessionFrontSkipped`/`aiHistoryFrontSkipped`；并在 `saveCurrentChatAsSession` 加**兜底护栏**——窗口比文件小时，多出来的部分一律当未加载头部保留（日志 `保留头部 N 条防止截断`）。单测：`savingWithUnloadedHeadKeepsTheWholeSession`、`savingSmallerWindowDoesNotShrinkSession` |

结论：**不需要调大 64MB**（本来就没碰到）；要修的是"保存时把头部丢了"这一类 bug。
真要把会话拆成多文件（按 8MB 分段），是另一个工程量：读侧/搜索/回收站/单测全要跟着改，暂不做。

## 9. 遗留

1. **旧的随机 id 本地会话不会自动合并**：修复前产生的 `conversations/<uuid>.json` 仍在历史里，
   与锚点那条并存。要么手删（进回收站可恢复），要么以后再做一个"孤儿会话清理"。
2. **条数还没逐条对齐桌面**：桌面侧栏可能是"最近 N 条"（用户看到 6），插件返回的是该工作区全部
   （当前工作区 4 条 / 另一个 28 条）。要严格一致得让插件读桌面端"最近列表"的口径。
3. **"最近活动"≈桌面当前会话**是启发式：多开/后台跑任务时，最新写入的可能不是桌面前台那条。
   真要精确，得让桌面端把"当前选中的会话"报给插件。
4. 事件帧仍不带 `anchor`；`acked_seq` 回传还没做。
5. 切换时的全量同步是**同步等待**（长会话几秒），进度框能看，但没有取消按钮。
