# ChatKit 依赖清单

## 〇、SPI 层（v2 起：逻辑组件零 Context）

逻辑组件不再直接持有 `android.content.Context`，经 `com.oilquiz.app.ai.spi`

接口取系统能力。宿主需提供（推荐 Application 里 `AppServices.install(context)`

装全部默认实现；组件构造传 Context 时自动兜底安装）：



| 接口                | 职责                           | 实现建议                                    |
| ----------------- | ---------------------------- | --------------------------------------- |
| `StringProvider`  | 取文案 / 格式化                    | Android 用 R.string；云端 / 多语言可自定义         |
| `PreferenceStore` | 键值持久化                        | SharedPreferences / DataStore / 内存      |
| `SpeechGateway`   | ASR 可用性 / TTS 朗读 / 本地 ASR 预热 | 桥接 SpeechManager + SenseVoiceAsr        |
| `ModelGateway`    | 功能模型查询 / 深度思考开关              | 桥接 OnlineModelManager + ChatModeManager |
| `ToolGateway`     | 具名工具执行                       | 桥接 AIToolManager                        |
| `FileDirProvider` | 缓存 / 音乐目录                    | getCacheDir / getExternalFilesDir       |



* `AppServices`：静态注册表（install/ensure/reset/ 取用），含 `appContext()`（平台工具构造用）。

* `AndroidAppServices`：默认实现（桥接既有单例与资源系统）。

* 平台适配工具（保留 Context 方法参数 / 构造，非逻辑组件）：`FileUriUtils`（ContentResolver）、

  `AttachmentFactory`（content:// 复制）、`VisionInferenceHelper`（saveAttachments 传参）、

  `LinkOpener`（静态 open (Context, url)）。

## 一、内部类依赖（本包组件引用，需随包带入或已在宿主工程）



| 类                                                   | 包                                          | 被谁依赖                                                  |
| --------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------- |
| `ChatMessage`                                       | `com.oilquiz.app.ai.chat`                  | 全部消息侧组件（构建 / 流式 / 发送 / 视觉 / 附件）                       |
| `ChatModeManager`                                   | `com.oilquiz.app.ai.chat`                  | ModeInstructionInjector                               |
| `ComponentData`                                     | `com.oilquiz.app.ai.chat.component`        | AttachmentPromptBuilder                               |
| `ChatAdapter`                                       | `com.oilquiz.app.ai.chat`                  | 消息流渲染（宿主注入 ChatShellView，非本包源码）                       |
| `AttachmentPreParser`                               | `com.oilquiz.app.ai.chat.input`            | ChatInputManager（附件预解析，非本包源码）                         |
| `ChatInputManager`                                  | `com.oilquiz.app.ai.chat.input`            | ChatInputBar.attachManager 返回类型                       |
| `AttachmentManager`                                 | `com.oilquiz.app.ai.util`                  | ChatInputBar/AttachmentFactory 数据源                    |
| `ConversationSession`                               | `com.oilquiz.app.ai.util`                  | ChatSessionController 会话接口                            |
| `OnlineModelManager`                                | `com.oilquiz.app.ai.model`                 | ChatSpeechInputController / AttachmentVisionPipeline  |
| `TokenStatsManager`                                 | `com.oilquiz.app.ai.stats`                 | TokenStatsTextBuilder / TokenStatsBar                 |
| `AIToolManager` / `AIToolResult` / `VoiceInputTool` | `com.oilquiz.app.ai.tool`                  | ToolExecutionOrchestrator / ChatSpeechInputController |
| `AgentLoopEngine`                                   | `com.oilquiz.app.ai.agent.software.engine` | ChatSessionController（EngineControl 接口）               |
| `SenseVoiceAsr`                                     | `com.oilquiz.app.ai.speech.asr`            | ChatSpeechInputController（本地预热）                       |
| `SpeechManager`                                     | `com.oilquiz.app.ai.speech`                | ChatSpeechController / ChatSpeechInputController      |

> 组件内还依赖同包类：
>
> `AttachmentPromptBuilder`
>
> （AttachmentVisionPipeline）、
> `FileUriUtils`
>
> （AttachmentChipsView/AttachmentVisionPipeline）、
> `ChatTextUtils`
>
> （GenerationStatusBar/TokenStatsBar）、
> `MessageRouteDecider`
>
> （ChatMessageSender）等，随包自带。

## 二、字符串资源（80 个 R.string，宿主需具备同名资源；缺失时改为常量即可）



