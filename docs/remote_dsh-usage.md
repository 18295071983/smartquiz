# 双端同步使用与运维说明

## 一、这套东西由哪几块组成

```
手机（答题宝）                                    电脑（DSH 桌面端）
 ├─ RemoteDshTool        HTTP 客户端 ────┐          ├─ 插件 smartquiz-remote
 ├─ 事件转换器（parts）                   │          │   ├─ /session  （会话/注入/审批/指令）
 ├─ 前台服务 RemoteSyncService  ─────────┼────────▶ │   ├─ /events   （原样转发 DSH 事件流）
 └─ AIChatActivity（RemoteChannel 推送 + 渲染）     │          │   ├─ /sessions.json（会话列表）
                                         │          │   └─ /status   （面板状态）
                                         └─ 隧道 ──▶ 0.0.0.0:8218 ◀── DSH 宿主（127.0.0.1:<主端口>）
```

关键设计：**电脑只原样转发事件，适配全在手机**。DSH 新增事件类型时，插件不用改。

## 二、手机端怎么用

1. **配对**：工具集 → 远程连接（电脑）→ 扫码（电脑面板「手机远程」里点“打开配对页”）
2. **选会话**：远程连接页 → 「选择电脑会话」，选中后手机就跟随这条对话
3. **连接即遥控**（2026-11-12 起）：只要电脑是「已连接」，聊天消息就**直接发给电脑**，不再经过手机模型：
   - 已选「跟随会话」→ 消息写进电脑上**同一条对话**（电脑端实时可见，零冷启动）
   - 还没选 → 不新建会话，提示先去「远程连接（电脑）」选一条电脑对话
   - **断开电脑后**，才回到手机本地模型
   - 执行位置（宿主内 / CLI 子进程）由电脑按「会话是否在桌面打开」自动决定，手机不需要选模式
4. **审批**：电脑端发起审批时，手机对话页会在**时间线里插一张审批卡**（允许 / 拒绝），
   点完就地变成"已允许/已拒绝"并回传电脑端；电脑端已处理时卡片标「已过期」。
   先点先赢，另一端随之收掉。
5. **产物/待办/命令/子代理/工作流**：自动出卡片，不需要额外操作

### 直连模式为什么不会把电脑拖卡

直连每来一句都会在电脑上起一个**完整的 `dsh` 子进程**（实测冷启动 ~1.5s、峰值 ~256MB、
普通一轮 3~6s）。以前没有任何限流：手机等不到回复就重发、超时就断连接，子进程却各自
跑到 5 分钟上限 —— 连发几句就能堆 4~5 个宿主，电脑自然卡。现在（插件 0.3.1 / v7）：

| 机制 | 行为 | 可配置 |
|---|---|---|
| 单并发闸门 | 同一时刻只跑 1 条，其余排队 | `cliMaxConcurrent`（默认 1） |
| 队列上限 | 排队超过 3 条 → 直接回「正在执行上一条」 | `cliMaxQueued`（默认 3） |
| 超时跟随手机 | CLI 最多跑手机给的 `timeout`，到点中断 | 手机端 `timeout` 参数 |
| 断开即取消 | 手机断连后 3 秒杀**整棵**进程树 | 固定 3s 宽限 |
| 低优先级 | CLI 子进程 BelowNormal，桌面不被抢 CPU | 固定 |

面板「📱 手机远程」里能看到「直连执行：运行中 / 排队 N」，`/status` 对应 `cli` 字段
（`running` / `queued` / `max`）。**不要**为了"快"把 `cliMaxConcurrent` 调到 2 以上：
每个并发都是一整套 DSH 宿主，正是这次要修的卡顿来源。

### 直连对话码：两端认同同一个电脑会话

> 2026-11-12：手机界面已移除「直连对话 / 对话码（留空=默认）」入口（不再有“默认会话”），
> 连接时由电脑下发绑定关系。以下为协议层说明，AI 工具与旧客户端仍可用。

旧版手机自己生成一个 `smartquiz-xxx` 当会话 id，插件再让 CLI 写一条 `session-xxx` ——
两边不是同一个 id，映射一丢（超时、清配置、重装）就冒出一条新会话，电脑侧栏越攒越多。

现在**由电脑端决定、手机端回传**：

```text
手机 POST /session {action:'run', text, session_code?, target_session_id?}
  → 电脑：对话码绑定到某条 session-xxx（没有就新建），并把 dsh_session_id 回传
手机保存 dsh_session_id，下一条消息作为 target_session_id 带回去
手机还可以 POST {action:'direct_state', session_code} 随时对齐（重装后靠它接回）
```

