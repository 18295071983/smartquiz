@echo off
chcp 65001 >nul
title Android APK 构建脚本

echo ============================================================
echo   Android APK 构建脚本
 echo ============================================================
echo.

:: 清理项目
echo 清理项目...
echo.
call gradlew clean
if errorlevel 1 (
    echo 清理项目失败
    pause
    exit /b 1
)
echo 清理完成

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
for /r "%cd%\build\outputs\apk\release" %%f in (*.apk) do (
    echo APK 构建成功！
    echo APK 路径: %%f
    echo 可以使用 adb 安装: adb install "%%f"
    goto :end
)

echo 未找到生成的 APK 文件

:end
echo.
echo 构建完成！
pause