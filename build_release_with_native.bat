@echo off
REM ============================================
REM Windows构建脚本 - 编译本地库和Release APK
REM 使用MSYS2环境编译本地库
REM ============================================

echo ============================================
echo 开始完整构建流程
echo ============================================
echo.

REM 检查MSYS2是否存在
set MSYS2_BASH=C:\msys64\usr\bin\bash.exe
if not exist "%MSYS2_BASH%" (
    echo [ERROR] MSYS2未找到: %MSYS2_BASH%
    echo 请先安装MSYS2
    pause
    exit /b 1
)

echo [1/3] 使用MSYS2编译本地库...
echo.

REM 获取脚本目录
set SCRIPT_DIR=%~dp0src\main\cpp

REM 在MSYS2中运行构建脚本
"%MSYS2_BASH%" -l -c "cd '%SCRIPT_DIR:\=/%' && chmod +x build_llama_jni_msys2.sh && ./build_llama_jni_msys2.sh"

if %errorlevel% neq 0 (
    echo [ERROR] 本地库编译失败
    pause
    exit /b 1
)

echo.
echo [2/3] 本地库编译完成！
echo.
echo [3/3] 开始编译Release APK...
echo.

REM 清理Gradle缓存
call gradlew.bat clean

REM 编译Release APK
call gradlew.bat assembleRelease

if %errorlevel% neq 0 (
    echo [ERROR] APK编译失败
    pause
    exit /b 1
)

echo.
echo ============================================
echo 构建完成！
echo APK位置: build\outputs\apk\release\
echo ============================================
echo.
pause
