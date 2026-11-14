# 34 · 组件管线审计与归并（2026-11-14 → 11-15）

> 用户口径：「看看还缺哪些消息类型，哪些没覆盖就新增消息类型、视图类型和组件管线。
> 我 app 还设计了内置组件、动态组件、工具组件 ui，你要调查好，把那些你自定义的组件管线能接的都接上。
> 不要造轮子，重复的 dsh 组件管线删了接到现有的组件上来，可以扩展功能；
> 有些事件是 dsh 独有的就单个依次创建原生组件，实在不好创建的用动态组件。」

本文由 2026-11-14 至 11-15 的多个并行任务逐节追加而成（旧 §1–§22）。2026-11-15 做了一次
**结构归并**：按主题重排为 §1–§8、编号连续；旧编号在新章节里以「旧 §x.y」标注，
完整映射见 §8.2。归并只动结构与重复表述，技术细节、真机数据、截图路径、阈值与提交号全部保留；
所有「没做到/降级」统一收进 §7，原章节只留一句「见 §7.x」。旧稿里被写坏的控制字符
（`\t`/`\x07`/`\r`/`\x08`/`\x0c` 混进代码片段）也一并还原成正常 token。

## 目录

| 章节 | 一句话摘要 |
|---|---|
| **§1 事件通道与帧** | WS/SSE 帧对齐、客户端周期重连、caps 能力协商与可观测性。 |
| **§2 事件分流** | 50–100 事件/秒下的批处理、快照兜底与「合并/丢弃/断」三级背压。 |
| **§3 消息转换与投影** | 投影预算、轮次合并 6 段、尾段增量投影与封口重建守卫。 |
| **§4 组件与卡片** | 37 张通用卡的合并史、动态兜底、语义令牌、校验器与动态卡三块能力。 |
| **§5 插槽甲板** | 瞬时状态的唯一落点：rev/TTL/epoch、档位表、16 条上限与快照拉取。 |
| **§6 消息流渲染与回收** | 在线 agent 的 parts 管线、RecyclerView 回收与 DSH 的消息粒度问题。 |
| **§7 已知边界与未做项** | 全部「没做到/降级」的汇总与理由。 |
| **§8 附录** | 时间线、提交号、旧编号映射、术语与计数口径。 |

## 1. 事件通道与帧：WS/SSE 对齐、caps、连接重试

> 本章解决什么问题：让 DSH 的事件与审批/重建/反问帧都能走 WS 长连接，断线后能自动回到推模式，
> 并让两端的能力声明在握手与 `/status` 里可观测 —— 不再出现「WS 只有合成帧」「插件一重载就永远 SSE」
> 「手机不知道插件缺什么」。

### 1.1 病历：WS 上为什么只有合成帧（旧 §16.1）

真机日志（01:36:30 / 01:37:12，`adb logcat -s DshProbe`）里，`RemoteConnection` 报
「WS 已连接」，但手机只收到 `ui/card`、`ui/slot` 这类合成帧，**一条真实 `event` 都没有**。
对照代码，是三处缺口叠加：

| # | 位置（改动前） | 问题 |
|---|---|---|
| ① | `sendEventFrame()`（SSE `GET /events`） | 只 `sse(out)` 直写响应，**从不进 `STREAM_HUB`** → WS 订阅者根本收不到事件 |
| ② | `CHANNELS['/ws/events'].out` | 只有 `event/pulse/ui/card/ui/slot`；`__force_resync`、`approval`、`question` 及 `*_gone` 到 `handleWsConn` 的 `ch.out.has(o.type)` 全被丢 |
| ③ | 手机 `RemoteChannel.runSocketOnce()` | 只开 `/ws/events` + `/ws/ui`，**从未打开 `/ws/alert`**；审批/反问在 WS 链路上 100% 收不到 |

所以「WS 只下发合成帧」不是某一条过滤写错，而是**事件生产者只接了 SSE 一条线**，
WS 白名单又把仅剩的控制帧挡掉。`/ws/events` 的 `event` 和 `/ws/alert` 的审批帧
都只是「声明了、没人发/没人订」。

### 1.2 帧对齐：改了什么（旧 §16.2）

| 改动 | 说明 |
|---|---|
| 新增 `wsBroadcast(sid, frame)` | 只投 `transport === 'ws'` 的 `STREAM_HUB` 订阅者；SSE 仍由各自 handler 直写响应，**不会双份** |
| `ctx.on('session/event')` 最前处 | 所有早退之前发 `wsBroadcast(sid, { type:'event', event })`，与 SSE `sendEventFrame` 完全同构；caps 与端点白名单仍由 `sub.send` 过滤 |
| WS 订阅者加标记 | `sub = { sid, caps, transport:'ws', channel, send }`，供 `wsBroadcast` 区分 |
| `/ws/events` 白名单 | 追加 `__force_resync`、`approval`、`approval_gone`、`question`、`question_gone`（都是**既有帧类型**，未新增类型） |
| 不动的部分 | `since/head`、`anchor`、`pull/batch`、`ack`、`ui/card`/`ui/slot` 合成帧、`/ws` 全功能、`/ws/ui`、`/ws/file`、`file_*` 全部保持原样 |

`approval_gone` / `question_gone` 一并放行（否则对话框收不掉），属于既有帧、非新协议。

### 1.3 连接周期重试：退避与 SSE 切换（旧 §17.1–17.3）

**病历：WS 掉线后永远回不去。** `RemoteChannel.run()` 开头只试 **3 次** WS（每次之间
`sleep(800)`），任一失败就 `break` 进 SSE 主循环，之后再也不会尝试 WS。插件重载 / 网络抖动
把长连接踢掉后，手机就永久停在 SSE：

- 重载前 `/status` 的 `ws_clients=2`（`/ws/events` + `/ws/ui`）；
- 重载后变 `0`，手机只在 SSE 上收事件；只有杀进程重开 App 才回到 2。

§1.2 已把插件侧 `/ws/events` 白名单补齐；本节处理的遗留问题在客户端重连策略。

**退避参数**（`WsRetryPolicy`，纯 JVM）：

| 参数 | 值 |
|---|---|
| 首次重试 | 1000ms |
| 增长 | ×2：1s → 2s → 4s → 8s → 16s |
| 基准封顶 | 30000ms（第 6 次起恒为 30s） |
| 抖动 | ±20%（`base × [0.8, 1.2]`，封顶那档实际最大约 36s） |
| 放弃条件 | 无（`retryWindowMs=0` 周期重试永不放弃；非 0 仅作限流护栏） |
| 重置 | 握手成功即 `reset()`，下一次断线仍从 1s 起 |

`nextDelayMs(attempt)` 是纯函数（含抖动）；`noteFailure()` / `nextDelayMs()` / `reset()` /
`shouldRetry(elapsedMs)` 供调度与单测使用。

**与 SSE 的切换语义**：两种通道**保持互斥**（不再双通道重复投递），只把「放弃 WS」
改成「周期重试」：

1. **启动**：WS 监管线程与 SSE 主循环并行启动；WS 没连上时 SSE 照常兜底，事件不断。
2. **WS 握手成功**：置 `wsActive`、`onConnection(true, "WS 已连接")`、打
   `[ws] 已连接，停 SSE`；`cancelEventStreams()` + `closeFollowStreams()` 立刻掐断 follow SSE
   （只动主循环那条，导入历史/一次性补拉不受影响），1.5s 后再清扫一次，关掉
   「SSE 线程检查完标志、正要开流」的竞态窗口。
3. **WS 断开**（`onFailure` / `onClosed`）：`wsActive=false` 唤醒 SSE 主循环恢复兜底，
   同时按策略排下一次重连；握手成功过的连接先 `reset()`，所以首次退避仍从 1s 起。
4. **重试期间**：SSE 持续兜底；每次失败记录
   `[ws] 重试第 N 次，延迟 Xms（握手失败|连接断开）`。
5. **关闭通道**：`close()` 移除所有 `postDelayed`、取消 event/ui socket、翻代号掐流，
   连接线程不再留下泄漏的 socket。

不变的部分：`pull` 批、`ack`、`anchor`、文件帧（`file_begin/chunk/end`）、`ui/card` / `ui/slot`、
`confirmApplied` 的落屏游标语义全部未改；`AIChatActivity` 的批处理/背压参数未改。

### 1.4 真机验证：真实事件、断线恢复（旧 §16.3 + §17.4）

**A. Node 自测（真 WebSocket + 真 HTTP，假 ctx，随机端口，不碰运行中的 8218）**

`tools/dsh-plugin-smartquiz-remote/ws_frames.test.mjs`：当时 9 项全过（现文件 10 条，
§1.8 又补了 `/status` caps 一条）：

```text
node ws_frames.test.mjs
  ok  握手：/ws/events 的 hello 声明了 event / __force_resync / approval / question
  ok  合成帧保持现状：POST /v2/card → ui/card 仍到 /ws/events
  ok  真实事件：session/event → {type:event, event} 到 /ws/events
  ok  __force_resync：POST /v2/action action=resync → 帧到 /ws/events
  ok  approval / approval_gone：debug.approval 推帧，应答后收回
  ok  question：user-questions/request 回调 → 帧到 /ws/events
  ok  caps 过滤仍生效：只声明 events.stream 的连接收 event、不收 approval
  ok  端点白名单没被打穿：/ws/ui 收不到 event
  ok  pull 语义未动：/ws/events 发 pull 仍回 batch
```

**B. 真实宿主链路（本机 8218，插件重载后）**

- 另起真 WS 客户端订阅当前会话 `561ebba7-…`，实测收到**真实事件帧**：
  `job/settled(708) → tool/result(709) → step/end(710) → step/start(711) → delivery-accepted(712) → assistant/message(713) → tool/call(714)`，
  全部带 `seq`（客户端断档检测可用）；同一条连接也收到 `__force_resync`、
  `approval`、`ui/card`、`ui/slot`。
- 手机（`192.168.1.10:41193`，插件重载后重启 App 回到 WS）：

| 证据 | 内容 |
|---|---|
| `/status` | `ws_clients=2`，`/ws/events=1`、`/ws/ui=1` |
| 连接日志 | `RemoteConnection: [DSH] connection=true WS 已连接`，`sid=session-cb82ea23-…` |
| approval | `[DSH] 审批帧 id=test-d74556b9057a`；`[flow] 模式=推 批=2 事件=6` |
| 批处理 | 12 条并发 `ui/slot` → `[batch] n=5 归约=5 跳过=0 投影=0 耗时=1ms` |
| `__force_resync` | `[DSH] 服务端要求重建（__force_resync）→ 快照` → `快照重建完成 seq=14841` |

**C. 断线/恢复（不重启 App、不重启 DSH）**：手机上把 `base_url` 临时指向本机 TCP 代理
（19000 → 8218），杀掉代理模拟插件不可达、再恢复代理：

| 时刻 | 证据 |
|---|---|
| 01:59:28.709 | `[ws] 连接结束：WS 失败: Connection reset`，`ws_clients` 2 → 0 |
| 01:59:28.713 起 | `[ws] 重试第 1/2/3/4/5 次，延迟 848 / 2258 / 4063 / 8872 / 16266ms` |
| 02:00:01.293 | `[ws] 已连接，停 SSE`，`ws_clients` 0 → 2（全程未重启 App） |
| 之后 | `session_streams=0`（无 SSE 双通道）；`[flow] 模式=推`、`[batch] n=8` 正常 |

代理关闭后已把 `base_url` 恢复为 `http://192.168.1.2:8218` 并重启 App 复核 `ws_clients=2`。

回退验证（杀进程重启）：`ws_clients` 2 →（`force-stop`）0 →（重启 App）2；
日志 `[ws] 已连接，停 SSE`。

**D. 编译/装机**：`.\gradlew.bat --offline assembleDebug` → BUILD SUCCESSFUL；
`adb -s 192.168.1.10:41193 install -r` 成功（APK sha256 `81F9553D…9592F8`）。
`WsRetryPolicyTest` 8 项全绿（退避序列、30s 封顶、±20% 抖动范围、`reset` 后从头开始、
`noteFailure` 计数、默认永不放弃、可选窗口）。

### 1.5 能力协商：服务端契约（旧 §18.1）

`CAP_VERSION = 1`；`SERVER_CAPS` 共 **25** 项：

```text
view.cards.status / checks / connections / session / approval / timeline / schedule / subagents / plan,
parts.approval, parts.tool-cards, parts.data-cards, parts.stream, parts.questions,
events.stream, events.replay, events.ack,
schedule.read, schedule.write, plan.read, plan.write, questions.answer,
subagents.panel, cmd.ui, cmd.shell
```

**解析规则**（`normalizeCaps` / `allows`）：

| 规则 | 行为 |
|---|---|
| 输入形态 | 数组 / 逗号分隔字符串；`null`、空串、对象结构 → 空列表 |
| 规范化 | 每项 `trim`、去空、按首次出现去重 |
| 命名 | 点号命名，**大小写敏感**（`Parts.Approval` ≠ `parts.approval`） |
| legacy | 列表为空 = 未声明 → `allows` **全部放行**，`missing=[]`（不裁剪） |
| 不认识 | 不影响放行；单独进 `unknown`，供排障 |
| 版本 | `version` 只是回执里的服务端常量，**不参与任何判定** |

**帧 → cap 映射**（`eventAllowed`，在通道白名单之前执行）：

| 帧类型 | 需要的 cap | 说明 |
|---|---|---|
| `approval` / `approval_gone` / `question` / `question_gone` | `parts.approval` | 四个共用一组；`parts.questions` 当前**不是**独立门禁 |
| `stream/delta` / `stream/end` | `parts.stream` | 不声明就只等整段 `assistant/message` |
| `subagent/activity` | `subagents.panel` | |
| `plan/mode` | `plan.read` | |
| `schedule/changed` | `schedule.read` | |
| `__force_resync` | 无 | 控制帧，任何客户端都放行（设计如此，见 §7.1） |
| 其余（`event` 包装层、`ui/card`、`ui/slot`、`pulse`…） | 无 | 前向兼容：未登记类型不裁 |

`VIEW_CARD_CAP` 把 10 种 `card.kind` 映射到 9 个 `view.cards.*`（`caps` 卡复用 `view.cards.status`）；
`filterViewCards` 对未知 kind **不裁**。`/v2/schedule|plan|subagents|answer|action|card` 另有
`caps_denied` 硬门禁（`schedule.read/write`、`plan.read/write`、`subagents.panel`、
`questions.answer`、`cmd.ui`）。

### 1.6 能力协商：手机端声明与差距（旧 §18.2–18.3）

`src/main/java/com/oilquiz/app/ai/tool/RemoteCaps.java`：

```java
public static final int VERSION = 1;

public static final String[] ALL = {
        "view.cards.status", ... "cmd.ui", "cmd.shell",
};

/** 逗号分隔（HTTP query / hello body 都用这一种表示） */
public static String csv() {
    return String.join(",", ALL);
}
```

`RemoteDshTool.openLiveSocket`（WS，`/ws/events` 与 `/ws/ui` 都用它）：

```java
String url = wsBase + path + "?session_id="
        + java.net.URLEncoder.encode(sid == null ? "" : sid)
        + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv())
        + "&token=" + java.net.URLEncoder.encode(tool.getToken());
```

SSE（`RemoteDshTool.streamOnce`）：

```java
String url = tool.getBaseUrl() + "/events?session_id=" + ... + "&since=" + since
        + "&follow=" + ... + "&timeout=" + ...
        + "&snapshot_threshold=" + ...
        + "&caps=" + java.net.URLEncoder.encode(RemoteCaps.csv(), "UTF-8")
        + (types == null || types.isEmpty() ? "" : "&types=" + ...);
```

结论：

- **声明链路是齐的**：WS/SSE 两条链路都带同一个 `caps=` query；`/v2/events/pull`、
  `/v2/view`、`/v2/schedule`、`/v2/plan`、`/v2/subagents` 也带；`/v2/hello`、`/v2/answer`、
  `/v2/action`、`schedule`/`plan` 写操作在 JSON body 里带 `caps`。**没有** `X-Dsh-Caps`
  之类的 header。
- **声明内容是全集**：`RemoteCaps.ALL` 与 `SERVER_CAPS` 25 项同名同量（`RemoteCapsTest`
  与新增 `caps.test.mjs` 双向比对），**声明层零缺失**。
- **版本号没有上行**：`RemoteCaps.VERSION = 1` 只存在于本地；请求里只有 CSV，
  `client.ver="2.0"` 是 App 版本而不是 caps 协议版本。
- **hello 回执从未消费**：`RemoteSdk.hello()` 实现了但**全仓库无调用点**；WS 的
  `hello` 帧以前只取 `seq`/`anchor`，服务端 `caps` 块没人解析，也没有任何校验/日志。

**cap 层（25 项）**：手机端全部声明，故「缺失后果」一列描述的是**若未声明**会怎样，
用来确认每一项都真的有用；不是当前缺陷。