| 你填的对话码 | 绑定到哪 | 场景 |
|---|---|---|
| 留空 | `.sessions.json` 里的 `@direct` | 一台电脑一条默认直连对话 |
| `work` / `工作` | `@code:work` | 不同主题各一条，互不干扰 |

- 手机「远程连接（电脑）」页新增 **直连对话**（显示当前绑定的会话标题/id）与
  **直连对话码**（输入 + 保存并同步）。
- 电脑端还没有这个码的会话时：**第一条消息由电脑端新建**（DSH 会话必须带工作目录，
  空闲时凭空建的不能被 CLI 续写），插件随即把 `dsh_session_id` 回传给手机并固定下来；
  手机不自己造 id，只保存/回传。
- 重装 App / 清配置后，填回同一个对话码就能接回原来那条电脑对话（会话 id 在电脑上）。
- 电脑端 `direct_bind` 可手动绑定/解绑（`target_session_id:''` 即解绑）。
- 旧数据不会丢：以前的 `smartquiz-*` 空壳会话已移到
  `~/.workbuddy/dsh-home/_quarantine/smartquiz-shells-20261110/`（可恢复）。

#### 电脑上正看着这条对话时

headless 的 one-shot CLI 续写会话有两个硬限制：会话头部带 `agentPreset`（桌面端建的
是 `standard`）会被拒绝；桌面端正打开着时又拿不到写权（单写者）。所以直连会自动分路：

| 目标会话状态 | 走哪条路 | 效果 |
|---|---|---|
| 宿主里有 live agent（你正看着/正在跑） | `ctx.agents.get` → 进程内投递 | 消息**实时出现在电脑那条对话里**，手机照常拿回复 |
| 头部带 `agentPreset`（桌面端建/被桌面端接管过） | `ctx.agents.resume` → 进程内投递 | 同上；用该会话自己的权限档位 |
| CLI 自己建的会话（无 agentPreset） | CLI 子进程 | 全权 `danger-full-access`，不起宿主内 agent |

进程内投递时如果工具需要审批，手机会在对话时间线里出审批卡（电脑端同时也有弹窗，先点先赢）。

## 三、消息是"一次请求、服务端推送"，不是轮询

- 电脑端每条事件带单调 `seq`；手机把「已应用到哪」存成 `last_seq`（按会话）；
- 连上后只发**一个**请求 `GET /events?follow=1&since=<last_seq>&caps=…`；
- 服务端立即下发 `state`（会话 id/标题/是否 live/执行位置/游标）和 `view`（与 `/v2/view` 同源），
  之后按 seq 增量推事件、每 5 秒推一次 `view`、10 秒心跳；
- 手机端 `RemoteChannel` 负责断线指数退避 + 带 `since` 续传 + 每 5 秒 `ack`；
  页面只做 `dispatch → 渲染 → 推进 since`，不再自己算"该拉哪一段"；
- 会话在内存（live）从内存读；已不 live（App 被杀/DSH 重启）由服务端解压会话日志重放；
  缺口 > 5000 条时服务端退化成 `snapshot`；
- 重复事件按 `turn/step` 覆盖式更新，不会重复插卡；
- 对账口径 = 游标：`/reconcile?light=1&last_seq=N` 回 `match|behind` + `gap`，不再比摘要。

**连接由前台服务持有**：`RemoteConnection`（单例）+ `RemoteSyncService`（前台服务）维护唯一一条通道；
页面只订阅/退订，切页/旋转/锁屏不会断。传输游标（重连用）和渲染游标（页面用）分开，后台收事件
不会把页面该补的那段吃掉；回到前台由页面按自己的 `last_seq` 补一次，然后再接实时事件。
用户点「断开」时才 `RemoteConnection.stop()` 并停掉服务。

**会话身份（两套绑定）**：
- **跟随会话**（`attached_session` / 电脑 `@code:follow`）：聊天通道、续写、事件流、对账都跟它；
  连接页「切换当前电脑会话」只改这个；
- **直连会话**（`direct_session` / 电脑 `@direct`）：`run` 直连执行用，手机专属；
  首次直连时由电脑新建并回绑，不会落进你正在跟随的桌面会话；
- 旧 `dsh_session_id` / `sync_target` 会自动迁移，但选择会话不再覆盖 `@direct`；
- 连接页分别显示「跟随会话」「直连会话」，没有会话时显示「首次直连时建立 / 首次发送时建立」。

## 四、后台与通知

- 切后台/锁屏：`RemoteSyncService`（前台服务）继续收事件，**只更新通知**（「电脑端 · 同步中」+ 最后一段回复）
- 页面回到前台时由 `RemoteChannel` 用 `last_seq` 续传补齐（服务端按 seq 重放），不靠页面轮询
- 点通知直接回到对话

