#!/bin/bash
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
JNI_LIBS_DIR="$APP_DIR/src/main/jniLibs"

ANDROID_SDK="/d/Android/Sdk"
NDK_DIR="$ANDROID_SDK/ndk/26.1.10909125"
CMAKE="/d/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
NINJA="/d/Android/Sdk/cmake/3.22.1/bin/ninja.exe"

log_info "Using CMake: $CMAKE"
log_info "Using Ninja: $NINJA"

if [ ! -f "$CMAKE" ]; then log_error "CMake not found"; exit 1; fi
if [ ! -f "$NINJA" ]; then log_error "Ninja not found"; exit 1; fi
if [ ! -d "$NDK_DIR" ]; then log_error "NDK not found"; exit 1; fi

TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"

log_info "=================================="
log_info "Building llama JNI native library"
log_info "NDK: $NDK_DIR"
log_info "=================================="

rm -rf "$SCRIPT_DIR/build"

mkdir -p "$JNI_LIBS_DIR/arm64-v8a"
mkdir -p "$JNI_LIBS_DIR/x86_64"

# ARM64
log_info "Building ARM64..."
ARM64_BUILD_DIR="$SCRIPT_DIR/build/arm64-v8a"
mkdir -p "$ARM64_BUILD_DIR"
cd "$ARM64_BUILD_DIR" || exit 1

"$CMAKE" "$SCRIPT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
    -GNinja || { log_error "ARM64 CMake failed"; exit 1; }

"$NINJA" || { log_error "ARM64 build failed"; exit 1; }

if [ -f "libllama-jni.so" ]; then
    cp "libllama-jni.so" "$JNI_LIBS_DIR/arm64-v8a/"
    log_info "ARM64 copied to jniLibs"
else
    log_error "ARM64 .so not found"
    exit 1
fi

# x86_64
log_info "Building x86_64..."
X64_BUILD_DIR="$SCRIPT_DIR/build/x86_64"
mkdir -p "$X64_BUILD_DIR"
cd "$X64_BUILD_DIR" || exit 1

"$CMAKE" "$SCRIPT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="x86_64" \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
    -GNinja || { log_error "x86_64 CMake failed"; exit 1; }

"$NINJA" || { log_error "x86_64 build failed"; exit 1; }

if [ -f "libllama-jni.so" ]; then
    cp "libllama-jni.so" "$JNI_LIBS_DIR/x86_64/"
    log_info "x86_64 copied to jniLibs"
else
    log_error "x86_64 .so not found"
    exit 1
fi

log_info "=================================="
log_info "Build complete!"
log_info "Output: $JNI_LIBS_DIR"
log_info "=================================="
ls -lh "$JNI_LIBS_DIR/arm64-v8a/libllama-jni.so"
ls -lh "$JNI_LIBS_DIR/x86_64/libllama-jni.so"
