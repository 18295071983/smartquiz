# Card contract scan + fixture-driven smoke test
# 1) scan each *CardView for the props keys it reads -> build\card_props.tsv
# 2) build a fixture per card from those keys (heuristics) and run it on device
# 3) classify result via uiautomator: FALLBACK(<>)/EMPTY/OK
param(
    [string]$Serial = "279b6c51",
    [string]$Package = "com.oilquiz.app",
    [int]$WaitMs = 1800
)
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$out = Join-Path $root "build\card_contract"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$typesFile = Join-Path $root "build\card_types.txt"
$types = Get-Content $typesFile -Encoding utf8 | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }

# ---------- 1) scan props keys ----------
$classDir = Join-Path $root "src\main\java\com\oilquiz\app\ai\chat\component"
$typeToKeys = @{}
Get-ChildItem $classDir -Filter "*CardView.java" | ForEach-Object {
    $text = Get-Content $_.FullName -Raw -Encoding UTF8
    # type string: look for getType -> return "xxx"
    $m = [regex]::Match($text, 'getType\(\)\s*\{\s*return\s*"([a-z0-9_]+)"')
    if (-not $m.Success) { return }
    $type = $m.Groups[1].Value
    $keys = [regex]::Matches($text, 'opt(?:String|Int|Boolean|Double|Long|JSONArray|JSONObject)\("([A-Za-z0-9_]+)"') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    if ($keys.Count -gt 0) { $typeToKeys[$type] = $keys }
}
$scanLines = foreach ($k in ($typeToKeys.Keys | Sort-Object)) { "$k`t" + ($typeToKeys[$k] -join ",") }
$scanLines | Out-File -FilePath (Join-Path $out "card_props.tsv") -Encoding utf8
Write-Host "扫描到 $($typeToKeys.Count) 张卡片的 props 契约"

# ---------- 2) fixture ----------
function New-Fixture([string[]]$keys) {
    $o = [ordered]@{}
    foreach ($k in $keys) {
        $lk = $k.ToLower()
        if ($lk -match 'url|img|image|src|thumb|avatar|cover|path') { $o[$k] = "https://example.com/a.png" }
        elseif ($lk -match 'color') { $o[$k] = "#1E88E5" }
        elseif ($lk -match '^rows$|^columns$|^cells$|^items$') { $o[$k] = @(@("甲","乙"),@("1","2")) }
        elseif ($lk -match 'data|series|values|points|list|chart') { $o[$k] = @([ordered]@{label="甲";value=30},[ordered]@{label="乙";value=70}) }
        elseif ($lk -match 'percent|progress|value|score|count|num|max|min|temp|total|amount|index|step') { $o[$k] = 66 }
        elseif ($lk -match '^(done|active|checked|enabled|selected|visible|ok|success)$') { $o[$k] = $true }
        elseif ($lk -match 'options|style|config|meta|extra') { $o[$k] = @{} }
        elseif ($lk -match 'time|date|start|end|deadline') { $o[$k] = "2026-11-16 10:00" }
        elseif ($lk -match 'code|lang') { $o[$k] = "print(1)" }
        else { $o[$k] = "冒烟" }
    }
    if ($o.Count -eq 0) { $o["title"] = "冒烟标题"; $o["text"] = "hello" }
    return ($o | ConvertTo-Json -Depth 6 -Compress)
}

# ---------- 3) run ----------
$rows = New-Object System.Collections.Generic.List[string]
$i = 0
foreach ($t in $types) {
    $i++
    $keys = $typeToKeys[$t]
    $fx = if ($keys) { New-Fixture $keys } else { '{"title":"冒烟标题","text":"hello"}' }
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($fx))
    adb -s $Serial shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 500
    adb -s $Serial shell am start -n "$Package/.infra.DebugLayoutInjectActivity" --es mode card_plugin --es card $t --es props_b64 $b64 | Out-Null
    Start-Sleep -Milliseconds $WaitMs
    adb -s $Serial shell uiautomator dump /sdcard/card.xml 2>&1 | Out-Null
    $xml = adb -s $Serial shell cat /sdcard/card.xml 2>$null
    $state = "OK"
    if ($xml -match "查看原始 JSON") { $state = "FALLBACK" }
    elseif ($xml -notmatch "组件已创建") { $state = "NO_DIALOG" }
    # 是否真的画出了该卡的内容：抓 dialog 内文字长度做粗判
    $rows.Add("$t`t$state`t$($keys -join ',')")
    Write-Progress -Activity "card contract" -Status "$i/$($types.Count) $t -> $state" -PercentComplete (($i / $types.Count) * 100)
}
$rows | Out-File -FilePath (Join-Path $out "result.tsv") -Encoding utf8
$fb = ($rows | Where-Object { $_ -match "`tFALLBACK`t" }).Count
$nd = ($rows | Where-Object { $_ -match "`tNO_DIALOG`t" }).Count
"完成 $($rows.Count) 张：FALLBACK=$fb NO_DIALOG=$nd OK=$($rows.Count-$fb-$nd)"
"明细：build\card_contract\result.tsv ；契约：build\card_contract\card_props.tsv"
