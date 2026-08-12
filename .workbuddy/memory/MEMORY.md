# SmartQuiz / 答题宝 — 项目长期记忆

## 用户协作约定（务必遵守）

- **不要擅自修改构建相关文件**：`CMakeLists.txt`、`build.gradle`、`gradle.properties`、`.cxx/` 等。
  用户原话："你不要给我乱改"。需要改动必须先说明理由并取得明确同意。
- **不要删除缓存目录**（如 `.cxx/`）——用户已明确拒绝过一次。
- 用户同时在 **Qoder** 等其他工具里操作同一仓库，构建可能由其他工具完成。
  遇到构建失败时，先怀疑自己的执行环境，不要急着断言"项目有问题"。
- 未纳入 git 的新增目录（如 `speech/core/`、`speech/tts/`）删除不可恢复，动之前必须确认。

## 构建环境（Windows）

- **必须用 PowerShell / cmd 构建原生层，不要用 Git Bash。**
  Git Bash(MSYS) 会改写 `cmd.exe /C vulkan-shaders-gen.exe ...` 的参数，
  导致 glslc 的 `-D DATA_A_*` 宏丢失，报 `mul_mat_vecq.comp: error: '#error' : unimplemented`。
  这是环境问题，不是 llama.cpp 或项目配置的问题。
- 若确实要在 Git Bash 里跑 gradlew：需先 `export PATH="/c/Program Files/Git/usr/bin:$PATH"`，
  否则报 `cygpath: command not found`。
- CMake `option()` 的默认值**不会覆盖 `.cxx` 中已缓存的值**；
  `-Pandroid.injected.cmake.arguments` 注入同样会被缓存挡住。改开关需连带处理缓存。
- 产物：`build/outputs/apk/debug/答题宝-debug-2.0.apk`（debug 包约 425MB）。

## 技术栈要点

- Android 应用 `com.oilquiz.app`，AGP 8.4.0，Java 17，minSdk 31 / targetSdk 34。
- LLM 推理：**llama.cpp**（GGUF + mmap，JNI 桥 `src/main/cpp/llama-bridge.cpp`），Vulkan 后端默认 ON。
- CV：**TFLite**（`Interpreter` + `MappedByteBuffer`）；OCR：**MLKit**。项目**未使用 MNN**。
- 语音：`SpeechManager` 门面 + `TtsEngine` 策略（OpenAiTtsEngine / DashScopeTtsEngine / SystemTtsEngine）。
  在线 ASR 走 OpenAI 兼容 `/audio/transcriptions`；在线 TTS 走 `/audio/speech` 或 DashScope 原生。

## 待办 / 已知问题

- `src/main/cpp/llama-bridge.cpp:98-103`：`n_batch` 被钳制到 `MAX_CONTEXT_BATCH = 8`，
  严重拖慢 prefill 速度。**已发现未修复**，需用户确认后再动。
- P0 安全：`NetworkSearchTool.java` 硬编码 METASO key；`build.gradle` 明文签名密码。未处理。
- 语音修复（SSL 宽松回退 + SystemTtsEngine 并发/回调）已进包，**真机效果尚未验证**。
