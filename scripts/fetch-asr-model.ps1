<#
.SYNOPSIS
  Fetch the local offline ASR model files (SenseVoice-Small).

.DESCRIPTION
  These two files are ~228 MB total and exceed the code host's per-file limit
  (Gitee/GitHub: 100 MB), so they cannot be committed. A fresh clone therefore
  does NOT contain them; run this script to obtain them from the upstream
  sherpa-onnx release.

  Upstream docs:
    https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html
  Model origin: FunAudioLLM/SenseVoice -> ModelScope iic/SenseVoiceSmall,
  exported via sherpa-onnx scripts/sense-voice/export-onnx.py

  Files placed:
    src/main/assets/asr/model.int8.onnx   (~228 MB)
    src/main/assets/asr/tokens.txt        (~308 KB)

  Note: silero_vad.onnx (1.7 MB) is ALREADY tracked in the repo - not needed here.

  NOTE: messages are ASCII on purpose. Windows PowerShell 5.1 reads BOM-less
  script files using the ANSI codepage, which corrupts non-ASCII string literals
  and breaks parsing. Keep this file ASCII-only.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-asr-model.ps1
#>
[CmdletBinding()]
param(
    # Official direct link (verified 2026-10-11: HTTP 200, 155.5 MB)
    [string]$Url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2',
    # Top-level directory inside the archive
    [string]$InnerDir = 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17',
    [string]$TempDir = "$env:TEMP\sensevoice-fetch"
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$destDir  = Join-Path $repoRoot 'src\main\assets\asr'
$archive  = Join-Path $TempDir 'sensevoice.tar.bz2'

$modelDst  = Join-Path $destDir 'model.int8.onnx'
$tokensDst = Join-Path $destDir 'tokens.txt'

Write-Host "[1/4] Destination: $destDir"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
New-Item -ItemType Directory -Force -Path $TempDir  | Out-Null

if ((Test-Path $modelDst) -and (Test-Path $tokensDst)) {
    Write-Host "[OK] Both files already present - nothing to do:"
    Get-Item $modelDst, $tokensDst | ForEach-Object {
        Write-Host ("     {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB))
    }
    exit 0
}

Write-Host "[2/4] Downloading (~155 MB, may take a few minutes)"
Write-Host "      $Url"
Invoke-WebRequest -Uri $Url -OutFile $archive -UseBasicParsing

Write-Host "[3/4] Extracting tar.bz2"
# Windows 10 1803+ ships tar (libarchive) which handles bz2 without extra tools.
& tar -xjf $archive -C $TempDir
if ($LASTEXITCODE -ne 0) {
    throw "tar extraction failed (exit code $LASTEXITCODE). Check that system tar is available."
}

$srcDir = Join-Path $TempDir $InnerDir
if (-not (Test-Path $srcDir)) {
    throw "Extracted directory not found: $srcDir"
}

Write-Host "[4/4] Copying into assets"
Copy-Item (Join-Path $srcDir 'model.int8.onnx') $modelDst  -Force
Copy-Item (Join-Path $srcDir 'tokens.txt')      $tokensDst -Force

Write-Host "[DONE] Result:"
Get-Item $modelDst, $tokensDst | ForEach-Object {
    Write-Host ("       {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB))
}
Write-Host ""
Write-Host "If the build still reports missing asr/* files, compare the paths above"
Write-Host "with the BINARY-PRECHECK list in build.gradle."
Write-Host "Temp files kept at $TempDir - safe to delete."