```
h\_03670db5 h\_11214c64 h\_14e8a21e h\_1575bdd9 h\_176d6e45 h\_1e7d25f8 h\_1ee53933

h\_24984e0c h\_24f55c59 h\_28c797b8 h\_29d6d222 h\_2a8f9dc2 h\_2d8ed504 h\_2dcef5d6

h\_30890ceb h\_36623a6c h\_3e238a20 h\_41d16b3d h\_447064b9 h\_46bc48a9 h\_48e86d79

h\_4b4e3db7 h\_4bc876d2 h\_4fe2794a h\_5529af52 h\_55a2a38e h\_5cc23262 h\_5d89be82

h\_5f27c9db h\_68e672a0 h\_6b35290e h\_6d66ba3b h\_715ae415 h\_743faf7e h\_764fab2b

h\_793176ee h\_803f889b h\_83afc322 h\_86b05ff3 h\_8997e243 h\_926e80d4 h\_94e069c2

h\_986cd3e8 h\_9e0ee86a h\_a4368a9d h\_a485628e h\_ad0acdc5 h\_b07b6bc9 h\_b1c1d48c

h\_b2f5500b h\_b557980d h\_b575ddeb h\_ba8cd317 h\_bd78f6c8 h\_bf63260f h\_c3f0e02f

h\_c59cad21 h\_c5d49541 h\_c5d9a5e1 h\_c65217ea h\_cad33dfa h\_cf88fe4b h\_d6c806dd

h\_d7824330 h\_d7a0f347 h\_dae5a2ac h\_db5abb31 h\_dc9307dd h\_e2a65c56 h\_e3ad3e92

h\_e4b86c39 h\_e6f7a8b9 h\_ee31e1e3 h\_f3df22c9 h\_f4854afd h\_f6a98d86 h\_f8a477f6

h\_f9154462 h\_fe85860b h\_feae15b8
```

## 三、第三方依赖（宿主 Gradle）



* `androidx.recyclerview:recyclerview`（ChatMessagesView / AttachmentChipsView）

* `androidx.appcompat`（AlertDialog 场景）

* `com.google.android.material:material`（ChatInputBar 的 MaterialButton / ModeChipGroup 的 Chip / ChatBottomSheet 的 BottomSheetDialog）

* `com.google.code.gson:gson`（ToolExecutionOrchestrator 参数序列化）

* Android SDK：`java.nio.file.Files`（API 26+，FileUriUtils/AttachmentVisionPipeline 读图）

## 三・五、本地 native 库：源码编译方法（源码随宿主工程，不随包）

本地 LLM / ASR / OCR 的 native 库**源码就在宿主工程内**（`src/main/cpp/`，

CMake 项目 `llama-jni`），由 Gradle `externalNativeBuild`（CMake + Ninja）

自动编译，**无需手动下载 .so**：



| 项    | 值                                                                                                                                                                    |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 源码根  | `src/main/cpp/`（llama.cpp + 顶层 CMakeLists + OpenCL 后端，`GGML_OPENCL_USE_ADRENO_KERNELS=ON` Adreno 优化 kernel）                                                          |
| 构建参数 | build.gradle `externalNativeBuild.cmake.arguments`：`-DANDROID_STL=c++_shared` `-GNinja` `-DGGML_OPENCL=ON` `-DGGML_VULKAN=OFF`（`CMAKE_MAKE_PROGRAM` 指向 SDK 自带 Ninja） |
| 产物目录 | `src/main/jniLibs/<abi>/`（CMakeLists `LIBRARY_OUTPUT_DIRECTORY`）                                                                                                     |
| ABI  | `arm64-v8a` 真机（libllama-jni.so + liblo-native-code.so + libc++\_shared.so 等）；`x86_64` 模拟器（libllama-jni.so）                                                           |
| 环境   | Android SDK + NDK（compileSdk 34 /minSdk 31）+ CMake 3.22.1（SDK 组件，Ninja 随附）                                                                                           |

编译命令（任选其一）：



```
:: ① 打包 APK（自动触发 native 编译，日常最常用）

gradlew.bat assembleDebug

:: ② 只编 native 库，不动 APK

gradlew.bat externalNativeBuildDebug

:: ③ 手动 CMake 交叉编译（改参数/出产物到指定目录时用；\<NDK> 替换为实际路径）

cmake -S src/main/cpp -B build/native ^

&#x20; -DCMAKE\_TOOLCHAIN\_FILE=\<NDK>/build/cmake/android.toolchain.cmake ^

&#x20; -DANDROID\_ABI=arm64-v8a -DANDROID\_PLATFORM=android-31 ^

&#x20; -DANDROID\_STL=c++\_shared -GNinja -DGGML\_OPENCL=ON -DGGML\_VULKAN=OFF

cmake --build build/native
```

