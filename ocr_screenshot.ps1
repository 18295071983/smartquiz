$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime

# Load WinRT projections
[void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
[void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
[void][Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics, ContentType = WindowsRuntime]
[void][Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    return $netTask.Result
}

$path = 'D:\qzq\smartquiz\bug_screenshot.jpg'
$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

Write-Output ("BITMAP: {0}x{1}" -f $decoder.PixelWidth, $decoder.PixelHeight)

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) {
    Write-Output 'NO_OCR_ENGINE'
    $langs = [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages
    foreach ($l in $langs) { Write-Output ("AVAIL_LANG: {0}" -f $l.LanguageTag) }
    exit 1
}
Write-Output ("ENGINE_LANG: {0}" -f $engine.RecognizerLanguage.LanguageTag)

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
Write-Output ("TEXT_ANGLE: {0}" -f $result.TextAngle)

$i = 0
foreach ($line in $result.Lines) {
    $words = @()
    foreach ($w in $line.Words) {
        $r = $w.BoundingRect
        $words += ('"{0}"@{1},{2} {3}x{4}' -f $w.Text, [int]$r.X, [int]$r.Y, [int]$r.Width, [int]$r.Height)
    }
    $i++
    Write-Output ("LINE {0}: {1}" -f $i, $line.Text)
    Write-Output ("  WORDS: " + ($words -join ' | '))
}
