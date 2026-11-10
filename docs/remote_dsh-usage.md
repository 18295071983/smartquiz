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
   - **直连模式**：电脑端新开一条 CLI 会话执行（不在电脑当前对话里）
4. **审批**：电脑端弹审批时手机也会弹，**先点先赢**（另一端自动消失）
5. **产物/待办/命令/子代理/工作流**：自动出卡片，不需要额外操作

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

# 审批状态 / 应答
POST /session {"action":"approval_state"}
POST /session {"action":"approval","approval_id":"…","decision":"allow|deny"}

# 反向指令
POST /session {"action":"enqueue_cmd","tool":"ui_component","args":{…}}
POST /session {"action":"cmd_poll"}   # 手机侧轮询
POST /session {"action":"cmd_ack","cmd_id":"…","ok":true,"result":"…"}
```
