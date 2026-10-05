# verify_plugin.ps1 —— SmartQuiz Remote 插件一键端到端验证
# 用法：powershell -ExecutionPolicy Bypass -File verify_plugin.ps1
$ErrorActionPreference = 'Continue'
$base = 'http://127.0.0.1:19387'
$dir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$tf   = Join-Path $dir '.token'
function Show($t){ Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Req($u,$m='GET',$body=$null,$tok=$null,$sec=120){
  $h=@{}
  if($tok){ $h['Authorization']="Bearer $tok" }
  try{
    if($body){ $r=Invoke-WebRequest -Uri $u -Method $m -Headers $h -ContentType 'application/json' -Body $body -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop }
    else     { $r=Invoke-WebRequest -Uri $u -Method $m -Headers $h -TimeoutSec $sec -UseBasicParsing -ErrorAction Stop }
    return @{ code=$r.StatusCode; body=$r.Content }
  } catch {
    $c=$_.Exception.Response.StatusCode.value__; $t=''
    try{ $sr=New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream()); $t=$sr.ReadToEnd() }catch{}
    return @{ code=$(if($c){$c}else{-1}); body=$(if($t){$t}else{$_.Exception.Message}) }
  }
}
Show '1) /health'
$a=Req "$base/health"; Write-Host "HTTP $($a.code) : $($a.body)"

if(-not (Test-Path $tf)){ Write-Host "`n✗ .token 不存在 → 插件未运行，先在插件管理器里启用/重载" -ForegroundColor Red; exit 1 }
$tok=(Get-Content $tf -Raw).Trim()
Show '2) /status（主令牌）'
$b=Req "$base/status" 'GET' $null $tok; Write-Host "HTTP $($b.code) : $($b.body)"

Show '3) /pair/code（本机生成一次性码）'
$c=Req "$base/pair/code" 'POST' '{}'
Write-Host "HTTP $($c.code) : $($c.body)"
$code=$null; try{ $code=($c.body|ConvertFrom-Json).code }catch{}
if($code){
  Show '4) /pair/claim（码 → 设备令牌）'
  $d=Req "$base/pair/claim" 'POST' ("{""code"":""$code"",""device_name"":""verify-script""}")
  Write-Host "HTTP $($d.code) : $($d.body)"
  $dev=$null; try{ $dev=($d.body|ConvertFrom-Json).device_token }catch{}
  if($dev){ Show '5) 设备令牌访问 /status'; $e=Req "$base/status" 'GET' $null $dev; Write-Host "HTTP $($e.code) : $($e.body.Substring(0,[Math]::Min(200,$e.body.Length)))" }
}

Show '6) /session action=start'
$s=Req "$base/session" 'POST' '{"action":"start"}' $tok 60
Write-Host "HTTP $($s.code) : $($s.body)"
$sid=$null; try{ $sid=($s.body|ConvertFrom-Json).session_id }catch{}
if(-not $sid){ Write-Host "`n✗ start 失败 → 若仍是 'agents without inject'，说明宿主未重载插件" -ForegroundColor Red; exit 1 }

Show '7) /session action=prompt（真跑一轮）'
$p=Req "$base/session" 'POST' ("{""action"":""prompt"",""session_id"":""$sid"",""text"":""用一句话说明你运行在哪台机器上""}") $tok 180
Write-Host "HTTP $($p.code) : $($p.body.Substring(0,[Math]::Min(900,$p.body.Length)))"

Show '8) /session action=history'
$h=Req "$base/session" 'POST' ("{""action"":""history"",""session_id"":""$sid"",""max"":5}") $tok 60
Write-Host "HTTP $($h.code) : $($h.body.Substring(0,[Math]::Min(500,$h.body.Length)))"

Show '9) SSE 流式（start→text→done，最多 60s）'
try{
  $req=[System.Net.HttpWebRequest]::Create("$base/session")
  $req.Method='POST'; $req.ContentType='application/json'; $req.Timeout=60000; $req.ReadWriteTimeout=60000
  $req.Headers.Add('Authorization',"Bearer $tok"); $req.Accept='text/event-stream'
  $bytes=[System.Text.Encoding]::UTF8.GetBytes("{""action"":""prompt"",""session_id"":""$sid"",""text"":""再短一点回答"",""stream"":true}")
  $req.ContentLength=$bytes.Length
  $st=$req.GetRequestStream(); $st.Write($bytes,0,$bytes.Length); $st.Close()
  $resp=$req.GetResponse(); $rd=New-Object System.IO.StreamReader($resp.GetResponseStream())
  $deadline=(Get-Date).AddSeconds(55); $n=0
  while((Get-Date) -lt $deadline){ $line=$rd.ReadLine(); if($null -eq $line){ break }; if($line -like 'data:*'){ Write-Host $line; $n++; if($line -match '"type":"done"'){ break } } }
  Write-Host "SSE 帧数: $n"
} catch { Write-Host "SSE 测试异常: $($_.Exception.Message)" -ForegroundColor Yellow }

Show '10) /devices'
$g=Req "$base/devices" 'GET' $null $tok; Write-Host "HTTP $($g.code) : $($g.body.Substring(0,[Math]::Min(500,$g.body.Length)))"