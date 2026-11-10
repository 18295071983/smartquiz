@echo off
rem Restart DeepSeek Harness: quit cleanly, wait, relaunch.
rem Launched detached (WMI) so it survives the host it is about to kill.
setlocal
timeout /t 45 /nobreak >nul
taskkill /IM "DeepSeek Harness.exe" /F >nul 2>&1
timeout /t 5 /nobreak >nul
start "" "C:\Users\xiaocong\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe"
endlocal
