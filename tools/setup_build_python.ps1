# ============================================================================
# setup_build_python.ps1 — 重建 Chaquopy buildPython 副本
# ----------------------------------------------------------------------------
# 背景 (2026-10-09):
#   系统 Python310 自带的 pip 23.0.1 不识别 Chaquopy 的 Android wheel 标签
#   (android_21/24_arm64_v8a), 在 --platform 交叉安装时会去访问 chaquo.com
#   索引并挂起 (generateReleasePythonRequirements 卡死 20+ 分钟)。
#   修复: 在项目内复制一份 Python310, 把其中的 pip 换成 Chaquopy env 里
#   自带的定制 pip 20.1 (compatibility_tags.py 有 android_2x 支持), 并把
#   build.gradle 的 buildPython 指向该副本。
#
#   本脚本用于在换机器 / 重装环境后一键重建该副本, 避免把 62MB 二进制
#   提交进 git。副本目录 _build_python/ 应保持在 .gitignore 中。
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File tools/setup_build_python.ps1
#   可选参数:
#     -Src   <Python310 安装路径>  默认 C:\Users\xxx\AppData\Local\Programs\Python\Python310
#     -Dst   <副本目标路径>         默认 D:\qzq\smartquiz\_build_python
#     -Env   <chaquopy env 路径>    默认 <项目根>\build\python\env\release
# ============================================================================
param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$Src,
    [string]$Dst,
    [string]$Env
)

$ErrorActionPreference = 'Stop'

# ---- 默认值 ----
if (-not $Src) { $Src = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python310' }
if (-not $Dst) { $Dst = Join-Path $ProjectRoot '_build_python' }
if (-not $Env) { $Env = Join-Path $ProjectRoot 'build\python\env\release' }

Write-Host "==> 源 buildPython : $Src"
Write-Host "==> 副本目标      : $Dst"
Write-Host "==> chaquopy env  : $Env"

# ---- 1. 校验源 ----
if (-not (Test-Path -LiteralPath (Join-Path $Src 'python.exe'))) {
    throw "未找到源 Python310: $Src"
}
$srcPip = & (Join-Path $Src 'python.exe') -m pip --version 2>&1 | Out-String
Write-Host "==> 源 pip: $($srcPip.Trim())"
if ($srcPip -match 'pip 20\.') {
    Write-Host '源已经是 pip 20.x(可能已被替换), 跳过 pip 换装, 直接复制。'
}

# ---- 2. 复制 Python310 (排除字节码缓存, 体积 ~62MB) ----
if (Test-Path -LiteralPath $Dst) {
    Write-Warning "目标已存在: $Dst, 正在覆盖重建..."
    Remove-Item -LiteralPath $Dst -Recurse -Force
}
Write-Host '==> 复制 Python310 -> 副本 (排除 __pycache__/*.pyc) ...'
robocopy $Src $Dst /E /NFL /NDL /NJH /NJS /XD __pycache__ /XF *.pyc | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $Dst 'python.exe'))) {
    throw '复制失败: 副本里没有 python.exe'
}

# ---- 3. 用 Chaquopy env 的定制 pip 20.1 替换副本里的 pip ----
$envSp = Join-Path $Env 'lib\site-packages'
$dstSp = Join-Path $Dst 'Lib\site-packages'
if (-not (Test-Path -LiteralPath (Join-Path $envSp 'pip'))) {
    throw "未找到 chaquopy env 的 pip: $envSp (先跑过一次构建生成 env 再执行本脚本)"
}

# 删除副本原有的 pip 23.0.1
Get-ChildItem -LiteralPath $dstSp -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq 'pip' -or $_.Name -match '^pip-\d.*\.dist-info$' } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

# 复制 chaquopy 定制 pip 20.1 (含 pip 与 pip-20.1.dist-info)
Copy-Item -LiteralPath (Join-Path $envSp 'pip') -Destination (Join-Path $dstSp 'pip') -Recurse -Force
Get-ChildItem -LiteralPath $envSp -Directory | Where-Object { $_.Name -match '^pip-20\.1.*\.dist-info$' } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $dstSp $_.Name) -Recurse -Force }

# ---- 4. 校验 ----
$dstPy = Join-Path $Dst 'python.exe'
Write-Host '==> 校验副本 pip:'
& $dstPy -m pip --version
$ok = & $dstPy -c @"
from pip._internal.utils.compatibility_tags import _get_custom_platforms
p = _get_custom_platforms('android_31_arm64_v8a')
assert 'android_21_arm64_v8a' in p, p
print('android_2x tag support: OK')
"@
$okText = $ok -join "`n"
Write-Host "==> $okText"

Write-Host ''
Write-Host "完成。build.gradle 里 buildPython 应指向: $Dst\python.exe"