| cap 名 | 服务端是否需要 | 手机是否声明 | 缺失后果（若未声明） |
|---|---|---|---|
| `view.cards.status` | 是（卡片裁剪） | 是 | 状态卡被 `filterViewCards` 裁掉 |
| `view.cards.checks` | 是 | 是 | 自检卡不显示 |
| `view.cards.connections` | 是 | 是 | 连接呼吸卡不显示 |
| `view.cards.session` | 是 | 是 | 会话卡不显示 |
| `view.cards.approval` | 是 | 是 | 审批视图卡不显示（审批**帧**另由 `parts.approval` 管） |
| `view.cards.timeline` | 是 | 是 | 时间线卡不显示；hello `features.view_timeline=false` |
| `view.cards.schedule` | 是 | 是 | 日程卡不显示 |
| `view.cards.subagents` | 是 | 是 | 子代理卡不显示 |
| `view.cards.plan` | 是 | 是 | 计划卡不显示 |
| `parts.approval` | 是（帧门禁） | 是 | `approval`/`question`/`*_gone` 全被挡，审批卡只能靠更早的弹窗 |
| `parts.questions` | 声明（**无独立门禁**） | 是 | 当前不影响：反问实际由 `parts.approval` 放行 |
| `parts.tool-cards` | 声明（无门禁） | 是 | 当前无过滤；预留给以后工具卡开关 |
| `parts.data-cards` | 声明（无门禁） | 是 | 当前无过滤；预留 |
| `parts.stream` | 是（帧门禁） | 是 | `stream/delta`/`stream/end` 不下发，正文只能等整段 |
| `events.stream` | 声明（无门禁） | 是 | 当前无过滤；语义上表示「能消费事件流」 |
| `events.replay` | 声明（无门禁） | 是 | 当前无过滤；重放实际由 `seq/since` 决定 |
| `events.ack` | 声明（无门禁） | 是 | ack 接口不校验，缺了也能 ack |
| `schedule.read` | 是（403 门禁） | 是 | `/v2/schedule` 回 `caps_denied`，日程页空 |
| `schedule.write` | 是（403） | 是 | 建/改/跑日程 403 |
| `plan.read` | 是（403） | 是 | `/v2/plan` 403，计划卡不可用 |
| `plan.write` | 是（403） | 是 | 切换计划模式 403 |
| `questions.answer` | 是（403） | 是 | `/v2/answer` 403，反问点不了 |
| `subagents.panel` | 是（403 + 帧门禁） | 是 | `/v2/subagents` 403；`subagent/activity` 不下发 |
| `cmd.ui` | 是（403） | 是 | `/v2/action`、`/v2/card`、`phone_ui` 全部 403 |
| `cmd.shell` | 声明（无门禁） | 是 | 当前无过滤；预留给 shell 类命令 |

**协议/链路层（真正的缺口）**：

| # | 缺口 | 体检结论 | 缺失后果 |
|---|---|---|---|
| ① | caps 版本不上行 | 手机只发 CSV，`VERSION` 留在本地；服务端 `version` 是常量 | 两端协议版本漂移在握手里不可见 |
| ② | hello 回执不消费 | `RemoteSdk.hello()` 无调用点；WS hello 的 `caps` 从未解析 | 手机不知道 `missing`/`unknown`，插件也不知道手机缺什么 |
| ③ | 握手帧无 caps 摘要 | 改前：WS hello 只有 `features/limits/anchor`；SSE `ready/snapshot` 没有 `caps` | 手机没有 `[caps]` 日志，排障只能猜 |
| ④ | `/status` 无客户端视角 | 只有 `ws_clients`/`ws_channels`/`ws_stats` | 数得清连接数，看不出「哪条连接缺 `parts.approval`」 |
| ⑤ | 手机无只读 getter | `RemoteConnection` 没有 caps 摘要字段 | 设置页没有数据源 |
| ⑥ | `/ws/alert` 从未打开 | 手机只开 `/ws/events` + `/ws/ui`；审批/反问已并入 `/ws/events` 白名单 | 不是 caps 缺失；极端 slot 洪水时审批与 slot 共用一条 TCP（本次不改） |
| ⑦ | `parts.questions` 无独立门禁 | 反问与审批共用 `parts.approval` | 只声明 `parts.questions` 的客户端收不到反问（当前手机两者都声明） |
| ⑧ | `__force_resync` 无 caps 门禁 | 控制帧，任何客户端都收 | 设计口径：旧客户端也靠它重建；加门禁会卡死旧端（本次不改） |

### 1.7 能力协商：改法与回执字段（旧 §18.4–18.5）

**改法（只加，不改既有语义）**：

| 位置 | 改动 |
|---|---|
| `tools/.../caps.js` | 新增 `clientCaps(raw)`：`{version, legacy, declared[], declared_count, missing[], unknown[]}`。`summarize()`（`declared` 布尔 + `client[]`）**原样保留**，`/v2/hello` 兼容旧口径 |
| `tools/.../index.js` | ① `/status` 增加 `caps`：`{version, server_count, clients:[{session_id, transport, channel, version, legacy, declared[], declared_count, missing[], unknown[]}]}`（数据源 `STREAM_HUB`，每条 SSE/WS 连接一条）；② WS `hello` 帧带 `caps: clientCaps(caps)`；③ SSE `ready`/`snapshot` 帧也带 `caps`（两条链路同口径）；④ SSE 订阅者补 `transport:'sse'`/`channel:'/events'` 元信息（此前只有 WS 有） |
| `RemoteCaps.java` | **未改**：25 项本来就齐，不删不增 |
| `CapsSummary.java`（新增） | 纯 JVM 解析/格式化：兼容新 `clientCaps` 与旧 `summarize` 两种形状；`line()` 输出 `[caps] 声明=25 缺失=0 未知=0 version=1 来源=ws:hello`；`local()` 兜底时 `缺失=?`，不假装零缺失 |
| `RemoteDshTool.java` | `DshSocketListener` / `DshEventListener` 各加 `default onHello(JSONObject)`；WS `hello/ready`、SSE `ready/snapshot` 把原始帧交给上层 |
| `RemoteChannel.java` | 收到握手帧 → `CapsSummary.parse(frame.caps)` → `Log.i("DshProbe", line)` + 回调 `Listener.onCaps`；服务端没带摘要（旧插件）时用本地声明兜底，每个通道生命周期只打一次；新增 `lastCapsSummary()` |
| `RemoteConnection.java` | 存 `volatile CapsSummary lastCapsSummary`，新增只读 `lastCapsSummary()`（**不做 UI**） |

**声明格式** —— 客户端 → 服务端：`caps=cap1,cap2,…`（URL-encoded CSV）

- WS：`/ws/events?session_id=&caps=&token=`、`/ws/ui?…`（同一 URL 拼装函数）；
- SSE：`/events?session_id=&since=&follow=&caps=`；
- 其它：`/v2/events/pull`、`/v2/view`、`/v2/schedule`（GET catalog 与 POST 写）、`/v2/plan`、
  `/v2/subagents` 用 query；`/v2/hello`、`/v2/answer`、`/v2/action`、`phone_ui` 用 JSON body
  的 `caps` 字段。**无** `X-Dsh-Caps` header。

**服务端 → 客户端**（`/status` 的 `caps.clients[]`、WS `hello.caps`、SSE `ready/snapshot.caps`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `version` | int | caps 协议版本（当前 `CAP_VERSION=1`，服务端常量） |
| `legacy` | bool | true = 客户端没带 caps，服务端不裁剪 |
| `declared` | string[] | 客户端**已声明**的能力（不是布尔；旧 `summarize` 的布尔同名，已在新接口避开） |
| `declared_count` | int | `declared.length` |
| `missing` | string[] | 服务端有、客户端没声明的；legacy 恒为 `[]` |
| `unknown` | string[] | 客户端声明了但服务端不认识 |

`/status.caps.clients[]` 额外带 `session_id`、`transport`（`ws`/`sse`）、`channel`
（`/ws/events` `/ws/ui` `/events`）。手机端 `RemoteConnection.lastCapsSummary()` 与
`RemoteChannel.lastCapsSummary()` 返回同一份 `CapsSummary`。

### 1.8 能力协商：验证（旧 §18.6）

**A. 插件纯逻辑（新增 `caps.test.mjs`）**：11 项全过 —— 解析/去重/大小写、legacy 全放行、
`approval/question/*_gone` 要 `parts.approval`、缺 `parts.approval` 拒绝、
`__force_resync` 不受 caps 门禁、`stream/plan/schedule/subagents` 按各自 cap、
`clientCaps` 字段、`summarize` 旧形状不变、与 `RemoteCaps.ALL` 双向同名。

**B. 插件真 WebSocket + 真 HTTP（`ws_frames.test.mjs`，随机端口，不碰 8218）**：10 项全过，
新增两项断言：

```text
ok  握手：/ws/events 的 hello 声明了 event / __force_resync / approval / question
    （含 hello.caps：version=1、legacy=false、declared_count=3、missing 不含 parts.approval）
ok  /status 按连接列出 caps 摘要（declared/missing/unknown/version）
```

**C. 手机 JVM 单测**：

```text
CapsSummaryTest   tests=8 failures=0 errors=0
RemoteCapsTest    tests=3 failures=0 errors=0
BUILD SUCCESSFUL（exit 0；testDebugUnitTest UP-TO-DATE）
```

**D. 编译/装机**：`.\gradlew.bat --offline assembleDebug --console=plain` → `BUILD SUCCESSFUL`；
`adb -s 192.168.1.10:41193 install -r` → `Success`；
APK `答题宝-debug-2.2.apk`，901,561,190 字节，sha256
`B4BFA2587ADAFF4D50BABBF59E7E924C85ED9A262F8FA521FA9FA73B537A5587`；
设备 `versionName=2.2 versionCode=4 lastUpdateTime=2026-11-15 02:12:14`。

**E. 真机（新 APK，force-stop 后冷启动）**：

```text
11-15 02:12:27.377 RemoteConnection: [DSH] open sid=session-cb82ea23-… since=15006
11-15 02:12:27.391 DshProbe: [caps] 声明=25 缺失=? 未知=0 version=1 来源=local
11-15 02:12:27.413 RemoteConnection: [DSH] connection=true WS 已连接
11-15 02:12:27.413 DshProbe: [ws] 已连接，停 SSE
```

`缺失=?` 是因为**当前运行的仍是旧插件**（见 F/G）—— 手机端新代码正确走了「服务端没回摘要 →
本地兜底」，声明数 25 = `RemoteCaps.ALL` 全集。审批链路仍通：
`POST /v2/action {action:'debug.approval'}` 推 `test-df228e3ed6a4` 后，手机
`DshSvc: [approval] 发独立审批通知 id=test-df228e3ed6a4`（随后 `decision=allow` 回收）。

**F. `/status` 新字段**：真机 8218 上的插件**未重载**（见 G），所以它仍回旧结构
（`ws_clients=2`，无 `caps`）。用新代码 + 手机同款 25 项 caps 的探针（假 ctx、随机端口、
真 WS/HTTP）得到的等价 curl 片段：

```json
hello.caps = {"version":1,"legacy":false,"declared":[...25 项...],
              "declared_count":25,"missing":[],"unknown":[]}
/status.caps = {
  "version": 1, "server_count": 25,
  "clients": [{
    "session_id": "caps-probe-session", "transport": "ws", "channel": "/ws/events",
    "version": 1, "legacy": false,
    "declared": [...25 项...], "declared_count": 25, "missing": [], "unknown": []
  }]
}
```

**G. 插件重载**：`plugin_manager set_plugin target=include:smartquiz-remote enabled=false`
→ `application:"failed", error:"HMR transactions cannot be nested", changed:false`；
插件仍在运行（`/status` 正常、`ws_clients=2`），按约束**不重启 DSH**，新 `/status`/hello
要重启 DSH 后生效。

### 1.9 本章边界（见 §7.1）

手机侧真实 `event` 帧样本未取到、插件重载未能真机验证、`/ws/alert` 仍未打开、
`parts.questions` 无独立门禁、caps 版本不上行、重试调度在主线程等 —— 逐条理由与降级口径见 §7.1。

## 2. 事件分流：批处理 / 快照 / 背压

> 本章解决什么问题：工具连续调用时电脑端 50–100 事件/秒，旧链路每条事件都上主线程、
> 每条都投影、每条都 notify，主线程全花在重绑同一行上，用户看到「比电脑慢半拍、越用越延迟」。
> 本章按 DSH/dsh-Go 的「合并 → 丢弃 → 断」口径重做主线程消费模型。

### 2.1 病历与三件事（旧 §15 开头）

旧链路：

```text
OkHttp/SSE 回调 → runOnUiThread → 每条 reduce → 每条投影 → 每条 notifyItemChanged
```

主线程全花在重绑同一行上，队列越积越长。按 DSH/dsh-Go 的「合并 → 丢弃 → 断」口径重做，
分三件事：A 批处理、B 快照兜底、C 三级背压。

### 2.2 批处理（A）（旧 §15.1）

落点：`EventBatchQueue`（纯逻辑，线程安全有界队列）+ `AIChatActivity` 的帧循环。

| 参数 | 值 | 说明 |
|---|---|---|
| 帧间隔 | 16 ms | `Handler.postDelayed`，等价 60fps 节拍；队列空即停，不空转 |
| 单帧批大小 | ≤ 50 条 | 归约在帧内连续做，**不逐条投影** |
| 队列上限 | 2000 条 | 满了丢**最旧**的并计数（正在发生的不能丢） |
| ack 水位 | 批内最大 seq | **整批处理完**才 `confirmApplied`；逐条 ack 会在中途崩溃时丢事件 |
| 投影节流 | 80 ms（沿用） | 卡片/收尾/阶段变化强制投影，token 增量按 80ms |

- 网络回调只 `offer`；主线程 `drain(50)` → 连续 `DshTimeline.reduce` → **帧末只投影一次、
  只 notify 一次**；
- 顺序：FIFO 为主；同一批内如果双通道重叠/补发导致乱序，按 seq 升序稳定排序，无 seq 的
  合成帧（审批/问卡/ui）跟着它入队时的水位走；
- 兼容旧语义：分段（`splitLiveIntoSegments`）、投影预算、DSH id 桶、甲板插槽、审批弹窗
  全部保持原样；只是「谁在什么时候投影」从每条一次变成每帧一次。
- `onEvent` 里不再 `runOnUiThread`（原来每条事件一个主线程消息），批处理循环也不再
  空转（队列空就退出）。

### 2.3 快照兜底（B）（旧 §15.2）

**触发**（`EventFlowPolicy`，主判据是连续性，数量阈值只当辅助）：

- 收到服务端 `snapshot` 帧 / `__force_resync` → 无条件转快照；
- `seq` 不连续：**连续两次跳号**（真断了，不是单条被 caps 过滤或乱序补发）→ 转快照；
  单次跳号超过 **200 条**（可调常量 `SNAPSHOT_GAP`）→ 直接转快照；
- `state` 帧里 `last_seq - 本页已落屏` > 200 → 转快照（辅助判据）；
- 比水位更旧的 seq（乱序/补发）不算断档（时间线按 seq 幂等）。

**重建**：

- 请求走服务端已有路径 `GET /events?since=-1&follow=0&snapshot_threshold=50`（live 会话
  `since<0` 直接回 `snapshot{turns}`，非 live 走 log 分支），一次往返、读到第一帧就断开；
- 清空时间线/组件 id，水位对齐快照 seq，**丢掉队列里 seq ≤ 水位的旧事件**（`dropUpTo`），
  传输游标也一并跳到水位（`RemoteConnection.resetCursorTo`）；
- 拿不到快照 → 兜底：重置时间线 + **主动重连**（按已 ack 游标重放），不静默缺一段。

### 2.4 背压（C）：合并 / 丢弃 / 断（旧 §15.3）

对齐 dsh-Go 的三级，**没有「客户端慢就切成拉模式」这一档**：

1. **合并**：同类增量只保留最新（客户端侧就是队列 + 每帧≤50 + 帧末只投影一次；
   token 增量本来就 80ms 节流）；
2. **丢弃**：队列满 2000 条丢最旧、计数（`Batch.droppedBefore`），日志
   `[flow] 队列满丢弃最旧 N 条`；
3. **断**：累计丢弃 ≥ 500 条（`HARD_DROP_LIMIT`）或单批丢弃 ≥ 200 条
   （`HARD_DROP_BURST`）→ 清空积压 + 请求快照 + `RemoteConnection.forceReconnect()`
   主动重连，只按快照水位续传。

**为什么没采用「切拉模式」（原方案已按评审撤回）**：

- DSH/dsh-Go 的背压协议只有「合并 / 丢弃 / 断」三级（4 Hz/250ms 合并窗口、>32 KiB 丢
  整份、待发 >1 MiB 服务端 1013 断连），没有「客户端慢 → 改成轮询」这一档；
