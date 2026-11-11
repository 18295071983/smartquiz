# SmartQuiz Remote 架构评估与重构设计

> 版本：评估稿 v1 · 2026-11-12
> 范围：`tools/dsh-plugin-smartquiz-remote`（电脑端宿主插件）+ `app` 的 `RemoteDsh*`（手机端）
> 结论一句话：**现在不是"双端"，是"一台电脑 + 一个远程遥控器"，而且遥控器和电脑各自维护了一份状态。** 要变成真正的双端，必须把"会话"的唯一事实源收到服务端，把"界面"降级成渲染器。

---

## 1. 现状：三份状态、三种模式、两套文案

### 1.1 数据流（as-is）

```
┌────────────── 手机（答题宝） ──────────────┐        ┌──────────── 电脑（DSH 宿主） ────────────┐
│ SharedPreferences                          │        │ index.js（2661 行）                      │
│  · session_id          ← 插件建的会话      │  HTTP  │  · handles Map（在管流）                 │
│  · dsh_session_id      ← 协商的直连会话    │ ─────► │  · resumedHandles Map                    │
│  · direct_code         ← 对话码            │  SSE   │  · local Map（兜底历史）                 │
│  · sync_target/title   ← 同步目标          │ ◄───── │  · sessions/*.zstd（磁盘，真源但没人读全）│
│  · remote_mode(auto/direct/sync)           │        │  · .devices.json / .acks.json / .token   │
│  · connected                               │        │  · 21 条路由，各写各的 JSON               │
└────────────────────────────────────────────┘        └──────────────────────────────────────────┘
        ▲                                                          ▲
        │ 自己拼文案、自己判分支                                    │ 自己拼文案、自己判分支
        └──────────── RemoteDshTool.java / ConnectActivity.java ───┘
```

**同一个"当前会话是哪条"这件事，手机存了 3 个键、电脑存了 3 个 Map、磁盘还有一份。** 出现不一致时，没有仲裁者。

### 1.2 三种模式（auto / direct / sync）本质是"三种产品"

| 模式 | 会话在哪 | 谁执行 | 工具集从哪来 | 手机看到的历史 |
|---|---|---|---|---|
| `sync` | 电脑桌面正在开的那条 | 宿主进程内 | 桌面预设 | `/events` 转发 |
| `direct`（in-process） | 插件新建（`smartquiz-*`） | 宿主进程内 | 插件 `presets.mount` | 插件自存 `local` Map |
| `direct`（CLI） | `dsh --profile headless` 子进程 | 子进程 | headless 预设 | 子进程 stdout |

同一条"发一句话"的请求，会走三条完全不同的代码路径、三套权限、三种 cwd 语义、三种失败方式。**用户看到的却是一个按钮。**

### 1.3 问题清单（按严重度）

| # | 级别 | 问题 | 证据 |
|---|---|---|---|
| 1 | P0 | **没有唯一事实源**。会话 id 由三端各自持有并可互相覆盖 | `KEY_SESSION` / `KEY_DSH_SESSION` / `handles` / `resumedHandles` / `local` |
| 2 | P0 | **模式即产品**。三个模式代码分叉，行为无法收敛 | `runCliTask` vs `runInProcessTurn` vs sync |
| 3 | P0 | **协议无契约**。字段名、错误码、分页、版本全靠约定；`sessions_count` 语义漂移就是后果 | 刚修的 0/110 事故 |
| 4 | P1 | **UI 逻辑在两端重复实现**。同一份状态，电脑端 `client.js` 拼一遍、手机端 Java 拼一遍、AI 工具再拼一遍 | `statusText()` vs Panel vs `handleStatus` |
| 5 | P1 | **UI 与工具层耦合**。页面直接 `new RemoteDshTool(c).httpJson(...)`，AI 动作与按钮走同一批私有方法 | `fetchHealth` / `handleHealth` |
| 6 | P1 | **能力不协商**。客户端不知道自己能干什么，服务端不知道客户端是谁；工具缺失表现为 `unknown tool` | 手机端曾整段没有 shell |
| 7 | P2 | **单文件上帝对象**。2661 行 / 2000 行级别，任何改动都在"全局变量 + 闭包"里游泳 | `index.js`、`RemoteDshTool.java` |
| 8 | P2 | **错误语义弱**。`spawn ENOENT` 既可能是程序缺失也可能是 cwd 不存在 | cwd 事故 |

> 结论：不是"哪里没写好"，是**分层缺失**。现在等于把 transport、session、presentation 三层压在一层里写。

---

## 2. 目标：一个源、一条流、一套视图

### 2.1 分层（to-be）

