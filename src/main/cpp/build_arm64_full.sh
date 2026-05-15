#!/bin/bash
set -e

SRC_DIR=/d/quzp/app/src/main/cpp
BUILD_DIR=$SRC_DIR/build/arm64-v8a
CMAKE=/mingw64/bin/cmake
NINJA=/mingw64/bin/ninja
NDK_TOOLCHAIN=/d/Android/Sdk/ndk/26.1.10909125/build/cmake/android.toolchain.cmake

mkdir -p $BUILD_DIR
cd $BUILD_DIR

echo "=== CMake configure (ARM64) ==="
$CMAKE $SRC_DIR \
  -DCMAKE_TOOLCHAIN_FILE=$NDK_TOOLCHAIN \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM=$NINJA \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_VULKAN=ON \
  -DGGML_CUDA=OFF \
  -DGGML_RPC=OFF \
  -GNinja 2>&1 | tee /tmp/arm64_cmake.log

echo "=== Ninja build (ARM64, -j4) ==="
$NINJA -j4 2>&1 | tee /tmp/arm64_build.log
EXIT_CODE=${PIPESTATUS[0]}

echo "=== NINJA EXIT CODE: $EXIT_CODE ===" | tee -a /tmp/arm64_build.log

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== Build SUCCESS ==="
    cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/arm64-v8a/
    ls -la /d/quzp/app/src/main/jniLibs/arm64-v8a/libllama-jni.so
else
    echo "=== Build FAILED ==="
    tail -50 /tmp/arm64_build.log
fi
