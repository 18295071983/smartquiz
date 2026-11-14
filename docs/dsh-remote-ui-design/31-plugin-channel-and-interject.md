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

## 5. 参考：dsh 官方客户端 / dsh-Go 到底怎么做

### 5.1 dsh-Go 的三种发送方式（用户看到的那层）

来源：[dsh-Go 对话进阶技巧](https://blog.gitcode.com/4d7f8ac62d12311c1c7e4748802b8394.html)、
[dsh-go 使用说明](https://raw.githubusercontent.com/ccch1mneyyy/dsh-go/master/docs/guide/README.md)。

生成中**直接发送 = 插话**（不打断，AI 下一步就看到）。长按发送键给三个选项：

| 方式 | 效果 | 适用 |
|---|---|---|
| 💬 插话 | 不打断，AI **下一步**会看到 | 补充要求、纠偏方向 |
| ⚡ 中断并发送 | 停下当前任务再发 | 方向彻底错了 |
| ⏳ 排队 | 本轮结束后自动发送 | 不急、想等这条说完 |

输入框为空时发送键变停止键；停止后**排队的消息会自动开始下一轮**。
插话/排队的消息显示在对话底部，每条可「立即发送 / 改为插话 / 撤回」。

### 5.2 底层对应：DSH 的 Inbox 是**两条**队列

来源：DSH `dsh-session-guard` 的 README（npm）、以及从 `app.asar` 里抽出的
Agent 接口声明：

```
send(message, target: InboxTarget, wakeup): void;
followup(message): void;   // → next-turn
steer(message): void;      // → next-step
inject(message): void;
```

| 队列 | 含义 | 消费时机 |
|---|---|---|
| `next-step` | 「等下一个 step 边界的输入」 | 下一个 `agent/pre-step`：**同一次 turn 里再走一个 step** |
| `next-turn` | 「等独立回合的提示」 | 当前回合结束后，作为**新的 turn** 开跑 |

`Inbox.claim()` 永远先取光 `next-step`；开新回合时再额外取 1 条 `next-turn`。
所以：**steer = 插话（同 turn 下一步）**，**followup = 排队（下一 turn）**，打断 = `cancel()` 之后再 steer。

### 5.3 我们现在的对齐程度（2026-11-14）

| dsh-Go / DSH | 我们 | 状态 |
|---|---|---|
| 插话（steer → next-step） | 插件 `agent.steer(...)`：忙且宿主内有 live agent 时走这条 | ✅ 本轮新增 |
| 排队（followup → next-turn） | 没在跑时普通注入走 `followup`；CLI 子进程占着时进插件队列 | ✅ |
| 中断并发送 | 手机上点停止（`cancelGeneration`）再发 | ⚠️ 两条链路没合到一个按钮里 |
| 排队消息「立即发送 / 改为插话 / 撤回」 | 只有插件队列，没有单条操作 | ❌ 未做 |
| 停止后排队消息自动开下一轮 | CLI 队列在 `drainSessionInbox` 里会自动投递 | ✅（CLI 侧） |
| 手机端显示"已插话 / 排队中（第 N 位）" | 占位气泡改写 + toast | ✅ 本轮新增 |

**手机端默认档位**：`mode=auto` —— 忙且能 steer 就插话，否则排队/注入。这就是 dsh-Go 的
"生成中直接发送就是插话"那条默认行为，不需要手机自己判断忙不忙。

## 6. 还没做的（明确记下来）

1. **手机端长按发送键的三档菜单**（插话 / 中断并发送 / 排队）——现在是 auto + 停止键，没有菜单；
2. **插话/排队消息的单条管理**（立即发送 / 改为插话 / 撤回）——插件队列有，UI 没入口；
3. 插件**不改桌面端私有状态**：曾经试过把手机新建的会话登记进 `storages/workspace.json`
   （让电脑侧栏也能看到），已按用户要求回退 —— 插件只做转发，不写宿主的账本。