```
        ┌──────────────────────── Presentation ────────────────────────┐
        │  电脑端 React 面板     手机端 Activity      AI 工具卡           │
        │         └──────────────┬──────────────┘                       │
        │            同一份 ViewModel（服务端派生）                      │
        └────────────────────────┬─────────────────────────────────────┘
                                 │  GET /v2/view?session=…  (可缓存、可 diff)
        ┌────────────────────────┴─────────────────────────────────────┐
        │                    Session Service（唯一事实源）              │
        │  session = { id, title, cwd, execution, policy, seq, status } │
        │  timeline = append-only events（seq 单调）                     │
        │  ack = 每客户端游标（ack_seq）                                 │
        └────────────────────────┬─────────────────────────────────────┘
                                 │  WS/SSE: {v, seq, id, type, ts, data}
        ┌────────────────────────┴─────────────────────────────────────┐
        │  Execution（可插拔）：host-in-process | cli-subprocess        │
        │  只决定"在哪跑"，不改变会话身份                                │
        └──────────────────────────────────────────────────────────────┘
```

三个硬规则：

1. **会话只有一个 id**，由服务端签发；手机只保存 `(session_id, ack_seq)` 两个值。
2. **执行位置是会话属性**，不是"模式"。`execution.kind = host | cli`，切换它不产生新会话。
3. **两端 UI 不拼文案**，只渲染 `ViewModel.cards[]`；新增一个状态字段，只改服务端。

### 2.2 数据流（关键路径）

**发消息**
```
手机 POST /v2/message {session_id, client_msg_id, text}
  → 服务端幂等检查(client_msg_id)
  → 追加 user/message 事件(seq=n)
  → 触发 execution
  → 事件流广播（两端同时收到）
  → 手机收到 seq=n 后 ack
```

**断线重连**
```
手机 GET /v2/stream?session=&since=<ack_seq>
  → 服务端从 seq>since 重放
  → 无事件则挂起等待（长连接）
```

**对账（保留，但降级为兜底）**
```
手机 POST /v2/reconcile {session_id, ack_seq, digest}
  → {ok | replay_from: seq}
```
正常情况下永远用不到；它存在的意义是"网络谎报"时能自愈。

### 2.3 协议草案（v2 envelope）

```jsonc
{
  "v": 2,
  "seq": 248,              // 会话内单调递增
  "id": "evt_01H...",      // 幂等/去重
  "ts": 1794414000000,
  "type": "assistant/message",
  "data": { "text": "…", "tool": null }
}
```

握手：
```jsonc
// POST /v2/hello
{ "client": {"id":"phone-xxx","kind":"android","ver":"2.0"},
  "accept": ["v2"], "caps": ["stream","ack","tool-cards"] }
→ { "v":2, "server":{"ver":"0.4.0"}, "session":{...}, "features":["stream","ack","view-model"] }
```

**版本协商失败 → 明确报错，不静默降级。**

### 2.4 UI：服务端派生 ViewModel

```jsonc
GET /v2/view?session=session-xxx
{
  "v": 2,
  "header": { "title": "汽水音乐", "badge": "同步中", "subtitle": "预设 standard · 全权" },
  "cards": [
    { "kind": "status", "rows": [
        {"label":"电脑端会话","value":"110 条"},
        {"label":"插件在管","value":"0 条"},
        {"label":"执行位置","value":"宿主内（in-process）"}
    ]},
    { "kind": "checks", "items": [
        {"state":"ok","name":"host_cwd","detail":"C:\\…\\desktop"},
        {"state":"warn","name":"agent_preset","detail":"(unset)"}
    ]},
    { "kind": "timeline", "events": [ ... 或 "since": 240 ] }
  ]
}
```

好处：
- 电脑面板和手机页面渲染**同一份 cards**，不会再出现"同一个数字两种解释"；
- 文案、排序、状态色只在服务端定义一次；
- 手机端页面从"手写一堆 findViewById + 拼字符串"变成**一个通用 card renderer**（状态卡、检查卡、会话列表卡、时间线卡）。

### 2.5 双端 agent 交互怎么"更亲密"

现状是"电脑端迎合客户端"：手机要什么，电脑就加一条路由、加一段文案。反过来做：

1. **同一时间线**：手机和电脑看的是同一份 event log，只是渲染窗口不同。桌面端输入框和手机输入框写进同一个 `POST /v2/message`。
2. **工具卡是一等公民**：`tool/call`、`tool/result` 是事件类型，两端都渲染成卡片（不是"手机上只看到最终文字"）。
3. **审批是事件**：`approval/request` 进时间线，两端都能点，先到先得（现在是旁路 relay）。
4. **AI 工具 = 客户端**：`remote_dsh` 不再是"另一套实现"，而是内部复用同一个 Session Service 的一个 SDK 客户端。页面按钮调的是同一批 `/v2/*`。

