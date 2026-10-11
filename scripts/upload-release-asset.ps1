# Upload the LibreOffice engine .so as a GitHub release asset (RAW body).
#
# WHY THIS SCRIPT EXISTS
#   The LibreOffice engine (viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so,
#   ~171 MiB) cannot be committed: Gitee allows 50 MB per file / 500 MB per repo,
#   GitHub 100 MB per file. It is therefore distributed as a **release asset**.
#   See BINARY_ASSETS.md for the full picture.
#
# CRITICAL GOTCHA (this repo hit it on 2026-10-11)
#   Do NOT use WebClient.UploadFile() for release assets. It wraps the file in a
#   multipart/form-data body even when Content-Type is explicitly set to
#   application/octet-stream, so GitHub stores the multipart envelope as part of
#   the file: the asset ends up 204 bytes larger than the source and its SHA256
#   never matches (and differs on every upload, because the multipart boundary is
#   random). This script writes the raw file bytes to the request stream instead.
#   Always verify afterwards - either against the API's `digest` field or by
#   re-downloading and comparing SHA256.
#
# CREDENTIALS
#   Uses the credential already stored for github.com (Windows Credential Manager
#   via git's credential helper), so no token needs to be passed in. The value is
#   never printed.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads BOM-less UTF-8 using the
# ANSI codepage, which corrupts non-ASCII string literals (this repo has hit that
# twice). Keep this file ASCII.
#
# USAGE
#   1) Create the release first (or reuse an existing one) and obtain its
#      upload_url, then drop it into -UploadUrl.
#   2) powershell -NoProfile -ExecutionPolicy Bypass -File scripts/upload-release-asset.ps1 `
#        -File <path> -UploadUrl 'https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets{?name,label}'
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$File,

    # Release upload_url, with or without the trailing {?name,label} template
    [Parameter(Mandatory = $true)]
    [string]$UploadUrl,

    # Asset name on GitHub; defaults to the local file name
    [string]$Name
)

$ErrorActionPreference = 'Stop'
$env:GIT_TERMINAL_PROMPT = '0'

if (-not (Test-Path $File)) { throw "file not found: $File" }
if (-not $Name) { $Name = Split-Path $File -Leaf }

$size = (Get-Item $File).Length
$sha = (Get-FileHash $File -Algorithm SHA256).Hash
Write-Host ("[info] local: {0}  {1:N0} bytes  sha256={2}" -f $Name, $size, $sha)

$cred = "protocol=https`nhost=github.com`n`n" | cmd /c "git credential fill 2>&1"
$tok = ($cred | Where-Object { $_ -match '^password=' }) -replace '^password=',''
if (-not $tok) { throw "no stored credential for github.com (git credential helper)" }

$url = $UploadUrl -replace '\{\?name,label\}', "?name=$Name"
# Guard: reject an upload URL that already carries a different name
if ($url -notmatch '\?name=') { $url = "$url`?name=$Name" }
Write-Host "[info] uploading raw body (NO multipart) ..."

$req = [System.Net.HttpWebRequest]::Create($url)
$req.Method = 'POST'
$req.UserAgent = 'dsh'
$req.Headers.Add('Authorization', "Bearer $tok")
$req.Accept = 'application/vnd.github+json'
$req.ContentType = 'application/octet-stream'
$req.ContentLength = $size      # explicit length -> no chunked encoding, no multipart
$req.Timeout = 1800000          # 30 min for large files

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$rs = $req.GetRequestStream()
$fs = [System.IO.File]::OpenRead($File)
try {
    $buf = New-Object byte[] 1048576
    $written = 0
    while (($n = $fs.Read($buf, 0, $buf.Length)) -gt 0) {
        $rs.Write($buf, 0, $n)
        $written += $n
    }
    $rs.Flush()
    if ($written -ne $size) { throw "short write: $written != $size" }
} finally {
    $fs.Dispose()
    $rs.Dispose()
}

try {
    $resp = $req.GetResponse()
} catch {
    Write-Host "[fail] $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Host "[fail] body: $($sr.ReadToEnd())"
        $sr.Dispose()
    }
    throw
}
$sr2 = New-Object System.IO.StreamReader($resp.GetResponseStream())
$jsonText = $sr2.ReadToEnd(); $sr2.Dispose(); $resp.Close()
$sw.Stop()
$json = $jsonText | ConvertFrom-Json

Write-Host ("[done] uploaded in {0:N0}s" -f $sw.Elapsed.TotalSeconds)
Write-Host ("       asset  : {0}" -f $json.name)
Write-Host ("       size   : {0:N0} bytes" -f $json.size)
Write-Host ("       digest : {0}" -f $json.digest)
Write-Host ("       url    : {0}" -f $json.browser_download_url)

$expect = 'sha256:' + $sha.ToLower()
if ($json.digest -and $json.digest -ne $expect) {
    Write-Host "[WARN] digest does NOT match the local file!"
    Write-Host "       expected: $expect"
    Write-Host "       actual  : $($json.digest)"
    exit 1
}
if ($json.size -ne $size) {
    Write-Host "[WARN] size mismatch: remote $($json.size) vs local $size"
    exit 1
}
Write-Host "[OK] size and digest match the local file."