> 产物体积大（arm64-v8a 合计约 340MB），换机克隆后先 
>
> `gradlew assembleDebug`
> 重建或直接复用 jniLibs 现有 .so。
> 增删 ABI 需改 build.gradle 
>
> `abiFilters`
>
>  后重编；OpenCL 后端仅对 Adreno GPU 生效，
> 其他 GPU 可关 
>
> `-DGGML_OPENCL=OFF`
>
>  回退 CPU。

## 三・六、CMakeLists 与 C++ 编写方法

### 1. 顶层 `src/main/cpp/CMakeLists.txt` 逻辑（225 行，三段式）



```
┌─ 段① 全局与后端准备（1–194 行）

│   · 语言：C++17（CMAKE\_CXX\_STANDARD 17）

│   · ggml/llama 后端开关：GGML\_OPENCL=ON（含

│     GGML\_OPENCL\_EMBED\_KERNELS / GGML\_OPENCL\_USE\_ADRENO\_KERNELS=ON /

│     GGML\_OPENCL\_TARGET\_VERSION=300）、GGML\_VULKAN=OFF（NDK 交叉编译的

│     find\_package 变量预配置，含 glslc/spirv-headers）、GGML\_CUDA=OFF、

│     GGML\_BACKEND\_DL=OFF、BUILD\_SHARED\_LIBS=OFF

│   · 路径：LLAMA\_CPP\_PATH=./llama.cpp、OPENCL\_HEADERS\_DIR=./opencl/headers、

│     OPENCL\_ICD\_LIB=./opencl/build/lib/libOpenCL.so（NDK stub，运行期加载厂商驱动）

│   · include\_directories：llama.cpp 各子目录 + opencl/vulkan/spirv-headers +

│     \*\*../java/com/oilquiz/app/ai/jni\*\*（JNI 头文件目录）

│   · add\_subdirectory(llama.cpp)：引入 llama / llama-common / mtmd /

│     ggml-opencl 等目标库

└─ 段② llama-jni 目标定义（196–234 行）

&#x20;   add\_library(llama-jni SHARED

&#x20;       native-lib.cpp      ← 主 JNI 实现（约 8700 行）

&#x20;       llama-bridge.cpp    ← llama 调用桥接（391 行）

&#x20;       agent\_kv\_cache.cpp  ← KV 缓存管理（101 行）)

&#x20;   target\_link\_libraries(llama-jni

&#x20;       llama llama-common mtmd log android z m atomic dl

&#x20;       \[ggml-opencl | ggml-vulkan]  ← 按后端开关条件链接)

&#x20;   set\_target\_properties(llama-jni PROPERTIES

&#x20;       LIBRARY\_OUTPUT\_DIRECTORY "\${CMAKE\_SOURCE\_DIR}/../jniLibs/\${ANDROID\_ABI}")

&#x20;       ← 产物直接进 src/main/jniLibs/\<abi>，APK 打包无需额外配置
```

### 2. Java ↔ C++ 对接约定（本工程已按此组织）



* **Java 侧**：native 方法集中在 `com.oilquiz.app.ai.jni` 包

  （LlamaHelper / ChatRequest / TypeConverter）。加载与声明：



```
public class LlamaHelper {

&#x20;   private static final String LIBRARY\_NAME = "llama-jni";

&#x20;   static { System.loadLibrary(LIBRARY\_NAME); }

&#x20;   private static native int  nativeInitModel(String modelPath, int nCtx, int nThreads);

&#x20;   private static native String nativeGenerate(String prompt, int maxTokens,

&#x20;                                               float temperature, float topP, int topK);

&#x20;   // 流式：native 层用 llama\_chat\_apply\_template 适配模型格式，经 TokenCallback 回调

&#x20;   private static native void nativeGenerateStream(String prompt, int maxTokens,

&#x20;                                                   float temperature, float topP, int topK,

&#x20;                                                   boolean enableThinking, TokenCallback callback);

}
```



* **C++ 侧**：函数名 = `Java_` + 包名（`.`→`_`）+ `_` + 类名 + `_` + 方法名，

  全部实现在 `native-lib.cpp`：



