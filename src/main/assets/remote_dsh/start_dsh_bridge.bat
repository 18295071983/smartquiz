@echo off
REM ==============================================================
REM  SmartQuiz remote bridge - one-click launcher (Windows)
REM
REM  HOW TO USE: put these files in ONE folder, then double-click
REM  this file. It will:
REM    1) create an access token (first run; saved as .bridge_token)
REM    2) start the dsh ACP server on 127.0.0.1:7800
REM    3) start the bridge on 0.0.0.0:8218 and open the pairing page
REM  Then pair from the phone app (Remote connect -> Scan QR).
REM  Full Chinese guide: see README.md in this folder.
REM
REM  REQUIRES: Python 3.9+ and Node.js 18+ with dsh installed:
REM            npm install -g @deepseek-ai/dsh
REM  STOP: close this window and the "dsh-acp-7800" window.
REM
REM  NOTE: this file is intentionally ASCII-only. Windows cmd re-reads a
REM  .bat by byte offset, so switching codepage (chcp) while the file
REM  still contains multi-byte text corrupts parsing - a real bug we hit.
REM ==============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
cd /d "%~dp0"

set "TOKEN="
if exist ".bridge_token" set /p TOKEN=<".bridge_token"
if "%TOKEN%"=="" (
  set "TOKEN=!RANDOM!!RANDOM!!RANDOM!!RANDOM!!RANDOM!!RANDOM!!RANDOM!!RANDOM!"
  echo !TOKEN!>".bridge_token"
)

echo.
echo   Access token : !TOKEN!
echo   Pair page    : http://127.0.0.1:8218/pair
echo   Work folder  : %CD%
echo.

where dsh >nul 2>nul
if errorlevel 1 (
  echo [ERROR] "dsh" not found. Install Node.js, then run: npm install -g @deepseek-ai/dsh
  pause
  exit /b 1
)
where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] "python" not found. Install Python 3.9+ and tick "Add Python to PATH".
  pause
  exit /b 1
)

echo [1/2] starting dsh ACP server on 127.0.0.1:7800 ...
start "dsh-acp-7800" cmd /k dsh --profile acp serve --host 127.0.0.1 --port 7800 --token !TOKEN!
ping -n 4 127.0.0.1 >nul

echo [2/2] starting bridge on 0.0.0.0:8218 - the pairing page will open ...
python "dsh_bridge_server.py" --port 8218 --token !TOKEN! --acp-token !TOKEN! --acp-base http://127.0.0.1:7800 --cwd "%CD%"

echo.
echo Bridge stopped. Double-click this file again to restart.
pause
endlocal
