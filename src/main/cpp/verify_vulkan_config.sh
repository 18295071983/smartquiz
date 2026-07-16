#!/bin/bash
# ============================================
# 验证Vulkan配置
# ============================================

set -e

echo "=================================="
echo "验证Vulkan配置"
echo "=================================="

# 配置路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_SDK="/d/Android/Sdk"
NDK_DIR="$ANDROID_SDK/ndk/26.1.10909125"

# 检查glslc
echo "检查glslc编译器..."
GLSLC="$NDK_DIR/shader-tools/windows-x86_64/glslc.exe"
if [ -f "$GLSLC" ]; then
    echo "  [OK] glslc: $GLSLC"
    $GLSLC --version | head -2
else
    echo "  [WARN] NDK glslc未找到"
fi

# 检查MSYS2 glslc
if which glslc > /dev/null 2>&1; then
    echo "  [OK] MSYS2 glslc: $(which glslc)"
    glslc --version | head -2
fi

echo ""
echo "检查CMakeLists.txt中的Vulkan配置:"
grep -E "(GGML_VULKAN|GGML_OPENCL)" "$SCRIPT_DIR/CMakeLists.txt"

echo ""
echo "检查构建脚本配置:"
grep -E "(VULKAN|GGLSLC|glslc)" "$SCRIPT_DIR/build_llama_jni_msys2.sh"

echo ""
echo "=================================="
echo "配置验证完成"
echo "=================================="
echo ""
echo "要执行完整构建，请在MSYS2 MinGW64环境中运行:"
echo "  cd /d/quzp/app/src/main/cpp"
echo "  ./build_llama_jni_msys2.sh"
echo ""
