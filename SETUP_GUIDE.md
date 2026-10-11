# 纯净环境构建指南

从**全新克隆**到一个能装到设备上的 APK，实际需要做什么。

> **先说结论**：直接 `git clone` + `./gradlew assembleDebug` **必然失败**。本仓库有
> 三类东西不在版本库里 —— **本机绝对路径**、**被忽略的源码目录**、**超平台限制的大二进制**。
> 本文把它们一次列全，并给出每项的取得或修正方式。

---

## 0. 缺口总览

按"必须先解决"的顺序：

| # | 缺口 | 影响 | 解决方式 |
| --- | --- | --- | --- |
| 1 | `local.properties`（未入库） | 找不到 Android SDK | 自建，写 `sdk.dir` |
| 2 | **5 处本机绝对路径** | 直接构建失败 | 改成你自己的路径 |
| 3 | `src/main/cpp/llama.cpp/` 等源码目录（被 ignore） | **native 构建失败** | 从上游克隆并切到指定提交 |
| 4 | `model.int8.onnx`（228 MB） | 预检报错，构建中止 | 脚本自动下载 |
| 5 | `liblo-native-code.so`（171 MB） | 预检报错，构建中止 | 从 Release 附件下载 |
| 6 | `libllama-jni.so` 等 native 产物（未入库） | 由第 3 项构建产生 | 构建 native |

第 4、5 项由 `build.gradle` 顶部的 **`BINARY-PRECHECK`** 在构建开始时就报出来（缺哪个、多大、
放哪、去哪拿），细节见 [BINARY_ASSETS.md](BINARY_ASSETS.md)。第 1~3 项不在预检范围内，
正是本文的重点。

---

## 1. 工具链版本（以 `build.gradle` 为准）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21** | `gradle.properties` 里 `org.gradle.java.home=D:/jdk-21`；JDK 17 也能编，但需改该行 |
| Gradle | **8.13** | 由 `gradle/wrapper/` 指定，无需单独安装 |
| Android Gradle Plugin | **8.9.2** | |
| compileSdk / targetSdk | **36** | |
| buildToolsVersion | **36.0.0** | |
| minSdk | **31** | |
| NDK | **26.1.10909125** | 构建 native 库必需 |
| CMake | **3.22.1** | 见 `build.gradle` 的 `externalNativeBuild.cmake.version` |
| Python | **3.10** | Chaquopy 的 `buildPython`；**必须 3.10（或 3.9）**，见下文说明 |

> ⚠️ 根 `README.md` 的"环境要求"一节写的是 *JDK 17 / API 34 / AGP 8.4.0*，
> **已过时**（该节早于 Android 16 升级），以 `build.gradle` 为准。

安装 NDK / CMake / build-tools：

```powershell
# 需先设置 JAVA_HOME，否则 sdkmanager 会报 "Java version 17 or higher is required"
$env:JAVA_HOME = 'D:\jdk-21'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" `
    "ndk;26.1.10909125" "cmake;3.22.1" "build-tools;36.0.0" "platforms;android-36"