这样"双端"才成立：**两个平等的客户端，一个权威的服务端**，而不是"主机 + 遥控器"。

---

## 3. 迁移路线（不推倒重来）

### 阶段 0（1 天，纯收口，无破坏）
- [x] `sessions_count` 语义修正（磁盘会话数 vs 插件在管）
- [x] `/health` 五项自检 + `severity`（error/warn/info）
- [ ] 字段字典：把 `/status`、`/health`、`/reconcile` 的字段写成一张表，两端只准用表里的名字
- [ ] 把 `statusText()`（Java）、Panel（JS）、`handleStatus` 三处文案收敛到**一处模板**（先放服务端，返回 `{lines:[]}`）

### 阶段 1（3-5 天，协议 v2 并存）
- 新增 `/v2/hello`、`/v2/message`、`/v2/stream`、`/v2/ack`、`/v2/view`
- 旧路由全部保留为适配层（内部调用 v2），客户端可灰度
- 事件 envelope 统一（seq/id/ts/type/data），`/events` 改为 v2 的兼容输出

### 阶段 2（1 周，模式收敛）
- `sync/direct` 降级为 `execution.kind` + `session.owner`
- CLI 子进程从"每条消息起一个进程"改成"会话级进程池"（1 会话 1 进程，空闲回收），消息走 stdin/stdout JSONL
- 手机端「远程连接」页改为通用 renderer：数据从 `/v2/view` 拿，页面只留"连接/自检/会话选择"三个动作

### 阶段 3（清理）
- 删除 `local` Map、`resumedHandles` 兜底、`sync_target` 双份存储
- `index.js` 拆包：`transport/`、`session/`、`execution/`、`view/`
- `RemoteDshTool.java` 拆：`RemoteApi`（HTTP/SSE）、`RemoteSession`（状态）、`RemoteUi`（渲染）

### 验收标准（可测）
| 指标 | 目标 |
|---|---|
| 状态源数量 | 1（服务端） |
| 断线重连丢消息 | 0（`since` 重放 + ack） |
| 两端同一会话显示差异 | 0（同一 view hash） |
| 新增一个状态字段的改动面 | 只改服务端 1 处 |
| 页面到数据的路径 | 页面不出现 `httpJson(...)` 字样 |

---

## 4. 现在最该先做的一件事

**阶段 0 的"字段字典 + 单一文案模板"。**

理由：它不需要动协议、不需要改数据库、两天内能落地，而且能立刻消灭"同一个数字两种说法"这类反复出现的低级事故（`sessions_count` 只是其中一个）。先让两端说同一种话，再谈同一份数据、同一套视图。

---

## 5. 实施进度（2026-11-12）

### 已落地

| 阶段 | 项目 | 状态 |
|---|---|---|
| 0 | 字段字典 `/v2/schema`（9 个字段，标注 owner/语义） | ✅ |
| 0 | 单一文案模板：`/status.text_lines`，手机端优先渲染，旧服务端自动退回本地拼 | ✅ |
| 0 | 自检单一实现 `buildHealthChecks()`：`/health` 与 `/v2/view` 共用 | ✅ |
| 0 | `sessions_count` 语义修正（磁盘会话数）+ `plugin_sessions` | ✅ |
| 1 | `/v2/hello` 握手 + 能力协商 | ✅ |
| 1 | `/v2/view` 服务端派生 ViewModel（status / checks / session / timeline 四类卡） | ✅ |
| 1 | `/v2/ack`、`/v2/reconcile`（与 legacy 同一 handler，无双实现） | ✅ |
| 1 | `/v2/stream` SSE：`since` 回放 + 1.5s 增量 + 保活 + 断开自停 | ✅ |
| 1 | `/v2/message`：复用 `runPrompt`，`x-dsh-protocol: 2` | ✅ |
| 1 | 两端渲染同一份 cards：电脑面板（React）+ 手机（`renderViewText` 单一实现） | ✅ |
| 2 | **会话身份权威化**：`/v2/session`（state/ensure/attach/list），`.sessions.json` 成为唯一绑定表 | ✅ |
| 2 | **客户端 SDK**：`RemoteSdk.java`（hello/view/session/attach/send/ack/reconcile/streamUrl），会话 id 只来自电脑 | ✅ |
| 2 | 客户端不再自己造 id：`sessionIdOf()` 统一取权威 id，`needs_first_message` 明确"首条消息才创建" | ✅ |
| 2 | **事件流客户端**：`RemoteStream.java`（SSE 读取、断连自停、超时自结束）+ `action=events` 按 since 增量拉取 | ✅ |
| 3 | **绑定存储收口**：`sessionBinding.get/set()` 统一读写 `.sessions.json`，`/v2/session` 与 legacy `direct_state/direct_bind` 共用 | ✅ |
| 3 | 绑定失效自清：绑定的会话不存在时自动解绑，客户端不会拿着死 id 续写 | ✅ |
| 3 | **消息来源归属**：`.origins.jsonl` 台账 + `/v2/view` 时间线 `origin=phone|desktop`，两端显示「你(手机)/你(电脑)」 | ✅ |
| 3 | **删除第三份历史缓存** `local`：history 只认"宿主内存活会话"或"落盘日志" | ✅ |
| 3 | **执行位置统一** `executionInfo()`：prompt/sync/session/status 用同一份 `{kind,profile,cwd,policy}` | ✅ |

