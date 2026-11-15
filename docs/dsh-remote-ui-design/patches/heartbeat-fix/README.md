# 心跳语义修复补丁（2026-11-16）

## 背景
`devices[].last_seen` 只在"带设备令牌发 HTTP 请求"时更新；
手机用 WebSocket 长连接 + 10s 心跳，走的是内存里的 `CONNECTIONS` 表，**不会刷这里**。
于是手机一直连着，`.devices.json` 的 last_seen 却会显示几十小时前 —— 排查时极易误判成"离线"。
实测判在线应看 `POST /v2/pulse` 返回的 `connections[].online`（TTL 30s）。

## 改了两处（仅插件侧，未动手机端）
1. `connectionSnapshot()`：超过 `CONNECTION_TTL_MS` 的条目**直接清理**，不再返回 `online:false` 的僵尸连接
   （此前 `/v2/pulse` 里能看到 age 165s 的陈旧记录）。
2. `/devices` 返回里新增 `last_auth_at`（与 `last_seen` 同值），并在代码注释里写明
   "它代表最近一次带令牌鉴权，不代表设备在线"。

## 为什么放在这里而不是直接改
`tools/dsh-plugin-smartquiz-remote/` 目录 ACL 拒绝写入（`Everyone: DeleteSubdirectoriesAndFiles: Deny`），
代理无法落笔，所以把**校验过语法的完整文件**放在这里。

## 应用方式
```powershell
Copy-Item docs\dsh-remote-ui-design\patches\heartbeat-fix\index.js `
          tools\dsh-plugin-smartquiz-remote\index.js -Force
```
然后重启/重载 DSH 插件（插件热更新不保证），再用：
```powershell
$tok=(Get-Content tools\dsh-plugin-smartquiz-remote\.token -Raw).Trim()
Invoke-RestMethod -Uri "http://127.0.0.1:8218/v2/pulse" -Method Post `
  -Body (@{client_id="probe";session_id="";caps="cmd.ui"} | ConvertTo-Json -Compress) `
  -ContentType "application/json" -Headers @{Authorization="Bearer $tok"}
```
看 `connections` 里不再出现 `online:false` 的陈旧条目即生效。