# ChatKit 依赖清单

## 〇、SPI 层（v2 起：逻辑组件零 Context）

逻辑组件不再直接持有 `android.content.Context`，经 `com.oilquiz.app.ai.spi`
接口取系统能力。宿主需提供（推荐 Application 里 `AppServices.install(context)`
装全部默认实现；组件构造传 Context 时自动兜底安装）：

| 接口 | 职责 | 实现建议 |
|---|---|---|
| `StringProvider` | 取文案/格式化 | Android 用 R.string；云端/多语言可自定义 |
| `PreferenceStore` | 键值持久化 | SharedPreferences / DataStore / 内存 |
| `SpeechGateway` | ASR 可用性 / TTS 朗读 / 本地 ASR 预热 | 桥接 SpeechManager + SenseVoiceAsr |
| `ModelGateway` | 功能模型查询 / 深度思考开关 | 桥接 OnlineModelManager + ChatModeManager |
| `ToolGateway` | 具名工具执行 | 桥接 AIToolManager |
| `FileDirProvider` | 缓存/音乐目录 | getCacheDir / getExternalFilesDir |

- `AppServices`：静态注册表（install/ensure/reset/取用），含 `appContext()`（平台工具构造用）。
- `AndroidAppServices`：默认实现（桥接既有单例与资源系统）。
- 平台适配工具（保留 Context 方法参数/构造，非逻辑组件）：`FileUriUtils`（ContentResolver）、
  `AttachmentFactory`（content:// 复制）、`VisionInferenceHelper`（saveAttachments 传参）、
  `LinkOpener`（静态 open(Context, url)）。

## 一、内部类依赖（本包组件引用，需随包带入或已在宿主工程）

| 类 | 包 | 被谁依赖 |
|---|---|---|
| `ChatMessage` | `com.oilquiz.app.ai.chat` | 全部消息侧组件（构建/流式/发送/视觉/附件） |
| `ChatModeManager` | `com.oilquiz.app.ai.chat` | ModeInstructionInjector |
| `ComponentData` | `com.oilquiz.app.ai.chat.component` | AttachmentPromptBuilder |
| `ChatAdapter` | `com.oilquiz.app.ai.chat` | 消息流渲染（宿主注入 ChatShellView，非本包源码） |
| `AttachmentPreParser` | `com.oilquiz.app.ai.chat.input` | ChatInputManager（附件预解析，非本包源码） |
| `ChatInputManager` | `com.oilquiz.app.ai.chat.input` | ChatInputBar.attachManager 返回类型 |
| `AttachmentManager` | `com.oilquiz.app.ai.util` | ChatInputBar/AttachmentFactory 数据源 |
| `ConversationSession` | `com.oilquiz.app.ai.util` | ChatSessionController 会话接口 |
| `OnlineModelManager` | `com.oilquiz.app.ai.model` | ChatSpeechInputController / AttachmentVisionPipeline |
| `TokenStatsManager` | `com.oilquiz.app.ai.stats` | TokenStatsTextBuilder / TokenStatsBar |
| `AIToolManager` / `AIToolResult` / `VoiceInputTool` | `com.oilquiz.app.ai.tool` | ToolExecutionOrchestrator / ChatSpeechInputController |
| `AgentLoopEngine` | `com.oilquiz.app.ai.agent.software.engine` | ChatSessionController（EngineControl 接口） |
| `SenseVoiceAsr` | `com.oilquiz.app.ai.speech.asr` | ChatSpeechInputController（本地预热） |
| `SpeechManager` | `com.oilquiz.app.ai.speech` | ChatSpeechController / ChatSpeechInputController |

> 组件内还依赖同包类：`AttachmentPromptBuilder`（AttachmentVisionPipeline）、
> `FileUriUtils`（AttachmentChipsView/AttachmentVisionPipeline）、
> `ChatTextUtils`（GenerationStatusBar/TokenStatsBar）、
> `MessageRouteDecider`（ChatMessageSender）等，随包自带。

## 二、字符串资源（80 个 R.string，宿主需具备同名资源；缺失时改为常量即可）

