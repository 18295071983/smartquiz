<#
Restore the project's local llama.cpp changes from the tracked patch.

src/main/cpp/llama.cpp is a nested repo that the main repository ignores,
so these changes only survive here. See patches/README.md.
#>
[CmdletBinding()]
param(
    [switch]$ForceClone
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$LlamaDir  = Join-Path $RepoRoot 'src\main\cpp\llama.cpp'
$Patch     = Join-Path $ScriptDir '0001-android-app-compat.patch'
$RevFile   = Join-Path $ScriptDir 'BASE_REVISION.txt'
$LlamaUrl  = 'https://gitcode.com/gh_mirrors/ll/llama.cpp.git'

if (-not (Test-Path -LiteralPath $Patch)) {
    throw "patch not found: $Patch"
}

$baseCommit = (Select-String -Path $RevFile -Pattern '^base_commit\s*=\s*(\S+)' |
               Select-Object -First 1).Matches.Groups[1].Value
if (-not $baseCommit) {
    throw "could not read base_commit from $RevFile"
}

Write-Host "patch       : $Patch"
Write-Host "base commit : $baseCommit"

$hasRepo = Test-Path -LiteralPath (Join-Path $LlamaDir '.git')

if ($hasRepo -and -not $ForceClone) {
    Write-Host "llama.cpp already present at $LlamaDir"
    Push-Location $LlamaDir
    Write-Host '--- current state ---'
    git status --short
} else {
    if ($hasRepo -and $ForceClone) {
        Write-Host "removing existing checkout (ForceClone)"
        Remove-Item -LiteralPath $LlamaDir -Recurse -Force
    }
    Write-Host "cloning llama.cpp into $LlamaDir"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LlamaDir) | Out-Null
    git clone $LlamaUrl $LlamaDir
    Push-Location $LlamaDir
    git checkout $baseCommit
}

try {
    Write-Host '--- applying patch ---'
    git apply --check $Patch 2>$null
    if ($LASTEXITCODE -eq 0) {
        git apply $Patch
        if ($LASTEXITCODE -ne 0) { throw 'git apply failed' }
        Write-Host 'OK: patch applied'
    } else {
        git apply --reverse --check $Patch 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'OK: patch already applied (nothing to do)'
        } else {
            throw ("patch does not apply cleanly. The tree may be on a different " +
                   "upstream revision. Re-generate with:`n" +
                   "  cd $LlamaDir; git diff origin/master..master > `"$Patch`"")
        }
    }

    Write-Host '--- result ---'
    git status --short
    Write-Host 'done.'
} finally {
    Pop-Location
}