### 未落地（下一阶段，按优先级）

| # | 项目 | 现状 | 阻塞点 |
|---|---|---|---|
| 1 | **热 agent 复用**（"进程池"的可行替代） | ✅ 已实现（`warmReuse`，默认开）：CLI 跑完后台 `agents.resume` 成宿主内句柄，下一轮直接宿主内执行；上限 4 条，失败自动退回 CLI | 待真机验证"第二轮确实不再起子进程" |
| 2 | **`/v2/message` 改为 v2 信封** | ✅ 已做：非流式响应包成 `{v,ok,session_id,reply,execution,error?,debug?,legacy}`；SSE 透传 | 旧字段保留在 `legacy` 兼容 |
| 3 | **审批进时间线** | ✅ 两端闭环：服务端 `/v2/view`「待你处理」卡 + `/v2/stream` `event: approval`；手机端落成 `ChatMessage.approvals` → `approval` part（允许/拒绝按钮 → `onApprovalDecision` → 回传电脑端，失败回滚成 pending） | — |
| 4 | **手机端通用 card renderer** | ✅ `RemoteCardView` 用于「远程连接 → 自检」；数据卡按 `ComponentData.round` 插进对话页对应步骤之后（不再全堆末尾） | 交错粒度到"步骤"，尚未细到"工具内部产物"（产物走 `attachOwnedProducts` 已挂在工具卡下） |
| 5 | **能力协商** | ✅ 客户端 `RemoteCaps`（14 项）随 hello/view/events 上送；服务端 `caps.js` 按 caps 裁剪卡片与审批事件，`/v2/view` 回显「能力协商」卡（客户端/未识别/缺少） | 反向命令（cmd.*）的 caps 门禁仅声明、尚未逐工具强制 |
| 5 | **物理拆包** | `index.js` ~3100 行、`RemoteDshTool.java` ~2500 行 | 纯工程整理，逻辑已分层 |
| 6 | **真·CLI 进程池** | ❌ 放弃 | `dsh headless` 是一次性 CLI（`--help` 明确 "answer one task, exit"），没有常驻协议 |

### 已完成但曾列为"未落地"的

- ✅ `sync/direct` 合并：服务端按"会话是否 live"自动决定宿主内 / CLI（`executionInfo(sid)`），客户端模式开关已删除（`setMode` 变 no-op，标签统一「自动」）
- ✅ `local` 历史缓存删除（history 只认活会话或落盘日志）
- ✅ 幂等：`client_msg_id` + 服务端 10 分钟去重
- ✅ 断线补发：非 live 会话 `/events` 改轮询跟随；客户端指数退避 + `since` 续传 + `cancelEventStreams()`

### 验收对照

| 指标 | 现状 |
|---|---|
| 状态源数量 | 会话绑定 1 份（`.sessions.json`）· 回执 1 份（`.acks.json`）· 来源 1 份（`.origins.jsonl`）· 历史 1 份（DSH 落盘/活会话）—— 各自的"唯一写入口"已明确 |
| 两端同一会话显示差异 | 0（同一 `/v2/view`） |
| 新增状态字段改动面 | 服务端 1 处（cards 构造） + 两个渲染器通用逻辑 |
| 页面到数据路径 | 手机页已不直接拼状态文案（走 `renderViewText`） |
| 消息来源 | phone / desktop 可区分（`.origins.jsonl` 启发式，10 分钟窗口） |

---

## 附：术语

- **会话（session）**：一次持续对话，有唯一 id、一条事件时间线、一个执行位置。
- **事件（event）**：时间线上不可变的一条记录，带单调 `seq` 和唯一 `id`。
- **游标（cursor / ack_seq）**：客户端已确认收到的最大 `seq`。
- **ViewModel**：服务端根据会话状态派生出的、给 UI 直接渲染的数据结构。
