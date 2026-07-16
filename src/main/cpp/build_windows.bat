@echo off
rem ========================================
rem Windows native build script for llama-jni
rem Uses Android SDK CMake + Ninja (no MSYS2 required)
rem ========================================

setlocal enabledelayedexpansion

rem 设置路径
set ANDROID_SDK=D:\Android\Sdk
set NDK_DIR=%ANDROID_SDK%\ndk\26.1.10909125
set CMAKE=%ANDROID_SDK%\cmake\3.22.1\bin\cmake.exe
set NINJA=%ANDROID_SDK%\cmake\3.22.1\bin\ninja.exe
set TOOLCHAIN=%NDK_DIR%\build\cmake\android.toolchain.cmake

set SCRIPT_DIR=%~dp0
set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%
set JNI_LIBS_DIR=%SCRIPT_DIR%\..\..\jniLibs

rem 检查工具
if not exist "%CMAKE%" (
    echo [ERROR] CMake not found: %CMAKE%
    exit /b 1
)
if not exist "%NINJA%" (
    echo [ERROR] Ninja not found: %NINJA%
    exit /b 1
)
if not exist "%TOOLCHAIN%" (
    echo [ERROR] NDK toolchain not found: %TOOLCHAIN%
    exit /b 1
)

echo [INFO] CMake: %CMAKE%
echo [INFO] Ninja: %NINJA%
echo [INFO] NDK: %NDK_DIR%
echo [INFO] Output: %JNI_LIBS_DIR%

rem 检查 OpenCL 头文件
set OPENCL_HEADERS_DIR=%SCRIPT_DIR%\opencl\headers
set OPENCL_ENABLED=0

if exist "%OPENCL_HEADERS_DIR%\CL\cl.h" (
    echo [INFO] OpenCL headers found, enabling OpenCL for ARM64
    set OPENCL_ENABLED=1
) else (
    echo [WARN] OpenCL headers not found, building CPU-only
)

rem 创建输出目录
if not exist "%JNI_LIBS_DIR%\arm64-v8a" mkdir "%JNI_LIBS_DIR%\arm64-v8a"
if not exist "%JNI_LIBS_DIR%\x86_64" mkdir "%JNI_LIBS_DIR%\x86_64"

rem ========================================
rem 构建 ARM64
rem ========================================
echo.
echo [INFO] ===================================
echo [INFO] Building ARM64...
echo [INFO] ===================================

set ARM64_BUILD_DIR=%SCRIPT_DIR%\build\arm64-v8a
if exist "%ARM64_BUILD_DIR%" rmdir /s /q "%ARM64_BUILD_DIR%"
mkdir "%ARM64_BUILD_DIR%"

set OPENCL_FLAG=OFF
if "%OPENCL_ENABLED%"=="1" set OPENCL_FLAG=ON

"%CMAKE%" -G Ninja ^
    -S "%SCRIPT_DIR%" ^
    -B "%ARM64_BUILD_DIR%" ^
    -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
    -DANDROID_ABI="arm64-v8a" ^
    -DANDROID_PLATFORM=android-31 ^
    -DANDROID_STL=c++_shared ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
    -DBUILD_SHARED_LIBS=ON ^
    -DGGML_OPENCL=%OPENCL_FLAG% ^
    -DGGML_VULKAN=OFF ^
    -DGGML_CUDA=OFF ^
    -DGGML_RPC=OFF ^
    -DGGML_OPENCL_EMBED_KERNELS=ON ^
    -DGGML_OPENCL_USE_ADRENO_KERNELS=ON ^
    -DOpenCL_INCLUDE_DIRS="%OPENCL_HEADERS_DIR%" ^
    -DOpenCL_FOUND=%OPENCL_ENABLED% ^
    -DOpenCL_VERSION_STRING="3.0" ^
    -DOpenCL_LIBRARIES=""

if errorlevel 1 (
    echo [ERROR] ARM64 CMake configuration failed!
    exit /b 1
)

"%NINJA%" -C "%ARM64_BUILD_DIR%" llama-jni

if errorlevel 1 (
    echo [ERROR] ARM64 build failed!
    exit /b 1
)

echo [INFO] ARM64 build completed

rem ========================================
rem 构建 x86_64 (CPU only)
rem ========================================
echo.
echo [INFO] ===================================
echo [INFO] Building x86_64...
echo [INFO] ===================================

set X64_BUILD_DIR=%SCRIPT_DIR%\build\x86_64
if exist "%X64_BUILD_DIR%" rmdir /s /q "%X64_BUILD_DIR%"
mkdir "%X64_BUILD_DIR%"

"%CMAKE%" -G Ninja ^
    -S "%SCRIPT_DIR%" ^
    -B "%X64_BUILD_DIR%" ^
    -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
    -DANDROID_ABI="x86_64" ^
    -DANDROID_PLATFORM=android-31 ^
    -DANDROID_STL=c++_shared ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DCMAKE_MAKE_PROGRAM="%NINJA%" ^
    -DBUILD_SHARED_LIBS=ON ^
    -DGGML_OPENCL=OFF ^
    -DGGML_VULKAN=OFF ^
    -DGGML_CUDA=OFF ^
    -DGGML_RPC=OFF

if errorlevel 1 (
    echo [ERROR] x86_64 CMake configuration failed!
    exit /b 1
)

"%NINJA%" -C "%X64_BUILD_DIR%" llama-jni

if errorlevel 1 (
    echo [ERROR] x86_64 build failed!
    exit /b 1
)

echo [INFO] x86_64 build completed

rem ========================================
rem 复制库文件
rem ========================================
echo.
echo [INFO] ===================================
echo [INFO] Copying libraries to jniLibs...
echo [INFO] ===================================

if exist "%ARM64_BUILD_DIR%\libllama-jni.so" (
    copy /Y "%ARM64_BUILD_DIR%\libllama-jni.so" "%JNI_LIBS_DIR%\arm64-v8a\"
    echo [INFO] Copied: arm64-v8a\libllama-jni.so
) else (
    echo [WARN] ARM64 library not found at expected location
    dir /s /b "%ARM64_BUILD_DIR%\libllama-jni.so" 2>nul
)

if exist "%X64_BUILD_DIR%\libllama-jni.so" (
    copy /Y "%X64_BUILD_DIR%\libllama-jni.so" "%JNI_LIBS_DIR%\x86_64\"
    echo [INFO] Copied: x86_64\libllama-jni.so
) else (
    echo [WARN] x86_64 library not found at expected location
    dir /s /b "%X64_BUILD_DIR%\libllama-jni.so" 2>nul
)

rem 复制依赖库 (ggml backends等)
for %%f in (libggml-opencl.so libggml-cpu.so libllama.so) do (
    if exist "%ARM64_BUILD_DIR%\build-llama\%%f" (
        copy /Y "%ARM64_BUILD_DIR%\build-llama\%%f" "%JNI_LIBS_DIR%\arm64-v8a\"
        echo [INFO] Copied: arm64-v8a\%%f
    )
)

for %%f in (libggml-cpu.so libllama.so) do (
    if exist "%X64_BUILD_DIR%\build-llama\%%f" (
        copy /Y "%X64_BUILD_DIR%\build-llama\%%f" "%JNI_LIBS_DIR%\x86_64\"
        echo [INFO] Copied: x86_64\%%f
    )
)

echo.
echo [INFO] ===================================
echo [INFO] Build completed successfully!
echo [INFO] ===================================
echo.
dir "%JNI_LIBS_DIR%\arm64-v8a"
echo.
dir "%JNI_LIBS_DIR%\x86_64"
