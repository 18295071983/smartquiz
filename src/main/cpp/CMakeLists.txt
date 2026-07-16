cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_SHARED_LINKER_FLAGS "-Wl,--allow-shlib-undefined")
set(CMAKE_MODULE_LINKER_FLAGS "-Wl,--allow-shlib-undefined")
set(CMAKE_EXE_LINKER_FLAGS "-Wl,--allow-shlib-undefined")
set(CMAKE_CXX_LINK_EXECUTABLE "${CMAKE_CXX_LINK_EXECUTABLE} -Wl,--allow-shlib-undefined")

set(ANDROID_STL c++_static CACHE STRING "Android STL type" FORCE)

# ggml requires non-finite math arithmetics (NaN/Inf handling)
# -ffast-math enables -ffinite-math-only which breaks ggml compilation
# Use -O3 without -ffast-math, and explicitly disable finite-math-only
add_compile_options(-O3 -DNDEBUG -fno-finite-math-only)

option(GGML_OPENCL "Enable OpenCL backend" ON)
option(GGML_VULKAN "Enable Vulkan backend (experimental, may improve speed on Adreno 7xx+ GPUs)" OFF)
option(GGML_CUDA "Enable CUDA backend" OFF)
option(GGML_HEXAGON "Enable Hexagon DSP backend" OFF)
option(GGML_BACKEND_DL "Enable dynamic backend loading" OFF)
option(BUILD_SHARED_LIBS "Build shared libraries" ON)

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
set(GGML_HEXAGON OFF CACHE BOOL "Enable Hexagon DSP backend" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Enable dynamic backend loading" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)

set(LLAMA_CPP_PATH "${CMAKE_SOURCE_DIR}/llama.cpp")
set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")
set(OPENCL_STUB_LIB "${CMAKE_SOURCE_DIR}/opencl/build/${ANDROID_ABI}/libOpenCL.so")

if(GGML_VULKAN)
    message(STATUS "Vulkan backend: ENABLED (glslc: ${GLSLC_EXECUTABLE})")
else()
    message(STATUS "Vulkan backend: DISABLED (use -DGGML_VULKAN=ON to enable; requires Vulkan SDK with glslc)")
endif()

if(GGML_OPENCL AND (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
    message(STATUS "OpenCL backend enabled (using dynamic loading)")
    set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno-optimized OpenCL kernels" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels in binary" FORCE)
    
    if(EXISTS "${OPENCL_HEADERS_DIR}/CL/cl.h")
        message(STATUS "OpenCL: Using local headers at ${OPENCL_HEADERS_DIR}")
        set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dirs" FORCE)
        set(OpenCL_FOUND TRUE CACHE BOOL "OpenCL found" FORCE)
        set(OpenCL_VERSION_STRING "3.0" CACHE STRING "OpenCL version" FORCE)
        set(OpenCL_LIBRARIES "" CACHE FILEPATH "OpenCL libraries" FORCE)
        message(STATUS "OpenCL: Using dynamic loading - no DT_NEEDED entry will be created")
    else()
        message(WARNING "OpenCL headers not found!")
        set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
    endif()
    
    message(STATUS "OpenCL: Adreno-optimized kernels enabled")
    message(STATUS "OpenCL: Embedded kernels enabled (no runtime compilation)")
elseif(GGML_OPENCL)
    message(STATUS "OpenCL disabled for unsupported architecture: ${ANDROID_ABI}")
    set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
else()
    message(STATUS "OpenCL backend disabled")
endif()

add_subdirectory(${LLAMA_CPP_PATH} build-llama)

target_link_libraries(ggml PRIVATE -Wl,--no-as-needed -lc++ -Wl,--as-needed)

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
    vulkan_gpu.cpp
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

if(GGML_OPENCL AND NOT OpenCL_LIBRARIES)
    target_link_options(llama-jni PRIVATE "-Wl,--allow-shlib-undefined")
    target_link_options(llama PRIVATE "-Wl,--allow-shlib-undefined")
    target_link_options(ggml PRIVATE "-Wl,--allow-shlib-undefined")
    target_link_options(ggml-opencl PRIVATE "-Wl,--allow-shlib-undefined")
endif()

set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)
