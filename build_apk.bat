@echo off
chcp 65001 >nul
title Android APK 构建脚本

echo ============================================================
echo   Android APK 构建脚本
 echo ============================================================
echo.

:: 检查 Java 环境
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java 未安装，请先安装 JDK 17+
    pause
    exit /b 1
)

:: 检查 Gradle 包装器
if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat 不存在，请确保在项目根目录运行
    pause
    exit /b 1
)

:: 清理项目
echo [步骤 1/3] 清理项目...
echo.
call gradlew clean
if errorlevel 1 (
    echo [ERROR] 清理项目失败
    pause
    exit /b 1
)
echo [SUCCESS] 清理完成

:: 构建发布版本
echo [步骤 2/3] 构建发布版本 APK...
echo.
call gradlew assembleRelease
if errorlevel 1 (
    echo [ERROR] 构建失败
    pause
    exit /b 1
)
echo [SUCCESS] 构建完成

:: 查找生成的 APK 文件
echo [步骤 3/3] 查找 APK 文件...
echo.
set APK_PATH=
for /r "%cd%\build\outputs\apk\release" %%f in (*.apk) do (
    set APK_PATH=%%f
    goto :found
)

:found
if defined APK_PATH (
    echo [SUCCESS] APK 构建成功！
    echo [INFO] APK 路径: %APK_PATH%
    echo [INFO] APK 大小: %~zAPK_PATH% 字节
) else (
    echo [ERROR] 未找到生成的 APK 文件
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   构建完成！
echo ============================================================
echo.
echo 使用说明:
echo   1. 生成的 APK 位于: %APK_PATH%
echo   2. 可以使用 adb 安装: adb install %APK_PATH%
echo   3. 或直接将 APK 文件复制到设备安装
echo.
pause