```
h_03670db5 h_11214c64 h_14e8a21e h_1575bdd9 h_176d6e45 h_1e7d25f8 h_1ee53933
h_24984e0c h_24f55c59 h_28c797b8 h_29d6d222 h_2a8f9dc2 h_2d8ed504 h_2dcef5d6
h_30890ceb h_36623a6c h_3e238a20 h_41d16b3d h_447064b9 h_46bc48a9 h_48e86d79
h_4b4e3db7 h_4bc876d2 h_4fe2794a h_5529af52 h_55a2a38e h_5cc23262 h_5d89be82
h_5f27c9db h_68e672a0 h_6b35290e h_6d66ba3b h_715ae415 h_743faf7e h_764fab2b
h_793176ee h_803f889b h_83afc322 h_86b05ff3 h_8997e243 h_926e80d4 h_94e069c2
h_986cd3e8 h_9e0ee86a h_a4368a9d h_a485628e h_ad0acdc5 h_b07b6bc9 h_b1c1d48c
h_b2f5500b h_b557980d h_b575ddeb h_ba8cd317 h_bd78f6c8 h_bf63260f h_c3f0e02f
h_c59cad21 h_c5d49541 h_c5d9a5e1 h_c65217ea h_cad33dfa h_cf88fe4b h_d6c806dd
h_d7824330 h_d7a0f347 h_dae5a2ac h_db5abb31 h_dc9307dd h_e2a65c56 h_e3ad3e92
h_e4b86c39 h_e6f7a8b9 h_ee31e1e3 h_f3df22c9 h_f4854afd h_f6a98d86 h_f8a477f6
h_f9154462 h_fe85860b h_feae15b8
```

## 三、第三方依赖（宿主 Gradle）

- `androidx.recyclerview:recyclerview`（ChatMessagesView / AttachmentChipsView）
- `androidx.appcompat`（AlertDialog 场景）
- `com.google.android.material:material`（ChatInputBar 的 MaterialButton / ModeChipGroup 的 Chip / ChatBottomSheet 的 BottomSheetDialog）
- `com.google.code.gson:gson`（ToolExecutionOrchestrator 参数序列化）
- Android SDK：`java.nio.file.Files`（API 26+，FileUriUtils/AttachmentVisionPipeline 读图）

## 三·五、本地 native 库：源码编译方法（源码随宿主工程，不随包）

本地 LLM / ASR / OCR 的 native 库**源码就在宿主工程内**（`src/main/cpp/`，
CMake 项目 `llama-jni`），由 Gradle `externalNativeBuild`（CMake + Ninja）
自动编译，**无需手动下载 .so**：

| 项 | 值 |
|---|---|
| 源码根 | `src/main/cpp/`（llama.cpp + 顶层 CMakeLists + OpenCL 后端，`GGML_OPENCL_USE_ADRENO_KERNELS=ON` Adreno 优化 kernel） |
| 构建参数 | build.gradle `externalNativeBuild.cmake.arguments`：`-DANDROID_STL=c++_shared` `-GNinja` `-DGGML_OPENCL=ON` `-DGGML_VULKAN=OFF`（`CMAKE_MAKE_PROGRAM` 指向 SDK 自带 Ninja） |
| 产物目录 | `src/main/jniLibs/<abi>/`（CMakeLists `LIBRARY_OUTPUT_DIRECTORY`） |
| ABI | `arm64-v8a` 真机（libllama-jni.so + liblo-native-code.so + libc++_shared.so 等）；`x86_64` 模拟器（libllama-jni.so） |
| 环境 | Android SDK + NDK（compileSdk 34 / minSdk 31）+ CMake 3.22.1（SDK 组件，Ninja 随附） |

编译命令（任选其一）：

```bat
:: ① 打包 APK（自动触发 native 编译，日常最常用）
gradlew.bat assembleDebug

:: ② 只编 native 库，不动 APK
gradlew.bat externalNativeBuildDebug

:: ③ 手动 CMake 交叉编译（改参数/出产物到指定目录时用；<NDK> 替换为实际路径）
cmake -S src/main/cpp -B build/native ^
  -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 ^
  -DANDROID_STL=c++_shared -GNinja -DGGML_OPENCL=ON -DGGML_VULKAN=OFF
cmake --build build/native
```

> - 产物体积大（arm64-v8a 合计约 340MB），换机克隆后先 `gradlew assembleDebug`
>   重建或直接复用 jniLibs 现有 .so。
> - 增删 ABI 需改 build.gradle `abiFilters` 后重编；OpenCL 后端仅对 Adreno GPU 生效，
>   其他 GPU 可关 `-DGGML_OPENCL=OFF` 回退 CPU。

## 四、可移植性说明

- 组件**不 import Activity/Fragment**，仅依赖 `Context`（applicationContext）。
- `R.string` 是唯一资源耦合点：移植时替换为宿主同名资源，或提取为常量/接口。
- `ChatShellView` 依赖 `ChatInputBar`/`ChatMessagesView`/`GenerationStatusBar`/`TokenStatsBar`/`ChatStateOverlay`（随包自带）。
