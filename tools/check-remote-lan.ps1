# check-remote-lan.ps1 — 手机 ↔ 电脑 局域网直连自检（2026-11-14）
#
# 用途：手机连不上电脑的 DSH 插件时，先在电脑上跑这个脚本。
# 它只做只读检查，并把"该怎么修"直接打印出来（不自动改系统设置）。
#
# 用法：
#   pwsh -File tools\check-remote-lan.ps1
#   powershell -ExecutionPolicy Bypass -File tools\check-remote-lan.ps1
#
# 退出码：0 = 关键项都通过；1 = 有需要处理的问题

$ErrorActionPreference = 'SilentlyContinue'
$port = 8218
$fail = 0

function Line($text) { Write-Host $text }
function Ok($text)   { Write-Host "  [OK]   $text" -ForegroundColor Green }
function Warn($text) { Write-Host "  [WARN] $text" -ForegroundColor Yellow }
function Bad($text)  { Write-Host "  [FAIL] $text" -ForegroundColor Red; $script:fail = 1 }

Line ""
Line "========== DSH 远程 · 局域网直连自检 =========="
Line ""

# 1) 插件是否在监听
Line "1) 插件监听 ($port)"
$listen = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listen) {
    Ok ("监听中: " + (($listen | ForEach-Object { "$($_.LocalAddress):$($_.LocalPort)" }) -join ', '))
} else {
    Bad "没有进程监听 $port：电脑端 DSH 没开，或插件没启用"
    Line "        处理：启动 DSH → 确认插件已启用 → 重跑本脚本"
}

# 2) 本机健康检查
Line ""
Line "2) 插件健康检查"
try {
    $r = Invoke-WebRequest "http://127.0.0.1:$port/health" -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200 -and $r.Content -match '"ok"\s*:\s*true') {
        Ok "127.0.0.1:$port/health → 200 ($($r.Content.Trim()))"
    } else {
        Bad "127.0.0.1:$port/health 返回异常: $($r.StatusCode) $($r.Content)"
    }
} catch {
    Bad "127.0.0.1:$port/health 失败：$($_.Exception.Message)"
}

# 3) 本机局域网 IP 与网关
Line ""
Line "3) 本机网段"
$ipv4 = Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
    $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown'
}
if (-not $ipv4) { Bad "没有可用的局域网 IPv4 地址" }
$primary = $null
foreach ($a in $ipv4) {
    $gw = (Get-NetIPConfiguration -InterfaceIndex $a.InterfaceIndex).IPv4DefaultGateway.NextHop
    Line ("  接口 {0,-10} IP {1}/{2}  网关 {3}" -f $a.InterfaceAlias, $a.IPAddress, $a.PrefixLength, ($(if ($gw) { $gw } else { '-' })))
    if (-not $primary -and $gw) { $primary = $a }
}

# 4) 从局域网 IP 访问插件（验证 0.0.0.0 绑定）
Line ""
Line "4) 局域网地址访问插件"
if ($primary) {
    $lanUrl = "http://$($primary.IPAddress):$port/health"
    try {
        $r = Invoke-WebRequest $lanUrl -UseBasicParsing -TimeoutSec 5
        Ok "$lanUrl → 200"
        Line ("        手机填这个地址：http://{0}:{1}" -f $primary.IPAddress, $port)
    } catch {
        Bad "$lanUrl 访问失败：$($_.Exception.Message)"
    }
} else {
    Warn "没有带网关的网卡，跳过"
}

# 5) 防火墙与网络类型（最常见的"不通"原因）
Line ""
Line "5) Windows 防火墙 / 网络类型"
$profiles = Get-NetConnectionProfile
foreach ($p in $profiles) {
    Line ("  网络 {0,-10} 类型 {1,-8} 配置名 {2}" -f $p.InterfaceAlias, $p.NetworkCategory, $p.Name)
}
$fw = Get-NetFirewallProfile
foreach ($f in $fw) { Line ("  防火墙 {0,-8} Enabled={1}" -f $f.Name, $f.Enabled) }

$publicIfaces = @($profiles | Where-Object { $_.NetworkCategory -eq 'Public' } | ForEach-Object { $_.InterfaceAlias })
$publicFwOn = @($fw | Where-Object { $_.Name -eq 'Public' -and $_.Enabled -eq 'True' }).Count -gt 0
$rule = Get-NetFirewallRule -DisplayName "*$port*" -ErrorAction SilentlyContinue |
        Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' }

if ($publicIfaces.Count -gt 0 -and $publicFwOn -and -not $rule) {
    Bad "网卡是『公用(Public)』且 Public 防火墙开着、没有 $port 放行规则 → 手机会被拦"
    Line "        处理（任选其一，均需管理员 PowerShell）："
    foreach ($i in $publicIfaces) {
        Line ("          Set-NetConnectionProfile -InterfaceAlias '{0}' -NetworkCategory Private" -f $i)
    }
    Line ("          New-NetFirewallRule -DisplayName 'DSH remote {0}' -Direction Inbound -Protocol TCP -LocalPort {0} -Action Allow" -f $port)
} elseif ($rule) {
    Ok ("已有入站放行规则：" + (($rule | ForEach-Object { $_.DisplayName }) -join ', '))
} else {
    Ok "网络类型/防火墙不构成拦截（或已放行）"
}

# 6) 令牌文件
Line ""
Line "6) 手机配对要用的令牌"
$tokenFile = Join-Path $PSScriptRoot "dsh-plugin-smartquiz-remote\.token"
if (Test-Path $tokenFile) {
    $t = (Get-Content $tokenFile -Raw).Trim()
    Ok ("令牌文件存在：$tokenFile（长度 " + $t.Length + "）")
    Line "        手机『远程连接（电脑）』里填上面的地址 + 令牌里的整串"
} else {
    Warn "没找到 $tokenFile（相对脚本目录）；确认插件目录位置"
}

# 7) 手机侧检查清单
Line ""
Line "7) 手机侧自查（脚本查不到，需要你确认）"
Line "        [ ] 手机连的是同一个 WiFi/局域网"
Line "        [ ] 手机 IP 与电脑同一网段（如 192.168.1.x）"
Line "        [ ] 手机浏览器打开 http://<电脑IP>:8218/health 能看到 {`"ok`":true,...}"
Line "        [ ] App 地址填 http://<电脑IP>:8218（端口 8218，不要用 45972 这类临时端口）"
Line "        [ ] App 令牌是主令牌整串（32 位，无空格）"

Line ""
if ($fail -eq 0) {
    Line "结论：电脑侧检查通过。若手机仍不通，看第 7 项，或核对手机与电脑是否真的同网段。" -ForegroundColor Green
} else {
    Line "结论：有需要处理的问题，按上面 [FAIL] 的提示修完再重跑。" -ForegroundColor Yellow
}
Line ""
exit $fail
