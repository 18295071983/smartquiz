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

# GPU 后端选型（2026-08 决策，2026-10 更新）：OpenCL 主后端 + Vulkan 可切换。
# 依据：Qualcomm 专有 OpenCL 驱动 + GGML_OPENCL_USE_ADRENO_KERNELS（Adreno 专属 kernel），
# llama.cpp OpenCL 后端在 Adreno 830 实测 663 t/s prefill（llama-adreno 项目），
# 上游有 Qualcomm 工程师持续维护（IWOCL 2025/2026）；
# 2026-09 官方 48 个 Vulkan 提交：正式启用 Adreno KHR coopmat（#29328，Gen6+ 硬件矩阵核心）、
# argsort 修复（#29469）、bfloat16 条件化（#28155）。故两后端均编译进 APK，
# 运行时由设置项 GPU 后端 切换（OpenCL/Vulkan/自动），见 native-lib.cpp setBackend。
# 注意：NDK glslc 2022.3 不支持 cooperativeMatrix/bfloat16 扩展 → Vulkan 走基础路径（正确但较慢），
# coopmat 变体需升级 shaderc 并真机验证后再放开。
option(GGML_OPENCL "Enable OpenCL backend" ON)
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

# ============================================================================
# NPU（Qualcomm Hexagon / HTP）ggml 后端开关。
#
# 目标：让 HTP 成为 CPU / Vulkan / OpenCL 之外的**第四个 ggml 设备**（上游文档原话：
# "Hexagon NPU behaves as a GPU device when it comes to -ngl"），这样我们自己这份
# llama.cpp 就能把层卸载到 NPU，全部功能（对话/Agent/题库导入/VLM）天然吃到 NPU，
# 不再需要 GenieX 那份运行时。
#
# 必须放在 add_subdirectory(llama.cpp) **之前**（ggml 的 option 要先生效）。
# 依赖 Hexagon SDK（qaic 生成 htp_iface stub + hexagon-clang 编 DSP skel）：
#   HEXAGON_SDK_ROOT / HEXAGON_TOOLS_ROOT 环境变量指到 SDK 即自动开启。
# 未设置时保持 OFF，现有构建完全不受影响。
# ============================================================================
if(DEFINED ENV{HEXAGON_SDK_ROOT} AND IS_DIRECTORY "$ENV{HEXAGON_SDK_ROOT}")
    set(GGML_HEXAGON ON CACHE BOOL "ggml: enable Hexagon backend" FORCE)
    message(STATUS "NPU: GGML_HEXAGON=ON (HEXAGON_SDK_ROOT=$ENV{HEXAGON_SDK_ROOT})")
else()
    set(GGML_HEXAGON OFF CACHE BOOL "ggml: enable Hexagon backend" FORCE)
    message(STATUS "NPU: GGML_HEXAGON=OFF (未设置 HEXAGON_SDK_ROOT，跳过 Hexagon 后端)")
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
    agent_kv_cache.cpp
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

# NPU（HTP）：链入 Hexagon 后端（它是静态注册的，链进来即生效），
# 并把 DSP 侧 skel（libggml-htp-v*.so）拷进 jniLibs 一起打包 —— 运行时由 FastRPC
# 按名字从 nativeLibraryDir 加载（file:///libggml-htp-v%u.so?...&_dom=adsp）。
if(GGML_HEXAGON)
    target_link_libraries(llama-jni ggml-hexagon)
    message(STATUS "NPU: linked ggml-hexagon backend (HTP)")
    foreach(_htp_v v73 v75 v79 v81)
        add_custom_command(TARGET llama-jni POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "$<TARGET_FILE_DIR:ggml-hexagon>/libggml-htp-${_htp_v}.so"
                    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-htp-${_htp_v}.so"
            COMMENT "NPU: copying libggml-htp-${_htp_v}.so to jniLibs")
    endforeach()
    # HEXAGON-SHARED-ONLY(2026-10-09)：仅当 ggml-hexagon 是共享库时才拷进 jniLibs。
    # 本项目 BUILD_SHARED_LIBS=OFF、GGML_BACKEND_DL=OFF，故 ggml-hexagon 是【静态库】，
    # 已由上面的 target_link_libraries 静态链入 libllama-jni.so（后端静态注册即生效）。
    # 旧写法直接用 $<TARGET_FILE:ggml-hexagon>（= libggml-hexagon.a）拷成 .so，
    # 会往 jniLibs 塞一个名为 .so 的 7MB 静态归档：不是动态库、无法 dlopen，还白占包体。
    if(BUILD_SHARED_LIBS OR GGML_BACKEND_DL)
        add_custom_command(TARGET llama-jni POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "$<TARGET_FILE:ggml-hexagon>"
                    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-hexagon.so"
            COMMENT "NPU: copying shared libggml-hexagon.so to jniLibs")
    else()
        message(STATUS "NPU: ggml-hexagon is static; linked into libllama-jni.so (no separate .so copied)")
    endif()
endif()

if(GGML_OPENCL)
    message(STATUS "OpenCL: Using NDK stub library for compilation (runtime will load vendor driver)")
endif()

set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

# ============================================================================
# ggml-probe：探测能否把 GenieX 的 Hexagon(NPU) 后端挂到我们自己的 llama.cpp 上。
# 只 dlopen libllama-jni.so 的符号来调用（不重链那个 195MB 的大库），
# 所以是独立的极简目标。见 ggml-probe.cpp 顶部注释。
# ============================================================================
add_library(
    ggml-probe
    SHARED
    ggml-probe.cpp
)

target_link_libraries(
    ggml-probe
    log
    dl
)

set_target_properties(
    ggml-probe
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

# ============================================================================
# NPU 推理：改用 Qualcomm 官方 GenieX Android SDK（AAR: libs/geniex-android-0.8.0.aar）。
# 它自带全套 arm64 原生库（libgeniex*、libllama/ggml-*、HTP v81 skel、QnnHtp* 2.45），
# 所以这里**不再**编 JNI 外壳、也不再随包我们自己的 QNN/Genie 库 ——
# 那些库与 AAR 里的同名文件会互相顶掉（AGP 让本地 jniLibs 取胜），最终把 2.43 的老 QNN
# 塞给需要 2.45 的 GenieX，加载 context binary 时版本不兼容。
# 旧的 npu-jni.cpp / qnn/ 头文件 / NpuHelper.java 已于 2026-10-03 整体退休。
# ============================================================================
