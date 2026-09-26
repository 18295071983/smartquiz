# 抓取并安装"内置 Linux 命令工具箱"（Termux 官方 busybox，bionic 构建）
#
# 为什么用 Termux 的包，而不是 busybox.net 的静态二进制：
#   busybox.net / Alpine 的静态版是 musl 静态链接，实测在 App 域会被 Android 的 seccomp
#   以 SIGSYS 杀掉（Bad system call，exit 159），只打印版本横幅能过、真正跑 applet 就死。
#   Termux 的包是 bionic 构建（和 Android 自带 toybox 一样），实测在 App 域跑得通。
#
# 为什么必须放进 jniLibs 并打开 useLegacyPackaging：
#   targetSdk>=29 的应用不能执行自己写进 data 目录的文件（实测 Permission denied, exit 126，
#   工作区/公共目录同样不行），唯一可执行的是系统解压出来的 nativeLibraryDir。
#
# 用法：powershell -ExecutionPolicy Bypass -File tools\tests\fetch_busybox.ps1
$ErrorActionPreference = 'Stop'
$base = 'https://packages.termux.dev/apt/termux-main/'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$out = Join-Path $repo 'src\main\jniLibs\arm64-v8a'
$tmp = Join-Path $repo '.workbuddy\tmp\busybox_fetch'
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Get-PackageFile([string]$pkg) {
    # 必须用 Invoke-WebRequest 并兼容返回 byte[] 的情况：
    # 用 [System.Net.WebRequest] + StreamReader 读这个索引会拿到解析不了的内容（实测：匹配不到任何包）
    $resp = Invoke-WebRequest -Uri ($base + 'dists/stable/main/binary-aarch64/Packages') -UseBasicParsing -TimeoutSec 180
    $c = $resp.Content
    $txt = if ($c -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($c) } else { [string]$c }
    # 逐行扫描，不用正则：索引是 CRLF、内容大，正则的 ^ $ 锚点在这里很容易踩坑（实测匹配不到）
    $lines = $txt -split "`n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -eq ('Package: ' + $pkg)) {
            for ($j = $i; $j -lt [Math]::Min($i + 40, $lines.Count); $j++) {
                if ($lines[$j].StartsWith('Filename: ')) { return $lines[$j].Substring(10).Trim() }
            }
        }
    }
    throw "索引里找不到包: $pkg"
}

function Save-Deb([string]$pkg, [string]$dest) {
    if (Test-Path $dest) { return }
    $fn = Get-PackageFile $pkg
    Write-Host "下载 $pkg <- $fn"
    Invoke-WebRequest -Uri ($base + $fn) -OutFile $dest -UseBasicParsing -TimeoutSec 300
}

# .deb 是 ar 归档：取出 data.tar.* 并用系统 tar 解开
function Expand-Deb([string]$deb, [string]$dir) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($deb)
    $pos = 8
    while ($pos + 60 -le $bytes.Length) {
        $name = ([System.Text.Encoding]::ASCII.GetString($bytes, $pos, 16) -replace '/$', '').Trim()
        $size = [int]([System.Text.Encoding]::ASCII.GetString($bytes, $pos + 48, 10).Trim())
        if ($name -like 'data.tar*') {
            $payload = Join-Path $dir 'data.tar.xz'
            $slice = New-Object byte[] $size
            [Array]::Copy($bytes, $pos + 60, $slice, 0, $size)
            [System.IO.File]::WriteAllBytes($payload, $slice)
            & tar -xf $payload -C $dir
            Remove-Item $payload -Force
            return
        }
        $pos = $pos + 60 + $size
        if ($pos % 2 -ne 0) { $pos++ }
    }
    throw "不是合法的 deb: $deb"
}

function Install-File([string]$src, [string]$dstName) {
    Copy-Item $src (Join-Path $out $dstName) -Force
    Write-Host ("  安装 " + $dstName + "  " + [math]::Round((Get-Item (Join-Path $out $dstName)).Length / 1KB, 1) + " KB")
}

# 1) busybox：bin/busybox 是 4KB 启动器（有 PT_INTERP，可 exec），
#    usr/lib/libbusybox.so.1.38.0 才是真正的多合一二进制（纯 .so，只能被启动器按 NEEDED 加载）。
#    两者都必须以 lib*.so 命名才能被 AGP 打包，所以把动态段里的 "libbusybox.so.1.38.0" 就地补丁成 "libbusybox.so"。
$bbDeb = Join-Path $tmp 'busybox.deb'
Save-Deb 'busybox' $bbDeb
$bbDir = Join-Path $tmp 'busybox'
Expand-Deb $bbDeb $bbDir
$launcher = Join-Path $bbDir 'data/data/com.termux/files/usr/bin/busybox'
$core = Join-Path $bbDir 'data/data/com.termux/files/usr/lib/libbusybox.so.1.38.0'
function Patch-Name([string]$src, [string]$dst) {
    $d = [System.IO.File]::ReadAllBytes($src)
    $old = [System.Text.Encoding]::ASCII.GetBytes('libbusybox.so.1.38.0')
    $new = [System.Text.Encoding]::ASCII.GetBytes('libbusybox.so' + [char]0 + [char]0 + [char]0 + [char]0 + [char]0 + [char]0 + [char]0 + [char]0)
    for ($i = 0; $i -le $d.Length - $old.Length; $i++) {
        $hit = $true
        for ($j = 0; $j -lt $old.Length; $j++) { if ($d[$i + $j] -ne $old[$j]) { $hit = $false; break } }
        if ($hit) { [Array]::Copy($new, 0, $d, $i, $new.Length); break }
    }
    [System.IO.File]::WriteAllBytes($dst, $d)
}
Patch-Name $core (Join-Path $out 'libbusybox.so')
Patch-Name $launcher (Join-Path $out 'libbusybox_launcher.so')
Write-Host '  安装 libbusybox.so / libbusybox_launcher.so'

