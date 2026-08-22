cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggml requires non-finite math arithmetics (NaN/Inf handling)
# -ffast-math enables -ffinite-math-only which breaks ggml compilation
# Use -O3 without -ffast-math, and explicitly disable finite-math-only
add_compile_options(-O3 -DNDEBUG -fno-finite-math-only)

# SPIRV-Headers 全局包含路径 (Vulkan 后端需要)
add_compile_options(-I${CMAKE_SOURCE_DIR}/spirv-headers/include)

option(GGML_OPENCL "Enable OpenCL backend" OFF)
option(GGML_VULKAN "Enable Vulkan backend for GPU acceleration" ON)
option(GGML_CUDA "Enable CUDA backend" OFF)
option(GGML_BACKEND_DL "Enable dynamic backend loading" OFF)
option(BUILD_SHARED_LIBS "Build shared libraries" OFF)

# b10276: 禁用 UI 和不必要的构建目标
option(LLAMA_BUILD_UI "llama: build the embedded Web UI for server" OFF)
option(LLAMA_USE_PREBUILT_UI "llama: use prebuilt UI from HF Bucket" OFF)
option(LLAMA_BUILD_TESTS "llama: build tests" OFF)
option(LLAMA_BUILD_TOOLS "llama: build tools" OFF)
option(LLAMA_BUILD_EXAMPLES "llama: build examples" OFF)
option(LLAMA_BUILD_SERVER "llama: build server example" OFF)
option(LLAMA_BUILD_APP "llama: build the unified binary" OFF)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common utils library" FORCE)

# 多模态支持：构建 mtmd 库（视觉投影 mmproj 加载与图片编码）
# 禁用视频支持（依赖 ffmpeg），仅保留图片能力
set(MTMD_VIDEO OFF CACHE BOOL "enable video support in mtmd" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "llama: build mtmd library" FORCE)

set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno-optimized OpenCL kernels" FORCE)
set(GGML_OPENCL_TARGET_VERSION "300" CACHE STRING "OpenCL API version" FORCE)

# --- Vulkan 配置 ---
# 使用 NDK 自带的 Vulkan headers + libvulkan.so + glslc
# 需要 SPIRV-Headers (已下载到 spirv-headers/ 目录)
if(GGML_VULKAN)
    # 查找 glslc（D6 回退：优先 NDK shader-tools。
    # 曾改用 MSYS2 mingw64 新版 shaderc（2026.1）——其生成的 bfloat16/e4m3/dot 变体
    # 在 Adreno 驱动上不兼容（真机输出乱码），且这些扩展无运行时禁用变量，
    # 故回退 NDK glslc（2022.3，不支持这些扩展 → 无加速变体 → 走基础路径，正确但较慢）。
    # 后续如要提速：逐扩展开启 + 真机验证正确性后再放开。
    find_program(GLSLC_EXECUTABLE glslc
        PATHS
            ${ANDROID_NDK}/shader-tools/windows-x86_64
            ${ANDROID_NDK}/shader-tools/linux-x86_64
            ${ANDROID_NDK}/shader-tools/darwin-x86_64
        NO_DEFAULT_PATH
    )
    if(NOT GLSLC_EXECUTABLE)
        find_program(GLSLC_EXECUTABLE glslc)
    endif()

    if(NOT GLSLC_EXECUTABLE)
        message(WARNING "Vulkan: glslc not found! Disabling Vulkan.")
        set(GGML_VULKAN OFF)
    else()
        message(STATUS "Vulkan: glslc found at ${GLSLC_EXECUTABLE}")

        # 配置 NDK Vulkan headers 和 library (根据目标架构动态选择)
        set(NDK_VULKAN_INCLUDE "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include")
        # 映射 ANDROID_ABI 到 NDK 库路径三元组
        if(ANDROID_ABI STREQUAL "arm64-v8a")
            set(NDK_VULKAN_ARCH_TRIPLE "aarch64-linux-android")
        elseif(ANDROID_ABI STREQUAL "x86_64")
            set(NDK_VULKAN_ARCH_TRIPLE "x86_64-linux-android")
        elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
            set(NDK_VULKAN_ARCH_TRIPLE "arm-linux-androideabi")
        elseif(ANDROID_ABI STREQUAL "x86")
            set(NDK_VULKAN_ARCH_TRIPLE "i686-linux-android")
        else()
            set(NDK_VULKAN_ARCH_TRIPLE "aarch64-linux-android")
        endif()
        # 使用 NDK 标准 Vulkan 存根 (API 34)
        # 存根仅用于编译链接，运行时由 Android 系统 Vulkan 加载器提供实际驱动
        # ggml-vulkan 通过 vkGetInstanceProcAddr 动态加载所有 Vulkan 函数，自适应任何 GPU
        set(NDK_VULKAN_LIB "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/${NDK_VULKAN_ARCH_TRIPLE}/34/libvulkan.so")
        message(STATUS "Vulkan: Using NDK stub libvulkan.so (API 34) - runtime adaptive via vkGetInstanceProcAddr")
        
        if(EXISTS "${NDK_VULKAN_INCLUDE}/vulkan/vulkan.h")
            message(STATUS "Vulkan: NDK headers found at ${NDK_VULKAN_INCLUDE}")
        else()
            message(WARNING "Vulkan: NDK headers not found! Disabling Vulkan.")
            set(GGML_VULKAN OFF)
        endif()
        
        if(GGML_VULKAN AND EXISTS "${NDK_VULKAN_LIB}")
            message(STATUS "Vulkan: NDK library found at ${NDK_VULKAN_LIB}")
        elseif(GGML_VULKAN)
            message(WARNING "Vulkan: NDK library not found at ${NDK_VULKAN_LIB}")
            set(GGML_VULKAN OFF)
        endif()
    endif()
