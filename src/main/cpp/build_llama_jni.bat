@echo off

set ANDROID_NDK_ROOT=D:\Android\Sdk\ndk\25.2.9519653
set ANDROID_SDK_ROOT=D:\Android\Sdk
set CMAKE_PATH=%ANDROID_SDK_ROOT%\cmake\3.22.1\bin
set NINJA_PATH=%ANDROID_SDK_ROOT%\cmake\3.22.1\bin

if not exist "%CMAKE_PATH%\cmake.exe" (
    echo CMake not found at %CMAKE_PATH%\cmake.exe
    pause
    exit /b 1
)

if not exist "%NINJA_PATH%\ninja.exe" (
    echo Ninja not found at %NINJA_PATH%\ninja.exe
    pause
    exit /b 1
)

call :build_arch arm64-v8a
if errorlevel 1 exit /b 1

call :build_arch x86_64
if errorlevel 1 exit /b 1

echo.
echo ========================================
echo All architectures built successfully!
echo ========================================
echo.
pause
exit /b 0

:build_arch
set ARCH=%1
set BUILD_DIR=build-android-%ARCH%
set OUTPUT_DIR=..\jniLibs\%ARCH%

echo.
echo ========================================
echo Building for %ARCH%
echo ========================================
echo.

if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
if exist "%OUTPUT_DIR%" rmdir /s /q "%OUTPUT_DIR%"
mkdir "%BUILD_DIR%"
mkdir "%OUTPUT_DIR%"

echo.
echo Configuring CMake for %ARCH%...
echo.

"%CMAKE_PATH%\cmake.exe" -G "Ninja" ^
    -DANDROID_ABI=%ARCH% ^
    -DANDROID_PLATFORM=android-31 ^
    -DANDROID_NDK=%ANDROID_NDK_ROOT% ^
    -DCMAKE_TOOLCHAIN_FILE=%ANDROID_NDK_ROOT%\build\cmake\android.toolchain.cmake ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DANDROID_STL=c++_static ^
    -DGGML_OPENCL=ON ^
    -DGGML_HEXAGON=OFF ^
    -DGGML_OPENMP=OFF ^
    -DGGML_LLAMAFILE=OFF ^
    -DLLAMA_OPENSSL=OFF ^
    -DBUILD_SHARED_LIBS=OFF ^
    -B "%BUILD_DIR%" ^
    -S .

if errorlevel 1 (
    echo CMake configuration failed for %ARCH%!
    pause
    exit /b 1
)

echo.
echo Building %ARCH% with Ninja...
echo.

"%NINJA_PATH%\ninja.exe" -C "%BUILD_DIR%"

if errorlevel 1 (
    echo Build failed for %ARCH%!
    pause
    exit /b 1
)

echo.
echo Copying shared libraries for %ARCH%...
echo.

copy "%BUILD_DIR%\libllama-jni.so" "%OUTPUT_DIR%"

if exist "%BUILD_DIR%\build-llama\bin\libggml-opencl.so" (
    copy "%BUILD_DIR%\build-llama\bin\libggml-opencl.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\ggml\src\ggml-opencl\libggml-opencl.so" (
    copy "%BUILD_DIR%\build-llama\ggml\src\ggml-opencl\libggml-opencl.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\libggml-opencl.so" (
    copy "%BUILD_DIR%\build-llama\libggml-opencl.so" "%OUTPUT_DIR%"
) else (
    echo WARNING: libggml-opencl.so not found for %ARCH%
)

if exist "%BUILD_DIR%\build-llama\bin\libggml-cpu.so" (
    copy "%BUILD_DIR%\build-llama\bin\libggml-cpu.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\libggml-cpu.so" (
    copy "%BUILD_DIR%\build-llama\libggml-cpu.so" "%OUTPUT_DIR%"
)

if exist "%BUILD_DIR%\build-llama\bin\libllama.so" (
    copy "%BUILD_DIR%\build-llama\bin\libllama.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\libllama.so" (
    copy "%BUILD_DIR%\build-llama\libllama.so" "%OUTPUT_DIR%"
)

if exist "%BUILD_DIR%\build-llama\bin\libggml-base.so" (
    copy "%BUILD_DIR%\build-llama\bin\libggml-base.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\libggml-base.so" (
    copy "%BUILD_DIR%\build-llama\libggml-base.so" "%OUTPUT_DIR%"
)

if exist "%BUILD_DIR%\build-llama\bin\libggml.so" (
    copy "%BUILD_DIR%\build-llama\bin\libggml.so" "%OUTPUT_DIR%"
) else if exist "%BUILD_DIR%\build-llama\libggml.so" (
    copy "%BUILD_DIR%\build-llama\libggml.so" "%OUTPUT_DIR%"
)

echo.
echo Build completed for %ARCH%!
echo.
echo Libraries:
dir "%OUTPUT_DIR%"

exit /b 0