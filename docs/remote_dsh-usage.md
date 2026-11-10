# 双端同步使用与运维说明

## 一、这套东西由哪几块组成

```
手机（答题宝）                                    电脑（DSH 桌面端）
 ├─ RemoteDshTool        HTTP 客户端 ────┐          ├─ 插件 smartquiz-remote
 ├─ 事件转换器（parts）                   │          │   ├─ /session  （会话/注入/审批/指令）
 ├─ 前台服务 RemoteSyncService  ─────────┼────────▶ │   ├─ /events   （原样转发 DSH 事件流）
 └─ AIChatActivity（渲染 + 3 秒补拉）     │          │   ├─ /sessions.json（会话列表）
                                         │          │   └─ /status   （面板状态）
                                         └─ 隧道 ──▶ 0.0.0.0:8218 ◀── DSH 宿主（127.0.0.1:<主端口>）
```

关键设计：**电脑只原样转发事件，适配全在手机**。DSH 新增事件类型时，插件不用改。

## 二、手机端怎么用

1. **配对**：工具集 → 远程连接（电脑）→ 扫码（电脑面板「手机远程」里点“打开配对页”）
2. **选会话**：远程连接页 → 「选择电脑会话（同步/接续用）」，列表每 3 秒自动对齐
3. **两种模式**（聊天页顶部状态条可切换）：
   - **同步模式**：消息发给电脑端**同一条会话**，电脑上能看到、手机能看到回复与工具卡
   - **直连模式**：电脑上有一条**专属于手机**的对话（默认一条；可设「对话码」分成多条），
     不打断你电脑上正在聊的那条
4. **审批**：电脑端弹审批时手机也会弹，**先点先赢**（另一端自动消失）
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

进程内投递时如果工具需要审批，手机和电脑两端都会弹（先点先赢）。

## 三、消息为什么不丢

- 电脑端每条事件带单调 `seq`
- 手机把「已渲染到哪」存成 `last_seq`（按会话）
- 回到聊天页时每 **3 秒**按 `last_seq` 补拉一次：
  - 会话在内存（live）：从内存读
  - 会话已不 live（App 被杀/DSH 重启）：**解压会话日志**重放
  - 缺口 > 5000 条：服务端退化成 `snapshot`（历史批 + 提示）
- 重复事件按 `turn/step` 覆盖式更新，不会重复插卡

## 四、后台与通知

- 切后台/锁屏：`RemoteSyncService`（前台服务）继续收事件，**只更新通知**（「电脑端 · 同步中」+ 最后一段回复）
- 服务**不能**反向推页面刷新（这是 Android 的进程/UI 约束），所以回页面靠 3 秒补拉
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

| 项 | 说明 |
|---|---|
| 逐字流 | 做不到：DSH 会话事件是 **step 粒度**，token 流不写日志（`assistant/attempt` 只有 block-start/end/finish） |
| `schedule/*` | 不产生会话事件（是控制器状态），手机端看不到日程变化 |
| `plan/*` | 同上，日志里没有此类事件 |
| `user-questions` | 手机只读提示，代答缺网关期望的答案结构样本 |
| 子代理面板 | 只有 `subagent/catalog`（一条登记卡），没有 PC 那样的实时活动面板 |

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
