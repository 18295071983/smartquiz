# Fetch the bundled CJK font used by PDF export (Noto Sans SC, OFL-1.1).
#
# Why this script: src/main/assets/fonts/ is in .gitignore (large binary), so a fresh
# clone has no Chinese font and PDF export would fail with "missing CJK font".
# Run this once before building, or copy the file in manually.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 scripts as ANSI,
# and non-ASCII literals break parsing (this repo got burned twice).
param(
    [string]$Url = 'https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf',
    [string]$Out = 'src/main/assets/fonts/NotoSansSC-Regular.ttf'
)

$ErrorActionPreference = 'Stop'

$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) {
    New-Item -ItemType Directory -Path $dir | Out-Null
}

if (Test-Path $Out) {
    $existing = (Get-Item $Out).Length
    if ($existing -gt 1MB) {
        Write-Host "[fetch-font] already present: $Out ($existing bytes)"
        exit 0
    }
}

Write-Host "[fetch-font] downloading $Url"
Invoke-WebRequest -Uri $Url -OutFile $Out -UseBasicParsing

$size = (Get-Item $Out).Length
if ($size -lt 1MB) {
    Remove-Item $Out -Force
    throw "[fetch-font] downloaded file is too small ($size bytes), aborting"
}

$hash = (Get-FileHash $Out -Algorithm SHA256).Hash
Write-Host "[fetch-font] ok: $Out"
Write-Host "[fetch-font] size: $size bytes"
Write-Host "[fetch-font] sha256: $hash"
Write-Host "[fetch-font] license: SIL Open Font License 1.1 (Noto Sans SC)"
