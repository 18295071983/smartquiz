# ==============================================================
#  SmartQuiz - dsh bridge + Cloudflare tunnel launcher (ASCII only)
#    1) dsh ACP serve  127.0.0.1:7800
#    2) cloudflared quick tunnel -> https://xxx.trycloudflare.com
#    3) bridge on 0.0.0.0:8218 with --public-url <tunnel>
#  Usage: powershell -ExecutionPolicy Bypass -File tools\start_tunnel_bridge.ps1
#  Stop : close the two windows (ACP / tunnel) and Ctrl+C this one
# ==============================================================
param(
  [int]$BridgePort = 8218,
  [int]$AcpPort = 7800,
  [string]$Token = "***REMOVED***",
  [string]$Cloudflared = "D:\Temp\cloudflared.exe"
)
$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent $PSScriptRoot          # tools/.. = repo root
$log  = Join-Path $env:TEMP "cloudflared-tunnel.log"

Write-Host "[1/3] starting dsh ACP serve on 127.0.0.1:$AcpPort ..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/k","dsh --profile acp serve --host 127.0.0.1 --port $AcpPort --token $Token"
Start-Sleep -Seconds 3

Write-Host "[2/3] starting cloudflared quick tunnel -> http://127.0.0.1:$BridgePort ..."
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

Write-Host "[3/3] starting bridge with --public-url ..."
Set-Location $repo
& "C:\Python314\python.exe" (Join-Path $PSScriptRoot "dsh_bridge_server.py") --port $BridgePort --token $Token --acp-token $Token --public-url $url --cwd $repo
