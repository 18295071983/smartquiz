@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ============================================================
:: AI资源快速下载工具 - Windows版
:: AI Resources Quick Downloader for Windows
:: ============================================================

title 🤖 AI模型和测试资源下载工具

echo.
echo ╔═══════════════════════════════════════════════════════════╗
echo ║     🤖 AI 模型和测试资源快速下载工具                       ║
echo ║     AI Model & Test Resources Quick Downloader            ║
echo ╚═══════════════════════════════════════════════════════════╝
echo.

:: 检查Python是否安装
where python >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 错误: 未检测到Python
    echo.
    echo 请先安装Python 3.6+:
    echo   1. 访问 https://www.python.org/downloads/
    echo   2. 下载并安装Python（勾选"Add Python to PATH"）
    echo   3. 重启命令提示符后重新运行此脚本
    echo.
    pause
    exit /b 1
)

:: 检查必要的Python库
python -c "import requests; import tqdm; from PIL import Image" >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ⚠️ 检测到缺少必要的Python库，正在自动安装...
    echo.
    pip install requests tqdm pillow numpy
    if %ERRORLEVEL% NEQ 0 (
        echo ❌ 安装依赖库失败！请手动运行:
        echo    pip install requests tqdm pillow numpy
        echo.
        pause
        exit /b 1
    )
    echo ✅ 依赖库安装完成！
    echo.
)

:: 显示菜单
:menu
echo 请选择操作：
echo.
echo   [1] 下载所有资源（推荐）
echo   [2] 只下载TFLite嵌入模型
echo   [3] 只生成测试图片
echo   [4] 检查已下载的资源
echo   [5] 列出可用的模型
echo   [0] 退出
echo.
set /p choice=请输入选项 (0-5): 

if "%choice%"=="1" goto download_all
if "%choice%"=="2" goto download_model
if "%choice%"=="3" goto download_images
if "%choice%"=="4" goto check_status
if "%choice%"=="5" goto list_models
if "%choice%"=="0" goto end

echo ❌ 无效选项，请重新选择
goto menu

:download_all
echo.
echo ========================================
echo 📦 开始下载所有资源...
echo ========================================
cd /d "%~dp0"
python download_resources.py --all
goto finish

:download_model
echo.
echo ========================================
echo 📦 选择要下载的模型：
echo ========================================
echo   [1] all-MiniLM-L6-v2 (推荐，22MB)
echo   [2] paraphrase-multilingual-MiniLM-L12-v2 (90MB)
echo   [0] 返回主菜单
echo.
set /p model_choice=请输入选项: 

if "%model_choice%"=="1" (
    cd /d "%~dp0"
    python download_resources.py --model all-MiniLM-L6-v2
) else if "%model_choice%"=="2" (
    cd /d "%~dp0"
    python download_resources.py --model paraphrase-multilingual-MiniLM-L12-v2
) else if "%model_choice%"=="0" (
    goto menu
) else (
    echo ❌ 无效选项
    goto download_model
)
goto finish

:download_images
echo.
echo ========================================
echo 🖼️ 正在生成测试图片...
echo ========================================
cd /d "%~dp0"
python download_resources.py --images
goto finish

:check_status
echo.
echo ========================================
echo 📊 检查资源状态...
echo ========================================
cd /d "%~dp0"
python download_resources.py --check
goto finish

:list_models
echo.
echo ========================================
echo 📋 可用的模型列表
echo ========================================
cd /d "%~dp0"
python download_resources.py --list
goto menu

:finish
echo.
echo ========================================
echo 操作完成！
echo ========================================
echo.
pause
goto menu

:end
echo.
echo 👋 感谢使用！再见！
echo.
timeout /t 2 >nul
exit /b 0
