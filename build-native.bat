@echo off
setlocal enabledelayedexpansion

set "MINGW_BIN=C:\msys64\mingw64\bin"
set "NINJA_EXE=D:\quzp\app\ninja.exe"
set "CPP_DIR=D:\quzp\app\src\main\cpp"
set "BUILD_DIR=%CPP_DIR%\build-native"
set "OUTPUT_DIR=%CPP_DIR%\output"

echo === Building Native Library (CPU Backend) ===

echo Cleaning old build directories...
rmdir /s /q "%BUILD_DIR%" 2>nul
rmdir /s /q "%OUTPUT_DIR%" 2>nul
mkdir "%BUILD_DIR%"
mkdir "%OUTPUT_DIR%"

echo Setting environment...
set "PATH=%MINGW_BIN%;C:\msys64\usr\bin;%PATH%"

echo Configuring CMake...
cd "%BUILD_DIR%"
"%MINGW_BIN%\cmake.exe" -G "Ninja" ^
    -DCMAKE_C_COMPILER="%MINGW_BIN%\gcc.exe" ^
    -DCMAKE_CXX_COMPILER="%MINGW_BIN%\g++.exe" ^
    -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
    -DGGML_VULKAN=OFF ^
    -DGGML_OPENCL=OFF ^
    -DGGML_CUDA=OFF ^
    "%CPP_DIR%"

if %errorlevel% neq 0 (
    echo CMake configuration failed!
    pause
    exit /b 1
)

echo Building...
"%NINJA_EXE%" -j8

if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo Copying output files...
copy "%BUILD_DIR%\*.dll" "%OUTPUT_DIR%" 2>nul
copy "%BUILD_DIR%\build-llama\*.dll" "%OUTPUT_DIR%" 2>nul
copy "%BUILD_DIR%\build-llama\ggml\src\*.dll" "%OUTPUT_DIR%" 2>nul

echo Build completed successfully!
echo Output directory: %OUTPUT_DIR%
dir "%OUTPUT_DIR%"

pause
