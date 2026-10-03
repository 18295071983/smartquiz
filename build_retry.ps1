$ok = $false
for ($i = 1; $i -le 8; $i++) {
  Add-Content -Path "D:\qzq\smartquiz\build_retry.log" -Value ("=== attempt $i at " + (Get-Date -Format HH:mm:ss) + " ===")
  Set-Location D:\qzq\smartquiz
  cmd /c "gradlew.bat assembleDebug --console=plain > build_log_sshterm.txt 2>&1"
  if ($LASTEXITCODE -eq 0) { $ok = $true; Add-Content -Path "D:\qzq\smartquiz\build_retry.log" -Value "BUILD_OK"; break }
  Start-Sleep -Seconds 8
}
if ($ok) {
  & "D:\Android\Sdk\platform-tools\adb.exe" -s 279b6c51 install -r "D:\qzq\smartquiz\build\outputs\apk\debug\答题宝-debug-2.0.apk" 2>&1 | Select-Object -Last 2
  Add-Content -Path "D:\qzq\smartquiz\build_retry.log" -Value "INSTALL_DONE"
} else {
  Add-Content -Path "D:\qzq\smartquiz\build_retry.log" -Value "ALL_FAILED"
}
