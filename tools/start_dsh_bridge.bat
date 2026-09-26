@echo off
REM ==============================================================
REM  SmartQuiz - dsh remote bridge launcher (starts BOTH servers)
REM    Usage : start_dsh_bridge.bat [bridge_port] [acp_port]
REM    Default: bridge 8218, acp 7800
REM    The bridge opens the pairing page automatically:
REM            http://127.0.0.1:8218/pair
REM  Token is fixed below (must match what the phone pairs with).
REM ==============================================================
setlocal
set TOKEN=***REMOVED***
if "%~1"=="" (set BPORT=8218) else (set BPORT=%~1)
if "%~2"=="" (set APORT=7800) else (set APORT=%~2)
cd /d "%~dp0.."
set SESSION_CWD=%CD%

echo [1/2] ACP serve  127.0.0.1:%APORT%  (dsh --profile acp serve)
start "dsh-acp-%APORT%" cmd /k dsh --profile acp serve --host 127.0.0.1 --port %APORT% --token %TOKEN%
timeout /t 3 /nobreak >nul

echo [2/2] bridge     0.0.0.0:%BPORT%  (dsh_bridge_server.py, pair page auto-opens)
start "dsh-bridge-%BPORT%" cmd /k python "%~dp0dsh_bridge_server.py" --port %BPORT% --token %TOKEN% --acp-token %TOKEN% --acp-base http://127.0.0.1:%APORT% --cwd "%SESSION_CWD%"

echo.
echo   Bridge token : %TOKEN%
echo   Pair page    : http://127.0.0.1:%BPORT%/pair
echo   Pair text    : dshpair://^<PC-LAN-IP^>:%BPORT%?token=%TOKEN%
echo   Session cwd  : %SESSION_CWD%
echo   Stop         : close the two new windows
echo.
endlocal
