#!/bin/bash

MINGW_BIN="/mingw64/bin"
NINJA_EXE="/d/quzp/app/ninja.exe"
CPP_DIR="/d/quzp/app/src/main/cpp"
BUILD_DIR="${CPP_DIR}/build-native"
OUTPUT_DIR="${CPP_DIR}/output"

echo "=== Building Native Library (CPU Backend) ==="

echo "Cleaning old build directories..."
rm -rf "$BUILD_DIR"
rm -rf "$OUTPUT_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

echo "Setting environment..."
export PATH="${MINGW_BIN}:/usr/bin:$PATH"

echo "Configuring CMake..."
cd "$BUILD_DIR"
"${MINGW_BIN}/cmake.exe" -G "Ninja" \
    -DCMAKE_C_COMPILER=gcc \
    -DCMAKE_CXX_COMPILER=g++ \
    -DCMAKE_MAKE_PROGRAM="$NINJA_EXE" \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_CUDA=OFF \
    "$CPP_DIR"

if [ $? -ne 0 ]; then
    echo "CMake configuration failed!"
    exit 1
fi

echo "Building..."
"$NINJA_EXE" -j8

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "Copying output files..."
cp "$BUILD_DIR"/*.dll "$OUTPUT_DIR" 2>/dev/null || true
cp "$BUILD_DIR"/build-llama/*.dll "$OUTPUT_DIR" 2>/dev/null || true
cp "$BUILD_DIR"/build-llama/ggml/src/*.dll "$OUTPUT_DIR" 2>/dev/null || true

echo "Build completed successfully!"
echo "Output directory: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR"