- 拉模式要客户端与插件同时改协议（`mode=notify` + `wake` 帧 + pull 循环），
  一旦半新半旧就是双份投递或静默丢事件；而且要掐流重开，延迟反而翻倍；
- 正确的解法是「追不上就丢旧的、必要时断线重建」，快照兜底已经覆盖了状态正确性。
  因此插件（`tools/dsh-plugin-smartquiz-remote/index.js`）本轮**未改**，
  `node --check` 通过、`src/main/assets/remote_dsh/plugin/index.js` 与源码仍字节一致。

### 2.5 日志与验收口径（旧 §15.4）

真机 `adb logcat -s DshProbe` 看三类行（1 秒最多一条汇总，不刷屏）：

```text
[flow] 模式=推 批=128 事件=2410 队列=36/2000 峰值=87 丢弃=0 本段丢弃=0 快照=0 断档=0 落后=12
[batch] n=23 归约=23 跳过=0 投影=0 耗时=2ms 队列剩=5
[flow] 转快照 reason=seq 断档 → 3021 queue=180 dropped=0
[flow] 快照重建 seq=3021 dropped=180
[flow] 队列满丢弃最旧 12 条（上限 2000，本段累计 12）
[flow] 背压过线：清空积压 40 条，转快照并重连（累计丢弃 512 条）
```

- 「模式」：`推` / `快照`（重建中）/ `重连`（硬线后）；
- 「批/事件」：累计帧数与事件数；「峰值」是本窗口队列峰值；「丢弃」=累计；
  「本段丢弃」=自上次快照/重连以来（硬线判据）；「断档」=策略判定过的断档次数；
  「落后」=服务端 seq − 本页已落屏；
- 顶部副标题只在有积压/重建时补一段（`· 推（积压 N）` / `· 重建中`），不弹 toast。

### 2.6 验证（旧 §15.5）

- 纯 JVM 单测（新增）：
  - `EventBatchQueueTest` 10：FIFO 顺序、乱序按 seq 排序、合成帧位置、单帧 50 上限、
    ack=批里最后一条、上限丢最旧并计数、`dropUpTo(快照水位)`、峰值深度；
  - `EventFlowPolicyTest` 14：连续性判定（连续/重复/小跳/连续两次跳/大跳 200）、
    快照后水位复位、`state` 落后判据、硬线（累计 500 / 单批 200）与阈值可注入；
  - `RemoteSubtitleTest` 5：副标题模式尾巴（新增 `flowLabel` 重载）。
- `.\gradlew.bat --offline assembleDebug`：BUILD SUCCESSFUL；
- 装机与真机日志见本轮交付报告（`adb -s 192.168.1.10:41193 install -r`）。

### 2.7 本章边界（见 §7.2）

快照只有最近 20 条 role/text、16ms 用 `postDelayed` 而非 `Choreographer`、队列按条数不按
字节、快照请求并发收敛、断线只做主动重连而非 1013 —— 见 §7.2。

## 3. 消息转换与投影：预算、轮次合并、尾段增量

> 本章解决什么问题：DSH 一个 turn 的原始数据被转换器整包塞进一条消息，落盘 10 MB、单条
> 150+ 个 part 视图，历史/流式都卡；本章用投影预算、轮次合并与尾段增量投影，把「每次重绑
> 重建整条消息」压到「只重建活动段」，并用守卫计数证明封口段重建为 0。

### 3.1 病历：一条消息塞进整个 turn（旧 §12.1 + §13.3）

用户口径：「还是要排查一下卡，再看看客户端的插件，消息转换器不行。」

真机会话实测：

```text
messages=93  file=10.4 MB
一条 AI 消息：content 2.6 KB + thinking 215 KB + 245 个 tool_call 组件
```

结论：**不是卡片画得不对，是转换器把一整个 turn 的原始数据全塞进一条消息**——
思考 215 KB 一条、245 张工具卡各带完整命令/输出，落盘 10 MB，加载解析把主线程拖住
（watchdog 抓到 8.9 秒）。DSH 自己的界面也不这么做：思考折叠、工具行只留摘要、详情按需展开。

消息流回收是按**消息**做的。DSH 一个 turn 有 51 步，转换器把它压成**一条消息**：

```text
1 条消息 = 51 段思考 + 51 段正文 + 245 张工具卡 ≈ 150+ 个 part 视图
```

150 个视图在一个 item 里，RecyclerView 无从回收；每次滚动重绑都是「重建一个巨型 item」。
在线 agent 为什么能用同一套回收配置还不卡，见 §6.3 的粒度对照。

### 3.2 投影预算（旧 §12.2）

`DshProjection` 对四个大字段截断，超限写「…（已截断，原文 N 字）」：

| 字段 | 上限 | 超限写法 |
|---|---|---|
| 思考 `thinkingContent` | 8000 字 | …（思考已截断，原文 N 字） |
| 工具参数 `args` | 400 字 | …（参数已截断，原文 N 字） |
| 工具输出 `result` | 800 字 | …（输出已截断，原文 N 字） |
| 官方 meta（diff/read 卡要用） | 4000 字 | 同上 |

截断标注带原始长度：用户能看出「这里被省了」，不是数据丢了；完整内容仍在电脑端会话日志。

### 3.3 轮次合并：MAX_ROUNDS_PER_MESSAGE = 6（旧 §13.4）

`DshProjection.mergeExtraRounds()`：超过 6 步的部分合并进最后一轮 ——
正文按原顺序拼接（内容不丢）、思考拼起来后按 8K 截断、工具 id 归到这一轮，
同时修正 `contentRoundBounds` / `contentRoundToolIds`。

效果：一条消息的 part 数从 150+ 降到 ≈ 13，回到在线 agent 的量级，
RecyclerView 的回收重新有效。最初留的「更彻底可以按 step 拆成多条消息」已在
2026-11-14 23:43 的 `7d322920` 落地：一个 turn 按 6 步切成多条消息，流式期即时分段，
`splitLiveIntoSegments()` 随后删除（见 §3.7）。

### 3.4 假事件卡一次性清理（旧 §12.3）

