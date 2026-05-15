#!/bin/bash
set -e

BUILD_DIR=/d/quzp/app/src/main/cpp/build/x86_64
SRC_DIR=/d/quzp/app/src/main/cpp
NDK_TOOLCHAIN=/d/Android/Sdk/ndk/26.1.10909125/build/cmake/android.toolchain.cmake
CMAKE=/mingw64/bin/cmake
NINJA=/mingw64/bin/ninja

echo "=== Step 1: CMake Configure ==="
mkdir -p $BUILD_DIR
cd $BUILD_DIR

$CMAKE $SRC_DIR \
  -DCMAKE_TOOLCHAIN_FILE=$NDK_TOOLCHAIN \
  -DANDROID_ABI=x86_64 \
  -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM=$NINJA \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_VULKAN=ON \
  -DGGML_CUDA=OFF \
  -DGGML_RPC=OFF \
  -GNinja

echo "=== Step 2: Build vulkan-shaders-gen manually ==="
SHADER_BUILD_DIR=$BUILD_DIR/build-llama/ggml/src/ggml-vulkan/vulkan-shaders-gen-prefix/src/vulkan-shaders-gen-build
SHADER_SRC_DIR=$SRC_DIR/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders
HOST_TOOLCHAIN=$BUILD_DIR/host-toolchain.cmake

if [ ! -f "$SHADER_BUILD_DIR/build.ninja" ]; then
  echo "Configuring vulkan-shaders-gen..."
  mkdir -p $SHADER_BUILD_DIR
  cd $SHADER_BUILD_DIR

  $CMAKE $SHADER_SRC_DIR \
    -DCMAKE_INSTALL_PREFIX=$BUILD_DIR/Release \
    -DCMAKE_INSTALL_BINDIR=. \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=$HOST_TOOLCHAIN \
    -DCMAKE_MAKE_PROGRAM=$NINJA \
    -GNinja

  echo "Building vulkan-shaders-gen..."
  $NINJA
  $CMAKE --install . --prefix $BUILD_DIR/Release
fi

echo "=== Step 3: Build llama-jni ==="
cd $BUILD_DIR
$NINJA llama-jni

echo "=== Step 4: Copy to jniLibs ==="
cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/x86_64/

echo "=== DONE ==="
ls -la /d/quzp/app/src/main/jniLibs/x86_64/libllama-jni.so
