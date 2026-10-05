# verify_plugin.ps1 —— SmartQuiz Remote 插件一键端到端验证
# 用法：pwsh -File verify_plugin.ps1    （powershell.exe 5.1 也能跑：文件带 UTF-8 BOM，
#        且请求体已显式按 UTF-8 编码，不会出现中文乱码）
#
# 自清理说明：第 4 步 /pair/claim 会在插件端无条件新增一条设备记录（不做同名去重、不会过期）。
# 历史上一跑就留一条，手机端「已配对设备」越跑越多（实测遗留 7 条 verify-script）。
# 因此本脚本先取设备表快照，运行结束（含中途失败）时在 finally 里 POST /devices/revoke
# 吊销本次新增的记录，并复核设备表是否复原。
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:19387'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$tf = Join-Path $dir '.token'
$newDevices = @()
$kid = ''
$dev = ''
$sid = ''

function Show($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }

# ★ 请求体必须显式转 UTF-8 字节：Invoke-WebRequest 5.1 对 application/json 默认按 ISO-8859-1
#   编码字符串体，中文会被替换成 '?'（实测 "用一句话说明…" 变成 "???"）。
function Req($u, $m, $body, $tok, $sec) {
  if (-not $m) { $m = 'GET' }
  if (-not $sec) { $sec = 120 }
  $h = @{}
  if ($tok) { $h['Authorization'] = "Bearer $tok" }
  try {
    if ($body) {
      $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$body)
      $r = Invoke-WebRequest -Uri $u -Method $m -Headers $h -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop
    } else {
      $r = Invoke-WebRequest -Uri $u -Method $m -Headers $h -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop
    }
    return @{ code = $r.StatusCode; body = $r.Content }
  } catch {
    $c = $_.Exception.Response.StatusCode.value__
    $t = ''
    try {
      $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      $t = $sr.ReadToEnd()
    } catch { }
    if ($t) { return @{ code = $(if ($c) { $c } else { -1 }); body = $t } }
    return @{ code = $(if ($c) { $c } else { -1 }); body = $_.Exception.Message }
  }
}

function DeviceIds($tok) {
  $r = Req "$base/devices" 'GET' $null $tok 30
  $out = @()
  try {
    $j = $r.body | ConvertFrom-Json
    foreach ($d in $j.devices) { $out += [string]$d.id }
  } catch { }
  return $out
}

function JsonField($raw, $field) {
  try {
    $j = $raw | ConvertFrom-Json
    $v = $j.$field
    if ($v) { return [string]$v }
  } catch { }
  return ''
}

Show '1) /health'
$a = Req "$base/health" 'GET' $null $null 20
Write-Host "HTTP $($a.code) : $($a.body)"

if (-not (Test-Path $tf)) {
  Write-Host "✗ .token 不存在 → 插件未运行，先在插件管理器里启用/重载" -ForegroundColor Red
  exit 1
}
$tok = (Get-Content $tf -Raw).Trim()

Show '2) /status（主令牌）'
$b = Req "$base/status" 'GET' $null $tok 30
Write-Host "HTTP $($b.code) : $($b.body)"

Show '2.5) 设备表快照（收尾据此吊销本次新增）'
$before = DeviceIds $tok
Write-Host "当前已配对设备数: $($before.Count)"

Show '3) /pair/code（本机生成一次性码）'
$c = Req "$base/pair/code" 'POST' '{}' $null 30
Write-Host "HTTP $($c.code) : $($c.body)"
$code = JsonField $c.body 'code'

if ($code) {
  Show '4) /pair/claim（码 → 设备令牌）'
  $payload = '{"code":"' + $code + '","device_name":"verify-script"}'
  $d = Req "$base/pair/claim" 'POST' $payload $null 30
  Write-Host "HTTP $($d.code) : $($d.body)"
  $dev = JsonField $d.body 'device_token'
  $kid = JsonField $d.body 'device_id'
  if ($dev) {
    Show '5) 设备令牌访问 /status'
    $e = Req "$base/status" 'GET' $null $dev 30
    $cut = [Math]::Min(200, $e.body.Length)
    Write-Host "HTTP $($e.code) : $($e.body.Substring(0, $cut))"
  }
}