修掉兜底逻辑之前落盘的 31 张「电脑事件 · job/settled|ui/slot|compaction/*」卡，
用 `ChatHistoryManager.cleanDshUnknownCardsOnce()` 从会话文件里删掉（幂等，开机后台跑）。
实测：`清理假事件卡: 31 张`（会话文件 2 个）。

判据只删 `type=dynamic && props.type=unknown_event && event_type ∈ 纯状态/合成帧`，
真人可见的未知事件卡不动。

### 3.5 尾段增量投影：封口判据（旧 §19.1–19.2）

**病历：批很小，投影却是全量。** 事件分流（§2）落地后，`AIChatActivity` 每 16ms 一批
（≤50 条）连续 `DshTimeline.reduce`，帧末只调一次投影。但投影入口仍是：

```java
DshProjection.project(live, dshTimeline, key -> dshComponentId(key, live.turnId), now);
// 等价于 project(..., 0, Integer.MAX_VALUE)
```

一段 DSH 回合在手机上按 6 步一条消息派发（`T12-G2`），可**流式期间只有一条气泡**：
每批事件（真机 1–10 条）都重建整条消息的 `roundSnapshots / components / content`，
已经写完的段（例如 8 步回合的 G1）被反复重建。dsh-Go 的做法是「只重排尾段」：
行主键带起点、已完成行缓存不重建。本次把同一口径搬到手机端。

验收口径：**优化后「已完成段重建」必须为 0**。

**封口判据**（`SegmentSealPolicy`，纯 JVM）：一条回合按 6 步切段（与历史导入/
旧 `splitLiveIntoSegments` 同粒度）：

| 概念 | 规则 |
|---|---|
| 段数 | `ceil(总步数 / 6)`，0 步 = 0 段 |
| 第 k 段范围 | `[6k, 6k+6)`（最后一段由总步数截断） |
| 封口 | `段号 < 最后一段`，**或**该回合已经 `turn/end` |
| 活动段 | 流式期间**永远是最后一段** |

```java
public static boolean isSealed(int segmentIndex, int lastSegmentIndex, boolean hasActiveWork) {
    if (segmentIndex < 0) return true;
    if (segmentIndex < lastSegmentIndex) return true;
    return !hasActiveWork;
}
```

**为什么不用「同段但已无活动工具/流式标志」作为流式期的封口依据**：一步结束到下一步
开始之间工具确实都停了，但正文/思考随时可能再追加；按「无活动工具」封口会把最后一段
提前锁死，尾部文本再也进不去。所以流式期最后一段一律算活动段，只有「不再是最后一段」
或「回合结束」才封口 —— 这也让**纯尾部文本追加**天然不触发任何封口段失效。

### 3.6 段缓存与失效条件（旧 §19.3）

`SegmentProjectionCache`（纯 JVM）负责「哪些封口段可以跳过」：

- **缓存载体就是段消息本身**：封口段的 `ChatMessage` 不再调用 `project()`，实例、已绑定
  的 `roundSnapshots/components` 与 RecyclerView 视图都不动；只有活动段每帧重建。
- **段内容指纹** `Progress`：`steps / textChars / thinkingChars / runningTools / finishedTools / toolResultChars / cards / approvals`。
  `plan()` 每帧对每个封口段比一次指纹，相等就跳过（计入 `skippedSealed`）。
- **失效条件**（每条都有单测）：

| 条件 | 判定 | 结果 |
|---|---|---|
| 新增段 | 段数增加 / 新槽位未投影 | 只投影新段；旧段仅内容变了才收口 |
| 工具结果晚到 | 段内 `running/finished/resultChars` 变化 | 重建该封口段 |
| 卡片增删 | `roundOfCard` 落在该段的卡片数变化 | 重建该封口段 |
| 审批插入 | 该段审批计数变化（消息级字段） | 重建该段 |
| 权威正文晚到 | 段内 `textChars/thinkingChars` 变化 | 重建该段 |
| 空步补内容 | 段内 `steps` 变化 | 重建该段 |
| **纯尾部文本追加** | 只改最后一段 | **封口段不失效** |
| 跨段瞬间 | 段从活动变封口且内容有变 | 一次「收口」投影（单列，不算封口重建） |

### 3.7 Activity 接线（旧 §19.4）

- `ensureMirrorBubble()` 同回合返回**活动尾段**（审批/反问消息级状态写尾段），
  `dshMirrorMessage` 仍是本回合第 0 段（`mirrorUserMessage` 插用户消息的锚点要往前插）。
- 新回合/新会话/清空/快照：`resetDshSegments()` 清空段列表、段审批计数与守卫计数；
  第 0 段是 `ensureMirrorBubble` 建的原气泡，后续段在流式期按 6 步边界用
  `ChatIdDispatcher.nextDshSegmentId()`（`DSH_SEGMENT`，`T12-G1`）新建，组件 id 按
  `:s{段号}` 分桶（与旧拆分一致）。
- 删除了 `splitLiveIntoSegments()`：原来一轮结束才拆，现在流式期已经按同一粒度落屏，
  `turn/end` 再拆会重复插段。
- `projectDshTail()` 只对 `plan.rebuilds()` 调 `project(seg, timeline, ids, now, from, to)`；
  投影后按对象定位 `safeNotifyItemChanged()`（封口段重建不会走 `pushLiveSync`，那只刷尾段）。
- `pushLiveSync()` 不再用 `dshTimeline.answer()/thinking()` 覆盖正文：那是**整轮**文本，
  会把段消息写成全文；正文/思考只由按区间的投影写。
- **真机抓到的坑（turn 92 → 93）**：一帧里同时有上一轮 `turn/end` 和下一轮 `step/start`
  （重连补拉会这样），`ended` 是整批标志，直接拿来封当前时间线的口，会把新回合第一段
  误封成已完成段 → 下一帧 `重建=封口1`。修法：只有 `endedMessages` 包含当前尾段时
  才算本回合结束（`endedHere`），并把它用于投影与 `pushLiveSync`。

### 3.8 守卫计数口径（旧 §19.5）

投影入口（`projectDshTail` 的 `markProjected`）记账，一行到 `DshProbe`：

```text
[proj] 段=总/封口/活动 重建=封口N 活动M 耗时=Xms
```

| 字段 | 口径 |
|---|---|
| `段=总/封口/活动` | 当前时间线切出的段数 / 已封口段数 / 活动段数 |
| `重建=封口N` | **上一次投影决策时已封口**、本帧又调 `project()` 的次数；验收目标 = 0 |
| `活动M` | 活动段 + 新建段 + 封口瞬间「收口」投影（允许且必要） |
| `耗时=Xms` | 本帧所有 `project()` 调用的总耗时（单帧只 1–2 次） |
| 归零 | 新回合 / 快照重建时 `reset()`；按回合看验收 |

口径刻意把「封口瞬间的收口投影」排除在 `封口N` 之外：那是一次段内内容的最终落屏，
不是「已完成段被反复重建」；否则每跨一段都会误报 1。

### 3.9 真机数据与落盘核对（旧 §19.6）

设备 `25113PN0EC` / `192.168.1.10:41193`；装机 APK sha256
`A03850C7DB0D7379FA34C19BC5DAF21A4DBA0821490A2E06414AAEB1F75C7D9D`。

**优化前（HEAD 真机基线，8 分钟）**：`[batch]` 14 行全部 `投影=1`，每行都是一次
`project(msg, timeline, ids, now)` 全量投影（示例 `[batch] n=10 归约=9 跳过=1 投影=1 耗时=3ms`）。
窗口内跟随会话的轮次都 ≤6 步，所以这批基线没直接打出「封口段」数字；
按旧实现口径折算：同一个 8 步 / 2 段回合，旧实现每帧全量投影都会重建已完成段 G1
（本轮的 9 个投影帧 → 9 次）。

**优化后（真机串行 8 步回合）**：turn 94 有 step1..step8，`[proj]` 共 22 行，其中
9 行进入两段态，**封口重建始终为 0**：

```text
[proj] 段=1/0/1 重建=封口0 活动12 耗时=1ms
[proj] 段=2/1/1 重建=封口0 活动13 耗时=1ms   ← 第 7 步跨进 G1，只新建/投影 G1
[proj] 段=2/1/1 重建=封口0 活动14 耗时=1ms
[proj] 段=2/1/1 重建=封口0 活动15 耗时=0ms
[proj] 段=2/1/1 重建=封口0 活动16 耗时=0ms
[proj] 段=2/1/1 重建=封口0 活动17 耗时=0ms
[proj] 段=2/2/0 重建=封口0 活动17 耗时=0ms   ← turn/end 收口，封口段不再重建
```

| 指标 | 优化前（同 8 步 / 2 段口径） | 优化后（真机 turn 94） |
|---|---|---|
| 每次投影覆盖 | 整条消息（0..MAX） | 活动段 `[6k,6k+6)` |
| 已完成段重建 | 9 次（9 个投影帧 × G1） | **0 次**（0/22 行） |
| 段数 | 流式期 1 条（结束才拆） | 流式期即时 2 条（A1 + G1） |
| 单次投影耗时 | 未单独打点（`[batch]` 3–8ms 含归约） | **0–1ms**（22 帧平均 0.18ms） |

落盘核对（`files/ai_chat_history.json`）：串行 8 步回合保存为两条消息 ——
`T1013-A1`（6 rounds / 10 components，前 6 步）+ `T1013-G1`（3 rounds / 6 components，
尾部工具与总结正文）；第二轮 `T1016-A1` + `T1016-G1` 同构。截图
`build/proj-verify/chat-now.png`：尾段只含 echo 7/echo 8 与总结，前 6 个 echo 在上一段。
说明消息流/工具卡/正文仍正确，且相邻两段是独立消息（不再是一轮结束后重新拆）。

单测：新增 `SegmentSealPolicyTest` 4 项 + `SegmentProjectionCacheTest` 13 项，全绿
（封口判定 / 新增段 / 工具结果晚到 / 卡片增删 / 审批插入 / 纯尾部文本不失效 /
收口一次 / 正常增长全程封口=0）。全量 `testDebugUnitTest` 仍有既有失败
（如 `DshProjectionMetaTest` 期望 `props.meta` 是 JSONObject，HEAD 投影写入的是 String），
与本次改动无关。

### 3.10 本章边界（见 §7.3）

文本旁路未做、审批是消息级字段、子代理卡不在指纹里、同帧跨两轮、快照重建、卡片跨段重复
—— 见 §7.3。

## 4. 组件与卡片：37 张通用卡、动态兜底、校验器

> 本章解决什么问题：DSH 的事件不该新增消息/视图类型，而应落进组件管线；本章记录
> 「DSH 专用卡 → 现有卡」的合并、最终保留的一张原生卡、动态兜底、推送链路、语义令牌、
> 校验器，以及 2026-11-15 补的 keyValue/compare、data:image、公式绑定与 layout 嵌套卡片。

### 4.1 分层与结论（旧 §1）

| 层 | 实现 | 数量 |
|---|---|---|
| 消息类型 | `ChatMessage.MessageType`：USER / AI / SYSTEM / THINKING / TOOL_CALL / TOOL_RESULT / AGENT_SUMMARY / ERROR / **REMOTE_CARD** | 9 |
| 视图类型 | `ChatAdapter.VIEW_TYPE_*`：USER / AI / SYSTEM / THINKING / TOOL_CALL / ERROR / CONTEXT_INJECTION / SYSTEM_PROMPT / **REMOTE_CARD** | 9 |
| 内置组件 | `ComponentRegistry` 注册的 `ChatComponent` | 当前 70 个注册实例 = 37 张通用卡 + layout 树卡 1 张 + `LayoutPrimitiveCardView` 桥接 32 种；初审计时是 39 个非循环注册（见 §4.2） |
| 动态组件 | `DynamicCardView`（`getType()="*dynamic*"`，通配兜底）：标题 + content/items/actions，其余字段收进「查看原始 JSON」折叠区 | 1（兜底） |
| 工具组件 UI | `ToolCallCardView`（`tool_call`）+ `ToolActivityRowView` / `ToolGroupPartRenderer`（工具组折叠行） | 2 |
| 别名归一 | `ComponentTypes.resolve`：`alert`→`alert_card`、`steps`→`steps_card`、`timeline`→`steps`、`markdown`→`markdown_card`、`keyValue`→`key_value`… | — |

**结论一：事件不需要新增消息/视图类型。** 消息类型表达的是「这条是用户/AI/系统/工具/远程卡」，
DSH 的事件（job、command、todo、subagent、审批…）都是「AI 回合内部的卡片」，
正确落点是**组件管线**（`ComponentData` + `ComponentRegistry`），不是新 MessageType。
唯一例外是 `REMOTE_CARD` —— 它本来就是给「远程 view 片段」准备的，现在用于推送卡。

### 4.2 合并：DSH 专用卡 → 现有组件（旧 §2）

| 原 DSH 卡 | 现在的落点 | 做法 |
|---|---|---|
| `dsh_notice`（生命周期提示：目标/压缩/重试/子代理/钩子/插话/模型切换…） | **`alert_card`** | `DshCards` 里 30 处 `"dsh_notice"` → `"alert_card"`（level 字段本来就兼容）；`AlertCardView` **扩展**：`jump=last` 滚到末尾气泡、`jump=subagent`+`childId` 滚到子代理轨迹、长按看全文（复用 `DshCardViews.showNoticeDialog`） |
| `dsh_command`（终端命令卡） | **`code_card`** | `command/run`、`command/done` 两处映射改 `code_card`；`CodeCardView` **扩展**：接受 `command` 作为 `code` 的别名（语言默认 shell），并把 `status`（running/success/failed）并入头部标签 |
| `todo/write` | `todo_card` | 本来就是现有组件（items 用 `text`/`done`，字段已对齐） |
| `deliverables/presented` | `file_list` | 现有组件 |
| `tool-workflow/*` | `steps`（= `steps_card`） | 现有组件（别名归一） |
| `developer/message`、`system/message`、`team/*` | `markdown_doc` / `alert_card` | 现有组件 |
| 工具调用 | `tool_call` | 现有组件（`DshProjection` 生成，和本地工具同一条渲染管线） |

删掉的重复实现：`DshCardViews.DshNoticeCard`、`DshCardViews.DshCommandCard`（含注册表登记与文档注释）。
`ComponentRegistry` 从 39 → 37 个非循环注册（这一步删了 2 张；第三轮再删 `dsh_job` → 36；
2026-11-15 补 `key_value`/`compare` → 38 个非循环注册，即 37 张通用卡 + 1 张 layout 树卡，
另有 32 个 layout 原语桥接，合计 70 个注册实例）。

**第一轮验证**：

- 单测：`DshEventCoverageTest` 当时 6/6（把 63 种事件逐个 reduce，`unknownEventKinds()==0`，
  并断言插话卡落在 `alert_card`；现文件 8 条，见 §8.3）；`DshHistoryImporterTest` 7/7；
- APK：860MB，`sha256 8b1e9fca…`，21:32 装机成功；
- 组件类型集合（DSH 侧）：`alert_card`、`code_card`、`todo_card`、`file_list`、`steps`、
  `tool_call`、`layout`、`markdown_doc`、`dsh_job`、`subagent_card` —— 除最后两个全部是现有组件。

### 4.3 保留的原生卡：只剩 subagent_card（旧 §3 + §7 结果）

先看最终状态：DSH 专用原生组件**只剩 `subagent_card`**（`DshSubagentCard`）。
`dsh_job` 一度也在保留名单，第三轮发现可以扩展现有 `job` 卡而合并（见 §4.6）。

为什么 `subagent_card` 接不进 `steps_card`：

| 组件 | 为什么不能接现有组件 |
|---|---|
| `subagent_card`（`DshSubagentCard`） | 子代理的**实时轨迹**（label + 最近 6 步工具/结果，`deck_only` 投到甲板卡槽），现有 `steps_card` 是静态步骤列表，没有 childId/轨迹累积语义 |
| `dsh_job`（第三轮前） | 现有 `job` 是**主动轮询的作业卡**（`job_id`+`title`+`cmd`，2s 轮询日志、带进度圈），语义是「App 自己跑的作业」；DSH 的 job 是宿主侧的**被动状态灯**（label + running/done/failed，key 按 jobId 原地更新），硬接会导致它去轮询一个不存在的作业。第三轮给 `JobCardView` 加静态模式后这一条已消解 |

`subagent_card` 属于用户说的「dsh 独有 → 单个依次创建原生组件」。

### 4.4 动态兜底：DynamicCardView（旧 §4）

`DynamicCardView` 作为**兜底**：`ComponentRegistry.render` 里未注册类型 → 走它；
已注册但 `canRender=false` 时也回退到它。DSH 以后新增事件类型时：

1. 若形状能套现有组件 → 在 `DshCards` 里一行映射即可；
2. 套不上但字段简单（title/content/items）→ 直接发 `dynamic`，无需改 App；
3. 只有需要专门交互（如子代理轨迹）才新增原生组件。

2026-11-15 的 `81d6b852` 又给旧 DSH 专用卡类型登记了别名（`dsh_command`→`code`、
`dsh_notice`→`alert`、`dsh_job`→`job`），避免历史载荷掉进兜底被画成原始 JSON 卡。

### 4.5 第二轮：内置工具卡与原生控件（旧 §5）

用户提醒：「我还有内置工具专用卡也可以用，还有原生控件组件」。把当时的 36 个已注册卡片
实现（含 layout 树卡）按语义过了一遍（今天通用卡是 37 张：+`key_value`/`compare`，见 §4.2）：

| 类别 | 组件（type） |
|---|---|
| **工具专用卡** | `tool_call`（工具执行）、`file_card`（单文件）、`file_list`（目录/产物）、`diff`（变更对比）、`code_card`（代码/命令）、`json_viewer`（JSON）、`markdown_doc`（整篇文档） |
| **数据/结构卡** | `table_card`、`chart`（柱/折/面/饼/散点/雷达）、`metric_card`、`progress_card`、`info_card`（键值）、`steps_card`、`todo_card`、`list_card`、`grid_card` |
| **媒体卡** | `image_grid`、`video`、`audio`、`playlist` |
| **场景/工具卡** | `quiz_card`（答题宝核心：可作答+判题）、`weather_card`、`agenda`（日程）、`location`、`link_card`、`file_card`、`app_card`、`contact_card` |
| **原生控件渲染** | `layout`（Layout 树 → 聊天流卡片）、`LayoutPrimitiveCardView`（`NativeLayoutRenderer` 单控件桥接）、`html`（WebView 富内容） |
| **状态/提示** | `alert_card`、`note_card`、`agent_status`（气泡内状态徽章，可 spinning）、`feedback_bar`、`job`（主动轮询作业卡） |
| **DSH 原生** | `subagent_card` |

第二轮据此又接了两处：

| DSH 事件 | 落点 | 说明 |
|---|---|---|
| `tool/ptc-dispatch`、`tool/ptc-dispatch-start` | **`tool_call`** | PTC 本质就是一次工具调用，复用内置工具卡（原来发的是提示卡） |
| `goal/*`、`team/*` 等正文 | **`alert_card`** | 修了一个真 bug：`alert_card` 只读 `content`，而这些映射写的是 `text` → 正文整段丢失。现在 `content` 空时回退 `text`（`canRender` 也认 `text`） |

仍然「接不上」的三类（diff 无逐文件载荷、image_grid 无取图端点、subagent 轨迹语义；
`job` 已在 §4.6 通过 `JobCardView` 静态模式接上）逐条理由见 §7.4。

### 4.6 第三轮：改原生组件（旧 §7）

用户问「可以改原生组件吗」——**可以，而且优先改原生组件、不新增。** 本轮把最后一对
重复的 dsh_job 也接进了内置 job：

- `JobCardView` **扩展静态模式**：job_id 为空但有 status 时，不轮询、直接画
  「状态点 + 任务名 + 运行中/完成/失败」；`canRender` 同时接受两种用法；
  原来那种带 job_id 的主动轮询作业卡行为**完全不变**；
- `DshCards` 的 job/registered、job/settled 改发 `job`（props：`title`/`job_label`/`status`）；
- 删除 `DshCardViews.DshJobCard` 与注册项；`DshCardViews` 现在只剩 `DshSubagentCard`；
- `ComponentTypes` 目录里删掉 dsh_command/dsh_job/dsh_notice 三条 schema，
  补上 `code_card`（模型要终端/提示/任务卡时用通用类型，同样能拿到原生渲染）。

**改原生组件的三条规矩**（这轮都遵守）：

1. **只加可选字段，不动老字段语义**（job_id 仍是轮询模式；content 仍优先于 `text`）；
2. **老生产者的渲染结果不变**（job 的轮询分支零改动；`alert` 只在正文为空时回退）；
3. **每改一个都跑单测 + 装机验证**（DshEventCoverageTest 当时 6 条全绿，现 8 条；APK 装机成功）。

至此 DSH 专用原生组件只剩 **subagent_card** 一个（子代理实时轨迹，childId 累积语义，
现有 steps_card 是静态列表，接不上）。

### 4.7 推送链路：`/v2/card` → 手机本地绘制（旧 §9）

用户口径：「可以创建 dsh 专用的动态原组件及动态 layout 功能了，你使用推送然后本地绘制。」

```text
插件 POST /v2/card { session_id?, key, type, props }
        │
        ├─ streamBroadcast → WS/SSE 帧 { type:'ui/card', data:{key,type,props} }
        ▼
手机 AIChatActivity.onChannelEvent → case "ui/card"
        │  同 key 覆盖更新；没有就追加一条 durable 的 REMOTE_CARD 消息
        ▼
ChatAdapter.bindRemoteCard
        │  remoteCardView.component = {type, props}
        ▼
ComponentRegistry.render(ComponentData.of(type, props))
        ├─ 内置卡：alert_card / code_card / list_card / file_list / …
        ├─ "layout" + props.layout：NativeLayoutRenderer 原生控件树
        └─ 未注册类型：DynamicCardView 兜底（标题 + content/items + 原始 JSON 折叠）
```

**关键点**：App 不解释语义，只按 `type/props` 走既有组件管线 —— 所以 DSH 以后新增卡片
可以完全不发版；需要复杂排版时插件直接给 `layout` 树。

用法：

```bash
curl -X POST http://<电脑IP>:8218/v2/card \
  -H "Authorization: Bearer <令牌>" -H "Content-Type: application/json" -d '{
    "session_id": "session-...",
    "key": "demo-layout",
    "type": "layout",
    "props": {
      "title": "DSH 动态卡",
      "layout": { "type": "column", "children": [
        { "type": "text", "text": "这张卡是插件推送、手机本地绘制的", "bold": true }
      ]}
    }
  }'
```

三种 `type` 的取法：

| type | props | 用途 |
|---|---|---|
| 内置类型（`alert_card` / `code_card` / `list_card` / `file_list` / `steps` / `todo_card` …） | 各自字段 | 最常见，一行映射搞定 |
| `layout` | `{layout: <控件树>, title?}` | 复杂排版，原生控件树（column/row/scroll/card/wrap/grid/tabs/stack/accordion/carousel + text/image/input/button…） |
| 任意新名字（如 `dsh_future_card`） | 任意 | App 不认识 → 动态兜底卡，**不用发版** |

落点与边界：

- 卡片进**消息流**（`MessageType.REMOTE_CARD`，durable，随会话落盘）——「发生过什么」；
- 会变的瞬时状态仍走**插槽**（`ui/slot`）——「现在是什么」（见 §5.1）；
- 同 `key` 重复推送 = 原地更新（改一张卡不用追加）。

验证：

- 插件：`POST /v2/card` → `{"ok":true,"key":"demo-layout","type":"layout","pushed":true}`；
- App：`ChatAdapter.bindRemoteCard` 新增，编译通过（`BUILD SUCCESSFUL`）；
- APK：860MB，`sha256 2847571d…`（本轮构建；adb 掉线未装机，USB 恢复后 `adb install -r` 即可）。

### 4.8 推送暂存与进页面补发（旧 §10）

`ui/card` 是瞬态推送：手机不在聊天页时没有订阅者，帧会丢。按用户口径补上暂存：

- 插件：`PUSHED_CARDS`（按「会话 × key」分桶，上限 50 条 / TTL 6 小时）；
  `POST /v2/card` 先 `rememberPushedCard` 再广播；
  新订阅接入时 `replayPushedCards` 补发 —— **WS hello 与 SSE ready 两条链路都挂**；
- App：同 key 覆盖，重复补发不会叠卡（`bindRemoteCard` 幂等）；
- 真机验证：App 被 force-stop 时推 stash-demo → 启动进聊天页后日志出现
  `[ui/card] type=alert_card key=stash-demo` 并落盘。

**顺带修的落盘 bug**：`ChatHistoryManager.saveCurrentChatAsSession` 只保留 USER/AI 消息 →
**没有正文的 REMOTE_CARD 被静默丢掉**（「推送的卡片一重进会话就没了」）。
新增 `isDurableForSession(msg)`：带 `remoteCardView.component` 的 REMOTE_CARD 也算 durable；
旧 v2 view 片段保持原状。验证：会话文件里出现
`"remoteCardView":{"component":{"type":"alert_card","props":{...}},"__key":"persist-demo"}`。

**使用约束**：`ui/card` 由聊天页处理；手机在首页/工具页时靠上面的暂存补发，但**必须回聊天页
才会落屏**。「任何页面都能弹」要用 `phone_ui` 的 toast/notification，不是这条链路。

### 4.9 语义渲染令牌与降级（旧 §11.1）

用户口径：「抄，然后他的渲染思路也值得。」插件只给**语义**，像素/颜色/深浅色由 App 定：

| 属性 | 取值 | 说明 |
|---|---|---|
| size | 数字 或 caption/footnote/body/subtitle/title/headline/display | 六档字号，token 名与 dsh-Go 一致；旧写法（数字、`fontSize`）不变 |
| weight | `regular`/`medium`/`semibold`/`bold` | `bold:true` 仍是等价写法 |
| `tone` | good/warn/bad/info/muted（别名 success/warning/danger/error/note/secondary） | 语义色，color 显式给了就以 color 为准 |
| spans | [{text, color\|tone, bold, weight, mono, size}] | 富文本片断（SpannableString） |

**降级口径**（抄 dsh-Go 的「单点坏不毁整块」）：

| 情况 | 之前 | 现在 |
|---|---|---|
| 不认识的 `type` | ⚠ 未知控件: xxx（可用 register_type…）—— 把开发者提示塞给用户 | 灰色一行「这部分无法显示」，日志记详情，其余照常渲染 |
| 节点 > 400 / 深度 > 9 / layout > 64 KiB | 直接渲染（大卡会把主线程拖住） | 降级成「内容过多，已省略」/「层级过深，已省略」 |

### 4.10 推卡校验器：cardcheck.js（旧 §11.2 + §14.4）

`/v2/card` 现在先跑 `checkCard()`，**坏卡不进手机**：

```text
POST /v2/card {"type":"alert_card","props":{"title":"只有标题"}}
→ 422 {"error":"card_invalid","diagnostics":[{"path":"/props","level":"error",
     "message":"缺少必填字段：content 或 text 或 message"}],"hint":"✗ /props: 缺少必填字段…"}
```

规则（思路抄 sdk/plugin-kit 的 view-check / limits / effects）：

- **error（拒收）**：缺 `type`、props 不是对象、结构体积 > 32 KiB、组件必填字段缺失、
  layout 节点 `type` 非法、layout 字符串不是 JSON；
- **warn（照发）**：嵌套 > 8 层、节点 > 400、节点缺 `type`（App 按 column 兜底）；
- **放行**：不认识的组件类型（App 有动态兜底卡）；
- `force:true` 可绕过拒收（排障用）。

2026-11-15 增补：`key_value` / `compare` 必填 `items` 规则（`keyValue` / `dsh_keyvalue` /
`compare_card` 先归一），以及 data:image 载荷预算 —— 结构体积与图片载荷分开算，
单张 base64 ≤ 512 KiB、总载荷 ≤ 2 MiB（4 张 × 512 KiB），结构体积仍 32 KiB。

自测：`node tools/dsh-plugin-smartquiz-remote/cardcheck.test.mjs` 初版 **15 passed**，
补完动态卡规则后 **23 passed, 0 failed**（现文件 23 条）。

### 4.11 keyValue / compare（旧 §14.1）

用户口径：「补全动态卡（ui/card + layout）三块能力，并验证、提交。」本轮只加可选字段/新类型，
既有卡片（alert/list/metric/progress/layout）行为不变。

| type | 别名 | props 形状 | 上限/降级 |
|---|---|---|---|
| `key_value` | `keyValue`、`dsh_keyvalue`（`ComponentTypes.resolve` 归一） | `items` 三种形态：`[["名称","值"],…]`、`[{label,value,tone?}]`、`{"名称":"值"}`；可选 `title` | 最多 24 项，超出 footer 显示「还有 N 项」；0 条有效数据显示空态；点击整卡复制「名称：值」全文 |
| `compare` | 无（`compare` 本身） | `items:[{title,badge?,rows}]`，`rows` 支持 `[["名称","值"]]` 或 `{"名称":"值"}`；可选顶层 `title` | 最多 4 项，超出显示「还有 N 项未显示」；0 项空态；不足 2 项也照常渲染（避免模型先给 1 项时掉原始 JSON 兜底） |

实现：`KeyValueCardView` / `CompareCardView`（`CardShell` + `ComponentTheme`/`ComponentColors`），
共用纯 JVM 解析 `CardKeyValues`（三形态归一、24 项截断、数字样值等宽字体、语义 tone）。
已注册进 `ComponentRegistry`，目录补 `key_value` / `compare` 两条 schema；别名 `keyValue` 先走
`normalize → keyvalue` 再命中 alias，旧 DSH 写法不用改。

### 4.12 data:image（旧 §14.2）

新增 `DataImageDecoder`，支持 `data:image/...;base64,...` 内联图（单张硬限制：
base64 payload ≤ 512 KiB、单边 ≤ 4096；先 bounds 再按 2048 采样解码）。接入五处：

- `NativeLayoutRenderer` 的 `image`、`avatar`、`avatar_group`、`carousel`（头像用圆形
  `RoundedBitmapDrawable`）；
- 内置 `image_grid` 的单图/网格项，以及 `ImagePreviewUtil` 的全屏预览（data URI 不进
  Glide/文件/网络分支）。

超限、格式坏、OOM 一律不抛异常：layout 侧显示浅底「图片无法显示」占位，`image_grid`
显示同文案占位且不提供无意义的「点击重试」。

**体积闸门配套调整**：`MAX_LAYOUT_CHARS=64 KiB` 仍然限制结构体积，但 data:image 的 base64
载荷不计入该 64 KiB（否则一张 512 KiB 的图永远过不了入口检查）；总图片载荷另设 2 MiB
上限（4 张 × 512 KiB）。`cardcheck.js` 同口径：结构体积与图片载荷分开算，并单独拦总载荷。

### 4.13 表单绑定与有界公式（旧 §14.3）

- **绑定**：任意节点属性可写 `{"$":"data.a.b"}`，渲染前从 `props.data` 取值；取不到按空值
  处理。文本节点就是显示解析出来的值。
- **公式**：只允许写在 `text` 的 `text` 与 `stat`/`metric` 的 `value` 位置
  `{"=":"a * b"}`。求值器 `LayoutFormula` 只认：
  - 数字（整数/小数）、`+ - * /`、一元正负号、括号；
  - `data.` / 裸数据路径（`a`、`data.a.b`、`items[0].price`，数值字符串也接受）；
  - `min / max / round / abs` 四个函数（round/abs 单参，min/max ≥1 参）。
  其它字符、未知函数、未知路径、非数值、除零、>256 字符、>24 层、>128 token 全部返回
  `null` 并降级成「—」；没有反射/脚本/字符串/赋值。
- **输入变化重算**：`LayoutBindingScope` 在渲染同棵树时登记绑定/公式 TextView；渲染完
  接线 EditText / Spinner / CompoundButton / RadioGroup / SeekBar，值变化写回 `props.data`
  并只刷新登记的文本节点（不重建 View，不丢焦点）。初始值双向同步：`props.data` 有值
  先灌控件，没有则把控件当前值写回 data，所以「input value=3 + 公式 a*2」首屏就是 6。
- **限制（如实写）**：date/time/color/rating/toggle/stepper/slider_range/tag_input 这些
  自定义 ref 控件没有统一监听接口，暂不参与「改动即重算」；提交按钮仍会经
  `collectValues` 回传。公式写在非 text/metric 位置一律「—」，绑定写在哪里都生效。

验证（旧 §14.5）：

- 新增单测全绿：`KeyValueCardViewTest` 9、`LayoutFormulaTest` 9、
  `DataImageDecoderTest` 3、`LayoutBindingScopeTest`（Robolectric）1；
- 受影响旧单测全绿：`ComponentTypesTest` 7、`ComponentFieldIntegrityTest` 5、
  `ComponentDisplayTest` 14、`NewCardShapeTest` 3、`ComponentNameIntegrityTest` 6；
- APK：`.\gradlew.bat --offline assembleDebug` BUILD SUCCESSFUL，约 901 MB，
  `sha256 74AD5818DD6BA7148D0563246DED00FEB0A4C939FF64CE733BDCF0FA18B5AAD2`，
  `adb -s 192.168.1.10:41193 install -r` Success；
- 真机推卡（插件 `/v2/card`，令牌/会话见本轮任务）：
  - `compare` 3 项：三张小卡 + badge + 键值行，数值等宽，截图 `build/verify.png` /
    `build/verify5.png`；
  - `layout` 含绑定/公式：`{"$":"data.name"}` 显示「答题宝」、`{"=":"price * qty"}` 与
    `metric` 都显示 50，输入框 12.5/4，截图 `build/verify5.png`；
  - 另推一张公式卡，首屏显示 6（`a=2,b=3`），截图 `build/verify12.png`；
  - 真机手动改输入值触发重算这一步没有截到稳定画面（会话持续推进导致列表滚动），
    该行为由 Robolectric `LayoutBindingScopeTest` 覆盖：`b` 3→5 后公式 6→10，
    `props.data.b` 同步为 "5"。

### 4.14 layout 嵌套卡片（旧 §21）

**问题：卡片类型进不了 layout。** `NativeLayoutRenderer.buildNode` 的 `default:` 分支此前
只认三条展开路径：① `UIComponentTypeRegistry`（`register_type` 的 layout 模板）
② `UIComponentPluginManager`（插件 `render.layout`）③ 节点自带 `layout` 字段。
`key_value`/`compare`/`alert_card` 这类卡注册在 `ComponentRegistry`，三条都不匹配
→ 返回 null → 灰字「这部分无法显示」。真机实测：layout 子节点
`{"type":"key_value","items":[["k1","v1"]]}` 服务端 200（校验器已放行），卡上却没有 k1。

**支持范围（第四条判定）**：在 `tryExpandRegisteredType` 之后补第四条
`tryRenderRegisteredCard(...)`：

- **判定**：`ComponentTypes.resolve(type)` 归一后 `ComponentRegistry.hasType(...)` 为真 ——
  `keyValue`/`keyvalue`、`compare_card`、`dsh_command`→`code` 等别名一并认；
- **props**：节点 `props` 对象打底、节点顶层属性覆盖；`type`/`children`/`key`/`props` 与父容器
  排版键（width/height/margin/weight/flex/align/alignItems/spacing）不进 props；
  **`items`/`rows`/`series` 是卡片数据，原样带过去，不会被当 `children` 递归**
  （渲染器只遍历 `children`，`addChildren` 也一样）；
- **外壳**：直接返回卡片自己的 View（CardShell 已带圆角/描边/标题/内边距），**不再额外套一层
  卡片外壳**；宽高交给父容器 `addChildren` 的 LayoutParams（列里铺满、行里自适应），
  没有额外包装层，嵌套时是「卡片里嵌一张紧凑小卡」；
- **key**：节点有 `key` 就登记 `viewRefs[key]`（卡片是 ViewGroup，`collectValues` 只收输入控件，
  不会把整张卡塞进回传值）；
- **绑定/公式**：props 里的 `{"$":"data.x"}` / `{"=":"…"}` 由
  `LayoutBindingScope.resolveCardValue` 递归求**一次**静态值再交给卡片；
- **交互**：props 带 `actions` 时仍由 `ComponentRegistry` 统一附加动作（卡片 footer），
  与聊天流卡片同一条路。

覆盖面 = `ComponentRegistry` 里所有已注册类型：`key_value`/`compare`/`alert_card`/`info_card`/
`list_card`/`table_card`/`metric_card`/`steps_card`/`json_viewer`/`code_card`/`layout`/场景卡……
（`text`/`input`/`chart` 这类**内置 layout 控件**仍由 `buildNode` 的 switch 先接住，行为不变。）

**嵌套深度上限**：卡片可能自己再走 layout 渲染（`layout` 卡 → `LayoutCardView` →
`NativeLayoutRenderer.render`），不设限会递归。用线程内 `CARD_NEST_DEPTH` 计数
（进卡片前 +1、`finally` −1，不跨请求残留）：

- layout 直接子卡片深度 **0**；
- 卡片内部再出现的卡片深度 +1；
- **上限 2**：深度 0/1/2 正常渲染，`>2` 的那一张返回灰字「嵌套太深」并记日志
  （即连续 4 张卡片时第 4 张降级；「layout 卡里再套 layout 卡再放 key_value」这种两层嵌套仍可用）。

预算沿用既有口径：卡片节点本身在 `buildNode` 入口计 **1 个节点**（`MAX_NODES=400`），
节点层级仍由 `MAX_DEPTH=9` 把关。注意 `LayoutCardView` 内层 render 会按既有实现重置节点预算
（本次不动这条），所以嵌套卡片深度是「卡片里再套卡片」这道环的唯一闸门。

**失败兜底**：

- `hasType` 为真但 `render` 返回 null → 灰字「这部分无法显示」，日志
  `嵌套卡片渲染返回 null type=…（归一后 …）`；
- `render` 抛异常 → 灰字「这部分无法显示」，日志 `嵌套卡片渲染异常 type=…（归一后 …）` 带堆栈；
- type 不在卡片注册表 → 返回 null，调用方继续走原有「未知控件」灰字（行为不变）；
- props 组装失败、绑定求值失败只记日志并按原值渲染，不把整张卡干掉。

**测试**：`NestedCardNodeTest`（Robolectric，新增 6 条，全绿）：

| 用例 | 断言 |
|---|---|
| `nestedCardTypesRenderInlineInsteadOfGrayPlaceholder` | layout=[text NEST-KV, key_value(k1/v1), compare(基础版)] → k1/v1/基础版 出现在 View 树，无「这部分无法显示」 |
| `unknownTypeStillFallsBackToGrayPlaceholder` | 未知 type 仍是灰字「这部分无法显示」 |
| `twoLevelsOfNestedCardsStillRenderContent` | layout 卡→layout 卡→key_value（深度 0/1/2）→ k1 渲染，无「嵌套太深」 |
| `nestedCardBeyondDepthLimitShowsTooDeep` | 深度 3 → 灰字「嵌套太深」，深层内容不渲染 |
| `cardItemsAreDataNotChildren` | items 里塞「像节点的对象」→ 其 text 不出现（没被当 children） |
| `cardPropsBindingsResolveOnceAgainstPropsData` | items 里 `{"$":"data.name"}` → 求值出「答题宝」 |

回归：`testDebugUnitTest --tests "com.oilquiz.app.ai.python.*" --tests "com.oilquiz.app.ai.chat.component.*"`
共 **46 个测试类 / 269 条用例，0 失败**（其中 `NestedCardNodeTest` 6/6）。

补记：`869c71d2` 修了校验器把 `items` 数据当节点树递归的问题 —— 真机推嵌套卡时服务端
误判 422；修法与本节「items 是卡片数据、不做 children」同口径。

**真机结论（2026-11-15）**：`POST /v2/card`（令牌/会话见本轮任务）推 `type=layout`：

- 卡 A（`key=nested-kv-1`）：children = [text "NEST-KV", key_value(items `[[k1,v1],[k2,v2]]`),
  compare(套餐对比 / 基础版 19 / 专业版 49)] → `/v2/card` **200** `pushed:true diagnostics:[]`；
  截图 **`build/nested/nested.png`**：NEST-KV、k1/v1、k2/v2、套餐对比两张小卡都画出来了，
  嵌套的 `key_value`/`compare` 是各自的**内嵌卡片视图**（圆角+标题），**没有**灰字占位；
  `uiautomator dump`：`k1=True v1=True k2=True v2=True NEST-KV=True 这部分无法显示=False`。
- 卡 B（`key=nested-deep-1`）：layout 卡×4 层后才放 key_value → 截图
  **`build/nested/nested_deep2.png`** 显示灰字「嵌套太深」；`dump`：
  `嵌套太深=True too-deep=False`，深层数据没有被渲染。
- 装机：`adb -s 192.168.1.10:41193 install -r "build\outputs\apk\debug\答题宝-debug-2.2.apk"`
  → `Success`；APK SHA256 `22B84F195D5EE1BAE192F9298BA6E12F51A6C185153D69A5DD6277236930AFC3`；
  `.\gradlew.bat --offline assembleDebug --console=plain` → BUILD SUCCESSFUL。

### 4.15 本章边界（见 §7.4）

接不上的三类卡（`job` 已在 §4.6 接上）、自定义控件不参与实时重算、compare 少于 2 项不拒绝、
data:image 硬限、校验器日志文本变化、嵌套卡绑定只求值一次、卡片内部输入不回写外层、
style 不覆盖、`items` 不做 children 等 —— 见 §7.4。

## 5. 插槽甲板：语义、rev/TTL/epoch、档位、上限、快照

> 本章解决什么问题：瞬时状态（后台任务、目标、压缩、重试、插话队列、权限…）不该留在
> 消息流里变成一张张「已完成但还在」的卡。本章定义甲板的职责边界，并用 rev/TTL/epoch、
> 命名档位、容量上限与快照拉取，治「同状态两行、旧帧覆盖、生产者失联残留、重连空板」。

### 5.1 语义与职责（旧 §8 引 + §9.3）

用户口径：「那就转成原生组件放到插槽里面。收到更新事件后更新，完成后消失。」

`DockSlotBus` 的类注释本来就写着这个分工：

> 进时间线的是「发生过什么」（消息 + 卡片，落定即快照、不可原地更新）；
> 插槽放「现在是什么、我能做什么」（后台任务、目标、压缩中…），随状态实时改写、消失即恢复原状。

因此三条落点规则：

- 卡片进**消息流**（`MessageType.REMOTE_CARD`，durable，随会话落盘）——「发生过什么」；
- 会变的瞬时状态走**插槽**（`ui/slot`）——「现在是什么」；
- 同 `key` 重复推送 = 原地更新（改一张卡不用追加）。

### 5.2 瞬时状态搬迁清单（旧 §8）

| 事件 | 以前 | 现在 |
|---|---|---|
| job/registered、job/settled | job 聊天卡 | 插槽 jobs（插件原有）+ 结束 `remove:true` |
| compaction/start\|summary\|prune\|end | 4 张 alert 卡 | 插槽 compaction（压缩中）→ 结束 `remove:true` |
| llm/retry、llm/retry-started | alert 卡 | **新增插槽 retry**（「第 N 次重试中…」，warn）→ `assistant/message`/`step/end`/`turn/end` 自动移除 |
| `agent/inbox/spliced`（插话排队） | 「插话已入队」alert 卡 | **新增插槽 inbox**（kind=list、count=N、`action=queue`）→ 队列空 `remove:true` |
| goal/change | alert 卡 | 插槽 goal（原有）→ 非激活 `remove:true` |
| permission/preset、sandbox/mode、`approval/policy` | alert 卡 | 插槽 permission（原有） |
| `agent-preset/selected`、model/selection | alert 卡 | 不再出卡（模型/预设信息在 permission 插槽 detail 里；后续可加独立槽） |
| subagent/catalog + 活动 | subagent_card | 插槽 subagents（原有）+ 甲板轨迹卡（保留，durable） |

**顺手修的关键 bug**：插件历史上有两种「移除」写法 —— `{id}` 空帧 和 `remove:true`，
但 App 的 `RemoteSlotBridge` 只认后者，于是「压缩中/子代理/任务」结束后**槽位只变成空壳
留在总线里**（甲板挂一条空白）。现在两种写法等价：`title`/`detail` 都为空且 `count<=0`
→ `DockSlotBus.remove(id)`（细节见 §5.3）。

保留在消息流的（durable，「发生过什么」）：`todo/write`、`deliverables/presented`、
`command/run|done`、`tool/call|result`、`tool/ptc-dispatch*`、`workspace/changes`、
`working-directory/change`、`developer/message`、`system/message`、`team/*` 消息、
`feedback/*`、`hook/*`、`image/offload`。

单测：`DshEventCoverageTest` 当时 5/5（63 种事件当时仍全部有归属；现 KNOWN 清单为 63 种、
8 条测试，插话入队改为「不出卡但已识别」断言）。

### 5.3 协议字段：rev / epoch / ttl_ms / remove（旧 §20.2 + §20.9）

```json
{ "type": "ui/slot", "data": {
    "id": "jobs", "priority": 40, "title": "任务", "detail": "1 个在跑 · 共 20 条",
    "action": "jobs", "kind": "display", "board": "task", "pinned": false, "count": 0,
    "rev": 7, "epoch": 1794684000123, "ttl_ms": 30000, "remove": false, "session_id": "..."
} }
```

| 字段 | 语义 |
|---|---|
| `rev` | **按 id 单调递增**，每条新帧 +1；补发沿用最近一帧的 rev，绝不回退。移除帧同样占一个新 rev |
| `epoch` | 插件实例世代（启动毫秒时间戳）。插件进程重载/重启后 rev 从 1 重来，App 靠它识别新世代并重置水位 |
| `ttl_ms` | 可选，相对存活时间（毫秒）；App 收到时换算成绝对 `expireAt = now + ttl_ms`（上限 24h） |
| `remove:true` / 空帧 | 与旧约定兼容：`remove:true` 或 title/detail 都空且 `count<=0` = 移除 |

插件侧默认 TTL（`SLOT_TTL_IDS`，全部 30000ms）：`jobs` / `compaction` / `retry` / `inbox` /
`subagents`；`goal` / `permission` / `schedule` / `mcp` 属稳定态，不带 ttl，靠显式 remove。
每次状态更新（再次 `broadcastSlot`）都会换新 rev 并重新计时。

App 侧 `DockSlotBus.Slot` 新增只读字段 `rev` / `expireAt` / `source`（`plugin` | `local`），
旧构造签名语义不变（新增重载，6 参/10 参构造默认 `rev=0, expireAt=0, source=""`）。

**移除语义（三种写法等价）**：

1. 空帧：`title`/`detail` 都空且 `count<=0` → `DockSlotBus.remove(id)`；
2. `remove:true`：显式移除，同样占一个新 rev，水位跨移除保留（旧帧不能复活槽位）；
3. `phone_ui(kind=slot, ...)` 空帧：以前工具层先拦「title/detail 至少给一个」，agent 没有
   任何手段手动清掉一条无 TTL 的测试/临时槽（只能等 App 重启）；现在 `kind=slot` 且
   title/detail 都空 = `remove:true`，**必须显式给 `id`**：不给 id 会清掉默认的 `phone_ui`
   槽，按错误返回「移除插槽需要 id」。对应的工具 schema 文案（title 描述 + 工具 description）
   也写明「slot 且 title/detail 都空 = 移除该 id」，模型不用猜。

`phone_ui` 的 slot 推送与 `POST /v2/action {action:'ui.slot'}` 共用 `pushUiSlot` →
`broadcastSlot`，每帧都补 `rev`（按 id 自增）与 `epoch`，`ttl_ms` 显式传入时原样透传。
所以 phone_ui 与 jobs/goal/permission 等插件产物是**同一个写者、同一套版本规则**，
不存在「rev=0 被同 rev 规则误杀」。自测 `slot.test.mjs` 会捕获插件注册的 `phone_ui` 定义并
直接调用 `execute`：验证带 rev/ttl、空帧产生 `remove` 帧且 rev 递增、无 id 被拒绝。

真机验证（A/B 一行、空帧移除）见 §5.11 的第 1 轮测试表。

### 5.4 rev 丢弃规则与 epoch 世代（旧 §20.3）

插槽总线原来是「同 id 覆盖」的裸状态帧，没有版本也没有生命周期，三个真实现象：

- **甲板两条「后台任务」**：插件 `ui/slot` 与 App `updateDockSlotsForEvent` 各写一次同一个 id（没有唯一写者）；
- **重连后状态倒退**：补发 + 实时推送重叠，晚到的旧帧覆盖新状态（`ui/slot` 无 rev）；
- **压缩/任务/重试行永远挂着**：生产者失联后不再有帧，也没有人清理（无 TTL）。

rev 治「旧帧覆盖」、TTL 治「残留」、epoch 治「插件重载后 rev 归零」，单一写者见 §5.6。

| 入站 | 条件 | 结果 |
|---|---|---|
| `rev > 0` | `rev < 已存最大 rev` | 丢弃（迟到帧；`[dock] 丢弃 回退 rev ...`） |
| `rev > 0` | `rev == 已存最大 rev` 且槽位仍在 | 丢弃（重复帧；`[dock] 丢弃 重复 rev ...`） |
| `rev > 0` | `rev == 已存最大 rev` 但槽位已被移除 | 丢弃（`DUPLICATE_REV`）：2026-11-15 起收紧 —— `clear()` 已清空水位，「按原 rev 补发恢复」只走 `prev == null`；剩下的只有显式移除与容量淘汰，同 rev 复活会让淘汰与补发互相打架 |
| `rev == 0` | 旧插件/本地写入 | 按旧「同 id 覆盖」语义接受，不建立水位 |
| 移除帧 | `rev <` 水位 | 丢弃（乱序移除不生效）；水位跨移除保留，旧帧不能复活槽位 |

**插件世代（epoch）规则**（2026-11-15 修订，真机插件重载后暴露）：

| 入站 | 条件 | 结果 |
|---|---|---|
| `epoch > 当前` | 插件重载/重启 | 清掉所有 `source=plugin` 的旧槽（本地槽保留）、清空全部 rev 水位；本帧继续按新水位处理 |
| `epoch == 当前` | 同一插件实例 | 正常按 rev 规则 |
| `epoch < 当前` | 上一世代迟到的帧 | **整帧丢弃**（`[dock] 丢弃旧世代帧 epoch=...`），防止旧移除/旧状态复活 |
| `epoch == 0` | 旧插件（不带该字段） | 不参与世代判断，按 rev=0 兼容路径 |

**为什么需要 epoch**：插件重载（`plugin_manager set_plugin`）或 DSH 重启后，插件的 `SLOT_REV`
从 1 重来，但 App 的 rev 水位还停在上一世代的几十 —— 新帧全被当成「回退」永久丢弃。真机现象：
插件重载后任务/权限槽不再更新，`phone_ui` 的移除也失效（reload 前 rev=1 的槽 vs reload 后 rev=1
的移除帧同号，被判重复）。旧方案靠「断连 clear() 清水位」，但快速重连时 App 可能观察不到
`online=false`，于是水位一直留着。epoch 不依赖断连信号，是确定性的世代判据。

**水位何时归零**：`DockSlotBus.clear()`（断连/切会话）仍清非 pinned + 清水位；epoch 变大时
也清水位并清旧插件槽。显式 `remove(id, rev)` 保留水位防同一个世代内的旧帧复活。

### 5.5 TTL 清理与续期覆盖（旧 §20.4 + §20.7）

- App `DockSlotBus.attachSweeper()` 在主线程挂 1s 一次的 `Handler`（`AIChatActivity.onCreate`
  调、`onDestroy` 摘）；每次 tick 调 `sweepExpired(now)`，`expireAt>0 && now>expireAt` 的槽位
  移除并通知 UI（`[dock] TTL 过期移除 id=...`）。
- 无 ttl（`expireAt==0`）的稳定态永不被清理。
- `addListener` 回放前先 sweep 一次：页面晚打开时不会看到已经死掉的「正在压缩」。
- 日志：`[dock] TTL 清扫已启动 间隔=1000ms` / `[dock] TTL 清扫已停止`。

**续期覆盖（如实）**：

| 槽位 | 续期触发 | 结论 |
|---|---|---|
| `jobs` | 每个 `job/registered` / `job/settled` 事件 | ✅ 有更新就续期；无事件 30s 后消失（符合预期） |
| `inbox` | inject/撤回/修改事件 | ✅ |
| `retry` | 每个 `llm/retry*` 事件 | ✅（另有 turn start/end 显式 remove） |
| `subagents` | `subagent/catalog` 与子代理活动轮询 | ✅ |
| `compaction` | **只有** `compaction/start` / `compaction/end`，没有周期心跳 | ⚠️ 压缩超过 30s 会提前消失（end 仍会 remove） |
| `goal` / `permission` / `schedule` / `mcp` | 无 ttl（稳定态） | 靠显式 remove；`mcp` 目前只增不减，靠断连 clear/重启消失，未纳入 TTL |

### 5.6 单一写者与本地兜底（旧 §20.5）

- App 侧 `ui/slot` 只剩**一个解析器**：`RemoteSlotBridge.apply(data)`。
  WS 通道（`RemoteDshTool` 的 `case "ui/slot"`）与事件通道（`AIChatActivity.updateDockSlotsForEvent`
  的 `case "ui/slot"`）都委托它；顺带修正了旧 SSE 路径丢 `kind/board/pinned/count` 的问题。
- 插件帧写 `source=plugin`；App 自己派生的状态（compaction、`remote/attention`、`remote_cmd`）
  改走 `DockSlotBus.updateLocal(slot)`：
  - 插件最近 **3 秒**内写过同一 id → 本地写入让位（`[dock] 丢弃 兜底让位（插件 3s 内写过）`）；
  - 旧插件不推时照常显示（兜底能力保留，不删）。
- `job/goal/permission` 的本地派生写入在 2026-11-13 已经删掉（只留插件那条），本次没有再
  恢复；`compaction/start` 是当前唯一保留的状态类兜底写入。
- `clear()` 语义：断连只清非 pinned，`pinned` 槽位保留（它们是「连着就该一直看到」的状态）。

### 5.7 档位表（旧 §22.2）

病历：priority 字面量散落在插件 10 处 + App 5 处（6/10/15/35/40/50/55/60/70…），改一处要在
两边各读一遍代码；`p0=0` 这种 0 值还会被 `Number(priority) || 50` 吞成 50，把「打断式」
排到最后。本轮把它收成一张表（Java 与插件同名同值）：

| 档位 | 数值 | 语义 |
|---|---|---|
| `p0` | 0 | 打断式：审批 / 等待用户 |
| `p1` | 5 | 失败 / 重试 / 断线 |
| `p2` | 10 | 权限 / 沙箱 |
| `p3` | 20 | 插话队列 / 上下文压缩 |
| `p4` | 30 | 目标 / 计划（**默认档**） |
| `p5` | 50 | 后台任务 / 子代理 / 统计 |

- **App**：`status/SlotPriorities.java`（`P0..P5`、`DEFAULT=P4`、`of(level)` / `isKnown` /
  `levelOf`）。`Slot.priority` 仍是 `int`（构造签名不变），档位只是「取值的唯一来源」。
- **插件**：`index.js` 顶层 `SLOT_PRIORITY = {p0:0,p1:5,p2:10,p3:20,p4:30,p5:50}`；
  `broadcastSlot` 统一归一化 —— 已知 `level` → 表换算；否则旧 `priority` 数值；都没有 → p4。
  归一化后帧里**同时**带 `priority`（旧 App 排序）与 `level`（新 App/快照/诊断）。
- **App 解析**：`RemoteSlotBridge.resolvePriority`：已知 level → 表；未知 level 且有旧
  `priority` → 用旧数值（向后兼容）；都没有 → p4（与插件默认一致）。
- `phone_ui` 新增 `level` 参数（enum p0..p5），`POST /v2/action {action:'ui.slot'}` 也收
  `body.level`。`pushUiSlot` 的 `Number(priority) || 50` 一并修掉（p0=0 不再被吞）。

旧数值 → 新档位的落点（本轮全部改完，插件里已无裸数字）：

| 槽位 | 旧值 | 新档位 |
|---|---|---|
| 等待用户 / 审批（`pushPhoneFailureSlot` 的 ui_component、`remote/attention` 待审批） | 5 / 6 | p0 |
| `retry`、断线（`remote/attention`） | 15 / 6 | p1 |
| `permission`、权限沙箱 | 35 | p2 |
| `compaction`（插件 + App 兜底）、`inbox`、通道落后 | 10 / 20 / 6 | p3 |
| `goal`、`remote/attention` 之外的稳定目标 | 30 | p4 |
| `jobs` 40、`subagents` 50、`mcp` 55、`schedule` 60、`remote_cmd` 70 | 40–70 | p5 |

### 5.8 容量上限与粘性淘汰（旧 §22.3）

病历：`DockSlotBus` 原先没有容量上限 —— 插件状态越多、补发/快照越多，列表越长，
`list()` 还要反复全量排序；甲板/全屏列表可能无界增长。

- `DockSlotBus.MAX_SLOTS = 16`；每次**成功写入**（插件 `update` 与本地兜底 `updateLocal` 都算）
  后在 `SlotLedger.enforceLimit(16)` 里压回上限。
- 淘汰规则：候选 = **非 pinned**；先淘汰 `priority` 数值**最大**的；同档淘汰
  **最旧**（按最后一次写入时间 `updatedAt`，同 id 覆盖会刷新时间 —— 持续更新的槽更该留下）。
  `pinned` 永远不参与；全是 pinned 且超限时放弃淘汰（宁可多几条，也不挤掉固定状态）。
- **淘汰是粘性的**（2026-11-15 第 2 轮修订）：`lastRev` 保留，同 rev 的补发/快照帧不能再把它
  塞回来（同 rev 规则见 §5.4）。真机曾因此空转：快照 18 条 + 补发帧反复重塞，一秒内淘汰
  日志打 29 行。
- 诊断：`DockSlotBus.evictedCount()`（自上次 `clear()` 起的累计数）；每条淘汰一行
  `[dock] 上限 16，淘汰 id=…（规则：priority 最大、同档最旧；累计淘汰 N 条）`。

### 5.9 「还有 N 条」与全屏列表（旧 §22.4）

甲板仍最多渲染 **3 条**（`AIChatActivity.DECK_MAX_SLOTS`；不再复用配置表安全阀
`DshUiCatalogLoader.HARD_MAX=6`，避免配置把甲板撑高）。第 4 行是**可点的溢出入口**：

- 文案：`还有 N 条 · 点开看全部`（N = 总线总数 − 实际渲染行数，所以它是「隐藏条数」而不是总数）；
- 样式比插槽行弱（无状态点、12sp、灰色、右侧 `›`），一眼区分「状态」与「列表入口」；
- 点击进 `RemoteDeckListActivity`（全屏列表，按 priority 升序，标题栏显示总条数）。

### 5.10 快照拉取：`GET /v2/slots`（旧 §22.5）

病历：`ui/slot` 只有推帧语义，连上后没有「当前状态」拉取口；生产者不推，甲板就一直没有内容。
插件新增快照端点（`AIChatActivity`/`RemoteSlotBridge` 只调一次）：

```json
{ "ok": true, "v": 2, "session_id": "…", "count": 3, "total": 18, "truncated": true,
  "slot_epoch": 1794686153763,
  "slots": [ { "id": "tier-p0", "rev": 1, "epoch": 1794686153763, "ttl_ms": 0,
               "priority": 0, "level": "p0", "title": "审批等待", "detail": "p0 · 审批等待",
               "action": "", "warn": false, "kind": "display", "board": "state",
               "pinned": false, "count": 0, "session_id": "" } ] }
```

重建口径（从插件内存 `SLOT_LAST`，与推帧**同一份事实**）：

| 规则 | 说明 |
|---|---|
| 会话隔离 | `rec.sid` 非空且 ≠ `session_id` → 跳过（B18）；全局槽（`sid=''`）任何会话都给 |
| 移除帧 | 不进快照（`SLOT_LAST` 里的 `remove:true` 只是「删过」的记忆） |
| TTL | 已过期（`age > ttl`）跳过；返回 `ttl_ms` 是**剩余**存活时间，App 收到后照常换算 `expireAt` |
| 排序/上限 | `priority` 升序（同档按 id），最多 **32** 条；`total`/`truncated` 说明被截断 |
| 权威字段 | `id/rev/epoch/priority/level/title/detail/kind/count` 全部带上，App 逐条走同一条 `apply()` |

App 侧：

- 触发：`RemoteConnection` 的通道 `onConnection(true)`（WS hello / SSE ready 之后）→
  `RemoteSlotBridge.pullSnapshotAsync`，**一次连接只拉一次**，断连复位；后台线程，失败静默重试一次。
- 落点：`pullSnapshotOnce` → `RemoteSlotBridge.apply`（与推帧同路径，rev/epoch/TTL/空帧语义一致），
  另提供 `applyAll(JSONArray)` 批量入口供单测与未来复用。
- 前提：`DockSlotBus.clear()` 现在连**插件世代水位**一起重置。否则重连后快照第一帧会因
  「epoch 比记录的旧」被整帧丢弃 —— 快照就白拉了（这也是本轮顺手补的一条）。

### 5.11 测试与真机（旧 §20.6 + §22.6）

两轮改动共享同一批测试类，数字按轮次增长，合并如下（不重复列两遍同样的事实）。

**第 1 轮：rev / TTL / 单一写者 + epoch（2026-11-15 03:05 / 03:38）**

| 项 | 结果 |
|---|---|
| `SlotLedgerTest`（新增，纯 JVM） | 18/18 通过：rev 递增/回退丢弃/同 rev 重复丢弃/rev=0 兼容、移除水位、断连后同 rev 补发、插件重启 rev=1、epoch 变大清旧插件槽+清水位、旧 epoch 拒绝、同 epoch 无操作、TTL 边界、无 ttl 不清、到达即过期、pinned 保留、3s 兜底窗口（3999ms 让位 / 4000ms 生效） |
| `DockSlotBusTest` | 11/11 通过（原 6 + 新增 5：rev、旧帧兼容、TTL sweep、pinned clear、本地兜底） |
| `RemoteSlotBridgeTest`（新增） | 9/9 通过：空帧移除、乱序移除不杀新槽、ttl→expireAt、无 rev 旧帧照旧覆盖、remove+rev、**插件重载后 rev=1 覆盖 rev=99**、旧 epoch 丢弃、世代切换清旧插件槽、重载后空帧移除生效 |
| 状态包其余用例 | 68 项中 4 项**既有失败**（`DshUiCatalogLoaderTest` 期望默认 1/2、代码是 2/5；`StatusDeckControllerTest` 同因），与本次改动无关（未触碰这两个类） |
| 插件 `slot.test.mjs`（新增，真 WS + 真 HTTP） | 9/9 通过：rev 1→2、epoch 稳定、jobs 默认 ttl=30000、goal 无 ttl、显式 3000 透传、空帧移除 rev 递增+带 epoch、新订阅补发沿用 rev/epoch、过期瞬时帧不补发、phone_ui 工具带 rev/ttl、空帧移除、无 id 拒绝 |
| 插件既有自测 | `ws_frames.test.mjs` 10/10、`caps.test.mjs` 11/11、`cardcheck.test.mjs` 23/23 通过；`node --check index.js` OK |
| assets 同步 | `tools/.../index.js` 与 `src/main/assets/remote_dsh/plugin/index.js` 字节一致，SHA256 `FD3BDF0BC5DE412F762E245EACA73931C0638FC8A2190FD9E22197FF0B79EEDC` |
| 编译 | `gradlew --offline assembleDebug` BUILD SUCCESSFUL |
| 装机 APK | `答题宝-debug-2.2.apk`，SHA256 `C6520E9A155E52EECCB48C30959435B0B64E41354C1DBCE2EEAE98F124FA922B`，`adb -s 192.168.1.10:41193 install -r` Success |
| 真机 · 插件重载 | `plugin_manager set_plugin include:smartquiz-remote enabled:false→true`（不再需要重启 DSH）；`/status` 出现 `slot_epoch=1794684643532`；logcat `[dock] 插件世代切换 epoch=1794684643532 清掉 4 条旧插件槽`（旧方案这里会全部按「回退 rev」丢弃） |
| 真机 · A/B 一行 | 最终 APK 上 `phone_ui(id=dock-rv)` 同 id 推「甲板 rev 测试 A」→「甲板 rev 测试 B」：甲板只有一行且显示 B（截图 `build/dock-verify.png`） |
| 真机 · 空帧移除 | `phone_ui(kind=slot, id=dock-rv)`（title/detail 都空）返回「已移除手机插槽 dock-rv」，同一屏甲板该行消失（截图 `build/dock-verify/final-removed2.png`） |
| 真机 · TTL | 最终 APK 上 `phone_ui(..., ttl_ms:3000)` 后 logcat `[dock] TTL 过期移除 id=dk-ttl4`（03:37:45 推 → 03:37:49 清，3s TTL + 1s 清扫粒度）；早前 `dk-ttl2`（5000ms）、`dk-ttl3`、`probe-new` 同样按时消失 |

**本轮真机为什么能验到**：上轮「插件未重载」的缺口这次用 `plugin_manager set_plugin` 补上了
（不必重启 DSH）；重载后 App 走了 SSE 快速重连、没有观察到 `online=false`，正是旧方案
「靠断连清 rev 水位」失效、新帧被当回退丢弃的场景 —— 加 epoch 后日志直接出现
「世代切换/清旧插件槽」，phone_ui 的更新与移除也随之恢复。

**第 2 轮：档位 / 上限 / 快照（2026-11-15 04:07）**

| 项 | 结果 |
|---|---|
| `SlotPrioritiesTest`（新增，纯 JVM） | 7/7：档位数值 0/5/10/20/30/50、表恰好 6 档、大小写/空白不敏感、未知 level → p4、`levelOf` 不硬凑历史中间值（6/15/35/60 → 空）、`Slot.priority` 仍是 int 且排序向后兼容 |
| `DockSlotBusTest` | 16/16（原 11 + 第 2 轮新增 5：上限 16、priority 最大先淘汰、同档最旧、pinned 永不淘汰、全 pinned 超限放过、淘汰粘性） |
| `SlotLedgerTest` | 18/18（断言未改；`clear()` 归零世代水位后仍全绿） |
| `RemoteSlotBridgeTest` | 19/19（原 9 + 新增 10：只给 level、level 优先于 priority、未知 level → p4 / 保留旧 priority、两者都无 → p4、批量 3 帧按序、批量乱序 rev + remove、批内换世代清旧槽、坏帧/无 id 跳过、批量 TTL 换算） |
| 插件 `slot.test.mjs`（真 WS + 真 HTTP） | 15/15（原 9 + 新增 6：level 补同名 priority、`phone_ui` 支持 level、`/v2/slots` 结构与 priority 升序、会话隔离、移除/过期不复活、最多 32 条） |
| 插件既有自测 | `ws_frames.test.mjs` 10/10、`caps.test.mjs` 11/11、`cardcheck.test.mjs` 23/23；`node --check index.js` OK |
| assets 同步 | `tools/dsh-plugin-smartquiz-remote/index.js` 与 `src/main/assets/remote_dsh/plugin/index.js` 字节一致，SHA256 `B7F5BE5B65CBF08137B7BCC798C4487F5A2AE107ABC0A9A58D96A8E8A1360751` |
| 编译 | `.\gradlew.bat --offline assembleDebug --console=plain` BUILD SUCCESSFUL（先删了 stale 的 `build/intermediates/{classes,javac}/debugUnitTest`） |
| 装机 | `答题宝-debug-2.2.apk` SHA256 `1ADB7955EB2E861AF8B14D1C0E1E3AA9A0AD894C5584D754CAFB382E673D8DD5`，`adb -s 192.168.1.10:41193 install -r` Success（装机后 `am force-stop` + `monkey` 冷启验证） |
| 真机 · 档位顺序 | `phone_ui` 用 `level` 逆序推 p5→p4→p2→p1→p0 五条，甲板/全屏列表按 **p0 → p1 → p2 → p4 → p5** 排列（截图 `build/deck-polish-list.png`，列表里 p2/p4/p5 的并列项按 id 稳定排序） |
| 真机 · 上限 + 溢出聚合 | 总线里共 20 条（5 个档位槽 + 12 个 p5 + 插件自带 jobs/goal/permission）：甲板显示 3 条 + **「还有 13 条 · 点开看全部」**（截图 `build/deck-polish.png`）；点该行进入 `RemoteDeckListActivity`，标题「16 项」（截图 `build/deck-polish-list.png`）；logcat `[dock] 上限 16，淘汰 id=cap-01…cap-04（累计淘汰 1…4 条）`，修复粘性淘汰后**不再空转** |
| 真机 · 快照恢复 | `adb shell am force-stop` + `monkey` 重开（全程没有再推帧）：logcat `[dock] 快照拉取完成 session=session-cb82ea23… 条数=20`，甲板直接恢复成同样的 3 条 + 还有 13 条（截图 `build/deck-polish.png`，最终 APK 上 04:07 拍摄） |

**真机为什么能验到快照**：`force-stop` 后插件内存里仍有 20 条槽（tier-*/cap-*/jobs/goal/permission），
新进程连接建立时 `RemoteConnection.onConnection(true)` → `GET /v2/slots` 拿到 20 条并逐条
`RemoteSlotBridge.apply`（日志里的「条数=20」），再被总线按 16 条上限淘汰 4 条 p5 ——
与推帧同一条路径、同一套 rev/epoch/TTL 规则。

