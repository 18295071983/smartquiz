<#
.SYNOPSIS
    html2apk 导出工具：把任意 HTML 目录打包成可安装的签名 APK。

.DESCRIPTION
    Agent 生成任意 HTML/CSS/JS 后，把包含 index.html 的目录交给本脚本，
    脚本会：
      1. 校验输入（index.html 存在，或指定 -RemoteUrl）
      2. 将 HTML 目录打包为 dt.jet（ZIP→AES）与 manifest.json 进壳 assets
      3. 按参数写入打包配置（应用名/包名/版本/图标/签名）
      4. 自动生成签名 keystore（首次）
      5. 用 gradlew 构建 release APK（63 桥 WebView 壳）
      6. 用 aapt 校验 APK 元信息并复制到输出目录

.EXAMPLE
    # 本地 HTML -> APK
    .\export_apk.ps1 -HtmlDir .\samples\demo -AppName "我的应用" -Package com.demo.myapp

    # 指定图标、版本
    .\export_apk.ps1 -HtmlDir .\samples\demo -AppName "Demo" -Icon .\icon.png -VersionName 2.0.0 -VersionCode 2

    # 只加载远程 URL（不打包本地 HTML）
    .\export_apk.ps1 -RemoteUrl "https://example.com" -AppName "WebApp"
