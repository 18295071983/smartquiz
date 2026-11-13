# 往反向通道发一条指令（UTF-8 安全）
#
# 为什么需要这个脚本：PowerShell 5.1 用字符串当 -Body 时按 ASCII 编码发送，
# 中文会当场变成 "??"，看起来像"服务端不支持中文"，其实是客户端把字丢了。
# 这里统一先转 UTF-8 字节再发。
#
# 用法：
#   .\send_cmd.ps1 -Tool toast -Title '编码自检' -Text '中文内容 · 应正常显示'
#   .\send_cmd.ps1 -Tool notification -Title '标题' -Text '正文' -Wait
param(
  [Parameter(Mandatory = $true)][ValidateSet('toast', 'notification', 'dialog')][string]$Tool,
  [string]$Title = '',
  [string]$Text = '',
  [string]$SlotId = '',
  [switch]$Wait,
  [string]$BaseUrl = 'http://127.0.0.1:8218'
)

$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tokenFile = Join-Path $dir '.token'
if (-not (Test-Path $tokenFile)) { throw "缺少 .token：$tokenFile" }
$token = (Get-Content $tokenFile -Raw).Trim()

# 中文 → UTF-8 字节（关键一步）
$payload = @{
  tool = $Tool
  args = @{ title = $Title; text = $Text }
  caps = 'cmd.ui'
} | ConvertTo-Json -Compress -Depth 5
$bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)

$resp = Invoke-RestMethod "$BaseUrl/cmd" -Method Post `
  -Headers @{ Authorization = "Bearer $token" } `
  -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 10
if (-not $resp.ok) { throw "入队失败：$($resp | ConvertTo-Json -Compress)" }
"queued cmd_id=$($resp.cmd_id) tool=$Tool"

if ($Wait) {
  for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 2
    $r = Invoke-RestMethod "$BaseUrl/cmd/result?id=$($resp.cmd_id)" `
      -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10
    "  [$($i * 2 + 2)s] state=$($r.state) result=$($r.result)"
    if ($r.state -eq 'done') { break }
  }
}