```
extern "C" JNIEXPORT jint JNICALL

Java\_com\_oilquiz\_app\_ai\_jni\_LlamaHelper\_nativeInitModel(

&#x20;       JNIEnv\* env, jclass clazz, jstring modelPath, jint nCtx, jint nThreads) {

&#x20;   const char\* path = env->GetStringUTFChars(modelPath, nullptr);

&#x20;   int code = llama\_init(path, nCtx, nThreads);   // 业务逻辑

&#x20;   env->ReleaseStringUTFChars(modelPath, path);

&#x20;   return code;

}
```

### 3. 新增一个 native 方法的完整流程（复用 llama-jni，不改 CMakeLists）



1. **Java 声明**：在 LlamaHelper 加 `private static native String nativeEcho(String s);`

2. **实现**：在 `native-lib.cpp` 加同签名函数（方法名按上节规则生成；

   需头文件可 `javac -h` 自动生成 `Java_com_..._LlamaHelper.h` 后 include）

3. **重新编译**：`gradlew assembleDebug`（native 变更自动触发增量编译）

JNI 类型映射速查：



| Java               | C/C++ 签名                  | JNI 类型                                               |
| ------------------ | ------------------------- | ---------------------------------------------------- |
| `String`           | `Ljava/lang/String;`      | `jstring`（GetStringUTFChars / NewStringUTF）          |
| `int` / `long`     | `I` / `J`                 | `jint` / `jlong`                                     |
| `float` / `double` | `F` / `D`                 | `jfloat` / `jdouble`                                 |
| `boolean`          | `Z`                       | `jboolean`                                           |
| `int[]`            | `[I`                      | `jintArray`（GetIntArrayElements）                     |
| 回调接口               | `Lcom/.../TokenCallback;` | `jobject`（NewGlobalRef + CallVoidMethod/GetMethodID） |
| 对象字段               | —                         | GetFieldID / SetIntField 等                           |

### 4. 修改 CMakeLists 的三种情况



| 场景                         | 操作                                                                                                    |
| -------------------------- | ----------------------------------------------------------------------------------------------------- |
| 往现有 cpp（native-lib.cpp）加方法 | **不改 CMakeLists**，只改 cpp + Java                                                                       |
| 新增 .cpp 并入 llama-jni       | `add_library` 源列表加一行文件名                                                                               |
| 新增独立 .so（如另一套推理引擎）         | 新 `add_library(xxx SHARED ...)` + 同样的 `LIBRARY_OUTPUT_DIRECTORY` + Java 侧 `System.loadLibrary("xxx")` |

### 5. 注意事项



* **STL 必须&#x20;**`c++_shared`：APK 需带 `libc++_shared.so`（jniLibs/arm64-v8a 已有）；

  换 `c++_static` 需同步清理共享件，避免多 .so 重复实例。

* **禁&#x20;**`-ffast-math`：ggml 依赖 NaN/Inf 语义（CMakeLists 内已注释说明），编译选项勿加。

* **OpenCL 后端**：编译期链接 NDK 提供的 stub `libOpenCL.so`，运行期由系统加载厂商

  驱动（libGLES\_mali.so 等）；Adreno 专属 kernel 提升 8 系 GPU prefill 吞吐。

* **日志**：`__android_log_print(ANDROID_LOG_INFO, "llama-jni", ...)`，Android Studio

  logcat 过滤 `llama-jni`。

* **耗时推理**：native 同步调用会阻塞调用线程 ——Android 侧走工作线程；

  流式输出经 `TokenCallback`（全局引用 + 方法 ID）回调 Java。

* **ABI**：真机 arm64-v8a、模拟器 x86\_64；新增 ABI 改 build.gradle `abiFilters`。

* **编码**：Windows 下编辑 cpp/CMakeLists 用 UTF-8；PowerShell 默认 GBK 读写

  会破坏中文注释（工程已踩坑，用 `[System.IO.File]::ReadAllText/WriteAllText`）。

## 四、可移植性说明



* 组件**不 import Activity/Fragment**，仅依赖 `Context`（applicationContext）。

* `R.string` 是唯一资源耦合点：移植时替换为宿主同名资源，或提取为常量 / 接口。

* `ChatShellView` 依赖 `ChatInputBar`/`ChatMessagesView`/`GenerationStatusBar`/`TokenStatsBar`/`ChatStateOverlay`（随包自带）。