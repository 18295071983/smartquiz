# fix-remote-lan.ps1 — 手机连不上电脑时，在电脑上双击运行（会自动请求管理员权限）
#
# 做两件事：
#   1) 把当前网络从「公用」改成「专用」（公用网络会拦手机的入站连接）
#   2) 放行 8218 端口（DSH 远程插件）
# 然后跑一遍自检，告诉用户"现在应该能连了"。
#
# 用法：双击 tools\fix-remote-lan.cmd（推荐），或管理员 PowerShell 运行本脚本。

$ErrorActionPreference = 'Continue'
$port = 8218

function Step($t) { Write-Host ""; Write-Host "== $t ==" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "   [完成] $t" -ForegroundColor Green }
function Warn($t) { Write-Host "   [注意] $t" -ForegroundColor Yellow }

Write-Host ""
Write-Host "=============================================="
Write-Host " DSH 远程 · 一键修复（手机连不上电脑）"
Write-Host "=============================================="

# 是否管理员
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Warn "没有管理员权限，修复会失败。请双击 tools\fix-remote-lan.cmd（会自动提权）"
    Read-Host "按回车退出"
    exit 1
}

Step "1/2 把网络改成「专用」（公用网络会拦住手机）"
$changed = 0
Get-NetConnectionProfile | ForEach-Object {
    if ($_.NetworkCategory -eq 'Public') {
        try {
            Set-NetConnectionProfile -InterfaceAlias $_.InterfaceAlias -NetworkCategory Private -ErrorAction Stop
            Ok "已改：$($_.InterfaceAlias)（$($_.Name)）→ 专用"
            $changed++
        } catch {
            Warn "改不了 $($_.InterfaceAlias)：$($_.Exception.Message)"
        }
    }
}
if ($changed -eq 0) { Ok "当前没有『公用』网络，跳过" }

Step "2/2 放行 $port 端口"
$rule = Get-NetFirewallRule -DisplayName "*$port*" -ErrorAction SilentlyContinue |
        Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' }
if ($rule) {
    Ok "已存在放行规则：$(($rule | ForEach-Object { $_.DisplayName }) -join ', ')"
} else {
    try {
        New-NetFirewallRule -DisplayName "DSH remote $port" -Direction Inbound -Protocol TCP `
            -LocalPort $port -Action Allow -Profile Any -ErrorAction Stop | Out-Null
        Ok "已新增规则：DSH remote $port（TCP 入站放行）"
    } catch {
        Warn "加规则失败：$($_.Exception.Message)"
    }
}

Step "复查"
$lanIp = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -First 1).IPAddress
try {
    $h = Invoke-WebRequest "http://127.0.0.1:$port/health" -UseBasicParsing -TimeoutSec 5
    if ($h.Content -match '"ok"\s*:\s*true') { Ok "电脑上的 DSH 插件正常" }
} catch {
    Warn "电脑上的 DSH 插件没响应：先启动 DSH，再回到手机上点『重试』"
}

Write-Host ""
if ($lanIp) {
    Write-Host "现在去手机 App 里检查：" -ForegroundColor White
    Write-Host "   地址填  http://$lanIp`:$port" -ForegroundColor White
    Write-Host "   然后点  「快捷自测」" -ForegroundColor White
} else {
    Write-Host "没读到电脑 IP，检查一下网线/WiFi。" -ForegroundColor White
}
Write-Host ""
Write-Host "跑完了。回到手机上点「快捷自测」看是否全绿。" -ForegroundColor Green
Read-Host "按回车关闭这个窗口"
