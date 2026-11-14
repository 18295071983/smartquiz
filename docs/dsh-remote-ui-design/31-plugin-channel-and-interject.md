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

## 3. 还没做的（明确记下来）

1. **CLI 子进程被手机端超时杀掉**：`runCliTask` 的 `onAbort` 仍是"3 秒收尾 + 杀进程树"。
   按用户要求应该改成"脱钩"（不再等，但让子进程自然跑完、结果留在会话日志里），
   这要把 `acquireCliSlot` 的槽位持有改成"子进程真退出才释放"，改动面较大，本轮没动 ——
   现在的缓解是：正常聊天走 `inject`（秒回），只有 `run`/流式 `prompt` 才会走到这条。
2. **`dsh-go` 的插话细节**还没逐项对齐（它是在 CLI 进程 stdin 上做 steer，我们这里是
   DSH 的 `followup` inbox + 插件队列）。
3. 插件**不改桌面端私有状态**：曾经试过把手机新建的会话登记进
   `storages/workspace.json`（让电脑侧栏也能看到），已按用户要求回退 —— 插件只做转发，
   不写宿主的账本。
