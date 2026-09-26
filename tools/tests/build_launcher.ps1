# 编译内置工具路由器 liblauncher.so（C，静态链接）
#
# 作用：bin/<name> 都是指向它的软链接；它按 内置 -> 系统 -> busybox -> toybox 顺序
# 找可用实现后 exec，并【只给自己】设置依赖库路径。
#
# 为什么不在 shell 里全局设 LD_LIBRARY_PATH：那会让系统二进制（如 /system/bin/curl）
# 加载到我们的 libcrypto.so 而符号不匹配（实测 "cannot locate symbol EVP_MD_CTX_create"）。
#
# 注意库路径顺序：toolkit_lib/lib 必须排在 nativeLibraryDir 之前，否则 App 自带的
# libc++_shared.so（不同 NDK 版本）会抢在 Termux 版前面被加载，ffmpeg 会符号不匹配。
#
# 用法：powershell -ExecutionPolicy Bypass -File tools\tests\build_launcher.ps1
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$src = Join-Path $repo 'tools\tests\launcher\router.c'
$dst = Join-Path $repo 'src\main\jniLibs\arm64-v8a\liblauncher.so'

$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk -or -not (Test-Path $ndk)) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { throw '请设置 ANDROID_NDK_HOME 或 ANDROID_HOME' }
    $ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$tc = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
Write-Host "NDK = $ndk"
& (Join-Path $tc 'aarch64-linux-android31-clang.cmd') -O2 -static -o $dst $src
if ($LASTEXITCODE -ne 0) { throw '编译失败' }
& (Join-Path $tc 'llvm-strip.exe') $dst
Write-Host ("OK  " + $dst + "  " + (Get-Item $dst).Length + " bytes")