### 5.12 改动文件（旧 §20.8 + §22.7）

- App：`status/SlotPriorities.java`（新，第 2 轮）、`status/SlotLedger.java`（新，第 1 轮）、
  `status/DockSlotBus.java`、`tool/RemoteSlotBridge.java`、`ui/activity/AIChatActivity.java`、
  `tool/RemoteStatusSlots.java`、`tool/RemoteCommandExecutor.java`、
  `tool/RemoteConnection.java`（第 2 轮加快照触发）
- 测试：`SlotPrioritiesTest.java`（新）、`DockSlotBusTest.java`、`SlotLedgerTest.java`（新）、
  `tool/RemoteSlotBridgeTest.java`（新）
- 插件：`tools/dsh-plugin-smartquiz-remote/index.js`、`slot.test.mjs`（新）、
  `src/main/assets/remote_dsh/plugin/index.js`（同步）

### 5.13 本章边界（见 §7.5）

溢出聚合只做可点一行、上限是总线级、快照只覆盖插件内存、拉取只在连接建立时、level 未参与
排序之外的逻辑、compaction 无周期心跳、mcp 只增不减 —— 见 §7.5。

## 6. 消息流渲染与回收：parts 管线、分页窗口、在线对照

> 本章解决什么问题：同样一套 RecyclerView 配置，在线 agent 不卡、DSH 同步消息卡；本章把
> 在线侧的 parts 复用与消息流回收参数列清楚，定位差异在「消息粒度」而不是「数据量」。

