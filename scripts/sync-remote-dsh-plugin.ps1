<#
  把电脑端插件源码（tools/dsh-plugin-smartquiz-remote）同步进 App 内置包
  （src/main/assets/remote_dsh/plugin），并逐文件校验。

  为什么需要：
    · App 的「导出电脑端插件」按钮直接读 assets/remote_dsh/plugin/；
    · 改了 tools 里的插件却忘记同步，APK 里的就是旧包（Gradle 只会告警，不会自动复制）。
  build.gradle 的 checkRemoteDshPlugin 会在缺文件时让构建失败、不一致时告警。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sync-remote-dsh-plugin.ps1           # 复制 + 校验
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\sync-remote-dsh-plugin.ps1 -Verify   # 只校验，不改文件
#>
param([switch]$Verify)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'tools/dsh-plugin-smartquiz-remote'
$dst = Join-Path $root 'src/main/assets/remote_dsh/plugin'
$files = @(
    'package.json', 'index.js', 'client.js', 'cordis.patch.yml',
    'icon.svg', 'pair_page.html', 'qrcodegen.js', '安装说明.txt'
)

if (-not (Test-Path $src)) { throw "找不到插件源码目录：$src" }
if (-not $Verify) {
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($f in $files) {
        $from = Join-Path $src $f
        if (-not (Test-Path $from)) { throw "源码缺文件：$from" }
        Copy-Item $from (Join-Path $dst $f) -Force
    }
    Write-Host "已同步 $($files.Count) 个文件：$src -> $dst" -ForegroundColor Cyan
}

$bad = @()
foreach ($f in $files) {
    $a = Join-Path $src $f
    $b = Join-Path $dst $f
    if (-not (Test-Path $b)) { $bad += "$f (缺)"; continue }
    $ha = (Get-FileHash $a -Algorithm SHA256).Hash
    $hb = (Get-FileHash $b -Algorithm SHA256).Hash
    if ($ha -ne $hb) { $bad += "$f (内容不同)" }
}

if ($bad.Count -gt 0) {
    Write-Host ("不一致：" + ($bad -join '、')) -ForegroundColor Red
    exit 1
}
Write-Host "OK：内置插件 $($files.Count) 个文件与 tools 源码完全一致。" -ForegroundColor Green
