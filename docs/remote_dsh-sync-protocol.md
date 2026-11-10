# 双端会话同步协议（设计）

目标：手机随时断线/被杀，回来时**不丢事件、不重复渲染、不整段重传**。

## 一、业界参考

| 方案 | 机制 | 借鉴点 |
|---|---|---|
| SSE `Last-Event-ID` | 每条事件带 `id:`，断线重连自动带上最后 id，服务端从该 id 之后重放 | 事件级游标 + 自动续传（[MCP/SSE 规范](https://raw.githubusercontent.com/nicolasriousset/MCP/refs/heads/main/rev2025-06-18/MCP%20-%20specification.md)） |
| Matrix `/sync` | 客户端保存 `since` token，服务端返回该 token 之后的增量；token 过期时 `full_state=true` 拿快照 | 增量 + 快照兜底（[Matrix sync](https://pub.dev/documentation/matrix/0.32.4/matrix_api_lite_generated_api/Api/sync.html)） |
| Telegram `updates.getDifference` | 客户端报 `pts/qts/date`，服务端返回差量；差量太大时返回 `tooLong` 让客户端重拉状态 | “差量过大→降级快照”的显式信号 |
| Kafka consumer offset | 消费者提交 offset，重连从 offset 继续；日志保留期外则重置 | 消费位点 + 保留期 |
| 快照-增量订阅 | 先发快照（带序号），之后只发 delta，客户端按序号去重 | 快照也要带 seq（[snapshot-then-delta](https://github.com/honua-io/honua-server/pull/3072/commits/31075081c3f4662295786b51cd0f2f9ee319443c)） |

共同点：
1. **单调序号**（seq/pts/token）是唯一真值；
2. **服务端可重放**（日志或缓冲区）；
3. **重放不可行时给快照**，而不是让客户端“猜”；
4. **客户端按序号去重**（至少一次投递 + 幂等应用）。

## 二、本项目的映射

DSH 的会话日志天然是「追加式事件日志」：`session.v4.jsonl.zstd`，每条事件带 `seq`。
所以不需要新造协议，只需要把 `seq` 暴露成游标。

```
手机                                  插件（电脑）                          DSH
 |  GET /events?since=<last_seq>&follow=1  |                                 |
 |---------------------------------------->|  会话 live？                      |
 |                                         |   ├─ 是：从 ctx.sessions 读 seq   |
 |                                         |   └─ 否：解压 session.v4.jsonl.zstd
 |  data:{"type":"ready","seq":N}          |        过滤 seq > since          |
 |<----------------------------------------|                                 |
 |  data:{"type":"event","event":{...}}    |  逐条原样转发（带 seq）           |
 |<----------------------------------------|                                 |
 |  data:{"type":"end"}                    |  非 follow：发完即断              |
 |<----------------------------------------|                                 |
```

### 1) 游标
- 每会话一个单调 `seq`（DSH 已有）
- 手机持久化 `last_seq`（按 `target_session_id` 存 SharedPreferences）
- 重连带 `since=last_seq`

### 2) 重放来源（两段式）
- **live 会话**：从内存 `session.eventAt(seq)` 顺序读（毫秒级）
- **非 live 会话**（App 被杀、DSH 重启过）：读 `$DSH_HOME/sessions/*/<id>/session.v4.jsonl.zstd`，
  按 zstd 多帧解压、逐行 JSON、过滤 `seq > since` —— 这就是“补发会话文件”

### 3) 快照兜底
触发条件（任一）：
- `since < 0`（首次连接）
- 日志缺失 / 解压失败（zstd 不可用或文件被清理）
- 缺口过大（`seq - since > SNAPSHOT_THRESHOLD`，默认 500）

返回：
```json
{"type":"snapshot","seq":1234,"turns":[{"role":"user","text":"…"},
  {"role":"assistant","text":"…","thinking":"…","tools":[{"name":"pwsh","result":"…"}]}]}
```
手机把 snapshot 当作“历史消息批”渲染，然后把 `last_seq = snapshot.seq`，继续吃增量。

### 4) 去重与幂等
- 服务端只保证“从 since 之后开始发”，断线可能重复最后一条 ⇒ 手机侧 `if (ev.seq <= lastSeq) skip`
- 卡片类事件（todo/命令/任务）按 `component.id` 覆盖更新，重复应用无副作用

### 5) 心跳与超时
- 每 15s 发 `: ping`（SSE 注释行），防止中间代理断流
- 单连接 `timeout` 默认 600s；到点发 `end`，手机立即重连（带最新 `last_seq`）

## 三、与现有实现的差距

| 能力 | 现状 | 目标 |
|---|---|---|
| 事件级游标 | ✅ `seq` 已在事件里 | 手机持久化 `last_seq` |
| 断线续传 | ⚠️ 只在同步中订阅，重进只补最后一条回复 | 重进按 `last_seq` 重放 |
| 非 live 会话补发 | ❌ | 读日志文件重放 |
| 快照兜底 | ❌ | `type:snapshot` + 阈值降级 |
| 去重 | ❌ | 按 `seq` 跳过 |
| 心跳 | ❌ | `: ping` |

## 四、实施顺序

1. 插件：`/events` 支持 `Last-Event-ID`、日志重放、`snapshot` 降级、心跳 —— 本次
2. 手机：`last_seq` 持久化 + 重进补拉 + 去重 —— 下一步
3. 观察指标：重连后 `since → seq` 的补发条数、snapshot 触发次数、单次补拉耗时
