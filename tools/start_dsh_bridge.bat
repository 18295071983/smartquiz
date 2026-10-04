@echo off
REM ==============================================================
REM  SmartQuiz - dsh remote bridge launcher (single window)
REM    Usage : start_dsh_bridge.bat [bridge_port]
REM    Default: bridge 8218
REM    The bridge spawns `dsh --profile acp` itself and speaks ACP
REM    over stdio, so there is NO second window, NO 7800 port and
REM    NO ACP token any more (dsh 0.2.0 dropped the HTTP serve).
REM    Pair page: http://127.0.0.1:8218/pair  (opens automatically)
REM  Token  : tools\.bridge_token (gitignored, never committed)
REM  Public : tools\.public_url   (gitignored; 花生壳/隧道地址, 会成为配对页第一个二维码)
REM ==============================================================
setlocal
REM 令牌从本地文件读（tools\.bridge_token 已在 .gitignore，绝不入库）
set TOKEN=
if exist "%~dp0.bridge_token" (set /p TOKEN=<"%~dp0.bridge_token")
if "%TOKEN%"=="" (
  echo [ERROR] missing token: put it into tools\.bridge_token
  pause
  exit /b 1
)
REM 公网/隧道地址（花生壳等）：放 tools\.public_url（已 gitignore），没有就不传
set PUBLICURL=
if exist "%~dp0.public_url" (set /p PUBLICURL=<"%~dp0.public_url")
set PUBLICOPT=
if not "%PUBLICURL%"=="" set PUBLICOPT=--public-url %PUBLICURL%

if "%~1"=="" (set BPORT=8218) else (set BPORT=%~1)
cd /d "%~dp0.."
set SESSION_CWD=%CD%

echo [1/1] bridge 0.0.0.0:%BPORT%  (dsh_bridge_server.py, ACP over stdio; pair page auto-opens)
start "dsh-bridge-%BPORT%" cmd /k python "%~dp0dsh_bridge_server.py" --port %BPORT% --token %TOKEN% %PUBLICOPT% --cwd "%SESSION_CWD%"

echo.
echo   Bridge token : %TOKEN%
echo   Pair page    : http://127.0.0.1:%BPORT%/pair
echo   Pair text    : dshpair://^<PC-LAN-IP^>:%BPORT%?token=%TOKEN%
echo   Session cwd  : %SESSION_CWD%
echo   Stop         : close the new window
echo.
endlocal
