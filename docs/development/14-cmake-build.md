# CMake 构建设计

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述 llama.cpp JNI 的 CMake 构建设计。全部依据真实源码 `src/main/cpp/CMakeLists.txt`（234 行）与 `build.gradle`。

## 一、构建入口

```
build.gradle (AGP externalNativeBuild)
    ↓ 调用 CMake
src/main/cpp/CMakeLists.txt
    ↓
llama-jni.so 库（JNI 实现）
    ↓ 输出
src/main/jniLibs/<abi>/llama-jni.so
```

**构建方式**：AGP 通过 `externalNativeBuild` 调用 CMake 编译本地库，产物输出到 `jniLibs/<abi>/`（AGP 打包时自动 strip）。

## 二、build.gradle 配置

```gradle
externalNativeBuild {
    cmake {
        cppFlags "-std=c++17 -O3 -DNDEBUG -fno-finite-math-only"
        arguments "-DANDROID_STL=c++_shared",
                  "-GNinja",
                  "-DCMAKE_MAKE_PROGRAM=<sdk>/cmake/3.22.1/bin/ninja.exe",
                  "-DHEXAGON_SDK_ROOT=${hexSdk}",      // 来自 System.getenv，用于开启 NPU
                  "-DHEXAGON_TOOLS_ROOT=${hexTools}"
    }
}
ndk {
    abiFilters "arm64-v8a"                                  // 仅 arm64（LibreOffice viewer 只有 arm64）
}
```

> 后端开关**不通过 `arguments` 传**，而是由 `src/main/cpp/CMakeLists.txt` 内部 `FORCE` 设定
> （见 3.2）。`HEXAGON_*` 必须走 CMake 变量：ggml-hexagon 只读 CMake 变量，不读环境变量；
> 若只设环境变量，后端会在配置阶段中止。

### 关键构建参数

| 参数 | 值 | 说明 |
|------|-----|------|
| CMAKE 版本 | 3.22.1 | `cmake_minimum_required(VERSION 3.22.1)` |
| C++ 标准 | c++17 | `CMAKE_CXX_STANDARD 17` |
| 优化 | -O3 -DNDEBUG | 无 `-ffast-math` |
| STL | c++_shared | ANDROID_STL |
| 生成器 | Ninja | `-GNinja` |
| ABI | arm64-v8a | `abiFilters` |
| GGML_OPENCL | ON | Adreno 优化 kernel（`GGML_OPENCL_USE_ADRENO_KERNELS`） |
| GGML_VULKAN | ON | 第二 GPU 后端（NDK glslc 不支持 coopmat/bfloat16，走基础路径） |
| GGML_HEXAGON | 由 SDK 是否存在决定 | NPU 后端；`HEXAGON_SDK_ROOT` 有效即 `ON` |
| BUILD_SHARED_LIBS | OFF | 四个后端**静态编入** `libllama-jni.so` |
| GGML_BACKEND_DL | OFF | 不做动态后端加载 |

## 三、CMakeLists.txt 设计

### 3.1 项目与编译选项

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(llama-jni)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggml 需要非有限数学（NaN/Inf）处理
# -ffast-math 会启用 -ffinite-math-only 破坏 ggml 编译
# 用 -O3 但不加 -ffast-math，显式禁用 finite-math-only
add_compile_options(-O3 -DNDEBUG -fno-finite-math-only)

# SPIRV-Headers 全局包含路径 (Vulkan 后端需要)
add_compile_options(-I${CMAKE_SOURCE_DIR}/spirv-headers/include)
```

**关键**：`-fno-finite-math-only` —— 因为 ggml 需要处理 NaN/Inf，而 `-ffast-math` 会破坏它。

### 3.2 GPU 后端选型（CMake option）

```cmake
option(GGML_OPENCL "Enable OpenCL backend" ON)
option(GGML_VULKAN "Enable Vulkan backend for GPU acceleration" ON)
option(GGML_CUDA "Enable CUDA backend" OFF)
option(GGML_BACKEND_DL "Enable dynamic backend loading" OFF)
option(BUILD_SHARED_LIBS "Build shared libraries" OFF)
```

**选型决策（2026-10：四后端，NPU 为默认）**：

本机（SM8850 / Hexagon v81）实测：

| 后端 | prefill | decode | 备注 |
|---|---|---|---|
| **Hexagon (HTP)** | ~1500 tok/s | 29-34 tok/s | **默认**，43/43 层全量卸载 |
| OpenCL | ~205 tok/s | ~15.6 tok/s | Adreno 专用 kernel |
| Vulkan | 较慢 | - | NDK glslc 不支持 coopmat/bfloat16，走基础路径 |

- **NPU 优先**：`LlamaHelper.DEFAULT_BACKEND = "hexagon"`；`auto` 亦按
  `Hexagon > OpenCL > Vulkan` 解析出**单个**设备。
- Hexagon 开启条件：`HEXAGON_SDK_ROOT` 指向有效目录（`CMakeLists.txt` 内 `FORCE` 设
  `GGML_HEXAGON`）。未设置则跳过该后端并打印 `NPU: GGML_HEXAGON=OFF`。
- **后端一律只放一个设备**进 `model_params.devices`。曾把多个 GPU 类型设备一起传入，
  模型加载时在 `ggml_backend_dev_get_props` 触发 SIGSEGV。
- 四个后端全部**静态编入** `libllama-jni.so`（`BUILD_SHARED_LIBS=OFF` +
  `GGML_BACKEND_DL=OFF`），因此**没有** `libggml-vulkan.so` / `-opencl.so` / `-hexagon.so`。
  ELF `DT_NEEDED` 可证：`libllama-jni.so` 无任何 ggml/llama 动态依赖，只用系统的
  `libvulkan.so` 与 `libOpenCL.so`。

> 常见误判：APK 里的 `libggml-opencl.so`、`libggml-hexagon.so`、`libggml-base.so`、
> `libggml-cpu.so` 来自 **GenieX AAR**（`libs/geniex-android-0.8.0.aar`），不是本工程的产物，
> 本工程的 so 不依赖它们。

### 3.3 多模态支持

```cmake
# 构建 mtmd 库（视觉投影 mmproj 加载与图片编码）
set(MTMD_VIDEO OFF CACHE BOOL "enable video support in mtmd" FORCE)  # 禁视频(依赖ffmpeg)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "llama: build mtmd library" FORCE)
```

只保留图片能力，禁用视频（视频依赖 ffmpeg）。

### 3.4 OpenCL 配置

```cmake
set(GGML_OPENCL_EMBED_KERNELS ON )          # 嵌入 OpenCL kernel（运行时无需编译）
set(GGML_OPENCL_USE_ADRENO_KERNELS ON )      # Adreno 优化 kernel
set(GGML_OPENCL_TARGET_VERSION "300")        # OpenCL API 版本

