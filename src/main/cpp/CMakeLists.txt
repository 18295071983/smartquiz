cmake_minimum_required(VERSION 3.22.1)

project(llama-jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
message(STATUS "Dual GPU backends enabled: OpenCL (Adreno optimized) + Vulkan")

set(LLAMA_CPP_PATH "${CMAKE_SOURCE_DIR}/llama.cpp")

set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")
set(OPENCL_ICD_BUILD_DIR "${CMAKE_SOURCE_DIR}/opencl/build/OpenCL-ICD-Loader/build")

set(OpenCL_INCLUDE_DIRS "${OPENCL_HEADERS_DIR}" CACHE PATH "OpenCL include dirs" FORCE)
set(OpenCL_LIBRARIES "${OPENCL_ICD_BUILD_DIR}/libOpenCL.so" CACHE FILEPATH "OpenCL library" FORCE)
set(OpenCL_FOUND TRUE CACHE BOOL "OpenCL found" FORCE)
set(OpenCL_VERSION_STRING "3.0" CACHE STRING "OpenCL version" FORCE)

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
