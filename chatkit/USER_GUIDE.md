# ChatKit 使用文档

AI 对话能力全局组件包：33 个组件（逻辑 22 + 渲染 8 + 装配 1 + 接入 2）+
SPI 服务层 8 + 资源 80 + 布局 1。**逻辑层零 Context、零页面引用**。

---

## 1. 包结构

```
chatkit/
├── README.md                   ← 概述/架构/接入速览
├── COMPONENT_INDEX.md          ← 组件清单（分层/职责/关键 API）
├── DEPENDENCIES.md             ← 依赖清单（SPI/内部类/R.string/第三方）
├── USER_GUIDE.md               ← 本文档（安装/初始化/示例/运行时依赖/测试）
└── src/
    ├── com/oilquiz/app/ai/     ← 41 个 Java（33 组件 + 8 SPI）
    ├── res/values/strings.xml  ← 80 个组件所需字符串（已含实际文案）
    └── res/layout/view_chat_input_bar.xml  ← ChatInputBar 布局
```

## 2. 安装（三步）

**① 拷源码**：把 `src/com/oilquiz/app/ai/` 下全部文件拷入宿主工程的
`com.oilquiz.app.ai` 包（保持包结构）。

**② 并资源**：把 `src/res/values/strings.xml` 的 80 个 `<string>` 并入宿主
`res/values/`（键名 `h_xxx` 必须保留；文案可改）。
`view_chat_input_bar.xml` 拷入宿主 `res/layout/`（内含所需 id，无其他资源引用）。
> 组件代码引用 `com.oilquiz.app.R.string.h_xxx`——若宿主包名不是
> `com.oilquiz.app`，全局替换 `com.oilquiz.app.R` → `宿主包名.R`。

**③ 补依赖**：见 DEPENDENCIES.md（第三方 Gradle 坐标 + 内部类依赖 +
运行期大件清单见本文档第 5 节）。

## 3. 初始化

```java
// Application#onCreate（推荐显式安装；组件构造传 Context 时也会自动兜底）
AppServices.install(this);
```

覆盖单个服务（云端文案 / 测试 mock）：

```java
AppServices.install((StringProvider) resId -> "自定义文案");
AppServices.reset();   // 测试隔离
```

## 4. 使用示例

### 4.1 整页壳（ChatShellView，最快接入）

```xml
<com.oilquiz.app.ai.chat.ui.ChatShellView
    android:id="@+id/chat_shell"
    android:layout_width="match_parent"
    android:layout_height="match_parent"/>
```

```java
ChatShellView shell = findViewById(R.id.chat_shell);
shell.bindSources(nativeSource, statsSource);          // 状态条/统计条数据源
shell.setAdapter(chatAdapter);                         // 消息流（ChatAdapter 或任意 Adapter）
ChatInputManager input = shell.attachInput(activity, callback, null); // 输入栏接线
shell.onResume();                                      // onResume：状态条轮询
// 流式回调中：
shell.onStreamingToken(totalTokens, tps);              // 实时统计
shell.onTokenStats(stats);                             // 完成态统计
shell.onThinkingSegment(disp, lineCount);              // 思考段
shell.onDestroy();                                     // onDestroy：停轮询
```

### 4.2 逻辑组件（零 UI，任意页面复用）

```java
// 发送编排（守卫→构建→分发）
ChatMessageSender.dispatch(raw, attachments, isOnline, localAgent,
        modelLoaded, fileExtractor, voiceInput, senderHost);

// 流式编排
GenerationStreamController stream = new GenerationStreamController(streamHost);
stream.beginGeneration();
stream.setStreamingTarget(msgId, idx);
stream.handleStreamTokenLegacy(token, history);
stream.safeUpdateMessage(history);
stream.endGeneration();

// 工具执行编排
new ToolExecutionOrchestrator(context, toolHost)
        .preCheckThenExecute("weather_query", params, msgPos, 0, onComplete);

// 附件视觉编排
AttachmentVisionPipeline vision = new AttachmentVisionPipeline(context, env);
vision.filterAttachments(list);
vision.canFollowUpOnlineVision(lastFailAt);
```

### 4.3 渲染组件（View 层）

