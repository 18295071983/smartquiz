#!/bin/bash

# 配置 NDK 路径（根据实际路径修改）
export ANDROID_NDK="C:\Users\xiaocong\AppData\Local\Android\Sdk\ndk\25.2.9519653"
export TOOLCHAIN=$ANDROID_NDK/build/cmake/android.toolchain.cmake

# 克隆 llama.cpp（如果还没有）
if [ ! -d "llama.cpp" ]; then
    git clone https://github.com/ggerganov/llama.cpp.git
fi

cd llama.cpp

# 为 Android 编译
mkdir -p build-android && cd build-android

cmake .. \
    -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_ARM_NEON=ON \
    -DANDROID_STL=c++_shared \
    -DLLAMA_CURL=OFF \
    -DBUILD_SHARED_LIBS=ON \
    -GNinja

# 编译
ninja

# 生成的库文件在 build-android/libllama.so
echo "编译完成，库文件路径：$(pwd)/libllama.so"
