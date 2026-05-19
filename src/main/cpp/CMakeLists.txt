cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(GGML_OPENCL "Enable OpenCL backend" ON)
option(GGML_VULKAN "Enable Vulkan backend" OFF)
option(GGML_CUDA "Enable CUDA backend" OFF)
option(GGML_BACKEND_DL "Enable dynamic backend loading" OFF)
option(BUILD_SHARED_LIBS "Build shared libraries" ON)

set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Enable dynamic backend loading" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libraries" FORCE)

set(LLAMA_CPP_PATH "${CMAKE_SOURCE_DIR}/llama.cpp")
set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")
set(OPENCL_ICD_LIB "${CMAKE_SOURCE_DIR}/opencl/build/lib/libOpenCL.so")

message(STATUS "Vulkan backend disabled (Adreno 750 has limited Vulkan compute support)")

if(GGML_OPENCL AND ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "OpenCL backend enabled (using local ICD loader)")
    set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "Use Adreno-optimized OpenCL kernels" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels in binary" FORCE)
    
    if(EXISTS "${OPENCL_HEADERS_DIR}/CL/cl.h")
        message(STATUS "OpenCL: Using local headers at ${OPENCL_HEADERS_DIR}")
        set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dirs" FORCE)
        set(OpenCL_FOUND TRUE CACHE BOOL "OpenCL found" FORCE)
        set(OpenCL_VERSION_STRING "3.0" CACHE STRING "OpenCL version" FORCE)
        if(EXISTS "${OPENCL_ICD_LIB}")
            message(STATUS "OpenCL: Using local ICD loader at ${OPENCL_ICD_LIB}")
            set(OpenCL_LIBRARIES "${OPENCL_ICD_LIB}" CACHE FILEPATH "OpenCL libraries" FORCE)
            message(STATUS "OpenCL: ICD loader found, will link directly")
        else()
            message(WARNING "OpenCL ICD loader not found!")
            message(WARNING "OpenCL: Will use dynamic loading as fallback")
            set(OpenCL_LIBRARIES "" CACHE FILEPATH "OpenCL libraries" FORCE)
        endif()
    else()
        message(WARNING "OpenCL headers not found!")
        set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
    endif()
    
    message(STATUS "OpenCL: Adreno-optimized kernels enabled")
    message(STATUS "OpenCL: Embedded kernels enabled (no runtime compilation)")
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

set_target_properties(
    llama-jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)
