@echo off
chcp 65001 >nul
title Android APK 构建脚本 - 修复版
setlocal enabledelayedexpansion

echo ============================================================
echo   Android APK Build Script (Fixed)
echo ============================================================
echo.

:: 设置环境变量 - 与 gradle.properties 保持一致
echo [CONFIG] Setting JAVA_HOME=D:\jdk-21
set "JAVA_HOME=D:\jdk-21"

echo [CONFIG] Setting GRADLE_USER_HOME=D:\Gradle\Home
set "GRADLE_USER_HOME=D:\Gradle\Home"

:: 确保全局Gradle缓存目录存在
if not exist "D:\Gradle\Home" (
    echo [INFO] Creating Gradle user home directory...
    mkdir "D:\Gradle\Home"
)

:: 检查Java
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME is invalid: %JAVA_HOME%
    echo [ERROR] Cannot find java.exe at %JAVA_HOME%\bin\java.exe
    pause
    exit /b 1
)
echo [OK] Java environment OK
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | head -1
echo.

:: 检查Gradle wrapper
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERROR] gradle-wrapper.jar NOT FOUND
    echo [INFO] Please ensure the wrapper files are intact
    pause
    exit /b 1
)
echo [OK] Gradle wrapper OK
echo.

:: 先停止旧的Gradle守护进程，避免文件锁定问题
echo [Step 0] Stopping existing Gradle daemons...
call "%~dp0gradlew.bat" --stop 2>nul
echo [OK] Daemon stop command sent
echo.

:: 清理项目（跳过可能导致问题的clean步骤，直接构建）
echo [Step 1/2] Building Debug APK (skipping clean to avoid lock issues)...
echo.
call "%~dp0gradlew.bat" assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g"
if errorlevel 1 (
    echo.
    echo [WARNING] Debug build with --no-daemon failed, trying with daemon...
    call "%~dp0gradlew.bat" assembleDebug
    if errorlevel 1 (
        echo [ERROR] Build FAILED
        echo.
        echo [TIPS] Common fixes:
        echo   1. Close Android Studio / IDE and try again
        echo   2. Restart computer to release file locks
        echo   3. Check if antivirus is blocking Gradle cache directories
        echo   4. Ensure D:\Gradle\Home has write permissions
        pause
        exit /b 1
    )
)
echo.
echo [SUCCESS] Debug APK Build Complete!
echo.

:: 查找生成的APK
echo [Step 2/2] Locating APK file...
echo.
set APK_PATH=
for /r "%cd%\build\outputs\apk\debug" %%f in (*.apk) do (
    set "APK_PATH=%%f"
    goto :found_debug
)

:found_debug
if defined APK_PATH (
    echo ============================================================
    echo   BUILD SUCCESSFUL!
    echo ============================================================
    echo.
    for %%A in ("%APK_PATH%") do (
        echo APK Size: %%~zA bytes
    )
    echo APK Location: %APK_PATH%
    echo.
    echo You can install with: adb install -r "%APK_PATH%"
) else (
    echo [WARNING] Debug APK not found, trying release build...
    echo.
    call "%~dp0gradlew.bat" assembleRelease
    if not errorlevel 1 (
        for /r "%cd%\build\outputs\apk\release" %%f in (*.apk) do (
            set "APK_PATH=%%f"
            goto :found_release
        )
        :found_release
        if defined APK_PATH (
            echo ============================================================
            echo   RELEASE BUILD SUCCESSFUL!
            echo ============================================================
            echo APK Location: %APK_PATH%
            echo.
        ) else (
            echo [ERROR] APK not found in build outputs
            pause
            exit /b 1
        )
    ) else (
        echo [ERROR] Release build also failed
        pause
        exit /b 1
    )
)

echo.
pause
