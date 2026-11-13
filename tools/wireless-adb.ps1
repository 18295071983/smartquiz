# wireless-adb.ps1 — 无线调试（ADB over Wi-Fi）一键配对 / 连接 / 装包
#
# 背景：Android 11+ 的「无线调试」需要**手机端手动开开关**，并显示一个 6 位配对码；
# 这个脚本负责电脑侧：发现服务 → 配对 → 连接 → 装 APK，省掉手敲 adb 命令。
#
# 用法：
#   # 手机：设置 → 开发者选项 → 无线调试 → 打开 → 「使用配对码配对设备」（保持该页面别关）
#   .\tools\wireless-adb.ps1 -PairCode 123456
#   .\tools\wireless-adb.ps1 -PairCode 123456 -Apk build\outputs\apk\debug\答题宝-debug-2.0.apk
#   .\tools\wireless-adb.ps1 -ListOnly          # 只看发现到哪些服务
#
# 说明：
#   - 依赖 adb 的 mDNS 发现（adb 30+；`adb mdns check` 应为 available）
#   - 配对成功后设备会一直出现在 `adb devices`，直到手机重启/关闭无线调试
#   - 找不到服务时，脚本会提示让手机重新打开「无线调试」页面（配对页只在此期间广播）

param(
    [string]$PairCode = '',
    [string]$Apk = '',
    [switch]$ListOnly,
    [int]$WaitSeconds = 15
)

$ErrorActionPreference = 'SilentlyContinue'

function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "[OK]   $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "       $msg" }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }

Write-Host ""
Write-Host "========== 无线调试（ADB over Wi-Fi） =========="
Write-Host ""

# 0) adb 是否可用
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidates = @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "D:\Android\Sdk\platform-tools\adb.exe")
    foreach ($c in $candidates) { if (Test-Path $c) { $adb = $c; break } }
}
if (-not $adb) { Fail "找不到 adb。装 Android platform-tools，或把 adb 加到 PATH。" }
Ok "adb: $adb"

# 1) mDNS 可用性
$mdns = (& $adb mdns check 2>&1) -join ' '
if ($mdns -notmatch 'available|daemon version') {
    Warn "adb mDNS 不可用（$mdns）"
    Info "处理：adb kill-server; adb start-server 再试；仍不行就用 -Manual 手动填 IP:port"
} else {
    Ok "mDNS: available"
}

function Get-AdbServices {
    $lines = & $adb mdns services 2>&1
    $out = @()
    foreach ($l in $lines) {
        if ($l -match '(\S+)\s+_adb-tls-(pairing|connect)\._tcp\.?\s+(\S+)') {
            $out += [pscustomobject]@{ Kind = $Matches[2]; Name = $Matches[1]; Endpoint = $Matches[3] }
        }
    }
    return $out
}

# 2) 发现服务（等一会，配对广播只在页面打开时出现）
$services = Get-AdbServices
if (-not $services -and $WaitSeconds -gt 0) {
    Info "没发现服务，等 $WaitSeconds 秒（请保持手机「无线调试」页面打开）…"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline -and -not $services) {
        Start-Sleep -Seconds 2
        $services = Get-AdbServices
    }
}

if ($services) {
    Write-Host ""
    Write-Host "发现的服务："
    $services | ForEach-Object { Write-Host ("  {0,-8} {1,-40} {2}" -f $_.Kind, $_.Name, $_.Endpoint) }
} else {
    Write-Host ""
    Warn "没有发现无线调试服务"
    Info "手机侧检查："
    Info "  1) 设置 → 关于手机 → 连点版本号 7 次（开发者选项）"
    Info "  2) 设置 → 开发者选项 → 无线调试 → 打开"
    Info "  3) 点「使用配对码配对设备」，**这个页面保持打开**，然后重跑本脚本"
    Info "  4) 手机和电脑要连同一个 WiFi（同网段）"
    if ($ListOnly) { exit 0 }
    exit 1
}

if ($ListOnly) { exit 0 }

# 3) 配对
$pairing = $services | Where-Object { $_.Kind -eq 'pairing' } | Select-Object -First 1
if ($pairing) {
    if (-not $PairCode) {
        Warn "发现配对服务 $($pairing.Endpoint)，但没给 -PairCode"
        Info "手机「使用配对码配对设备」弹窗里的 6 位码，用 -PairCode 传入，例如："
        Info "  .\tools\wireless-adb.ps1 -PairCode 123456"
        exit 1
    }
    Info "配对中：$($pairing.Endpoint)（码 $PairCode）…"
    $pairOut = (& $adb pair $pairing.Endpoint $PairCode 2>&1) -join "`n"
    if ($pairOut -match 'Successfully paired') {
        Ok "配对成功"
    } else {
        Fail "配对失败：$pairOut`n        常见原因：配对码输错/过期；或手机配对页已关闭（重开再试）"
    }
} else {
    Warn "没看到 pairing 服务（可能之前已配过），跳过配对直接连接"
}

# 4) 连接
Start-Sleep -Seconds 1
$services = Get-AdbServices
$connect = $services | Where-Object { $_.Kind -eq 'connect' } | Select-Object -First 1
if (-not $connect) {
    # 有些设备配对后不广播 connect，退化成 adb devices 看是否已经连上
    $devices = (& $adb devices) -join "`n"
    if ($devices -match '(\d+\.\d+\.\d+\.\d+:\d+)\s+device') {
        Ok "设备已连接（legacy）：$($Matches[1])"
        $serial = $Matches[1]
    } else {
        Fail "没发现连接服务，也没已连接设备。重开手机「无线调试」页面后重试。"
    }
} else {
    $serial = $connect.Endpoint
    Info "连接中：$serial …"
    $connOut = (& $adb connect $serial 2>&1) -join ' '
    if ($connOut -match 'connected') { Ok "已连接：$serial" } else { Fail "连接失败：$connOut" }
}

# 5) 确认设备
$online = (& $adb devices) -join "`n"
if ($online -notmatch [regex]::Escape($serial) + '\s+device') {
    Fail "adb devices 里没有 $serial（可能是授权弹窗没点「允许」）"
}
Ok "adb devices 可见：$serial"

# 6) 装包（可选）
if ($Apk) {
    if (-not (Test-Path $Apk)) { Fail "APK 不存在：$Apk" }
    $sizeMB = [math]::Round((Get-Item $Apk).Length / 1MB, 0)
    Info "安装 $Apk（$sizeMB MB，无线会慢一点）…"
    $installOut = (& $adb -s $serial install -r $Apk 2>&1) -join "`n"
    if ($installOut -match 'Success') {
        Ok "安装成功"
        & $adb -s $serial shell monkey -p com.oilquiz.app -c android.intent.category.LAUNCHER 1 | Out-Null
        Info "已尝试启动 App"
    } else {
        Fail "安装失败：$installOut"
    }
} else {
    Info "没传 -Apk，跳过安装；装包示例："
    Info "  .\tools\wireless-adb.ps1 -PairCode 123456 -Apk build\outputs\apk\debug\答题宝-debug-2.0.apk"
}

Write-Host ""
Ok "完成。设备序列号：$serial（后续 adb -s $serial … 即可）"
