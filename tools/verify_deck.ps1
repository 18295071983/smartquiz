# A1 验收脚本：甲板不挤聊天区（2026-11-13）
# 采集 DeckMetrics 日志：deck_dp / chat_dp / deck_share
# 判定：deck_share <= 12%（阈值来自 90-tests-acceptance.md A1）
# 用法： .\verify_deck.ps1  [-Count 5] [-MaxShare 12]
param(
  [int]$Count = 5,
  [int]$MaxShare = 12
)
$ErrorActionPreference = 'Stop'
$lines = adb logcat -d -s DeckMetrics 2>$null |
  Select-String -Pattern 'deck_dp=(\d+) chat_dp=(\d+) deck_share=(-?\d+)%' |
  Select-Object -Last $Count
if (-not $lines) {
  Write-Output 'no DeckMetrics log: open AI chat once to trigger it'
  exit 1
}
$worst = -1
foreach ($l in $lines) {
  if ($l.Line -match 'deck_dp=(\d+) chat_dp=(\d+) deck_share=(-?\d+)%') {
    $deck = [int]$Matches[1]; $chat = [int]$Matches[2]; $share = [int]$Matches[3]
    if ($share -gt $worst) { $worst = $share }
    Write-Output ('deck={0,4}dp chat={1,4}dp share={2,3}%' -f $deck, $chat, $share)
  }
}
if ($worst -lt 0) { Write-Output 'no valid deck_share'; exit 1 }
if ($worst -gt $MaxShare) { Write-Output ('FAIL: max share {0}% > {1}%' -f $worst, $MaxShare); exit 1 }
Write-Output ('PASS: max share {0}% <= {1}%' -f $worst, $MaxShare)