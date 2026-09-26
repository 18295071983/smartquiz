<#
.SYNOPSIS
  本地快速 Java 编译校验（秒级）：只编你指定的文件，不用等 Gradle 全量 assembleDebug。

.DESCRIPTION
  为什么需要：改完 Java 代码想确认语法/符号对不对，Gradle 全量构建要 30s~几分钟；
  本脚本对指定文件跑一次 javac，通常 1~3 秒出结果。

  类路径怎么来的（不依赖 Gradle 命令行，也不需要改 build.gradle）：
    android.jar(SDK) + R.jar(build/intermediates) + 已编译的 app 类目录 +
    Gradle transforms 缓存里解包好的 jetified-*.jar（= 所有第三方依赖的 classes.jar）
  注意：Gradle 的 debugCompileClasspath 导出的多是 .aar，javac 读不了，必须用 transforms 里的 jar。

  三个坑（都已在脚本里规避）：
    1) @argfile 必须【无 BOM】，且路径用正斜杠 —— argfile 中 \ 是转义符，
       带 BOM 时 javac 会报 "无效的标记: ?-nowarn"。
    2) 改动的多个文件若互相引用，必须【一起】传给本脚本（-Files 支持多个）。
    3) 【本脚本自身必须存成 UTF-8 with BOM】！这台机器上 pwsh 实际是 Windows PowerShell 5.1，
       无 BOM 的 UTF-8 会被按 ANSI/GBK 读 → 中文注释变乱码、还会吞掉字符串引号导致一片语法错误。
       用编辑器/工具改完本脚本后，请确认文件头仍是 EF BB BF（否则重新存成“UTF-8 带 BOM”）。

.PARAMETER Files
  要编译的 Java 源文件（相对仓库根或绝对路径均可）。多个用空格分隔。

.PARAMETER OutDir
  编译输出目录，默认 .workbuddy/tmp/javac_out。

.PARAMETER KeepOut
  保留输出目录（默认每次覆盖；.class 可用于事后 javap 检查）。

.EXAMPLE
  # 单个文件（推荐子进程方式：脚本结尾会 exit，同会话 & 调用会结束该 pwsh 会话）
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\tests\javac_check.ps1 ^
      -Files src\main\java\com\oilquiz\app\ai\python\PythonToolManager.java

.EXAMPLE
  # 多个互相引用的文件：用【逗号】分隔（-Files a.java,b.java），或用数组 @('a.java','b.java')
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\tests\javac_check.ps1 ^
      -Files src\main\java\com\oilquiz\app\ai\tool\BaseAITool.java,src\main\java\com\oilquiz\app\ai\python\PythonFileOpsTool.java

.NOTES
  ⚠️ 不要写成 "-Files a.java b.java"（空格分隔）：PowerShell 会把第二个值绑到 -OutDir 上，
  历史上曾因此把源码文件当成输出目录（先删文件、再建同名目录），源码看起来"凭空消失"。
  脚本已加硬护栏（输出目录必须在 <repo>\.workbuddy\ 下、且不得等于任何源文件）兜底，
  但仍请用逗号或数组形式。
#>
# PositionalBinding=$false：禁止位置绑定。否则 "javac_check.ps1 -Files a.java b.java" 里的
# 第二个值可能被绑到后面的 -OutDir 上 —— 曾因此把源码文件当成输出目录（先删文件、再建同名目录），
# 造成源码"凭空消失"。
[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$OutDir = ".workbuddy/tmp/javac_out",

    [switch]$KeepOut,

    # $Files 必须【放在最后】并带 ValueFromRemainingArguments：
    #   · 放前面时，"-Files a.java b.java" 里的第二个值会被绑到下一个位置参数 -OutDir 上
    #     —— 曾因此把源码文件当输出目录（先删文件、再建同名目录）；
    #   · powershell -File 模式下逗号不会自动拆成数组，所以下面还会按逗号再拆一次。
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Files
)

$ErrorActionPreference = "Stop"