#>
[CmdletBinding()]
param(
    # 包含 index.html 的 HTML 目录（与 -RemoteUrl 二选一）
    [string]$HtmlDir,

    # 应用显示名称（支持中文）
    [string]$AppName = "HTML App",

    # 应用包名（applicationId），默认 com.html2apk.wrapper
    [string]$Package = "com.html2apk.wrapper",

    # 版本号
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,

    # 自定义图标 PNG（512x512 推荐）。不传则沿用当前图标。
    [string]$Icon,

    # 输出目录，默认 <工具目录>\out
    [string]$OutDir = "",

    # 复用已有 keystore（不传则自动生成 html2apk-release.keystore）
    [string]$Keystore = "",
    [string]$KeystorePass = "html2apk123",
    [string]$KeyAlias = "html2apk",
    [string]$KeyPass = "html2apk123",

    # 直接加载远程 URL（不打包本地 HTML；与 -HtmlDir 二选一）
    [string]$RemoteUrl = "",

    # 构建 debug 包（默认 release 签名包）
    [switch]$DebugBuild,

    # 只准备资源不构建（调试用）
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$script:ToolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Err($msg)  { Write-Host "[ERR] $msg" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------
# 0. 环境检查
# ------------------------------------------------------------
Write-Step "环境检查"
if (-not $env:JAVA_HOME) { Write-Err "JAVA_HOME 未设置（本机应为 D:\jdk-21）" }
$javaBin = Join-Path $env:JAVA_HOME "bin"
if (-not (Test-Path (Join-Path $javaBin "java.exe"))) { Write-Err "JAVA_HOME 无效: $env:JAVA_HOME" }
Write-Ok "JDK: $env:JAVA_HOME"

$gradlew = Join-Path $script:ToolRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) { Write-Err "找不到 gradlew.bat，工具目录不完整" }

# SDK 路径（优先 local.properties）
$sdkDir = ""
$lp = Join-Path $script:ToolRoot "local.properties"
if (Test-Path $lp) {
    $m = Select-String -Path $lp -Pattern '^sdk\.dir=(.+)$'
    if ($m) { $sdkDir = $m.Matches[0].Groups[1].Value -replace '\\:', ':' }
}
if (-not $sdkDir) { $sdkDir = $env:ANDROID_HOME }
if (-not $sdkDir -or -not (Test-Path $sdkDir)) { Write-Err "无法定位 Android SDK（local.properties / ANDROID_HOME）" }
Write-Ok "Android SDK: $sdkDir"

# 定位 aapt（取最高 build-tools 版本）
$aapt = ""
if (Test-Path (Join-Path $sdkDir "build-tools")) {
    $aapt = Get-ChildItem (Join-Path $sdkDir "build-tools") -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "aapt.exe" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
}
if (-not $aapt) { Write-Err "SDK 中未找到 aapt.exe（build-tools）" }

# ------------------------------------------------------------
# 1. 输入校验
# ------------------------------------------------------------
Write-Step "输入校验"
if ($RemoteUrl) {
    if ($RemoteUrl -notmatch '^https?://') { Write-Err "-RemoteUrl 必须以 http:// 或 https:// 开头" }
    if ($HtmlDir) { Write-Host "[WARN] 同时指定了 -HtmlDir 与 -RemoteUrl，将优先加载远程 URL，本地 HTML 仍会打包进 assets 备用" }
    Write-Ok "加载方式: 远程 URL -> $RemoteUrl"
} else {
    if (-not $HtmlDir) { Write-Err "请提供 -HtmlDir（含 index.html 的目录）或 -RemoteUrl" }
    $HtmlDir = (Resolve-Path $HtmlDir -ErrorAction SilentlyContinue).Path
    if (-not $HtmlDir) { Write-Err "HTML 目录不存在: $HtmlDir" }
    if (-not (Test-Path (Join-Path $HtmlDir "index.html"))) { Write-Err "HTML 目录中缺少 index.html: $HtmlDir" }
    Write-Ok "HTML 目录: $HtmlDir"
}
if ($Package -notmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$') {
    Write-Err "包名格式非法: $Package（示例: com.demo.myapp）"
}

# ------------------------------------------------------------
# 2. 打包 HTML 到 assets（dt.jet 加密 + manifest.json）
# ------------------------------------------------------------
Write-Step "打包 HTML 资源"
$assetsDir = Join-Path $script:ToolRoot "app\src\main\assets"
if (Test-Path $assetsDir) { Get-ChildItem $assetsDir -Force | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

if ($HtmlDir) {
    # HTML 目录 → ZIP → AES-128-CBC(dt.jet)；与 apk_shell/ApkPacker 同一格式
    $genScript = Join-Path $script:ToolRoot "tools\gen_dt_jet.py"
    $dtJet = Join-Path $assetsDir "dt.jet"
    & python $genScript $HtmlDir $dtJet
    if ($LASTEXITCODE -ne 0) { Write-Err "dt.jet 生成失败（需 Python3 + cryptography）" }
    Write-Ok "dt.jet 已生成: $((Get-Item $dtJet).Length) bytes"
}
# manifest.json：本地入口 index.html + 可选远程 url
$manifest = @{ main = "index.html"; targver = 1 }
if ($RemoteUrl) { $manifest["url"] = $RemoteUrl }
$manifestJson = $manifest | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText((Join-Path $assetsDir "manifest.json"), $manifestJson, (New-Object System.Text.UTF8Encoding($false)))
Write-Ok "manifest.json: $manifestJson"
$fileCount = (Get-ChildItem $assetsDir -Recurse -File).Count
Write-Ok "assets 已就绪，共 $fileCount 个文件（63 桥壳：TTS/方向/亮度/权限/SAF/截图/剪贴板/网络代理等）"

# ------------------------------------------------------------
# 3. 图标
# ------------------------------------------------------------
Write-Step "图标处理"
$iconTarget = Join-Path $script:ToolRoot "app\src\main\res\drawable-nodpi\app_icon.png"
if ($Icon) {
    $Icon = (Resolve-Path $Icon -ErrorAction SilentlyContinue).Path
    if (-not $Icon) { Write-Err "图标文件不存在: $Icon" }
    Copy-Item $Icon $iconTarget -Force
    Write-Ok "已使用自定义图标: $Icon"
} else {
    if (Test-Path $iconTarget) { Write-Ok "沿用当前图标（如需更换请传 -Icon）" }
    else { Write-Err "缺少默认图标 app_icon.png" }
}

# ------------------------------------------------------------
# 4. 签名 keystore
# ------------------------------------------------------------
Write-Step "签名配置"
$ksFinal = ""
if ($Keystore) {
    $Keystore = (Resolve-Path $Keystore -ErrorAction SilentlyContinue).Path
    if (-not $Keystore) { Write-Err "keystore 不存在: $Keystore" }
    $ksFinal = $Keystore
    Write-Ok "复用 keystore: $Keystore"
} else {
    $ksFinal = Join-Path $script:ToolRoot "keystore\html2apk-release.keystore"
    if (-not (Test-Path $ksFinal)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $ksFinal) | Out-Null
        $dname = "CN=html2apk, OU=Doubao, O=Doubao, L=Beijing, ST=Beijing, C=CN"
        & (Join-Path $javaBin "keytool.exe") -genkeypair -v `
            -keystore $ksFinal -alias $KeyAlias `
            -keyalg RSA -keysize 2048 -validity 10950 `
            -storepass $KeystorePass -keypass $KeyPass -dname $dname | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Err "keystore 生成失败" }
        Write-Ok "已自动生成 keystore: $ksFinal"
    } else {
        Write-Ok "使用已有 keystore: $ksFinal"
    }
}
# 统一为正斜杠绝对路径，供 Gradle file() 使用
$ksFinal = ($ksFinal -replace '\\', '/')

# ------------------------------------------------------------
# 5. 写入构建配置（UTF-8，避免中文乱码）
# ------------------------------------------------------------
Write-Step "写入构建配置"
$cfgFile = Join-Path $script:ToolRoot "build_config.properties"
$cfg = @"
# 由 export_apk.ps1 自动生成
appName=$AppName
appPackage=$Package
appVersionName=$VersionName
appVersionCode=$VersionCode
ksPath=$ksFinal
ksPass=$KeystorePass
ksAlias=$KeyAlias
ksAliasPass=$KeyPass
"@
[System.IO.File]::WriteAllText($cfgFile, $cfg, (New-Object System.Text.UTF8Encoding($false)))
Write-Ok "配置已写入: $cfgFile"
Write-Ok "应用名: $AppName | 包名: $Package | 版本: $VersionName ($VersionCode)"

# ------------------------------------------------------------
# 6. Gradle 构建
# ------------------------------------------------------------
if ($SkipBuild) {
    Write-Ok "-SkipBuild 已指定，跳过构建（资源已就绪）"
} else {
    Write-Step "Gradle 构建 ($(if ($DebugBuild) { 'debug' } else { 'release' }))"
    $task = if ($DebugBuild) { "assembleDebug" } else { "assembleRelease" }
    Push-Location $script:ToolRoot
    try {
        # 先尝试离线构建（命中缓存最快）；缺依赖时自动转联网下载一次
        & .\gradlew.bat $task "--offline" "-PconfigFile=$cfgFile"
        $code = $LASTEXITCODE
        if ($code -ne 0) {
            Write-Host "[WARN] 离线构建缺少缓存依赖，转联网构建..." -ForegroundColor Yellow
            & .\gradlew.bat $task "-PconfigFile=$cfgFile"
            $code = $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
    if ($code -ne 0) {
        Write-Host "`n构建失败。若为依赖解析错误，可去掉 --offline 重试（首次构建需联网下载依赖）。" -ForegroundColor Yellow
        Write-Err "Gradle 构建失败 (exit=$code)"
    }
    Write-Ok "构建成功"
}

# ------------------------------------------------------------
# 7. 定位 APK 并输出
# ------------------------------------------------------------
Write-Step "输出 APK"
$variant = if ($DebugBuild) { "debug" } else { "release" }
$apk = Get-ChildItem (Join-Path $script:ToolRoot "app\build\outputs\apk\$variant") -Filter "*.apk" -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Write-Err "未找到构建产物 APK" }

