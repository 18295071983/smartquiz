# 31 · 通道校验 + 插话排队（2026-11-14）

> 用户口径：「推送事件容易推送错会话，推送前你校验 id，而且有可能电脑上多个会话都在运行」
> 「你还没有适配手机插话排队功能」「cli 子进程模式运行久了容易故障。我需要发消息，你进入插话模式。
> 不要干扰电脑端的运行，电脑端容易故障，你仿造一下 dsh-go」

## 1. 推送前校验会话 id

**问题**：`streamBroadcast(sid, obj)` 原来只有一道判断 —— "订阅者的 sid ≠ 广播 sid 就跳过"。
但插槽类帧有些调用方把 `sid` 传成 `''`（本意"全局帧"），帧里却带着某条会话的数据
（`goal` / `compaction` / 自定义 slot）。结果：同一台手机上另一条对话也收到，页面把它画进当前对话。

**改法**（`streamBroadcast`）：

```
订阅者 sid 必须匹配：
  · 广播带 sid  → sub.sid === sid
  · 广播不带 sid 但帧里带 session_id（data.session_id 或顶层 session_id）→ sub.sid === 帧里的
  · 两者都没有（真正的全局帧：jobs / subagents / mcp / permission）→ 所有订阅者
```

多会话同时在跑时，各会话的帧只发给订阅了它的那一条连接；`/status` 里也能看到有哪些会话在跑
（`interject.busy`）。

## 2. 插话排队（interject）

**现状（改之前）**：
- 手机发消息走 `POST /session {action:'inject'}`：有 live agent 就 `agent.followup()`（DSH 会排进
  next-turn inbox），没有就 `ctx.agents.resume()`；**resume 撞上 CLI 子进程的写锁 → 409**，
  手机显示"发送失败"。
- 插件不知道"这条会话正在跑"，`followup` 排进 DSH 的 inbox 之后也没人告诉手机，
  表现就是"发了没反应"。
- 更糟的是手机 HTTP 超时后会**杀整棵 CLI 进程树**（`CLI_ABORT_GRACE_MS` 3 秒），
  正在跑的那一轮被打断 —— 电脑端容易因此进入故障态。

**改法**：

| 步 | 做什么 |
|---|---|
| 记账 | `SESSION_BUSY: sid → {since, kind}`。来源两处：① `ctx.on('session/event')` 的 `turn/start` / `turn/end`（宿主内与桌面端发起的轮次都覆盖）；② `runCliTask` 起进程 / 收尾时 mark / clear（CLI 子进程写日志时宿主不一定有事件） |
| 忙时 inject | 不再 resume、不碰正在跑的进程：消息进 `SESSION_INBOX[sid]`（每会话最多 20 条），立刻回 `{ok:true, queued:true, position, busy_kind, busy_since}`；手机端当前只认 `ok`，所以不会报错，消息照常在跑完后送达 |
| 排满 | 回 429 `inbox_full`，并给人话提示 |
| 投递 | 这一轮 `finish` → `clearSessionBusy(sid)` → `onSessionIdle` → `drainSessionInbox(sid)`：宿主内有 agent → 按顺序 `followup`（交给 DSH 的 inbox）；CLI 会话 → 起**一轮新的** CLI 送队首，跑完再触发下一次 drain |
| 观测 | `/status.interject = { busy:[{session_id, kind, since, ms}], queues:[{session_id, queued}], max }` |

**原则**：从不在"电脑正在跑"的时候抢写权或杀进程；手机这一侧永远先拿到"已受理"，
真正的送达由队列保证。

## 3. CLI 子进程：手机断开不再杀（已改）

`runCliTask` 的 `onAbort` 现在是**脱钩**而不是杀进程：

| 以前 | 现在 |
|---|---|
| 手机 HTTP 超时/切后台 → 3 秒后 `taskkill /T /F` 整棵进程树 → 电脑上这一轮被打断，DSH 侧留半截 turn | 只把"手机这一侧"结束掉（立刻返回 `detached:true`）；子进程**继续跑完**，结果照常写进会话日志，手机重连后按游标补拉；CLI 槽位仍由它占着，直到它自己退出才释放（不会并发跑两条） |
| 到点必杀 | 兜底上限 `DETACHED_MAX_MS = 10min`（或本轮预算，取大者），到点还没退出才杀 |

## 4. 手机端的"排队中"提示（已接）

插件回的 `queued/position/busy_kind` 现在会被 App 用起来（对照 dsh 官方客户端的 Inbox echo 语义）：

- `RemoteDshTool.injectDetailed()` 返回完整响应（原 `inject()` 保留，只取 `seq_before`）；
- `AIChatActivity.sendToFollowSession`：`queued=true` 时把占位气泡改成
  **「已插话排队（第 N 位）· 电脑上这条对话正在跑 / 电脑正在处理这一轮，跑完自动发送」** 并 toast 一次；
- 非排队（正常注入）路径完全不变；真机验证需要"电脑正忙时发消息"这个场景。

## 5. 参考：dsh 官方客户端是怎么做的

从 `app.asar` 里抽出 `@deepseek-ai/dsh-client-ui-chat/README.md`（`build/asar_tool.js` 可复现）：

> 「During uninterrupted following, local transcript and steering echoes remain mounted through
> **Inbox acceptance and claim** until the durable message arrives… Pending steering follows
> **Inbox order** across clients… Admitted local steering also suppresses matching stale Inbox rows
> until the claim projection arrives.」

也就是说官方客户端：**先把消息投进 Host 的 Inbox（本地先放 echo）→ Host 受理（admit）→ 持久化消息（claim）
到达后替换 echo**，排队顺序按 Inbox。我们这条链路对应关系：

| 官方 | 我们 |
|---|---|
| 投进 Inbox | 插件 `agent.followup({role:'user', source:{kind:'user'}})`（DSH 写 `agent/inbox/spliced` target=next-turn） |
| 本地 echo | 手机上的占位气泡（"正在电脑上执行…"） |
| 忙时排队 | 插件 `SESSION_INBOX` + `{queued:true, position}`；App 把气泡改成"已插话排队（第 N 位）" |
| claim 后替换 echo | `user/message` 事件到达后 `ensureMirrorBubble` 复用同一气泡 |

差异：CLI 子进程正在写这条会话时，Host 里没有 live agent（没法 followup），所以插件自己排队，
等子进程退出再起一轮投递 —— 这是官方客户端没有的形态（桌面端永远是宿主内执行）。

## 6. 还没做的（明确记下来）

1. **`dsh-go` 的插话细节**还没逐项对齐（它是在 CLI 进程 stdin 上做 steer，我们这里是
   DSH 的 `followup` inbox + 插件队列）。
2. 插件**不改桌面端私有状态**：曾经试过把手机新建的会话登记进 `storages/workspace.json`
   （让电脑侧栏也能看到），已按用户要求回退 —— 插件只做转发，不写宿主的账本。
