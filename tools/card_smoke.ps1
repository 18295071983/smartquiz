# 卡片渲染冒烟测试（批量）：逐个把内置卡片类型喂给动态组件 render.card，看谁画不出来
# 用法：pwsh -File tools\card_smoke.ps1
# 输出：build\card_smoke\result.tsv（type、创建是否成功、日志是否报渲染失败）
param(
    [string]$Serial = "279b6c51",
    [int]$WaitMs = 2500,
    [string]$Package = "com.oilquiz.app"
)

$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$outDir = Join-Path $root "build\card_smoke"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$typesFile = Join-Path $root "build\card_types.txt"
if (-not (Test-Path $typesFile)) { throw "缺少 build\card_types.txt（先跑 --es mode list_cards 抓名单）" }
$types = Get-Content $typesFile -Encoding utf8 | Where-Object { $_.Trim() -ne "" } | ForEach-Object { $_.Trim() }

# 一份"尽量通用"的 props：各类卡片按需取其中字段，取不到的走默认值
$genericProps = '{"title":"冒烟标题","description":"冒烟描述","text":"hello","value":"42","items":[["键","值"]],"rows":[["列1","列2"]],"percent":66,"progress":66,"status":"running","url":"https://example.com","code":"print(1)"}'
$propsB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($genericProps))

$rows = New-Object System.Collections.Generic.List[string]
$i = 0
foreach ($t in $types) {
    $i++
    adb -s $Serial logcat -c | Out-Null
    adb -s $Serial shell am start -n "$Package/.infra.DebugLayoutInjectActivity" --es mode card_plugin --es card $t --es props_b64 $propsB64 | Out-Null
    Start-Sleep -Milliseconds $WaitMs
    $log = (adb -s $Serial logcat -d -s DebugLayoutInject 2>&1) -join "`n"
    $created = if ($log -match "组件已创建") { "OK" } else { "NO_CREATE" }
    $renderFail = if ($log -match "卡片渲染失败") { "RENDER_FAIL" } else { "" }
    $rows.Add("$t`t$created`t$renderFail")
    Write-Progress -Activity "卡片冒烟" -Status "$i/$($types.Count) $t" -PercentComplete (($i / $types.Count) * 100)
    # 每 20 张留一张截图作为抽样证据
    if ($i % 20 -eq 0) {
        cmd /c "adb -s $Serial exec-out screencap -p > `"$outDir\sample_$i.png`"" 2>$null
    }
    adb -s $Serial shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 400
}
$rows | Out-File -FilePath (Join-Path $outDir "result.tsv") -Encoding utf8
$fail = ($rows | Where-Object { $_ -match "RENDER_FAIL" }).Count
$noCreate = ($rows | Where-Object { $_ -match "NO_CREATE" }).Count
"完成：共 $($rows.Count) 张，创建失败 $noCreate，渲染失败 $fail"
"明细：build\card_smoke\result.tsv"
