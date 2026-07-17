cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggml requires non-finite math arithmetics (NaN/Inf handling)
# -ffast-math enables -ffinite-math-only which breaks ggml compilation
# Use -O3 without -ffast-math, and explicitly disable finite-math-only
add_compile_options(-O3 -DNDEBUG -fno-finite-math-only)

option(GGML_OPENCL "Enable OpenCL backend" OFF)
option(GGML_VULKAN "Enable Vulkan backend (experimental, may improve speed on Adreno 7xx+ GPUs)" OFF)
option(GGML_CUDA "Enable CUDA backend" OFF)
option(GGML_BACKEND_DL "Enable dynamic backend loading" OFF)
option(BUILD_SHARED_LIBS "Build shared libraries" OFF)

set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno-optimized OpenCL kernels" FORCE)
set(GGML_OPENCL_TARGET_VERSION "300" CACHE STRING "OpenCL API version" FORCE)

# --- Vulkan auto-detection ---
# Check for glslc (needed for SPIR-V shader compilation).
# If not found, gracefully disable Vulkan instead of failing the build.
# Common location: $ANDROID_NDK/shader-tools/<host-platform>/glslc[.exe]
if(GGML_VULKAN)
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
        message(WARNING "Vulkan: glslc not found! Install Vulkan SDK or ensure "
                        "ANDROID_NDK is set (needs shader-tools/ subdirectory). "
                        "Falling back to OpenCL-only. Disabling Vulkan.")
        set(GGML_VULKAN OFF)
    else()
        message(STATUS "Vulkan: glslc found at ${GLSLC_EXECUTABLE}")
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
    ${OPENCL_HEADERS_DIR}
    ${CMAKE_SOURCE_DIR}/vulkan
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

if(GGML_OPENCL)
    message(STATUS "OpenCL: Using NDK stub library for compilation (runtime will load vendor driver)")
endif()

set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)