```

---

## 2. `local.properties`（必须自建，已被 .gitignore 排除）

在**仓库根目录**新建：

```properties
sdk.dir=D\:\\Android\\Sdk
```

> 这是 Android 项目的标准文件，不入库是**有意为之**（各人 SDK 路径不同）。
> Linux/macOS 写成 `sdk.dir=/home/you/Android/Sdk` 即可（注意转义规则不同）。

---

## 3. 需要修改的本机绝对路径（**5 处**）

这些是开发机上遗留的硬编码路径，**在新机器上必然不存在**，必须改。可直接搜索
`C:\\Users`、`D:/`、`D:\\`、`/d/` 定位。

### 3.1 `gradle.properties`

```properties
org.gradle.java.home=D:/jdk-21                 # ← 改成你的 JDK 21 路径
systemProp.gradle.user.home=D:/Gradle/Home     # ← Gradle 用户目录（放缓存）
```

> 注：`systemProp.` 前缀的 `gradle.user.home` **未必生效**（Gradle 通常只认环境变量
> `GRADLE_USER_HOME`）。若发现缓存目录没按预期变化，用环境变量设置更可靠。

### 3.2 `build.gradle`

| 行 | 内容 | 处理 |
| --- | --- | --- |
| ~104 | `-DCMAKE_MAKE_PROGRAM=D:\\Android\\Sdk\\cmake\\3.22.1\\bin\\ninja.exe` | 改成你的 SDK 下 ninja 路径 |
| ~126 | `buildPython "C:\\Users\\<你>\\AppData\\Local\\Programs\\Python\\Python310\\python.exe"` | 改成你的 Python 3.10 |
| ~197 | `options "--find-links", "file:///D:/qzq/smartquiz/python-wheels"` | 改成 `<你的仓库路径>/python-wheels` |

> **`buildPython` 为什么必须是 3.10**：Chaquopy 在构建期用它解包/预编译。
> Android 端运行时是 Python 3.10，**3.12/3.14 生成的字节码 3.10 无法加载**，
> 会导致 `.pyc` 预编译失败（首次启动变慢）。
>
> `--find-links` 指向的 `python-wheels/`（56 个 wheel，约 59 MB）**是入库的**，
> 但路径写成了绝对路径 —— 这是全仓库最容易被忽略的一处，因为它只在
> Chaquopy 的 pip 阶段才被用到。

---

## 4. 被 ignore 但构建必需的源码目录

`.gitignore` 忽略了以下目录（历史原因：避免把上游源码塞进仓库），
但 `src/main/cpp/CMakeLists.txt` **直接引用它们**：

| 目录 | 本地体积 | CMake 中的用途 |
| --- | --- | --- |
| `src/main/cpp/llama.cpp/` | 167 MB / 3665 文件 | `add_subdirectory()` —— **必需的 llama.cpp 源码** |
| `src/main/cpp/opencl/` | 2.1 MB / 189 文件 | OpenCL headers + ICD 库 |
| `src/main/cpp/spirv-headers/` | 4.1 MB / 121 文件 | Vulkan 后端的 SPIR-V 头 |
| `src/main/cpp/cmake/` | 2 文件 | shader 生成用的 host toolchain |
| `src/main/cpp/vulkan/` | 0 文件 | 目录存在但为空（当前配置走 NDK 自带 Vulkan 头） |

### `llama.cpp` 的获取（含本项目改动，注意）

该目录本身是一个 **git 仓库**（不是 submodule），本机状态：

```
remote: https://gitcode.com/gh_mirrors/ll/llama.cpp.git
HEAD:   a809b47
```

获取方式：

```bash
cd src/main/cpp
git clone https://gitcode.com/gh_mirrors/ll/llama.cpp.git
cd llama.cpp
git checkout a809b47          # 与本项目对齐的提交
```

> ⚠️ **本项目对 llama.cpp 做过本地改动**（历史上有一条提交
> `patches: keep the local llama.cpp changes in the main repo`）。
> 仅按上面的地址与提交克隆**未必与维护者的工作副本完全一致** ——
> 如果 native 构建或 NPU/GPU 后端出现异常行为，需向维护者确认所需的补丁。
> 这部分我无法从仓库中还原（改动只存在于本机工作副本）。

其余目录（`opencl/`、`spirv-headers/`、`cmake/`）同样不在库里。若 Vulkan / OpenCL 后端
不是必需，可先关闭对应后端再构建（见 `src/main/cpp/CMakeLists.txt` 的
`GGML_OPENCL` / `GGML_VULKAN` 选项）。

---

## 5. 大二进制（预检会报）

两个因超过代码托管平台单文件上限而无法入库的文件：

| 文件 | 体积 | 获取 |
| --- | --- | --- |
| `src/main/assets/asr/model.int8.onnx` | 228 MB | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-asr-model.ps1` |
| `viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so` | 171 MB | 从 [Release 附件](https://github.com/18295071983/smartquiz/releases/tag/binary-assets-v1) 下载（含 SHA256 校验） |

`src/main/assets/asr/tokens.txt` 与 `silero_vad.onnx` **已在库中**，无需下载。

完整清单、许可与来源见 [BINARY_ASSETS.md](BINARY_ASSETS.md)。

---

## 6. 构建顺序

```powershell
# ① 前置：JDK / SDK / NDK / CMake / Python3.10 就绪，local.properties 已建
# ② 取得第 4、5 项（源码目录与两个大二进制）
# ③ 修正第 3 项的 5 处路径
# ④ 构建
./gradlew assembleDebug

# 仅编译 Java（更快，用于验证代码改动；不触发 native 构建）
./gradlew compileDebugJavaWithJavac

# 装到设备
./gradlew installDebug
```

### native 库（`libllama-jni.so` 等）

`libllama-jni.so`（**199 MB**）是 CMake 的产物，**同样不在库里**（体积原因）。
它由 AGP 的 `externalNativeBuild` 在 `assembleDebug` 时自动构建，前提是第 4 项的源码目录已就位。
`src/main/cpp/` 下另有若干手动构建脚本，但**它们内部也写着本机绝对路径**
（例如 `build_arm64_full.sh` 里的 `SRC_DIR=/d/quzp/app/...`，连本项目路径都不是），
使用前需自行修正。

---

## 7. 常见失败与定位

| 现象 | 真正原因 |
| --- | --- |
| `specify compileSdk in build.gradle`（但 `compileSdk 36` 明明配了） | **缺 `viewer/.../liblo-native-code.so`**。这是误导性报错，见 [BINARY_ASSETS.md](BINARY_ASSETS.md) 的说明 |
| 预检输出"构建缺少必要的二进制资源…" | 第 5 项缺文件，按提示的路径放置 |
| `找不到 sdk.dir` / `SDK location not found` | 第 2 项 `local.properties` 未建 |
| Chaquopy 阶段失败 / `buildPython` 报错 | 第 3.2 项的 Python 路径未改，或版本不是 3.10 |
| `add_subdirectory` 报 llama.cpp 不存在 | 第 4 项源码目录未获取 |
| `ninja` 找不到 | 第 3.2 项 CMake ninja 路径未改 |

> **排查提示**：判断"是不是缺文件导致失败"时，**必须先 `./gradlew --stop` 清掉 daemon**
> 再复测 —— Gradle 会复用上一次的配置状态，可能造成"缺文件也能构建"的**假象**
> （本项目 2026-10 排查时踩到过）。

---

## 8. 已知未提供的东西（如实说明）

以下内容**不在仓库中，也没有自动获取方式**，需向维护者索取：

- `liblo-native-code.so` —— 已有 Release 附件（见第 5 节）
- `llama.cpp` 的**本地补丁** —— 不在仓库中，只有本机工作副本有
- `opencl/`、`spirv-headers/`、`cmake/` 等 CMake 依赖目录 —— 不在仓库中

本文档描述的步骤基于维护者机器上**实测可用**的状态；若你在某一步卡住，
请把**完整错误输出**（不是最后一行）提供给维护者 —— 本项目的报错信息存在
多处"指错方向"的情况（见第 7 节第一行）。
