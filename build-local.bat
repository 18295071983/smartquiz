@echo off
setlocal enabledelayedexpansion

set "MINGW_BIN=C:\msys64\mingw64\bin"
set "NINJA_EXE=D:\quzp\app\ninja.exe"
set "CPP_DIR=D:\quzp\app\src\main\cpp"
set "BUILD_DIR=%CPP_DIR%\build-local"

echo === Local Windows Build Script ===
echo Cleaning old build directory...
rmdir /s /q "%BUILD_DIR%" 2>nul
mkdir "%BUILD_DIR%"

echo Setting environment variables...
set "PATH=%MINGW_BIN%;C:\msys64\usr\bin;%PATH%"

echo Configuring CMake...
cd "%BUILD_DIR%"
"%MINGW_BIN%\cmake.exe" -G "Ninja" ^
    -DCMAKE_C_COMPILER="%MINGW_BIN%\gcc.exe" ^
    -DCMAKE_CXX_COMPILER="%MINGW_BIN%\g++.exe" ^
    -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
    -DVulkan_GLSLC_EXECUTABLE="%MINGW_BIN%\glslc.exe" ^
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

echo Build completed successfully!
echo Looking for generated DLL...
dir "%BUILD_DIR%\*.dll" 2>nul
dir "%BUILD_DIR%\build-llama\*.dll" 2>nul

pause