endif()

set(GGML_VULKAN ${GGML_VULKAN} CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Enable dynamic backend loading" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)

set(LLAMA_CPP_PATH "${CMAKE_SOURCE_DIR}/llama.cpp")
set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")
set(OPENCL_ICD_LIB "${CMAKE_SOURCE_DIR}/opencl/build/lib/libOpenCL.so")

if(GGML_VULKAN)
    message(STATUS "Vulkan backend: ENABLED (glslc: ${GLSLC_EXECUTABLE})")
    
    # 预配置 Vulkan find_package 变量 (NDK 交叉编译)
    set(Vulkan_INCLUDE_DIRS "${NDK_VULKAN_INCLUDE}" CACHE PATH "Vulkan include dirs" FORCE)
    set(Vulkan_LIBRARIES "${NDK_VULKAN_LIB}" CACHE FILEPATH "Vulkan libraries" FORCE)
    set(Vulkan_LIBRARY "${NDK_VULKAN_LIB}" CACHE FILEPATH "Vulkan library" FORCE)
    set(Vulkan_FOUND TRUE CACHE BOOL "Vulkan found" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${GLSLC_EXECUTABLE}" CACHE FILEPATH "glslc" FORCE)
    
    # SPIRV-Headers 路径 (使用自定义 config)
    set(SPIRV_HEADERS_DIR "${CMAKE_SOURCE_DIR}/spirv-headers")
    set(SPIRV-Headers_DIR "${CMAKE_SOURCE_DIR}/cmake")
    list(APPEND CMAKE_PREFIX_PATH "${CMAKE_SOURCE_DIR}/cmake")
    
    # 交叉编译时 vulkan-shaders-gen 需要主机工具链
    if(CMAKE_CROSSCOMPILING)
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_SOURCE_DIR}/cmake/host-toolchain-windows.cmake" 
            CACHE FILEPATH "Host toolchain for vulkan-shaders-gen" FORCE)
        message(STATUS "Vulkan: Host toolchain for shader gen: ${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
    endif()
else()
    message(STATUS "Vulkan backend: DISABLED (use -DGGML_VULKAN=ON to enable; requires Vulkan SDK with glslc)")
endif()

if(GGML_OPENCL AND ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "OpenCL backend enabled (using device vendor driver at runtime)")
    
    if(EXISTS "${OPENCL_HEADERS_DIR}/CL/cl.h")
        message(STATUS "OpenCL: Using local headers at ${OPENCL_HEADERS_DIR}")
        
        set(OpenCL_INCLUDE_DIR "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dir" FORCE)
        set(OPENCL_INCLUDE_DIR "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dir" FORCE)
        set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dirs" FORCE)
        set(OpenCL_FOUND TRUE CACHE BOOL "OpenCL found" FORCE)
        set(OpenCL_VERSION_STRING "3.0" CACHE STRING "OpenCL version" FORCE)
        
        set(NDK_OPENCL_LIB "${ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libOpenCL.so")
        if(EXISTS "${NDK_OPENCL_LIB}")
            set(OpenCL_LIBRARIES "${NDK_OPENCL_LIB}" CACHE FILEPATH "OpenCL libraries" FORCE)
            set(OpenCL_LIBRARY "${NDK_OPENCL_LIB}" CACHE FILEPATH "OpenCL library" FORCE)
            message(STATUS "OpenCL: Using NDK stub library for compilation: ${NDK_OPENCL_LIB}")
            message(STATUS "OpenCL: Runtime will load vendor driver from /vendor/lib64/libOpenCL.so")
        else()
            message(WARNING "NDK OpenCL stub library not found at ${NDK_OPENCL_LIB}")
            set(OpenCL_LIBRARIES "" CACHE FILEPATH "OpenCL libraries" FORCE)
            set(OpenCL_LIBRARY "" CACHE FILEPATH "OpenCL library" FORCE)
            message(STATUS "OpenCL: Dynamic runtime loading enabled (no linking)")
        endif()
    else()
        message(WARNING "OpenCL headers not found at ${OPENCL_HEADERS_DIR}!")
        set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
    endif()
    
    message(STATUS "OpenCL: Adreno-optimized kernels enabled")
    message(STATUS "OpenCL: Embedded kernels enabled (no runtime compilation)")
    message(STATUS "OpenCL: Target version ${GGML_OPENCL_TARGET_VERSION}")
elseif(GGML_OPENCL)
    message(STATUS "OpenCL disabled for non-ARM64 architecture: ${ANDROID_ABI}")
    set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
else()
    message(STATUS "OpenCL backend disabled")
endif()

add_subdirectory(${LLAMA_CPP_PATH} build-llama)

include_directories(
    ${LLAMA_CPP_PATH}
    ${LLAMA_CPP_PATH}/common
    ${LLAMA_CPP_PATH}/include
    ${LLAMA_CPP_PATH}/ggml/include
    ${LLAMA_CPP_PATH}/tools/mtmd
    ${LLAMA_CPP_PATH}/vendor
    ${OPENCL_HEADERS_DIR}
    ${CMAKE_SOURCE_DIR}/vulkan
    ${CMAKE_SOURCE_DIR}/spirv-headers/include
    ${CMAKE_SOURCE_DIR}/../java/com/oilquiz/app/ai/jni
)

add_library(
    llama-jni
    SHARED
    native-lib.cpp
    llama-bridge.cpp
)

target_link_libraries(
    llama-jni
    llama
    llama-common
    mtmd
    log
    android
    z
    m
    atomic
    dl
)

if(GGML_OPENCL)
    target_link_libraries(llama-jni ggml-opencl)
endif()

if(GGML_VULKAN)
    target_link_libraries(llama-jni ggml-vulkan)
    message(STATUS "Vulkan: Linked ggml-vulkan backend")
endif()

if(GGML_OPENCL)
    message(STATUS "OpenCL: Using NDK stub library for compilation (runtime will load vendor driver)")
endif()

set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)