$outDir = if ($OutDir) { (Resolve-Path $OutDir -ErrorAction SilentlyContinue).Path } else { Join-Path $script:ToolRoot "out" }
if (-not $outDir) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null; $outDir = (Resolve-Path $OutDir).Path }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 输出文件名用 ASCII（aapt/adb/Windows 对非 ASCII 路径支持差）；应用显示名仍可中文
$safeName = ($AppName -replace '[^\x20-\x7E]', '' -replace '[\\/:*?"<>|]', '_').Trim()
if (-not $safeName) {
    $safeName = ($Package -split '\.')[-1]
}
$finalApk = Join-Path $outDir "$safeName-$VersionName.apk"
Copy-Item $apk.FullName $finalApk -Force
$sizeMb = (Get-Item $finalApk).Length / 1MB

# ------------------------------------------------------------
# 8. aapt 校验
# ------------------------------------------------------------
Write-Step "APK 校验 (aapt dump badging)"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"   # 原生命令 stderr 不中断脚本
$badging = & $aapt dump badging $finalApk 2>&1
$ErrorActionPreference = $prevEap
$pkgLine   = $badging | Where-Object { $_ -is [string] -and $_ -match "package: name=" } | Select-Object -First 1
$labelLine = $badging | Where-Object { $_ -is [string] -and $_ -match "application-label:" } | Select-Object -First 1
$sdkLine   = $badging | Where-Object { $_ -is [string] -and $_ -match '^sdkVersion:' } | Select-Object -First 1
if (-not $pkgLine) { Write-Err "aapt 校验失败，APK 可能无效" }
Write-Ok "包名/版本: $pkgLine"
if ($labelLine) { Write-Ok "应用标签: $labelLine" }
if ($sdkLine)   { Write-Ok "SDK 版本: $sdkLine" }

Write-Host ""
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  APK 导出完成！" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
Write-Host "  APK 路径 : $finalApk"
Write-Host ("  APK 大小 : {0:N2} MB" -f $sizeMb)
Write-Host "  应用名   : $AppName"
Write-Host "  包名     : $Package"
Write-Host "  版本     : $VersionName ($VersionCode)"
Write-Host "  加载方式 : $(if ($RemoteUrl) { $RemoteUrl } else { '本地 dt.jet 解密 + HTTP 服务' })"
Write-Host "  安装命令 : adb install -r `"$finalApk`""
Write-Host "================================================================" -ForegroundColor Green