# ---- 仓库根（本脚本位于 <repo>/tools/tests/）----
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {
    # ---- 1. 依赖 jar（transforms 缓存）----
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
    $transforms = Join-Path $gradleHome "caches\8.13\transforms"
    $depJars = @()
    if (Test-Path $transforms) {
        $depJars = Get-ChildItem $transforms -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
                   Select-Object -ExpandProperty FullName
    }
    if ($depJars.Count -eq 0) {
        Write-Warning "transforms 缓存里没有 jar（$transforms）。先跑一次 Gradle 构建再试：.\gradlew.bat compileDebugJavaWithJavac"
    }

    # ---- 2. android.jar（取 platforms 里版本号最高的）----
    $sdkDir = $null
    $localProps = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $sdkDir = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim() -replace '\\\\', '\' }
    }
    if (-not $sdkDir -or -not (Test-Path $sdkDir)) {
        $sdkDir = $env:ANDROID_HOME
    }
    if (-not $sdkDir -or -not (Test-Path $sdkDir)) {
        throw "找不到 Android SDK：请在 local.properties 配 sdk.dir，或设 ANDROID_HOME"
    }
    $androidJar = Get-ChildItem (Join-Path $sdkDir "platforms") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-(\d+)$' } |
        Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName "android.jar" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if (-not $androidJar) { throw "找不到 android.jar（$sdkDir\platforms）" }

    # ---- 3. R.jar + 已编译的 app 类目录 ----
    $rJar = Get-ChildItem (Join-Path $repoRoot "build\intermediates") -Recurse -Filter "R.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\debug\\' } |
        Sort-Object { $_.FullName -match 'compile_and_runtime_not_namespaced' } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    $appClasses = Join-Path $repoRoot "build\intermediates\javac\debug\compileDebugJavaWithJavac\classes"

    $cpParts = @($androidJar)
    if ($rJar) { $cpParts += $rJar }
    if (Test-Path $appClasses) { $cpParts += $appClasses }
    $cpParts += $depJars
    $cp = ($cpParts -join ';').Replace('\', '/')

    # ---- 4. 源文件（转绝对路径 + 正斜杠）----
    $srcFiles = @()
    $fileList = @()
    foreach ($f in $Files) {
        foreach ($part in ([string]$f -split ',')) {   # 兼容 -Files a.java,b.java
            if ($part.Trim().Length -gt 0) { $fileList += $part.Trim() }
        }
    }
    if ($fileList.Count -eq 0) { throw "没有要编译的源文件（-Files 不能为空）" }
    foreach ($f in $fileList) {
        $p = if ([System.IO.Path]::IsPathRooted($f)) { $f } else { Join-Path $repoRoot $f }
        if (-not (Test-Path $p)) { throw "源文件不存在: $f" }
        if ((Get-Item $p).PSIsContainer) { throw "源文件是目录而不是文件: $f" }
        $srcFiles += (Resolve-Path $p).Path.Replace('\', '/')
    }

    # ---- 5. 写无 BOM 的 @argfile ----
    $outAbs = if ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $repoRoot $OutDir }

    # ===== 硬护栏（血的教训）=====
    # 输出目录必须落在 <repo>/.workbuddy/ 下，且不能等于任何源文件：
    # 参数绑定一旦出错（曾发生：第二个文件被绑到 -OutDir），脚本会「先删掉那个源文件、再建同名目录
    # 当输出目录」，javac 还把 class 写进去、退出码 0 —— 源码看起来就"凭空消失"了。双重拦截。
    $outAbsFull = [System.IO.Path]::GetFullPath($outAbs)
    $safeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".workbuddy"))
    if (-not $outAbsFull.StartsWith($safeRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝执行：输出目录必须在 $safeRoot 下（当前为 $outAbsFull）"
    }
    foreach ($sf in $srcFiles) {
        if ($sf -is [string] -and $sf.Trim().Length -gt 0) {
            $sfFull = [System.IO.Path]::GetFullPath($sf)
            if ($sfFull.Equals($outAbsFull, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "拒绝执行：输出目录与源文件相同（$outAbsFull）——参数绑定可能出错"
            }
            if ((Get-Item $sfFull).PSIsContainer) {
                throw "拒绝执行：源文件其实是目录而不是文件（参数绑定可能出错）: $sfFull"
            }
        }
    }
    if (-not $KeepOut -and (Test-Path $outAbs)) { Remove-Item $outAbs -Recurse -Force }
    New-Item -ItemType Directory -Force $outAbs | Out-Null
    $argFile = Join-Path $outAbs "javac.args"
    $lines = @("-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", $outAbs.Replace('\', '/'), "-cp", $cp) + $srcFiles
    [System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

    # ---- 6. 找 javac（优先 JAVA_HOME，其次 PATH）----
    $javac = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        Join-Path $env:JAVA_HOME "bin\javac.exe"
    } else { "javac" }

    Write-Host "javac: $javac"
    Write-Host ("类路径条目: {0}（依赖 jar {1}）" -f $cpParts.Count, $depJars.Count)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $logFile = Join-Path $outAbs "javac.log"
    $stdoutFile = Join-Path $outAbs "javac.stdout.txt"
    $stderrFile = Join-Path $outAbs "javac.stderr.txt"

    # 用 Start-Process 重定向【原始】输出：直接用 & javac ... > file 2>&1 有两个 PS 5.1 坑——
    #   ① $ErrorActionPreference='Stop' 时原生命令写 stderr（javac 成功也会写「注: …」）会被
    #      当成终止错误让脚本崩掉；
    #   ② 该写法会把 stderr 包成 PowerShell 的 ErrorRecord 文本、并以 UTF-16 落盘，
    #      既不是 javac 原始诊断，正则也匹配不到。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $proc = Start-Process -FilePath $javac -ArgumentList "@$argFile" -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
    $code = $proc.ExitCode
    $ErrorActionPreference = $prevEap
    $sw.Stop()

    # 解码容错：javac 诊断编码随控制台而定，UTF-8 → ANSI → UTF-16 都试一遍
    $readAny = {
        param($path)
        if (-not (Test-Path $path)) { return "" }
        foreach ($enc in @([System.Text.Encoding]::UTF8, [System.Text.Encoding]::Default, [System.Text.Encoding]::Unicode)) {
            $t = [System.IO.File]::ReadAllText($path, $enc)
            if ($t -match 'error:|错误:|warning:|警告:') { return $t }
        }
        return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    }
    $logText = (& $readAny $stdoutFile) + "`r`n" + (& $readAny $stderrFile)
    [System.IO.File]::WriteAllText($logFile, $logText, (New-Object System.Text.UTF8Encoding($false)))

    $errs = @($logText -split "`r?`n" | Where-Object { $_ -match 'error:|错误:' })
    $classes = (Get-ChildItem $outAbs -Recurse -Filter "*.class" -ErrorAction SilentlyContinue).Count

    if ($code -eq 0) {
        Write-Host ("OK  编译通过（{0:N1}s，{1} 个 .class）" -f $sw.Elapsed.TotalSeconds, $classes) -ForegroundColor Green
    } else {
        Write-Host ("FAIL 编译失败（{0:N1}s，{1} 个错误）" -f $sw.Elapsed.TotalSeconds, $errs.Count) -ForegroundColor Red
        $errs | Select-Object -First 30 | ForEach-Object {
            $line = if ($_ -is [string]) { $_ } elseif ($_ -ne $null -and $_.PSObject.Properties['Line']) { $_.Line } else { "$_" }
            Write-Host ("  " + ("$line").Trim())
        }
        Write-Host "提示：若错误是『找不到符号』且指向第三方类，先跑一次 .\gradlew.bat compileDebugJavaWithJavac 刷新 transforms 缓存"
        Write-Host "完整日志: $logFile"
    }
    exit $code
} finally {
    Pop-Location
}