### 6.1 在线 agent 的 parts 管线（旧 §13.1）

用户口径：「是历史显示没处理好……看看在线 agent 的消息怎么渲染的，消息流怎么回收性能的。」

```text
ChatMessage ──AssistantTurn──▶ parts[]：reasoning / text / tool_call / tool_group / data
                                  │
                          TurnPartsHost：按 part id 复用视图，按内容指纹只重绑变化的那段
```

- **一条消息 = 一次模型生成 = 1~5 个 part**（思考折叠成一行、正文一段、连续工具折叠成
  「已运行 N 个工具」一张组卡、产物卡一张）；
- `PartsRendererFlag` 出厂开（2026-10-24），DSH 同步过来的消息**也走这条**；
- 复用粒度是 **part**：重绑时只重建指纹变了的 part，WebView/图表/卡片不白建。

### 6.2 消息流怎么回收性能（旧 §13.2）

| 机制 | 位置 | 值 |
|---|---|---|
| 分页加载 | `HISTORY_PAGE_SIZE` | 50 条一页 |
| 内存窗口 | `HISTORY_ARCHIVE_KEEP` / `HISTORY_ARCHIVE_CHUNK` | 保留 150，超出每次移出 50 |
| 视图缓存 | `setItemViewCacheSize` | 4 |
| 固定尺寸 / 关动画 | `setHasFixedSize(true)` / `setItemAnimator(null)` | — |
| 稳定 id | `setHasStableIds(true)` | — |
| 重组件释放 | `releaseAndClearComponents` | 滚出屏幕即放 WebView/播放器 |

