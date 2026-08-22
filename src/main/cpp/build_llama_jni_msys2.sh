#!/bin/bash
# ============================================
# MSYS2环境构建脚本 - 编译llama JNI本地库
# 用于Android平台的ARM64和x86_64架构
# ============================================

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 输出函数
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 配置
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
JNI_LIBS_DIR="$APP_DIR/src/main/jniLibs"

# Windows路径转换（msys2环境）
ANDROID_SDK="/d/Android/Sdk"
NDK_DIR="$ANDROID_SDK/ndk/26.1.10909125"
# 使用msys2 mingw64的cmake和ninja
CMAKE="/mingw64/bin/cmake"
NINJA="/mingw64/bin/ninja"
# NDK中的glslc编译器（用于Vulkan着色器编译）
GLSLC="$NDK_DIR/shader-tools/windows-x86_64/glslc.exe"
# 将glslc所在目录添加到PATH（CMake查找glslc需要）
export PATH="$NDK_DIR/shader-tools/windows-x86_64:$PATH"

# OpenCL: 在Android上使用NDK提供的stub库进行编译时链接
# 运行时会自动加载设备厂商的驱动（/vendor/lib64/libOpenCL.so）
OPENCL_ENABLED=0

# 检查工具是否存在
if [ ! -f "$CMAKE" ]; then
    log_error "CMake not found at: $CMAKE"
    log_error "请安装: pacman -S mingw-w64-x86_64-cmake"
    exit 1
fi

if [ ! -f "$NINJA" ]; then
    log_error "Ninja not found at: $NINJA"
    log_error "请安装: pacman -S mingw-w64-x86_64-ninja"
    exit 1
fi

# 启用Vulkan（NDK自带Vulkan headers + libvulkan.so 存根 + glslc；
# 运行时由 Android 系统 Vulkan 加载器提供设备驱动，PC 无需安装 Vulkan SDK）
VULKAN_ENABLED=1
log_info "Vulkan已启用（NDK头+存根库+glslc，运行时设备驱动动态加载）"

log_info "OpenCL已禁用"

log_info "使用CMake: $CMAKE"
log_info "使用Ninja: $NINJA"
log_info "Vulkan支持: $VULKAN_ENABLED"
log_info "OpenCL支持: $OPENCL_ENABLED"

# 检查NDK
if [ ! -d "$NDK_DIR" ]; then
    log_error "NDK not found at: $NDK_DIR"
    exit 1
fi

TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"

if [ ! -f "$TOOLCHAIN" ]; then
    log_error "NDK toolchain not found at: $TOOLCHAIN"
    exit 1
fi

log_info "=================================="
log_info "开始编译llama JNI本地库"
log_info "NDK路径: $NDK_DIR"
log_info "项目路径: $APP_DIR"
log_info "=================================="

# 清理旧的构建目录
log_info "清理构建目录..."
rm -rf "$SCRIPT_DIR/build"

# 创建JNI库目录
mkdir -p "$JNI_LIBS_DIR/arm64-v8a"
mkdir -p "$JNI_LIBS_DIR/x86_64"

# ============================================
# 编译ARM64架构
# ============================================
log_info "开始编译ARM64架构..."

ARM64_BUILD_DIR="$SCRIPT_DIR/build/arm64-v8a"
mkdir -p "$ARM64_BUILD_DIR"

cd "$ARM64_BUILD_DIR" || exit 1

"$CMAKE" "$SCRIPT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENCL=$OPENCL_ENABLED \
    -DGGML_VULKAN=$VULKAN_ENABLED \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
    -DCMAKE_CXX_FLAGS="-D_GLIBCXX_USE_CXX11_ABI=1" \
    -GNinja || {
    log_error "ARM64 CMake配置失败"
    exit 1
}

"$NINJA" || {
    log_error "ARM64编译失败"
    exit 1
}

