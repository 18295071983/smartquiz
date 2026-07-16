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

# OpenCL: 在Android上使用设备厂商的驱动（运行时动态加载）
# 厂商驱动位于 /vendor/lib64/libOpenCL.so
OPENCL_LIB=""
OPENCL_LIB_PATHS=()
OPENCL_ENABLED=1

# Python3: OpenCL内核嵌入需要Python
# 优先使用系统Python，其次使用NDK中的Python
PYTHON3="/c/Users/xiaocong/AppData/Local/Programs/Python/Python312/python.exe"
if [ ! -f "$PYTHON3" ]; then
    PYTHON3="/c/Users/xiaocong/AppData/Local/Programs/Python/Python311/python.exe"
fi
if [ ! -f "$PYTHON3" ]; then
    PYTHON3="/c/Python312/python.exe"
fi
if [ ! -f "$PYTHON3" ]; then
    PYTHON3="/c/Python311/python.exe"
fi
if [ ! -f "$PYTHON3" ]; then
    PYTHON3="/d/Android/Sdk/ndk/26.1.10909125/prebuilt/windows-x86_64/bin/python3.exe"
fi

export PYTHONPATH=""

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

if [ ! -f "$PYTHON3" ]; then
    log_error "Python3 not found! OpenCL kernel embedding requires Python3."
    log_error "请安装Python3或设置PYTHON3环境变量"
    exit 1
fi

# 禁用Vulkan（Adreno 750对Vulkan计算支持有限，使用OpenCL代替）
VULKAN_ENABLED=0
log_info "Vulkan已禁用（使用OpenCL代替）"

# OpenCL: 使用设备厂商驱动，运行时动态加载
log_info "OpenCL已启用（使用设备厂商驱动，运行时动态加载）"

log_info "使用CMake: $CMAKE"
log_info "使用Ninja: $NINJA"
log_info "使用Python3: $PYTHON3"
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

# 构建OpenCL ICD Loader（仅ARM64需要，Android运行时通过dlopen动态加载厂商驱动）
STUB_LIB="$SCRIPT_DIR/opencl/build/lib/libOpenCL.so"
if [ ! -f "$STUB_LIB" ]; then
    log_info "构建OpenCL ICD Loader库..."

    ICD_SRC="$SCRIPT_DIR/opencl/OpenCL-ICD-Loader"
    ICD_BUILD="$SCRIPT_DIR/build/opencl-icd-loader"

    mkdir -p "$ICD_BUILD"
    cd "$ICD_BUILD" || exit 1

    "$CMAKE" "$ICD_SRC" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="arm64-v8a" \
        -DANDROID_PLATFORM=android-31 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON \
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$SCRIPT_DIR/opencl/headers" \
        -DENABLE_OPENCL_LAYERS=OFF \
        -DENABLE_OPENCL_LOADER_MANAGED_DISPATCH=OFF \
        -DENABLE_OPENCL_LAYERINFO=OFF \
        -G"Ninja" || {
        log_error "OpenCL ICD Loader CMake配置失败"
        exit 1
    }

    "$NINJA" || {
        log_error "OpenCL ICD Loader编译失败"
        exit 1
    }

    mkdir -p "$(dirname "$STUB_LIB")"
    cp "libOpenCL.so" "$STUB_LIB"
    log_info "OpenCL ICD Loader库已构建: $STUB_LIB"
    cd "$SCRIPT_DIR" || exit 1
fi

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
    -DGGML_OPENCL=$OPENCL_ENABLED \
    -DGGML_VULKAN=$VULKAN_ENABLED \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
    -DGGML_OPENCL_EMBED_KERNELS=ON \
    -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
    -DOpenCL_INCLUDE_DIRS="$SCRIPT_DIR/opencl/headers" \
    -DOpenCL_FOUND=$OPENCL_ENABLED \
    -DOpenCL_VERSION_STRING="3.0" \
    -DOpenCL_LIBRARIES="" \
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

# 复制所有依赖库到jniLibs目录
ARM64_BIN_DIR="$ARM64_BUILD_DIR/bin"
for lib in libllama.so libggml.so libggml-cpu.so libggml-base.so; do
    if [ -f "$ARM64_BIN_DIR/$lib" ]; then
        cp "$ARM64_BIN_DIR/$lib" "$JNI_LIBS_DIR/arm64-v8a/"
        log_info "ARM64依赖库已复制: $lib"
    else
        log_warn "ARM64依赖库未找到: $lib (搜索路径: $ARM64_BIN_DIR)"
    fi
done

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
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_RPC=OFF \
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

# 复制所有依赖库到jniLibs目录
X64_BIN_DIR="$X64_BUILD_DIR/bin"
for lib in libllama.so libggml.so libggml-cpu.so libggml-base.so; do
    if [ -f "$X64_BIN_DIR/$lib" ]; then
        cp "$X64_BIN_DIR/$lib" "$JNI_LIBS_DIR/x86_64/"
        log_info "x86_64依赖库已复制: $lib"
    else
        log_warn "x86_64依赖库未找到: $lib (搜索路径: $X64_BIN_DIR)"
    fi
done

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
