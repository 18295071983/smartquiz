@echo off
setlocal

echo ========================================
echo Building llama-jni for Snapdragon devices
echo ========================================

REM 设置Android NDK路径
if not defined ANDROID_NDK_ROOT (
    set ANDROID_NDK_ROOT=D:\Android\ndk\25.2.0
    echo Using default NDK: %ANDROID_NDK_ROOT%
)

REM 设置SDK根目录
set ANDROID_SDK_ROOT=D:\Android\Sdk

REM Qualcomm Snapdragon SDK 配置
REM 这些路径需要根据您的实际安装位置进行调整
set OPENCL_SDK_ROOT=D:\Qualcomm\OpenCL_SDK\2.3.2
set HEXAGON_SDK_ROOT=D:\Qualcomm\Hexagon_SDK\6.4.0.2
set HEXAGON_TOOLS_ROOT=%HEXAGON_SDK_ROOT%\tools\HEXAGON_Tools\19.0.04

REM 检查SDK是否存在
if not exist "%OPENCL_SDK_ROOT%" (
    echo Warning: OpenCL SDK not found at %OPENCL_SDK_ROOT%
    echo OpenCL support may not be available
)

if not exist "%HEXAGON_SDK_ROOT%" (
    echo Warning: Hexagon SDK not found at %HEXAGON_SDK_ROOT%
    echo Hexagon NPU support may not be available
)

REM 设置环境变量
set PATH=%ANDROID_SDK_ROOT%\cmake\3.22.1\bin;%PATH%
set PATH=%ANDROID_NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\bin;%PATH%

REM 清理之前的构建
if exist "build" rmdir /s /q build
if exist ".cxx" rmdir /s /q .cxx

echo.
echo Starting CMake configuration...
echo.

REM 使用Snapdragon特定的CMake预设进行配置
cmake -G "Ninja" ^
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
    -DHEXAGON_SDK_ROOT=%HEXAGON_SDK_ROOT% ^
    -DOPENCL_SDK_ROOT=%OPENCL_SDK_ROOT% ^
    -B build-snapdragon ^
    -S .

if %ERRORLEVEL% neq 0 (
    echo.
    echo CMake configuration failed!
    pause
    exit /b 1
)

echo.
echo Building with Ninja...
echo.

cmake --build build-snapdragon --config Release

if %ERRORLEVEL% neq 0 (
    echo.
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo ========================================
echo Build completed successfully!
echo ========================================
echo.
echo Output libraries are in:
echo   build-snapdragon\lib\
echo.
echo To rebuild, run this script again.
echo.

pause
