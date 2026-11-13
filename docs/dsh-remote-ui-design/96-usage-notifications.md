# 手机端审批 / 通知使用说明（2026-11-13）

> 面向使用者的操作手册：怎么配对、怎么在后台/锁屏收到审批、点哪里能审批、出问题看什么。
> 设计细节见 [10-modules.md](10-modules.md)，缺陷取舍见 [99-review-findings.md](99-review-findings.md)。

## 1. 架构一句话

```
电脑 DSH 进程（插件 index.js，端口 8218）
   │  HTTP + SSE
手机 App 进程
   └─ RemoteSyncService（前台服务，常驻）── 持有连接、发通知、回传审批
        └─ AIChatActivity（对话页）────── 页内对话框 / 甲板卡
```

- **插件跑在电脑端**，不可能进手机进程；手机侧真正"保活"的是 `RemoteSyncService` 这条常驻连接。
- 审批是**先答先赢**：电脑端点了，手机再点必然失败（反之亦然）。这是 DSH 的设计，不是手机坏了。

## 2. 前提与启动

1. 电脑端 DSH 运行，插件已启用（`/health` 返回 `{"ok":true,...}`）
2. 手机与电脑在同一网络，已完成配对（有 token）
3. 打开 App 任意页面：`MainActivity.onCreate` 会拉起前台服务（已配对才拉）

服务起来的判断：

```powershell
adb shell dumpsys activity services | Select-String RemoteSyncService
# 期望：* ServiceRecord{... com.oilquiz.app/.ai.tool.RemoteSyncService ...}
```

## 3. 通知行为

### 3.1 常驻连接通知（id 3418）

始终存在，正文是「已连接 · 会话标题」或最近一句回复。它同时是审批的载体：

| 状态 | 通知内容 |
|---|---|
| 无审批 | 电脑端 · 远程连接 / 已连接 · 会话名 |
| 有审批 | **待你处理 · 电脑端请求审批** / 请求权限：`write_file` · 原因 |

审批结束（本机或电脑端先答）后自动恢复成连接文案。

### 3.2 怎么点审批

- **MIUI/HyperOS**：折叠状态只显示标题+正文，按钮藏在"展开"层级 —— **在那一行通知上再向下拖一次**，会出现 `允许 / 拒绝 / 详情`
- 其他 ROM：下拉通知栏后直接可见 action 按钮
- 「详情」是唯一会打开对话页的按钮；「允许/拒绝」走广播，**后台/锁屏直接回传，不拉起页面**

### 3.3 页内对话框

从「详情」或通知本体进入对话页后，页内会弹出原生审批对话框（允许 / 拒绝 / 交给电脑）。
弹出时通知上的审批绑定会撤掉，避免"通知 + 页面"同时显示两份。

## 4. 后台保活（重要）

Android 会杀后台，小米系还需要系统级放行：

| 设置项 | 建议值 |
|---|---|
| 通知权限 | 允许（「待你处理」「远程同步」两个渠道都别屏蔽） |
| 自启动 | 开 |
| 省电策略 | **无限制** |
| 最近任务 | 下拉锁定 App |

代码侧已做：

- 前台服务 `START_STICKY`，自己持有连接（Activity 退出不影响）
- App 启动（首页/对话页）即拉起服务
- `RemoteBootReceiver` + `RECEIVE_BOOT_COMPLETED`：手机重启后自动恢复（仅已配对时）

## 5. 已知限制（如实记录）

1. **折叠态看不到按钮**：MIUI 对第三方通知的自定义视图会剥离/裁剪（实测 `contentView (0 bytes)`），
   所以按钮只能靠系统 action，而系统 action 在折叠态被 MIUI 隐藏 → 需要展开那一行。
2. **在线改权限不可用**：这台 DSH 没组装 `permissionPresets`，`/v2/action permission.set` 返回
   `409 failed:["sandbox:unavailable"]`，手机端会提示"去电脑端改"。
3. **审批存活很短**：不带人值守时，宿主应答者可能 10~15 秒内自动答掉；想验证 UI 用下面的
   `debug.approval` 注入一条 60~600 秒的测试审批。

## 6. 调试与排障

### 6.1 注入测试审批（不需要真实审批）

```powershell
$tok = Get-Content tools\dsh-plugin-smartquiz-remote\.token -Raw
$body = '{"action":"debug.approval","ttl_sec":120,"tool":"write_file","reason":"测试"}'
Invoke-RestMethod "http://127.0.0.1:8218/v2/action" -Method Post `
  -Headers @{Authorization="Bearer $($tok.Trim())"} -ContentType "application/json; charset=utf-8" `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

返回 `{"ok":true,"id":"test-…"}`；手机上会出现绑定审批的连接通知。
点完按钮后查回传证据：

```powershell
(Invoke-RestMethod "http://127.0.0.1:8218/status" -Headers $h).plugin_features.test_approval
# 期望 log 里出现 {"id":"test-…","decision":"allowed-once"|"rejected"}
```

### 6.2 /status 里看什么

| 字段 | 含义 |
|---|---|
| `plugin_features.approval_relay` | `hooked/seen/taken`：agent 作用域挂钩与 waterfall 是否触发 |
| `plugin_features.approval_debug` | 手机点审批那一刻：`calls/local/gateway_pending/ok/last_error` |
| `plugin_features.test_approval` | 测试审批的 pending 数与最近回传记录 |
| `session_streams` | 当前 SSE 订阅数（0 = 手机没连上） |

### 6.3 手机日志

```powershell
adb logcat -d | Select-String "DshSvc"      # 服务收到 approval / 绑定 / 结束
adb logcat -d | Select-String "DshApproval" # 通知按钮点击与回传结果
```

## 7. 常见现象对照

| 现象 | 先看什么 | 常见原因 |
|---|---|---|
| 通知栏没有审批通知 | `DshSvc` 日志 | 服务没起来 / 没连上 / 审批已被自动答掉 |
| 通知有但不显示按钮 | 是否展开了那一行 | MIUI 折叠态隐藏 action |
| 点按钮没反应 | `DshApproval` 日志 + `approval_debug` | 审批已被电脑端先答（先答先赢） |
| 页内和通知同时显示 | `clearApprovalBinding` 是否被调用 | 页面弹框时未撤绑定（旧版本） |
| 锁屏收不到 | 服务是否在跑 + 省电策略 | 被系统杀后台 |
