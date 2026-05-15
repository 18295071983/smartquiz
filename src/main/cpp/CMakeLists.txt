cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(GGML_OPENCL "Enable OpenCL backend" ON)
option(GGML_VULKAN "Enable Vulkan backend" OFF)
option(GGML_CUDA "Enable CUDA backend" OFF)

set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)

set(LLAMA_CPP_PATH "${CMAKE_SOURCE_DIR}/llama.cpp")
set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")

message(STATUS "Vulkan backend disabled (Adreno 750 has limited Vulkan compute support)")

if(GGML_OPENCL AND ANDROID_ABI STREQUAL "arm64-v8a")
    message(STATUS "OpenCL backend enabled (using device vendor drivers)")
    set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
    set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dirs" FORCE)
    set(OpenCL_FOUND TRUE CACHE BOOL "OpenCL found" FORCE)
    set(OpenCL_VERSION_STRING "3.0" CACHE STRING "OpenCL version" FORCE)
    set(OPENCL_ICD_LIB "${CMAKE_SOURCE_DIR}/opencl/build/OpenCL-ICD-Loader/build_android/libOpenCL.so")
    if(EXISTS "${OPENCL_ICD_LIB}")
        set(OpenCL_LIBRARIES "${OPENCL_ICD_LIB}" CACHE FILEPATH "OpenCL library (for linking only)" FORCE)
        message(STATUS "OpenCL: Linking with ICD Loader at ${OPENCL_ICD_LIB}")
        message(STATUS "OpenCL: Runtime will use device vendor libOpenCL.so")
    else()
        message(WARNING "OpenCL ICD Loader not found at: ${OPENCL_ICD_LIB}")
        set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
    endif()
elseif(GGML_OPENCL)
    message(STATUS "OpenCL disabled for non-ARM64 architecture: ${ANDROID_ABI}")
    set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
else()
    message(STATUS "OpenCL backend disabled")
endif()

add_subdirectory(${LLAMA_CPP_PATH} build-llama)

include_directories(
    ${OPENCL_HEADERS_DIR}
    ${LLAMA_CPP_PATH}
    ${LLAMA_CPP_PATH}/common
    ${LLAMA_CPP_PATH}/include
    ${LLAMA_CPP_PATH}/ggml/include
    ${CMAKE_SOURCE_DIR}/../java/com/oilquiz/app/ai/jni
)

add_library(
    llama-jni
    SHARED
    native-lib.cpp
    vulkan_gpu.cpp
    agent_inference.cpp
    agent_inference_jni.cpp
    agent_inference_optimizations.cpp
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
