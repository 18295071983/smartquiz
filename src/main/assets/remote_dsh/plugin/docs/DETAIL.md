# 答题宝手机远程（SmartQuiz Remote）

把电脑上的 DSH 会话接到手机 App（`com.oilquiz.app`）。手机通过局域网 HTTP/WebSocket 端点
连到电脑，收发消息、看事件流、操作甲板槽位、审批工具调用；电脑侧既是**远程端点**，
也是**任务执行位置**（CLI 子进程或宿主内直连）。

## 功能

| 能力 | 说明 |
|---|---|
| 配对 | 电脑屏幕显示二维码（`dshpair://`），手机扫码后换取**设备令牌**；主令牌仅用于本机/配对页 |
| 鉴权 | `Authorization: Bearer <token>`；支持设备吊销、来源白名单、一次性配对码 |
| 双向事件 | `/v2/stream`（SSE）与 `/ws`（WebSocket）推送事件、视图、心跳；手机回执水位线落盘 |
| 会话跟随 | 手机可列出电脑会话并切换；切换后重新对齐会话身份并拉取插槽快照 |
| 甲板槽位 | `ui/slot` 帧驱动手机输入区上方的状态行（权限/目标/任务/产物等），按会话隔离 |
| 卡片 | 工具结果、计划、压缩、子代理等事件投影成卡片；卡片渲染失败会回传诊断给 AI |
| 审批 | 手机可列出并应答待审批（与桌面端"先答先赢"）；网关不暴露中继时退回插件本地兜底 |
| 执行 | `cliMode=auto` 时宿主有 agent 能力就走宿主内直连，否则起 `dsh --profile headless` 子进程（并发/排队可配） |
| 反向指令 | 电脑端可让手机弹提示/通知/对话框（白名单动作，仅 UI） |

## 配置

配置在 `cordis.patch.yml` 的插件行 `config` 里（可调参数均声明在 `Config` schema 中）：

| 键 | 默认 | 含义 |
|---|---|---|
| `token` | 空 | 主令牌；空则用环境变量或插件目录 `.token`（自动生成并持久化） |
| `provider` / `model` | `deepseek-account` / `deepseek-flash` | 插件创建 agent 时使用的模型路由 |
| `allowExec` | `true` | 是否允许 `/exec` 在电脑上直连执行命令（电脑面板开关优先） |
| `cliMode` | `auto` | `auto`=宿主有 agent 能力就直连；`true`=强制 CLI 子进程；`false`=强制宿主内 |
| `cliProfile` | `headless` | CLI 子进程使用的 profile |
| `cliMaxConcurrent` / `cliMaxQueued` | `1` / `3` | 同时跑的 CLI 子进程数 / 排队上限（超限直接回"忙"） |
| `warmReuse` | `false` | CLI 跑完是否在后台 resume 成宿主内句柄以省冷启动 |
| `workingDir` | 空 | CLI 任务 cwd（决定手机会话在桌面侧栏归属哪个工作区） |
| `lanEnabled` / `lanPort` / `extraLanPorts` | `true` / `8218` / `80` | 自建局域网入口（DSH 自带 webserver 只听 127.0.0.1） |
| `publicUrl` | 空 | 隧道/公网域名（也可用 `.public-url` 文件或环境变量） |

## 使用

1. 在 DSH 插件面板确认本插件已启用（`/health` 返回 `ok`）；
2. 打开配对页（`/pair`）用手机 App 扫码；
3. 手机连上后顶栏显示「电脑 · 阶段」，甲板显示当前会话状态；
4. 切换电脑会话：手机「选择会话」→ 列表 → 切换（会自动重对齐 + 拉槽位快照）。

## 限制

- 修改**已安装**插件的 JS 需要重启 DSH 才能载入新的模块世代（DSH 机制，非本插件特有）；
- 网关不暴露审批中继时（部分 DSH 入口），手机只能收提醒、无法替桌面端点"允许"；
- `primitive_*` 类卡片是聊天流只读卡，不支持 `get_result`；需要输入回传请用 `layout_canvas` + 带 `key` 的控件 + 一个按钮；
- 手机与电脑必须网络可达（同一局域网、或配置隧道 `publicUrl`）。
