# 33 · dsh-Go 源码与对照（2026-11-14）

## 1. 源码位置

```
D:\qzq\dsh-Go\dsh-Go-main\      # 400.2 MB / 7,273 个文件（git 主分支快照）
```

来源：GitHub 直连两次被 reset，最终用镜像
`https://gh-proxy.com/https://github.com/ccch1mneyyy/dsh-Go/archive/refs/heads/main.tar.gz`
一次成功（347MB，2 分 37 秒，2.2MB/s）。codeload 不支持断点续传，`.tar.gz` 下完已删除。

目录：

| 目录 | 内容 |
|---|---|
| `app/` | Flutter 客户端（`lib/state/chat_state.dart` 是插话/排队状态机，84KB） |
| `runtime/bridge/dsh-go-bridge/` | **宿主侧插件**（已装进本机 DSH，见 31 号文档 §9 的鉴权结论） |
| `runtime/` 其余 | boot / direct / media / patches / provider-discovery / locks |
| `packages/` | `dsh_client`（Remote 协议客户端）、`dsh_remote`、`dsh_tavern`、`dartssh2` |
| `sdk/plugin-kit/` | 原生插件开发套件（含模板） |
| `docs/` | `design/native-plugins-{protocol,views}.md`、`plugins/{README,actions,inputs,slots}.md`、`guide/{zh,en}/01-chat.md` |
| `examples/plugins/` | 10 个示例插件（album/files/overlay/quick/readaloud/styles/toolbox/usage/weather/workbench） |

## 2. 插话/排队的真实实现（对照我们的）

dsh-Go 的做法（`app/lib/state/chat_state.dart`）：

- `PendingPrompt{requestId, text, mode: now|steer|queue, inboxId, slot: nextStep|nextTurn, resendable}`；
- 生成中直接发送 = `steer`；长按发送键可选 三种发送方式；
- 队列条目的三个操作，全部打在**宿主 Inbox** 上：

```dart
client.updateQueue(sessionId, inboxId, remove: true);   // 撤回
client.updateQueue(sessionId, inboxId, steer: true);    // 改为插话（立即生效）
// 还有 action.kind = edit（改文字）
```

DSH 宿主对应 API：`session/updateQueue { sessionId, itemId, action: edit|steer|remove }`，
错误码 `session/queue-item-not-found`、`session/steer-unavailable`（本机 app.asar 里已确认）。

我们（`smartquiz-remote` 插件）的一致性：

| dsh-Go | 我们 | 状态 |
|---|---|---|
| 撤回（remove） | 插件队列 `op=cancel` | ✅ |
| 修改（edit） | 插件队列 `op=update` | ✅ |
| 立即发送（升为 steer） | 插件队列 `op=send_now` → `agent.steer` | ✅ |
| 撤回内容回到输入框 | 输入框为空时回填 | ✅ |
| 排队改为插话（已排队的提级） | 插件队列没有 live agent 时无法 steer，返回 409 保留 | ⚠️ 语义受限 |
| 改宿主 Inbox 里的条目 | 未接 `session/updateQueue` | ❌ 见下 |

**差异原因**：dsh-Go 跑在同一个宿主进程里、以 owner 身份直连 Remote API；我们的插件虽然也在宿主里，
但走的是自建 HTTP 桥 + 自己的队列（因为 CLI 子进程占写权时宿主没有 live agent）。
要在插件里调 `session/updateQueue`，得先拿到宿主 owner 通道（`ctx.get('connection')` 只做鉴权，
不暴露队列写接口），属于另一条链路 —— 记在这里待评估。

## 3. 用户手册口径（`docs/guide/zh/01-chat.md`）

> AI 生成时直接发送，就是插话：不打断，AI 下一步会看到。长按发送键可以选发送方式：
> 插话 / 中断并发送 / 排队。插话和排队的消息显示在对话底部，可以立即发送、改为插话或撤回。
> **撤回的内容回到输入框，改完再发。** 输入框为空时，生成中的发送键变为停止键；
> 停止后，排队的消息会接着开始下一轮。

我们已按这条把「立即发送 / 修改 / 撤回 + 回填输入框」做齐（提交 `23b4ff75` 与后续 `send_now`）。