# 头文件
set(OPENCL_HEADERS_DIR "${CMAKE_SOURCE_DIR}/opencl/headers")
# 链接库
set(OPENCL_ICD_LIB "${CMAKE_SOURCE_DIR}/opencl/build/lib/libOpenCL.so")
```

仅 `arm64-v8a` 启用 OpenCL（不同架构自动禁用）。

### 3.5 Vulkan 配置（OFF 时跳过）

`GGML_VULKAN` 为 ON 时：
- 查找 `glslc`（优先 NDK shader-tools `<NDK>/shader-tools/<host>`）。
- 使用 NDK Vulkan headers + `libvulkan.so`（API 34 存根，仅编译链接，运行时由系统加载器提供实际驱动）。
- `ggml-vulkan` 通过 `vkGetInstanceProcAddr` 动态加载 Vulkan 函数。
- 交叉编译时用主机工具链（`host-toolchain-windows.cmake`）生成 shader。

## 四、JNI 库（llama-jni）

### 4.1 源文件

```cmake
add_library(
    llama-jni
    SHARED
    native-lib.cpp        # JNI 主实现
    llama-bridge.cpp      # 桥接
    agent_kv_cache.cpp    # KV 增量缓存
)
```

### 4.2 链接库

```cmake
target_link_libraries(llama-jni
    llama          # llama.cpp 核心
    llama-common   # common utils
    mtmd           # 多模态
    log android z m atomic dl
)

if(GGML_OPENCL) target_link_libraries(llama-jni ggml-opencl) endif()
if(GGML_VULKAN) target_link_libraries(llama-jni ggml-vulkan) endif()
```

### 4.3 输出目录

```cmake
set_target_properties(llama-jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
```

产物输出到 `src/main/jniLibs/<abi>/llama-jni.so`。

## 五、重要构建决策（注释提取）

1. **OpenCL 优先**：Adreno 走 OpenCL 后端，Vulkan 备用（2026-08 决策）。
2. **不 -ffast-math**：`-fno-finite-math-only` 保证 ggml NaN/Inf 处理正确。
3. **嵌入 kernel**：`GGML_OPENCL_EMBED_KERNELS=ON` 运行时无需编译 kernel，降低启动开销。
4. **Vulkan 降级**：Adreno 驱动对 coopmat/bfloat16/dot 支持不稳，走 F16 基础路径。
5. **NDK glslc 回退**：MSYS2 新版 shaderc 生成的 bfloat16/e4m3 变体在 Adreno 不兼容，回退 NDK glslc（2022.3）。

## 六、完整构建目录（vendored 依赖）

```
src/main/cpp/
├── CMakeLists.txt      主构建
├── native-lib.cpp      JNI 主实现（~347KB）
├── llama-bridge.cpp    桥接
├── agent_kv_cache.cpp  KV 增量缓存
├── llama.cpp/          llama.cpp 源码（含 ggml 各后端）
├── ggml/src/ggml-opencl/  ggml-opencl 后端
├── opencl/             OpenCL headers/ICD-loader
├── spirv-headers/      SPIRV（Vulkan）
└── vulkan/             Vulkan 相关
```

## 七、构建命令

```bash
# 通过 Gradle 构建（编译本地 .so + 打包 APK）
.\gradlew.bat assembleDebug --offline

# 验证 JNI 库输出
Get-ChildItem build/intermediates/cxx/Debug -Recurse -Filter "*.so"
```

## 相关文档

- [llama.cpp 功能设计](12-llama-cpp.md)
- [端侧大模型部署设计](11-edge-model-deployment.md)
- [推理库与推理引擎设计](13-inference-engine.md)
- [硬件与性能](07-hardware-performance.md)
