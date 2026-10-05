# revoke_stale_devices.ps1 —— 吊销 verify-plugin 测试残留设备
# 用法：pwsh -File revoke_stale_devices.ps1      （PowerShell 7；无 BOM 文件用 powershell.exe
#        5.1 运行会把中文按 ANSI 解码，输出乱码甚至报语法错，所以请用 pwsh）
#
# 为什么要用 API 而不是直接改 .devices.json：
#   /status 返回的 devices 是插件**内存里**的数组长度。改磁盘文件不会让运行中的实例重新加载；
#   反而插件下次保存设备表（/pair/claim 或 /devices/revoke）时会用内存内容覆盖磁盘。
#   所以必须走 /devices/revoke，让内存与磁盘同时收敛。
#
# 安全约束：只吊销 $stale 列表里的 id，真机 id（$keep）与任何新设备都不动。
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:19387'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tf = Join-Path $dir '.token'
$keep = 'dev-b0575b15'
$stale = @(
  'dev-5a99d502',
  'dev-9c0845af',
  'dev-0e95ffee',
  'dev-5dd6e097',
  'dev-5af551e5',
  'dev-3fb1db7f',
  'dev-cbff9148'
)

function Req($u, $m, $body, $tok, $sec) {
  if (-not $m) { $m = 'GET' }
  if (-not $sec) { $sec = 60 }
  $h = @{}
  if ($tok) { $h['Authorization'] = "Bearer $tok" }
  try {
    if ($body) {
      $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$body)
      $r = Invoke-WebRequest -Uri $u -Method $m -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop
    } else {
      $r = Invoke-WebRequest -Uri $u -Method $m -Headers $h -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop
    }
    return @{ code = $r.StatusCode; body = $r.Content }
  } catch {
    $c = $_.Exception.Response.StatusCode.value__
    $t = ''
    try {
      $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      $t = $sr.ReadToEnd()
    } catch { }
    if ($t) { return @{ code = $(if ($c) { $c } else { -1 }); body = $t } }
    return @{ code = $(if ($c) { $c } else { -1 }); body = $_.Exception.Message }
  }
}

if (-not (Test-Path $tf)) {
  Write-Host "✗ .token 不存在 → 插件未运行" -ForegroundColor Red
  exit 1
}
$tok = (Get-Content $tf -Raw).Trim()

Write-Host "=== 吊销前 ===" -ForegroundColor Cyan
$b = Req "$base/devices" 'GET' $null $tok 30
Write-Host "HTTP $($b.code) : $($b.body)"

$revoked = 0
$failed = 0
foreach ($id in $stale) {
  if ($id -eq $keep) { continue }
  $payload = '{"device_id":"' + $id + '"}'
  $rv = Req "$base/devices/revoke" 'POST' $payload $tok 30
  Write-Host "revoke $id → HTTP $($rv.code) : $($rv.body)"
  if ($rv.code -eq 200) { $revoked++ } else { $failed++ }
}

Write-Host ""
Write-Host "=== 吊销后 ===" -ForegroundColor Cyan
$a = Req "$base/devices" 'GET' $null $tok 30
Write-Host "HTTP $($a.code) : $($a.body)"

$left = @()
try {
  $j = $a.body | ConvertFrom-Json
  foreach ($d in $j.devices) {
    if ([string]$d.id -ne $keep) { $left += [string]$d.id }
  }
} catch { }

if ($left.Count -gt 0) {
  Write-Host "✗ 仍非真机记录 $($left.Count) 条：$($left -join ', ')" -ForegroundColor Red
  exit 1
}
if ($failed -gt 0) {
  Write-Host "⚠ 有 $failed 条吊销请求非 200，但设备表已只剩真机（可能已被吊销过）" -ForegroundColor Yellow
}
Write-Host "✓ 已吊销 $revoked 条，设备表只剩真机 $keep 一条" -ForegroundColor Green