### 6.3 DSH 为什么卡：消息粒度对照（旧 §13.3）

回收是按**消息**做的。DSH 一个 turn 有 51 步，转换器把它压成**一条消息**
（`1 条消息 = 51 段思考 + 51 段正文 + 245 张工具卡 ≈ 150+ 个 part 视图`，实测数据见 §3.1）：
150 个视图在一个 item 里，RecyclerView 无从回收；每次滚动重绑都是「重建一个巨型 item」。
在线 agent 一条 1~5 个 part，同样的列表、同样的回收配置就不卡 —— 差异在**消息粒度**，
不在数据量。

| 维度 | 在线 agent | DSH 同步消息 |
|---|---|---|
| 一条消息的 part 数 | 1~5 | 150+（优化前）→ ≈13（轮次合并后）→ 流式期即时 2 段 |
| 复用粒度 | part（按指纹） | 先按 part，2026-11-15 后封口段整段复用（§3.5） |
| 回收单位 | 消息 | 消息（6 步一条，见 §3.7） |

修法分两步：轮次合并把 part 数拉回在线量级（§3.3），尾段增量投影让封口段不再每帧重建（§3.5）。

## 7. 已知边界与未做项

> 本章解决什么问题：把散落在各章的「没做到/降级」集中到一处，保留原始理由与影响面，
> 避免在正文里反复打断；正文对应位置只留「见 §7.x」。

### 7.1 事件通道与能力协商（对应 §1；旧 §16.4 / §17.5 / §18.7）

**WS 帧对齐（旧 §16.4）**

- 手机端这次取到的 WS 新样本是 approval / `__force_resync` / ui-slot；真实 `event`
  帧的手机侧样本没取到 —— 手机跟随的 `session-cb82ea23` 在验证窗口内处于「等子代理」
  状态，没有新事件。真实 `event` 帧由**真 WS 客户端 + 真 `session/event` 总线**验证，
  帧格式与手机 `case "event"` 的解析口径一致（`o.optJSONObject("event")`）。
- `pulse` 仍未由 WS 主动发（只有协议级 ping；白名单里的 `pulse` 保持原状）；
  非 live 会话的 WS 实时事件仍靠 `pull` 补（SSE 侧是轮询持久化日志，两条线仍有差异）。
- 未改 Android Java、未改 `/ws` 全功能端点、未改文件帧与 ack 语义（本轮范围）。
- §1.1 病历里的「手机只在 run() 开头试 3 次 WS、失败后不会重新升级」已由 §1.3 的
  周期重试修复，不再列为未做项；原始现象保留在 §1.3 病历里。

**连接重试（旧 §17.5）**

- **插件重载场景未验证**：两次 `plugin_manager set_bundle`（target 为
  `@local/smartquiz-remote`、`enabled=false`）均被 `HMR transactions cannot be nested` 挡住，
  **未能完成真机插件重载验证**；按要求改用「杀 App 进程再启动」验证；
  另用 TCP 代理模拟链路中断，验证了「不重启 App 自动回到 WS」，但它走的是连接失败路径，
  与「插件重载瞬间服务端主动关闭 socket」不完全等价。
- 重试调度在主线程 `Handler.postDelayed` 上：主线程若被长时间阻塞，重连时机会顺延
  （与既有 UI 线程模型一致，未另起调度器）。
- WS 常连后 `/ws/ui` 仍未独立保活：event 通道断开时会一起重建，ui 单边断开不自愈
  （与改动前一致，不在本次范围）。

**能力协商（旧 §18.7）**

- **声明层没有可补的 cap**：手机端 25/25 已声明，本次没有新增/删除任何能力名；
  补的是「回执消费 + 可观测性」，不是声明。
- **版本号仍不上行**：没有加 `caps_version` query / header，也没有在 `allows` 里校验版本；
  `version` 字段目前只是服务端常量。真正的版本协商（不兼容时降级）未做；本次也没有真实
  版本不兼容样本可验。
- **`parts.questions` 仍未独立门禁**：反问继续由 `parts.approval` 放行；拆开会让
  「只声明 parts.questions」的客户端行为变化，本次按「不改既有语义」不动。
- **`__force_resync` 仍未门禁**：理由见 §1.5（旧客户端也靠它重建，加门禁会卡死旧端）。
- **`/ws/alert` 仍未打开**：审批/反问走 `/ws/events`，功能可用；审批与 slot 抢同一条 TCP
  的风险仍在，属端点分工遗留，不在 caps 范围。
- **`cmd.shell` / `parts.tool-cards` / `parts.data-cards` / `events.stream` / `events.replay` /
  `events.ack` 是「声明但服务端未用作门禁」**：保留为前向契约，不删（删了会破坏两端同名约定）。
- `/status` 的 `clients[]` 来自 `STREAM_HUB`，只覆盖**当前订阅中的** SSE/WS 连接；
  `pull`、`/v2/view` 这类短请求不出现（它们没有长连接身份）。