## 五、反向指令（电脑 → 手机）

电脑端可让手机执行纯 UI/轻状态工具：

```
POST /session {"action":"enqueue_cmd","tool":"ui_component","args":{...}}
```

白名单只有：`ui_component`、`ui_component_plugin`、`task`、`reminder`。
手机 4 秒轮询执行并回执；面板里能看到队列与最近命令。

## 六、故障排查

| 现象 | 排查 |
|---|---|
| 手机不同步 | 面板看 `events`/`approval_relay` 是否正常；“已配对设备”是否 ≥1 |
| 卡片显示「未命名工具」 | 工具名不在 `ToolMetaRegistry`（已补 34 个 DSH 工具名） |
| 审批点了没反应 | 面板 `approval_relay.gateway=false` 说明网关不可达；或已被另一端处理 |
| 消息重复 | `turn/end` 落盘前被杀会重放整轮（内容不丢） |
| 直连后电脑变卡 | 已修：单并发 + 断开杀进程树 + 低优先级。若仍卡，看 `/status` 的 `cli.running/queued` 是否为 1/0；`>1` 说明配置里 `cliMaxConcurrent` 被调大了 |
| 直连回「电脑端正在执行上一条消息」 | 队列满了（默认 3）。等当前这轮结束再发，或调大 `cliMaxQueued` |
| 电脑上正看着直连对话，手机发不进去 | 已修：目标会话有 live agent / 带 `agentPreset` 时自动改走宿主进程内投递（消息会实时出现在电脑那条对话里）；`/status` 不显示该错误后仍失败，看 `/session {action:'direct_state'}` 返回的 `agent_preset` |
| Python 装包崩溃 | 已修：pip 加 `--no-build-isolation --only-binary :all:`；需要编译的包会直接报错 |

## 七、已知限制

> 2026-11-12 起，原来这张表里的五项都已补上（DSH 侧不需要改协议，插件在宿主内取数/转发）：
> 逐字流、日程、计划、反问代答、子代理面板。它们各自依赖的能力见“八、新增接口”。

| 项 | 现在的行为 | 边界 |
|---|---|---|
| 逐字流 | 宿主内 agent 通过 `agent/assistant-stream` 的 `text-delta`/`reasoning-delta` 实时推给手机（`/events` 的 `stream/delta` 合成事件） | CLI 子进程没有进程内流；会话不在电脑端打开时仍是 step 粒度 |
| 日程 | `/v2/schedule` 读 `ctx.schedule` 的目录/详情/投递历史，可「执行一次」「删除」；`schedule/changed` 会推给手机 | 新建/编辑时间规则仍建议在电脑面板做（手机只做管理动作） |
| 计划 | `plan/mode` 事件进时间线（状态卡）；`/v2/plan` 可读写 `ctx.planMode`（需要会话 live） | 会话不 live 时返回 `agent_not_live`，需先在电脑打开或让手机发一句 |
| 反问 | 问答卡进时间线：选项/多选/自由文本，整批提交到 `/v2/answer`（阻塞走网关 settle，超时后走 continued） | 需要电脑端 `user-questions` 网关可达（与审批同一套 relay） |
| 子代理面板 | `/v2/subagents` 列目录 + 每个子会话的最近活动/是否在跑；`subagent/activity` 事件实时更新对话里的子代理卡 | 外部后端（ACP/Codex）如果不在会话日志里留 catalog，只能看到登记过的那部分 |

## 八、常用接口速查

```bash
# 健康
GET  /health

# 会话列表（light=1 只返回轻量字段，面板轮询用）
GET  /sessions.json?limit=10[&light=1]

# 事件流（可续传）
GET  /events?session_id=…&since=<seq>&follow=1&timeout=600
     # 也支持 SSE 标准头 Last-Event-ID；缺口大时返回 {"type":"snapshot",…}

# 注入一句话（不等回复）
POST /session {"action":"inject","target_session_id":"…","text":"…"}

# 直连会话协商（对话码 → 电脑会话；target_session_id 传空串 = 解绑）
POST /session {"action":"direct_state","session_code":"work"}
POST /session {"action":"direct_bind","session_code":"work","target_session_id":"session-…"}
# run/prompt 响应里的 dsh_session_id 就是"这一轮实际写入的电脑会话"，手机存下来下次带回

# 审批状态 / 应答
POST /session {"action":"approval_state"}
POST /session {"action":"approval","approval_id":"…","decision":"allow|deny"}

# 反向指令
POST /session {"action":"enqueue_cmd","tool":"ui_component","args":{…}}
POST /session {"action":"cmd_poll"}   # 手机侧轮询
POST /session {"action":"cmd_ack","cmd_id":"…","ok":true,"result":"…"}
```
