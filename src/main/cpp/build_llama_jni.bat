@echo off

rem ========================================
rem Building llama-jni shared library
rem ========================================

rem 设置环境变量
set ANDROID_NDK_ROOT=D:\Android\ndk\25.2.0
set ANDROID_SDK_ROOT=D:\Android\Sdk
set CMAKE_PATH=%ANDROID_SDK_ROOT%\cmake\3.22.1\bin
set NINJA_PATH=D:\quzp\app

rem 确保路径存在
if not exist "%CMAKE_PATH%\cmake.exe" (
    echo CMake not found at %CMAKE_PATH%\cmake.exe
    echo Please set ANDROID_SDK_ROOT correctly
    pause
    exit /b 1
)

if not exist "%NINJA_PATH%\ninja.exe" (
    echo Ninja not found at %NINJA_PATH%\ninja.exe
    echo Please download ninja.exe and place it in the app directory
    pause
    exit /b 1
)

rem 设置构建目录
set BUILD_DIR=build-android
set OUTPUT_DIR=..\..\..\jniLibs\arm64-v8a

rem 清理之前的构建
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
if exist "%OUTPUT_DIR%" rmdir /s /q "%OUTPUT_DIR%"
mkdir "%BUILD_DIR%"
mkdir "%OUTPUT_DIR%"

echo.
echo Configuring CMake...
echo.

rem 配置 CMake
"%CMAKE_PATH%\cmake.exe" -G "Ninja" ^
    -DANDROID_ABI=arm64-v8a ^
    -DANDROID_PLATFORM=android-31 ^
    -DANDROID_NDK=%ANDROID_NDK_ROOT% ^
    -DCMAKE_TOOLCHAIN_FILE=%ANDROID_NDK_ROOT%\build\cmake\android.toolchain.cmake ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DGGML_OPENCL=ON ^
    -DGGML_HEXAGON=ON ^
    -DGGML_OPENMP=OFF ^
    -DGGML_LLAMAFILE=OFF ^
    -DLLAMA_OPENSSL=OFF ^
    -B "%BUILD_DIR%" ^
    -S .

if errorlevel 1 (
    echo.
    echo CMake configuration failed!
    pause
    exit /b 1
)

echo.
echo Building with Ninja...
echo.

rem 构建项目
"%NINJA_PATH%\ninja.exe" -C "%BUILD_DIR%" llama-jni

if errorlevel 1 (
    echo.
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Copying shared libraries...
echo.

rem 复制构建好的共享库
copy "%BUILD_DIR%\libllama-jni.so" "%OUTPUT_DIR%"

rem 复制依赖的共享库
if exist "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v73.so" (
    copy "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v73.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v75.so" (
    copy "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v75.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v79.so" (
    copy "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v79.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v81.so" (
    copy "%BUILD_DIR%\build-llama\ggml\src\ggml-hexagon\libggml-htp-v81.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\libggml-hexagon.so" (
    copy "%BUILD_DIR%\build-llama\libggml-hexagon.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\libggml-opencl.so" (
    copy "%BUILD_DIR%\build-llama\libggml-opencl.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\libggml-cpu.so" (
    copy "%BUILD_DIR%\build-llama\libggml-cpu.so" "%OUTPUT_DIR%"
)
if exist "%BUILD_DIR%\build-llama\libllama.so" (
    copy "%BUILD_DIR%\build-llama\libllama.so" "%OUTPUT_DIR%"
)

echo.
echo ========================================
echo Build completed successfully!
echo ========================================
echo.
echo Shared libraries copied to:
echo   %OUTPUT_DIR%
echo.
echo To rebuild, run this script again.
echo.

rem 显示构建结果
dir "%OUTPUT_DIR%"

echo.
pause