```java
// 模式芯片组（主题配色注入）
modeChips.setChipStyle(主色, 浅灰, 12f);
modeChips.setOnModeChanged(mode -> { ... });

// 通用弹窗壳
new ChatBottomSheet(context)
        .title("选择操作").message("说明")
        .action("确定", v -> { ... })
        .secondaryAction("取消", null)
        .show();

// 附件条 / 录音指示 / 步骤引导流见 COMPONENT_INDEX.md 关键 API
```

## 5. 依赖与运行期大件清单

### 5.1 第三方 Gradle 坐标（组件直接依赖）

| 坐标 | 用途 |
|---|---|
| `androidx.recyclerview:recyclerview:1.3.2` | 消息容器/附件条 |
| `androidx.appcompat:appcompat:1.7.0` | AlertDialog 场景 |
| `com.google.android.material:material:1.12.0` | MaterialButton / Chip / BottomSheetDialog |
| `com.google.code.gson:gson:2.10.1` | 工具参数序列化 |
| `com.microsoft.onnxruntime:onnxruntime-android:1.18.0` | SenseVoice ASR（经 SpeechGateway） |
| API 26+：`java.nio.file.Files` | 文件读取（读图 base64） |

### 5.2 运行期宿主需具备的引擎/大件（不随包分发，体积过大）

| 大件 | 路径 | 体积 | 对应能力 |
|---|---|---|---|
| SenseVoice 模型 | `assets/asr/model.int8.onnx` | 228 MB | 本地语音识别（ChatSpeechInputController） |
| 本地 LLM native 库 | `jniLibs/arm64-v8a/libllama-jni.so` 等 | 140–170 MB/abi | 本地推理（GenerationStatusBar 数据源） |
| OCR 模型 | `assets/ocr/v6/{rec,det}.onnx` | 30 MB | 附件图片 OCR（AttachmentVisionPipeline 回退） |
| 词表 | `cpp/llama.cpp/models/ggml-vocab-*.gguf` | 10–15 MB | llama.cpp 运行 |
| TTS 引擎 | 系统 TTS / 在线合成 | — | 朗读（ChatSpeechController） |

> 这些大件由宿主工程提供（`SpeechGateway`/`ModelGateway` 桥接其管理器），
> 不随 ChatKit 打包；缺件时组件降级可用（ASR 不可用→提示、OCR 失败→回退）。

### 5.3 内部类依赖（随包带入或宿主已有）

见 DEPENDENCIES.md 第一节：`ChatMessage` / `ChatAdapter` / `AttachmentManager` /
`ConversationSession` / `OnlineModelManager` / `TokenStatsManager` /
`AIToolManager` / `AgentLoopEngine` / `SenseVoiceAsr` / `SpeechManager` 等。

## 6. 适配指南

- **包名**：非 `com.oilquiz.app` 时全局替换 `com.oilquiz.app.R` 引用。
- **文案**：strings.xml 键名不变即可换语言/换措辞。
- **主题**：`ModeChipGroup.setChipStyle`、`ChatBottomSheet` 内联色值均可覆盖；
  View 层组件的色值建议统一提取到宿主主题。
- **数据源**：`ChatShellView.bindSources`、各组件 `Host`/`Source`/`Env` 接口
  均为宿主注入点，换实现不改组件。

## 7. 测试指南（JVM）

逻辑层组件零 android 依赖（除 `android.util.Log`/`MediaRecorder` 等平台工具类），
可对纯逻辑类（MessageRouteDecider / RegenerationPlanner / ChatMessageSender /
ChatTextUtils / GenerationStreamController 等）直接写 JUnit：

```java
@Before public void setup() {
    AppServices.reset();
    AppServices.install(mockStringProvider);   // 或覆盖需要的接口
}
// 断言决策/构建/编排结果，不触 Android 运行时
```

## 8. 版本记录

- `c1f2512` SPI 解耦（逻辑层零 Context）
- `6c77387` ChatShellView 验证页装机通过
- `0d48909` 渲染层 UI 5 组件
- `144921d` 渲染层状态条 3 组件
- `0f570bc` A 档 5 编排（逻辑层收官）
- 早期：`501dd1f`/`a4d4cc2`/`50384da`/`726e85f`