# 2) 依赖链：启动器 -> libbusybox.so -> libandroid-selinux.so -> libpcre2-8.so
$selDeb = Join-Path $tmp 'libandroid-selinux.deb'
Save-Deb 'libandroid-selinux' $selDeb
$selDir = Join-Path $tmp 'selinux'
Expand-Deb $selDeb $selDir
Install-File (Get-ChildItem $selDir -Recurse -Filter 'libandroid-selinux.so' | Select-Object -First 1).FullName 'libandroid-selinux.so'

$pcDeb = Join-Path $tmp 'pcre2.deb'
Save-Deb 'pcre2' $pcDeb
$pcDir = Join-Path $tmp 'pcre2'
Expand-Deb $pcDeb $pcDir
Install-File (Get-ChildItem $pcDir -Recurse -Filter 'libpcre2-8.so' | Select-Object -First 1).FullName 'libpcre2-8.so'

# 3) RUNPATH 补成 $ORIGIN：让内置库在自己的目录里被找到，不带 LD_LIBRARY_PATH 裸跑也不再报
#    "CANNOT LINK EXECUTABLE ... library libbusybox.so not found"（Termux 原值是 /data/data/com.termux/files/usr/lib）
function Patch-Runpath([string]$path) {
    $d = [System.IO.File]::ReadAllBytes($path)
    $old = [System.Text.Encoding]::ASCII.GetBytes('/data/data/com.termux/files/usr/lib')
    $newStr = '$ORIGIN' + ([string][char]0) * ($old.Length - 7)
    $new = [System.Text.Encoding]::ASCII.GetBytes($newStr)
    $hit = 0
    for ($i = 0; $i -le $d.Length - $old.Length; $i++) {
        $same = $true
        for ($j = 0; $j -lt $old.Length; $j++) { if ($d[$i + $j] -ne $old[$j]) { $same = $false; break } }
        if ($same) { [Array]::Copy($new, 0, $d, $i, $new.Length); $hit++; $i += $old.Length - 1 }
    }
    [System.IO.File]::WriteAllBytes($path, $d)
    Write-Host ("  RUNPATH -> $ORIGIN: " + (Split-Path $path -Leaf) + "  hits=" + $hit)
}
foreach ($f in @('libbusybox_launcher.so', 'libbusybox.so', 'libandroid-selinux.so')) {
    Patch-Runpath (Join-Path $out $f)
}

# 4) wget/curl 包装脚本：https 交给 App 内下载服务（busybox 未编译 TLS）。
#    必须以 lib*.so 命名并放在 jniLibs 里 —— App 数据目录里的文件不允许 exec（实测 Permission denied）。
$wgetShim = @'
#!/system/bin/sh
BIN_DIR=$(dirname "$0")
PORT="$HTTP_FETCH_PORT"
if [ -z "$PORT" ]; then PORT=$(cat "$BIN_DIR/.http_port" 2>/dev/null); fi
OUT=""
URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    -O) OUT="$2"; shift 2 ;;
    -O*) OUT=$(echo "$1" | cut -c3-); shift ;;
    -q|--quiet|-nv|--no-verbose|-c|--continue|-N|--timestamping) shift ;;
    -*) shift ;;
    *) if [ -z "$URL" ]; then URL="$1"; fi; shift ;;
  esac
done
if [ -z "$URL" ]; then echo 'wget: missing URL' >&2; exit 1; fi
if [ -z "$OUT" ]; then OUT=$(basename "$URL"); fi
if [ -z "$PORT" ]; then echo 'wget: download service not ready' >&2; exit 1; fi
B64=$(printf '%s' "$URL" | base64 | tr -d '\n' | tr '+/' '-_')
exec "$BIN_DIR/busybox" wget -O "$OUT" "http://127.0.0.1:$PORT/$B64"
'@
$curlShim = @'
#!/system/bin/sh
BIN_DIR=$(dirname "$0")
PORT="$HTTP_FETCH_PORT"
if [ -z "$PORT" ]; then PORT=$(cat "$BIN_DIR/.http_port" 2>/dev/null); fi
OUT=""
URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o|--output) OUT="$2"; shift 2 ;;
    -O|--remote-name) OUT=""; shift ;;
    -s|--silent|-S|--show-error|-L|--location|-f|--fail|-k|--insecure|-#|--progress-bar) shift ;;
    -X|--request|-H|--header|-d|--data|-u|--user|-A|--user-agent) shift 2 ;;
    -*) shift ;;
    *) if [ -z "$URL" ]; then URL="$1"; fi; shift ;;
  esac
done
if [ -z "$URL" ]; then echo 'curl: no URL specified' >&2; exit 2; fi
if [ -z "$OUT" ]; then OUT="-"; fi
if [ -z "$PORT" ]; then echo 'curl: download service not ready' >&2; exit 1; fi
B64=$(printf '%s' "$URL" | base64 | tr -d '\n' | tr '+/' '-_')
exec "$BIN_DIR/busybox" wget -q -O "$OUT" "http://127.0.0.1:$PORT/$B64"
'@
[System.IO.File]::WriteAllText((Join-Path $out 'libwget_shim.so'), ($wgetShim -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText((Join-Path $out 'libcurl_shim.so'), ($curlShim -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host '  安装 libwget_shim.so / libcurl_shim.so'

Write-Host '完成。注意：busybox 是 GPLv2，随 APK 分发需按 GPL 提供对应源码（busybox.net）。'