# 检查ARM64库（可能直接输出到jniLibs或在build目录）
ARM64_LIB_FOUND=0
ARM64_LIB_PATHS=(
    "$JNI_LIBS_DIR/arm64-v8a/libllama-jni.so"
    "libllama-jni.so"
    "CMakeFiles/llama-jni.dir/libllama-jni.so"
)
for path in "${ARM64_LIB_PATHS[@]}"; do
    if [ -f "$path" ]; then
        log_info "ARM64库已找到: $path"
        ARM64_LIB_FOUND=1
        # 如果不在目标目录，复制过去
        if [ "$path" != "$JNI_LIBS_DIR/arm64-v8a/libllama-jni.so" ]; then
            cp "$path" "$JNI_LIBS_DIR/arm64-v8a/"
            log_info "ARM64库已复制到: $JNI_LIBS_DIR/arm64-v8a/libllama-jni.so"
        fi
        break
    fi
done

if [ $ARM64_LIB_FOUND -eq 0 ]; then
    log_error "未找到ARM64库文件"
    log_info "搜索过的路径:"
    for path in "${ARM64_LIB_PATHS[@]}"; do
        log_info "  $path"
    done
    exit 1
fi

# 静态链接模式：只需要libllama-jni.so，所有依赖已嵌入
log_info "静态链接模式: 所有依赖库已嵌入libllama-jni.so"

# ============================================
# 编译x86_64架构
# ============================================
log_info "开始编译x86_64架构..."

X64_BUILD_DIR="$SCRIPT_DIR/build/x86_64"
mkdir -p "$X64_BUILD_DIR"

cd "$X64_BUILD_DIR" || exit 1

"$CMAKE" "$SCRIPT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="x86_64" \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
    -DCMAKE_CXX_FLAGS="-D_GLIBCXX_USE_CXX11_ABI=1" \
    -GNinja || {
    log_error "x86_64 CMake配置失败"
    exit 1
}

"$NINJA" || {
    log_error "x86_64编译失败"
    exit 1
}

# 检查x86_64库（可能直接输出到jniLibs或在build目录）
X64_LIB_FOUND=0
X64_LIB_PATHS=(
    "$JNI_LIBS_DIR/x86_64/libllama-jni.so"
    "libllama-jni.so"
    "CMakeFiles/llama-jni.dir/libllama-jni.so"
)
for path in "${X64_LIB_PATHS[@]}"; do
    if [ -f "$path" ]; then
        log_info "x86_64库已找到: $path"
        X64_LIB_FOUND=1
        # 如果不在目标目录，复制过去
        if [ "$path" != "$JNI_LIBS_DIR/x86_64/libllama-jni.so" ]; then
            cp "$path" "$JNI_LIBS_DIR/x86_64/"
            log_info "x86_64库已复制到: $JNI_LIBS_DIR/x86_64/libllama-jni.so"
        fi
        break
    fi
done

if [ $X64_LIB_FOUND -eq 0 ]; then
    log_error "未找到x86_64库文件"
    log_info "搜索过的路径:"
    for path in "${X64_LIB_PATHS[@]}"; do
        log_info "  $path"
    done
    exit 1
fi

# 静态链接模式：只需要libllama-jni.so，所有依赖已嵌入
log_info "静态链接模式: x86_64所有依赖库已嵌入libllama-jni.so"

# ============================================
# 编译完成
# ============================================
log_info "=================================="
log_info "本地库编译完成！"
log_info "输出目录: $JNI_LIBS_DIR"
log_info "=================================="

# 显示文件信息
log_info "ARM64库:"
ls -lh "$JNI_LIBS_DIR/arm64-v8a/libllama-jni.so"

log_info "x86_64库:"
ls -lh "$JNI_LIBS_DIR/x86_64/libllama-jni.so"

log_info "=================================="
log_info "现在可以使用以下命令编译APK:"
log_info "cd $APP_DIR && ./gradlew.bat assembleRelease"
log_info "=================================="