- 手机端设置页 UI 未做（按要求只暴露 getter）。
- **caps 字段的真机复验未做**：`/status` 的 `caps` 与 WS hello 的 `caps` 只在 Node 自测/探针里
  验证过；2026-11-15 02:14 时真机 8218 仍是旧插件（HMR 事务嵌套挡住 `set_plugin`），日志走的是
  「缺失=? 来源=local」兜底。03:05 那轮用 `plugin_manager set_plugin` 打通了插件重载（见 §5.11），
  但没有留下 caps 字段的复验记录；重启 DSH 后应看到「缺失=0 来源=ws:hello」。
- **审批真机只验到「帧到达手机」**（独立审批通知 + `test-*` id + 应答 `allowed-once`），
  没有在手机 UI 上人工点「允许」（远程操作界面不做）；caps 放行逻辑由 `caps.test.mjs` /
  `ws_frames.test.mjs` 的断言覆盖。

### 7.2 事件分流（对应 §2；旧 §15.6）

- **快照只有最近 20 条 role/text**：重建只对齐「当前这一轮」的正文，工具卡/思考/子代理
  轨迹不会从快照恢复（会等后续事件或下一轮补），这是服务端 `snapshotTurns` 的结构决定；
- **帧循环用 `postDelayed(16ms)` 而不是 `Choreographer`**：节拍等价，省一个
  FrameCallback 生命周期；代价是后台/低帧率时最多晚一帧；
- **队列上限按条数（2000）不按字节**：逐条 `toString()` 估字节的开销大于收益，
  1 MiB 口径留给后续版本；
- 快照请求并发收敛为「同一时刻只有一个在飞」（`dshResyncInFlight`），后续请求合并；
- 「断」这一档只做主动重连（`forceReconnect`），没有像 dsh-Go 那样发 1013 让服务端断连
  —— 我们的服务端没有对应帧，客户端主动断更直接。

### 7.3 消息转换与投影（对应 §3；旧 §19.7）

- **文本旁路未做**：活动段的纯文本追加仍走区间 `project()`（0–1ms），没有改成
  `updateAIMessageContent` 之类的轻量更新。原因：投影同时要维护
  `roundSnapshots/contentRoundBounds/components`，只改 `content` 会让三份视图不一致。
- **审批是消息级字段**：审批/反问落在段消息的 `msg.approvals/questions` 上，
  不进 `DshProjection`。守卫里按段记了审批计数（失效口径），但审批实际只写当前尾段；
  封口段事后收到审批时，投影重建不会搬动审批卡，只保证指纹口径可见。
- **子代理卡不在指纹里**：`subagent_card` 是 `deck_only`，且投影会把当前全部子代理
  写进每段；封口段里的子代理卡可能落后于甲板实时值（消息流不画它，落盘数据除外）。
  本次未把子代理指纹纳入（纳入会让子代理活跃时所有封口段每帧失效）。
- **同一帧里跨两轮**：`endedHere` 已避免把新回合第一段误封；但如果「上一轮结束」和
  「下一轮开始」在同一帧，上一轮的多段不会补最后收口投影（下一轮开始会 reset 段列表）。
  实际事件流里极少见，重连补拉时可能出现。
- **快照重建**：`applySnapshotResync` 会 reset 段缓存并对所有已落屏段重投影一遍；
  这是数据源整体换掉，守卫计数随 reset 归零，不计入「封口重建」。
- **未改**：`EventBatchQueue` / `EventFlowPolicy` 参数与语义、插件、卡片渲染。
- **卡片跨段重复**：`DshProjection` 仍把全轮卡片/子代理写进每个段（旧拆分也这样），
  本次未改投影的卡片归属，避免动渲染口径。

### 7.4 组件与卡片（对应 §4；旧 §5 / §10.2 / §14.6 / §21.7）

仍然「接不上」的三类（`job` 已在 §4.6 通过 `JobCardView` 静态模式接上，移出本表）：

| 想接的 | 为什么不行 |
|---|---|
| `diff` | `workspace/changes` 事件只有 `{turn}`，没有逐文件 diff 载荷；`deliverables/presented` 也只给路径 |
| `image_grid` | `image/offload` 只有附件引用，插件没有取图端点 |
| `steps_card` | 可用，但 `tool-workflow/*` 已用它；子代理轨迹需要 `childId` 累积语义，故保留 `subagent_card` |

其余边界：

- **推送卡必须回聊天页才落屏**：`ui/card` 由聊天页处理；手机在首页/工具页时靠暂存补发，
  但必须回聊天页才会落屏。「任何页面都能弹」要用 `phone_ui` 的 toast/notification（§4.8）。
- **自定义选择类控件（日期/取色/评分等）不参与实时重算**（见 §4.13 限制）；
- `compare` 少于 2 项不拒绝，按 1 项渲染（产品口径是 2~4，但拒绝会掉原始 JSON 兜底，
  故选择可渲染 + 文档说明）；
- data:image 按单张 512 KiB、总 2 MiB 硬限；更长的图需要拆卡或走文件 URL；
- `cardcheck.js` 的结构体积现在显示「结构体积」而不是总体积，调用方若按旧日志文本做
  正则匹配需要同步。
- **嵌套卡片的绑定/公式只求值一次**（卡片没有「输入变化 → 重算」的通道），之后不会随输入刷新；
- **卡片内部输入控件的变化不会回写外层 layout 的 `props.data`**（`LayoutCardView` 会另开自己的
  `viewRefs`，回传走它自己的 input collector，与节点级绑定互不干扰）；
- **嵌套卡片不吃节点级 `style` 的容器覆盖**：`buildNode` 已把节点 `style` 展开成顶层属性，
  而 `ComponentRegistry` 只认 `props.style`，所以嵌套卡片的 padding/圆角/底色由卡片自身决定；
- **卡片节点上的 `children` 不做渲染**（卡片类型没有子节点语义），只有 `items` 等数据字段进 props。

### 7.5 插槽甲板（对应 §5；旧 §20.7 部分 + §22.8）

- **溢出聚合只做了「可点的一行」**：没有做按 `board`/`kind` 归组或「前 3 条 + 分组计数」的形态；
  全屏列表仍是平铺（按 priority 排序），未做分组标题。
- **上限是总线级、非会话级**：16 条是全局容量，多个会话的槽位共用；pinned 超限时允许超过 16。
- **快照只覆盖「插件内存里还在的槽」**：插件重启后 `SLOT_LAST` 为空（job/goal 等另有落盘，
  但通用 `phone_ui` 槽不落盘），此时 `/v2/slots` 只能返回空数组 —— 这是「内存状态」的边界，
  不是漏拉。
- **拉取只在连接建立时**：没有做「App 从后台回前台再拉一次」（回前台走的是事件补拉 + 推帧）；
  也没有做客户端定时对账（避免和推帧打架）。
- **level 未参与排序权重之外的任何逻辑**（例如没有按档位做颜色/分组）。
- **模型/预设独立槽未做**：`agent-preset/selected`、`model/selection` 不再出卡，信息暂时塞在
  permission 插槽的 detail 里，「后续可加独立槽」尚未落地（见 §5.2）。
- **TTL 续期不均**：`compaction` 只有 start/end、没有周期心跳，压缩超过 30s 会提前消失；
  `mcp` 只增不减，靠断连 clear/重启消失（见 §5.5）。
- **同 rev 复活收紧的边界**：`clear()` 已清水位，「断连后按原 rev 补发恢复」只走 `prev==null`；
  如果未来需要跨 `clear()` 的补发恢复，要重新设计水位（见 §5.4）。
- 未改：事件分流、投影、卡片渲染；`phone_ui` 的历史 `priority` 参数仍保留（数值语义不变）。

第 6 章（消息流渲染与回收）没有独立未做项；相关限制分别归入 §7.3（投影）与 §7.4（卡片）。

## 8. 附录：时间线与提交号

> 本章解决什么问题：把各轮交付按时间与提交号串起来，并给出旧编号映射与术语/计数口径，
> 方便从任一结论回溯到当时的提交和代码位置。

### 8.1 时间线（按日期）

**2026-11-14**

| 时间 | 提交 | 交付 | 落点 |
|---|---|---|---|
| 19:41 | `022d1a7b` | 清掉已落盘的 DSH 系统通知气泡（background job …） | §3.4 前身 |
| 19:47 | `d7eece75` | DSH 63 种会话事件全部绑定（插话入队/子代理目录/PTC/钩子/图片移出/模型切换） | §4.1–4.3 背景、§4.2 验证 |
| 19:52 | `fc23000e` | dsh_notice 提示卡可点开看全文并复制 | §4.2 |
| 19:57 | `9282937f` | 提示卡可跳转：插话卡滚到排队气泡、子代理卡滚到轨迹 | §4.2 |
| 20:09 | `a589e178` | 子代理一创建就有轨迹卡；发送方式三档；dsh-Go 桥接鉴权结论 | §4.3 |
| 21:32 | `b11cd3cc` | 合并 dsh_notice→alert_card、dsh_command→code_card | §4.2 |
| 21:39 | `2b390e0c` | 第二轮审计：PTC 改 tool_call、修 alert_card 正文丢失 | §4.5 |
| 21:49 | `e70cafc6` | dsh_job 接进内置 job（静态模式），原生卡只剩 subagent_card | §4.3 / §4.6 |
| 22:01 | `73c695d9` | 瞬时状态整体搬进甲板插槽，「完成即消失」 | §5.2 |
| 22:12 | `bc8e609b` | 插件推送动态组件 + 动态 layout（手机本地绘制） | §4.7 |
| 22:37 | `8208135d` | 未知事件动态兜底 + ui/card 推送链路修通 | §4.4 / §4.7 |
| 22:43 | `9e0332e9` | 推送卡暂存 + 进页面补发；修 REMOTE_CARD 落盘被丢 | §4.8 |
| 23:07 | `ae69527e` | 纯状态事件不再变成「电脑事件 · xxx」JSON 兜底卡 | §3.4 / §4.4 |
| 23:16 | `0ff39b63` | 抄 dsh-Go 的语义渲染令牌与推卡校验器 | §4.9 / §4.10 |
| 23:27 | `ead5cf05` | 投影预算截断 + 假事件卡一次性清理 | §3.2 / §3.4 |
| 23:36 | `9f986c82` | 一条消息 150+ part 卡顿 → 轮次合并到 6 段 | §3.3 |
| 23:43 | `7d322920` | 消息派发器改密：一个 turn 按 6 步切成多条消息 | §3.3 / §3.7 |
| 23:46 | `5b467a1f` | 实时派发器同粒度：一轮结束把 mirror 气泡拆成多条小消息 | §3.7 |
| 23:48 | `1c602f9c` | 分段消息派发即分配独立 id；组件 id 带段号 | §3.7 |
| 23:50–23:57 | `82a5746e`、`8ca4d5ae`、`a0355211`、`7f409254`、`569f7078` | DSH id 桶扩容/预留/连续性测试（分段 id 的配套） | §3.7 配套 |

**2026-11-15**

| 时间 | 提交 | 交付 | 落点 |
|---|---|---|---|
| 00:00 | `5cfe02ab` | part 类型闸门改成问渲染器注册表 | §6.1（parts 管线） |
| 00:04 | `6f142fa5` | 失败工具产出 error part | §6.1 |
| 00:18 | `81d6b852` | 旧 DSH 专用卡类型登记别名，不再被动态兜底画成 JSON 卡 | §4.4 |
| 00:35 | `0af32a6e` | 关掉模拟通道（/v2/emit）+ 团队卡标题去重 | 邻近清理（正文未单列） |
| 01:06 | `bc43a5c6` | 动态卡补 keyValue/compare、data:image 与有界公式绑定 | §4.10–4.13 |
| 01:28 | `44d4e3d6` | 事件分流：批处理 / 快照兜底 / 三级背压 | §2 |
| 01:44 | `6be0d994` | WS /ws/events 下发真实事件与重建/审批帧 | §1.1–1.4 |
| 02:02 | `5cd948ee` | WS 连接周期重试，断开后自动回到推模式 | §1.3–1.4 |
| 02:14 | `7ce78e1d` | 能力协商体检：caps 摘要回执 + 客户端能力可观测 | §1.5–1.8 |
| 02:37 | `374ccc0d` | 尾段增量投影：封口段复用，守卫计数封口重建=0 | §3.5–3.9 |
| 03:05 | `de23b4af` | 插槽甲板 rev/TTL 与单一写者 | §5.3–5.6 |
| 03:38 | `79c5bac3` | phone_ui 空帧移除 + 插件世代 epoch | §5.3–5.4 |
| 03:39 | `869c71d2` | 校验器不再把 items 数据当节点树（真机 422 误判） | §4.14 |
| 03:52 | `8c4ae352` | layout 子节点支持嵌套卡片类型内嵌渲染 | §4.14 |
| 04:07 | `77299b7c` | 插槽甲板命名档位/容量上限/快照拉取 | §5.7–5.12 |

### 8.2 旧编号 → 新章节

| 旧编号 | 新落点 | 说明 |
|---|---|---|
| §1 | §4.1 | 现有分层与「不新增消息/视图类型」结论 |
| §2 | §4.2 | 合并 DSH 专用卡（39→37） |
| §3 | §4.3 | 保留的原生卡（最终只剩 subagent_card） |
| §4 | §4.4 | 动态组件兜底 |
| §5 | §4.5 | 第二轮：内置工具卡/原生控件（36 张时期的审计） |
| §6 | §4.2 | 第一轮验证（并入合并一节） |
| §7 | §4.6 | 第三轮：改原生组件与三条规矩 |
| §8 | §5.1–5.2 | 瞬时状态搬进插槽、移除写法 bug |
| §9 | §4.7 | DSH 专用动态组件 + 动态 layout |
| §10 | §4.8 | 推送暂存 + 进页面补发 + REMOTE_CARD 落盘 |
| §11 | §4.9–4.10 | 语义令牌 + cardcheck 校验器 |
| §12 | §3.1–3.2、3.4 | 转换器病历、投影预算、假事件卡清理 |
| §13 | §3.1、§3.3、§6.1–6.3 | 在线 parts 管线、回收、消息粒度病历、轮次合并 |
| §14 | §4.10–4.13 | keyValue/compare、data:image、绑定/公式、校验器增补 |
| §15 | §2 | 事件分流（批处理/快照/背压） |
| §16 | §1.1–1.4 | WS 帧对齐与验证 |
| §17 | §1.3–1.4 | WS 连接周期重试与验证 |
| §18 | §1.5–1.8 | caps 契约/声明/改法/验证 |
| §19 | §3.5–3.9 | 尾段增量投影与守卫计数 |
| §20 | §5.3–5.6、5.11–5.12 | rev/TTL/epoch、单一写者、测试与文件 |
| §21 | §4.14 | layout 嵌套卡片 |
| §22 | §5.7–5.13 | 档位/上限/溢出/快照/测试与文件 |

旧稿标题里的任务书编号与任务代号也一并保留在这里，正文不再重复：§15 对应任务书 §14；
§16 = **WS-FRAMES**；§17 = **WS-RETRY**（任务书 §17）；§18 = **CAPS-AUDIT**（§18）；
§19 = **TAIL-PROJECTION**（§19）；§20 = **DOCK-REV/TTL**（§20）；§22 = **DECK-POLISH**（§22）。

### 8.3 术语与计数口径（一致性校对）

| 术语/数字 | 本文口径 |
|---|---|
| 插槽 / 甲板 | `ui/slot` 的单个状态条目 / 聊天页顶部的插槽区；两者同义，代码里是 `DockSlotBus` |
| 事件分流 | 批处理 + 快照兜底 + 三级背压（§2），不是「按事件类型分流」 |
| 封口段 | 流式回合里不再是最后一段、或回合已 `turn/end` 的 6 步段；封口段不再每帧重建（§3.5） |
| rev / epoch | rev 是按 id 单调递增的帧版本；epoch 是插件实例世代（§5.3–5.4） |
| 通用卡 / 注册实例 | 37 张通用卡（不含 layout 树卡与原语桥接）；38 个非循环 `register(new …)` + 32 个 `LayoutPrimitiveCardView` 桥接 = 70 个注册实例 |
| 事件种类 | 63 种（对照 DSH 0.2.2-rc.3 `KNOWN_SESSION_EVENT_TYPES`，见 `DshEventCoverageTest`） |
| caps | 25 项（`SERVER_CAPS` 与 `RemoteCaps.ALL` 同名同量） |
| 测试条数 | 以各节标注为准；归并时核对：`DshEventCoverageTest` 8、`DshHistoryImporterTest` 7、`SlotLedgerTest` 18、`DockSlotBusTest` 16、`RemoteSlotBridgeTest` 19、`SlotPrioritiesTest` 7、`EventBatchQueueTest` 10、`EventFlowPolicyTest` 14、`SegmentSealPolicyTest` 4、`SegmentProjectionCacheTest` 13、`NestedCardNodeTest` 6 |
| 构建数据 | 文中 860MB/901MB、各 APK sha256 是各轮构建产物，不是当前源码状态；阈值均按当前代码核对 |
| 仓库基线 | 归并时 HEAD 为 `77299b7c`（2026-11-15 04:07） |
