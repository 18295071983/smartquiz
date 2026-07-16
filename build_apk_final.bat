@echo off
chcp 65001 >nul
title Android APK 构建脚本

echo ============================================================
echo   Android APK 构建脚本
 echo ============================================================
echo.

:: 构建发布版本
echo 构建发布版本 APK...
echo.
call gradlew assembleRelease
if errorlevel 1 (
    echo 构建失败
    pause
    exit /b 1
)
echo 构建完成

:: 查找生成的 APK 文件
echo 查找 APK 文件...
echo.
set APK_PATH=
for /r "%cd%\build\outputs\apk\release" %%f in (*.apk) do (
    set APK_PATH=%%f
    goto :found
)

:found
if defined APK_PATH (
    echo APK 构建成功！
    echo APK 路径: %APK_PATH%
    echo 可以使用 adb 安装: adb install "%APK_PATH%"
) else (
    echo 未找到生成的 APK 文件
)

echo.
echo ============================================================
echo   构建完成！
echo ============================================================
echo.
echo 使用说明:
echo   1. 生成的 APK 位于: %APK_PATH%
echo   2. 可以使用 adb 安装: adb install "%APK_PATH%"
echo   3. 或直接将 APK 文件复制到设备安装
echo.
pause