# 快照差分：不依赖回包解析，凡本次新增的 id 都要吊销
$afterClaim = DeviceIds $tok
foreach ($id in $afterClaim) {
  if ($before -notcontains $id) { $newDevices += $id }
}
if ($newDevices.Count -eq 0 -and $kid) { $newDevices += $kid }
if ($newDevices.Count -gt 0) {
  Write-Host "本次新增设备: $($newDevices -join ', ')（收尾自动吊销）" -ForegroundColor Yellow
}

try {
  Show '6) /session action=start'
  $s = Req "$base/session" 'POST' '{"action":"start"}' $tok 60
  Write-Host "HTTP $($s.code) : $($s.body)"
  $sid = JsonField $s.body 'session_id'

  if (-not $sid) {
    Write-Host "✗ start 失败 → 若仍是 'agents without inject'，说明宿主未重载插件" -ForegroundColor Red
  } else {
    Show '7) /session action=prompt（真跑一轮，中文应原样送达）'
    $p1 = '{"action":"prompt","session_id":"' + $sid + '","text":"用一句话说明你运行在哪台机器上"}'
    $p = Req "$base/session" 'POST' $p1 $tok 180
    $cut = [Math]::Min(900, $p.body.Length)
    Write-Host "HTTP $($p.code) : $($p.body.Substring(0, $cut))"

    Show '8) /session action=history'
    $p2 = '{"action":"history","session_id":"' + $sid + '","max":5}'
    $h2 = Req "$base/session" 'POST' $p2 $tok 60
    $cut = [Math]::Min(500, $h2.body.Length)
    Write-Host "HTTP $($h2.code) : $($h2.body.Substring(0, $cut))"

    Show '9) SSE 流式（start→text→done，最多 60s）'
    try {
      $req = [System.Net.HttpWebRequest]::Create("$base/session")
      $req.Method = 'POST'
      $req.ContentType = 'application/json; charset=utf-8'
      $req.Timeout = 60000
      $req.ReadWriteTimeout = 60000
      $req.Headers.Add('Authorization', "Bearer $tok")
      $req.Accept = 'text/event-stream'
      $p3 = '{"action":"prompt","session_id":"' + $sid + '","text":"再短一点回答","stream":true}'
      $bytes = [System.Text.Encoding]::UTF8.GetBytes($p3)
      $req.ContentLength = $bytes.Length
      $st = $req.GetRequestStream()
      $st.Write($bytes, 0, $bytes.Length)
      $st.Close()
      $resp = $req.GetResponse()
      $rd = New-Object System.IO.StreamReader($resp.GetResponseStream())
      $deadline = (Get-Date).AddSeconds(55)
      $n = 0
      while ((Get-Date) -lt $deadline) {
        $line = $rd.ReadLine()
        if ($null -eq $line) { break }
        if ($line -like 'data:*') {
          Write-Host $line
          $n++
          if ($line -match '"type":"done"') { break }
        }
      }
      Write-Host "SSE 帧数: $n"
    } catch {
      Write-Host "SSE 测试异常: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    Show '10) /devices'
    $g = Req "$base/devices" 'GET' $null $tok 30
    $cut = [Math]::Min(500, $g.body.Length)
    Write-Host "HTTP $($g.code) : $($g.body.Substring(0, $cut))"
  }
} finally {
  # 11/12) 收尾清理：无论上面成败都吊销本次新增的设备记录
  Show '11/12) cleanup：吊销本次新增设备（避免「已配对设备」数量累积）'
  if ($newDevices.Count -eq 0) { Write-Host "无新增设备，无需吊销" }
  foreach ($id in $newDevices) {
    $payload = '{"device_id":"' + $id + '"}'
    $rv = Req "$base/devices/revoke" 'POST' $payload $tok 30
    Write-Host "revoke $id → HTTP $($rv.code) : $($rv.body)"
  }
  $final = DeviceIds $tok
  $left = @()
  foreach ($id in $final) {
    if ($before -notcontains $id) { $left += $id }
  }
  if ($left.Count -gt 0) {
    Write-Host "✗ 仍有残留（$($left -join ', ')）→ 手动执行 POST /devices/revoke {""device_id"":""...""}" -ForegroundColor Red
    exit 1
  }
  Write-Host "✓ 设备表已复原（当前 $($final.Count) 台）" -ForegroundColor Green
  if (-not $sid) { exit 1 }
}
