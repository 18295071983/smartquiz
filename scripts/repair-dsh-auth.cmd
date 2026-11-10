@echo off
rem 修复 DSH 桌面端鉴权失败（Cookie 累积撑爆 16KB 请求头 → 431）。
rem 步骤：等 10 秒（让调用方先返回）→ 结束 DSH → 清理多余 dsh-auth-* cookie → 重新启动。
rem 必须 detached 启动（WMI），否则会连同它要结束的宿主一起死掉。
setlocal
set PY=C:\Users\xiaocong\.workbuddy\dsh-home\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
timeout /t 10 /nobreak >nul
taskkill /IM "DeepSeek Harness.exe" /F >nul 2>&1
timeout /t 5 /nobreak >nul
"%PY%" "D:\qzq\smartquiz\scripts\prune_dsh_cookies.py" >> "D:\qzq\smartquiz\scripts\prune_dsh_cookies.log" 2>&1
timeout /t 2 /nobreak >nul
start "" "C:\Users\xiaocong\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe"
endlocal
