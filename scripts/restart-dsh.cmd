@echo off
rem Restart DeepSeek Harness: quit cleanly, wait, prune accumulated auth cookies, relaunch.
rem Launched detached (WMI) so it survives the host it is about to kill.
rem
rem 2026-11-10: DSH 每次启动都新写一个 dsh-auth-* cookie 且从不清理；累积到 70+ 个后
rem Cookie 头超过 Node http 的 16KB 上限 → 431 → "desktop welcome: Web authentication failed"。
rem 这里在启动前顺手裁掉多余的（每个 host 只留最新一条），从源头避免复发。
setlocal
set PY=C:\Users\xiaocong\.workbuddy\dsh-home\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe
timeout /t 45 /nobreak >nul
taskkill /IM "DeepSeek Harness.exe" /F >nul 2>&1
timeout /t 5 /nobreak >nul
if exist "%PY%" "%PY%" "D:\qzq\smartquiz\scripts\prune_dsh_cookies.py" >> "D:\qzq\smartquiz\scripts\prune_dsh_cookies.log" 2>&1
timeout /t 2 /nobreak >nul
start "" "C:\Users\xiaocong\AppData\Local\Programs\DeepSeek Harness\DeepSeek Harness.exe"
endlocal
