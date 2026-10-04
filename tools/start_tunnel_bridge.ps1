# ==============================================================
#  SmartQuiz - dsh bridge + Cloudflare tunnel launcher (ASCII only)
#    1) cloudflared quick tunnel -> https://xxx.trycloudflare.com
#    2) bridge on 0.0.0.0:8218 with --public-url <tunnel>
#       (bridge spawns `dsh --profile acp` itself: ACP over stdio,
#        so no ACP serve window / 7800 port / ACP token any more)
#  Usage: powershell -ExecutionPolicy Bypass -File tools\start_tunnel_bridge.ps1
#  Stop : close the tunnel window and Ctrl+C this one
# ==============================================================
param(
  [int]$BridgePort = 8218,
  [string]$Token = "",   # 留空则从 tools\.bridge_token 读取（该文件在 .gitignore 里，绝不入库）
  [string]$Cloudflared = "D:\Temp\cloudflared.exe"
)
$ErrorActionPreference = "Continue"
if (-not $Token) {
  $tokFile = Join-Path $PSScriptRoot ".bridge_token"
  if (Test-Path $tokFile) { $Token = (Get-Content $tokFile -Raw).Trim() }
}
if (-not $Token) {
  Write-Host "ERROR: token missing. Put it into tools\.bridge_token (gitignored) or pass -Token xxx"
  exit 1
}
$repo = Split-Path -Parent $PSScriptRoot          # tools/.. = repo root
$log  = Join-Path $env:TEMP "cloudflared-tunnel.log"

Write-Host "[1/2] starting cloudflared quick tunnel -> http://127.0.0.1:$BridgePort ..."
if (-not (Test-Path $Cloudflared)) { Write-Host "cloudflared not found: $Cloudflared"; exit 1 }
Remove-Item $log -ErrorAction SilentlyContinue
Start-Process -FilePath $Cloudflared -ArgumentList "tunnel","--url","http://127.0.0.1:$BridgePort","--no-autoupdate" -RedirectStandardError $log -RedirectStandardOutput "$log.out" -WindowStyle Hidden

$url = $null
for ($i = 1; $i -le 30; $i++) {
  Start-Sleep -Seconds 2
  if (Test-Path $log) {
    $m = Select-String -Path $log -Pattern "https://[a-z0-9-]+\.trycloudflare\.com" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($m) { $url = ($m.Matches[0].Value); break }
  }
}
if (-not $url) { Write-Host "tunnel URL not found in $log (check the log)"; exit 1 }
Write-Host "     tunnel = $url"

Write-Host "[2/2] starting bridge with --public-url ..."
Set-Location $repo
& "C:\Python314\python.exe" (Join-Path $PSScriptRoot "dsh_bridge_server.py") --port $BridgePort --token $Token --public-url $url --cwd $repo
