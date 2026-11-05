# 死代码与未接线能力台账（2026-11-06）

这份文档是给**人复核**和**以后我自己接着用**的：本轮删了什么、按什么规则删、哪些看着像死码但其实有活的权威、哪些是真没接线的生产能力、哪些是刻意停用别去补，以及怎么复现这套扫描、怎么单独恢复某一条。同一口径的前一轮对话侧清理见 `docs/chat-dead-code-cleanup-2026-10-29.md`。

本轮在这条分支上的连续提交（提交号与规模见第 10 节），死代码清理本身是两笔：

- `8e2ce6b1` — 第一轮：零引用的私有方法与私有常量，100 个文件、−1827 行、纯删除
- `c46bb05d` — 第二轮：删完上游后新暴露的连带孤儿，迭代到不动点，9 个文件、−260 行（+14 是改写一处误导性类注释）

另三笔是配套改动：`bc39200d`（浏览器鉴权接线 + 删掉一个从未被调用的 207 行旧初始化路径）、`608bd69f`（模型详情延迟不再拿 0ms 冒充实测 + 清掉 `last_used_*` 近似撞名残留）、`8dbaf52e`（`linux_shell` 补回注册——扫描时撞出来的"已实现但不可达"，见第 6b 节）。

## 1. 判据与盲区

只处理**文件内可证死**的两类成员：`private` 方法、`private static final` 常量。私有成员的外部不可见性由编译器保证，所以一条判据就够：**该标识符在全文件出现次数 ≤ 1**（即除了它自己的声明，文件里没有任何地方提它）。这一条同时挡掉三类经典误判：

- 同名撞车：别处有同名方法（例如 `WebViewActivity.loadUrlDelayed` 与 `WebViewLoadManager.loadUrlDelayed` 是两回事）；
- 点号后视：`obj.getFileNameFromUri(...)` 也算一次出现，不会漏数；
- 方法引用与按名字的反射：`this::name`、字符串 `"name"` 同样计入出现次数。

主动排除、不删的入口（它们零引用也不代表死）：带 `@JavascriptInterface`（JS 按名调用）、`@Override`、`@Subscribe` 的方法；带 `@SerializedName`/Room 注解的字段；**所有实例字段**（Gson/Room 按字段名映射，删了会改持久化形状）。无用 import 一律不碰（纯卫生噪声，会淹没真实改动）。整类删除不在本轮范围，候选与假阳性类型见第 7 节。

## 2. 本轮删除的规模与验证

100 个私有方法 + 65 个私有常量，100 个文件，`git diff` 为 **1827 行删除、0 行新增**。验证靠编译器 + 全量单测：

- `compileDebugJavaWithJavac` 通过（私有成员可达性由编译器定案）；
- `testDebugUnitTest --offline`：**1736 tests / 1 skipped / 0 failures / 0 errors**（1 skipped 是存量，非本轮引入）；
- 每一轮删完都重新出包装机核对 md5；当前设备上是最新的 `878e30c423c8b3586cd4c7e5af6dff81`（序列见第 10 节）。

## 3. 完整删除台账

列含义：方法数 | 常量数 | 删除行数 | 成员名。

| 文件 | 方法 | 常量 | 行 | 成员 |
| --- | --- | --- | --- | --- |
| `src/main/java/com/oilquiz/app/MainActivity.java` | 6 | 0 | 155 | getAvailableMemory, getAvailableStorage, getScreenResolution, getVersionName, runTests, showEnvironmentInfo |
| `src/main/java/com/oilquiz/app/SimpleWebViewActivity.java` | 1 | 0 | 13 | openGenericPreview |
| `src/main/java/com/oilquiz/app/SmartQuizApplication.java` | 2 | 0 | 12 | getCrashCount, incrementCrashCount |
| `src/main/java/com/oilquiz/app/WebViewActivity.java` | 2 | 0 | 41 | getFileNameFromUri, loadUrlDelayed |
| `src/main/java/com/oilquiz/app/ai/agent/ResponseCacheManager.java` | 0 | 1 | 2 | CACHE_EXPIRY_MS |
| `src/main/java/com/oilquiz/app/ai/agent/SmartIntentRecognizer.java` | 0 | 1 | 1 | CONFIDENCE_HIGH |
| `src/main/java/com/oilquiz/app/ai/agent/ToolResultInterpreter.java` | 2 | 1 | 48 | countResults, truncate, INTERPRET_OUTPUT_MAX |
| `src/main/java/com/oilquiz/app/ai/agent/online/OnlineAgentEngine.java` | 2 | 0 | 29 | containsAnyCasual, extractToolNames |
| `src/main/java/com/oilquiz/app/ai/agent/software/AgentSoftwareLayer.java` | 2 | 0 | 20 | countTokensSafe, notifyInferenceProgress |
| `src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java` | 5 | 2 | 80 | formatCurrentTime, isThinkingModel, resolveArgsObject, resolveCallId, toolResultInjectionLimitChars, PROMPT_BUDGET_RATIO, UI_WAIT_MS |
| `src/main/java/com/oilquiz/app/ai/agent/ui/AgentMemoryView.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/chat/ChatAdapter.java` | 1 | 0 | 6 | scrollToPosition |
| `src/main/java/com/oilquiz/app/ai/chat/InferenceStateManager.java` | 1 | 0 | 17 | calculateDefaultProgress |
| `src/main/java/com/oilquiz/app/ai/chat/coordination/AIChatCoordinator.java` | 1 | 0 | 8 | notifyChatHistoryChanged |
| `src/main/java/com/oilquiz/app/ai/chat/input/AttachmentProcessor.java` | 0 | 1 | 1 | SUMMARY_CACHE_EXPIRY_MS |
| `src/main/java/com/oilquiz/app/ai/chat/ui/ChatBottomSheet.java` | 1 | 0 | 7 | rounded |
| `src/main/java/com/oilquiz/app/ai/config/DataValidator.java` | 0 | 1 | 1 | EMAIL_PATTERN |
| `src/main/java/com/oilquiz/app/ai/engine/ALChat.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/gpu/GpuAdaptiveTuner.java` | 0 | 1 | 2 | MIN_TPS_IMPROVEMENT |
| `src/main/java/com/oilquiz/app/ai/gpu/GpuCapabilityDetector.java` | 3 | 0 | 42 | checkFP16Support, checkVulkanSupport, detectApiVersion |
| `src/main/java/com/oilquiz/app/ai/importing/ExcelSheetPicker.java` | 1 | 0 | 4 | chineseDigitToIndex |
| `src/main/java/com/oilquiz/app/ai/importing/v2/ImportCsvIngestor.java` | 0 | 1 | 2 | SMALL_BATCH_SIZE |
| `src/main/java/com/oilquiz/app/ai/model/ModelCacheManager.java` | 1 | 1 | 10 | validateBuffer, BUFFER_SIZE |
| `src/main/java/com/oilquiz/app/ai/model/ModelChunkLoader.java` | 0 | 1 | 2 | LOAD_TIMEOUT_MS |
| `src/main/java/com/oilquiz/app/ai/model/ModelStateCache.java` | 1 | 2 | 34 | restoreChatContext, KEY_CHAT_HISTORY_SIZE, KEY_MODEL_PATH |
| `src/main/java/com/oilquiz/app/ai/model/MultiModelManager.java` | 0 | 1 | 2 | DEFAULT_TIMEOUT |
| `src/main/java/com/oilquiz/app/ai/optimization/ResourceConfig.java` | 0 | 1 | 1 | DEFAULT_CONTEXT_SIZE |
| `src/main/java/com/oilquiz/app/ai/refactor/AIInferenceCore.java` | 1 | 0 | 22 | buildPromptFromMessages |
| `src/main/java/com/oilquiz/app/ai/service/AIService.java` | 12 | 2 | 275 | applyAdrenoOptimizations, applyXiaomi14Optimizations, autoTuneBasedOnCapabilities, checkGpuSupport, compressHistory, endInferenceTiming, getCurrentDate, getModelNameForPrompt, initDefaultChatContext, safeMemoryPoolSize, startInferenceTiming, updateProgress, N_CTX_HIGH_END, N_CTX_LOW_MEMORY |
| `src/main/java/com/oilquiz/app/ai/service/AIServiceInitializer.java` | 1 | 0 | 9 | isComplete |
| `src/main/java/com/oilquiz/app/ai/service/AgentService.java` | 2 | 0 | 11 | buildDynamicToolSchemas, hasToolSchema |
| `src/main/java/com/oilquiz/app/ai/service/ModelPreloadService.java` | 0 | 1 | 1 | MAX_PRELOAD_MODELS |
| `src/main/java/com/oilquiz/app/ai/sessionlog/AgentEventBridge.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/speech/AudioPcmDecoder.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/speech/asr/IflytekAsrEngine.java` | 0 | 2 | 3 | DEFAULT_AUE, DEFAULT_ENGINE_TYPE |
| `src/main/java/com/oilquiz/app/ai/speech/tts/OpenAiTtsEngine.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/AIToolUsageGuide.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/AIWeatherManager.java` | 5 | 0 | 116 | fetchAllIndices, getApiHost, getCoordinatesFromLocationId, getMinutelyAITool, getQWeatherApiKey |
| `src/main/java/com/oilquiz/app/ai/tool/CalculatorTool.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/ControlLookupTool.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/DashscopeMediaTool.java` | 2 | 0 | 12 | formatSize, resolveBailianBaseUrl |
| `src/main/java/com/oilquiz/app/ai/tool/GetModelsProfileTool.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/NetworkSearchTool.java` | 3 | 0 | 28 | extractUrls, getDomain, isDynamicPage |
| `src/main/java/com/oilquiz/app/ai/tool/ScreenCaptureTool.java` | 0 | 1 | 2 | MAIN |
| `src/main/java/com/oilquiz/app/ai/tool/SystemResourceTool.java` | 0 | 3 | 7 | DEVICE_WRITE, OPENSSL_BIN_LIB, SAFE_DEV_TARGETS |
| `src/main/java/com/oilquiz/app/ai/tool/TermuxEnvInstaller.java` | 1 | 0 | 13 | joinShell |
| `src/main/java/com/oilquiz/app/ai/tool/TermuxEnvSetupActivity.java` | 1 | 0 | 9 | styleStepBtn |
| `src/main/java/com/oilquiz/app/ai/tool/TextToolsTool.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/TimeDateTool.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/UnitConverterTool.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/tool/UpdateModelsProfileTool.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/usage/db/repository/UsageRepository.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/ai/util/ApiBalanceChecker.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ai/util/NetworkUtil.java` | 1 | 0 | 4 | getRandomAcceptEncoding |
| `src/main/java/com/oilquiz/app/database/DatabaseVersionChecker.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/infra/AppLogger.java` | 1 | 2 | 11 | trimLogFile, MAX_CRASH_LOG_LINES, MAX_LOG_LINES |
| `src/main/java/com/oilquiz/app/infra/GlobalExceptionHandler.java` | 2 | 0 | 95 | saveCrashLog, saveErrorLog |
| `src/main/java/com/oilquiz/app/manager/ConfigManager.java` | 0 | 1 | 2 | KEY_APP_SETTINGS |
| `src/main/java/com/oilquiz/app/resource/FontResourceProvider.java` | 1 | 0 | 17 | loadFontFromResource |
| `src/main/java/com/oilquiz/app/resource/ResourceManager.java` | 1 | 0 | 9 | notifyResourceChanged |
| `src/main/java/com/oilquiz/app/resource/SystemUIResourceAdapter.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/toolkit/AppToolkit.java` | 1 | 0 | 16 | toImageFormat |
| `src/main/java/com/oilquiz/app/ui/accessibility/AccessibilityHelper.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java` | 4 | 0 | 44 | parseParameters, refreshAttachmentsUI, releaseMediaRecorder, updateRecordingUI |
| `src/main/java/com/oilquiz/app/ui/activity/AIImportActivity.java` | 1 | 0 | 18 | requestPublicStoragePermission |
| `src/main/java/com/oilquiz/app/ui/activity/AIServiceStatusActivity.java` | 2 | 0 | 37 | scanNpuModelFile, setRowText |
| `src/main/java/com/oilquiz/app/ui/activity/ApiConfigActivity.java` | 0 | 1 | 1 | REQUEST_CODE_EXPORT_FILE |
| `src/main/java/com/oilquiz/app/ui/activity/BackupActivity.java` | 0 | 1 | 2 | REQUEST_CODE_STORAGE_PERMISSION |
| `src/main/java/com/oilquiz/app/ui/activity/ExportActivity.java` | 1 | 1 | 19 | previewWebViewFile, TAG |
| `src/main/java/com/oilquiz/app/ui/activity/ImportActivity.java` | 2 | 0 | 36 | detectDataIssuesAndProceed, showProgressCard |
| `src/main/java/com/oilquiz/app/ui/activity/ModelDownloadActivity.java` | 2 | 2 | 18 | formatEta, loadOnlineModels, HUGGINGFACE_API, TAG |
| `src/main/java/com/oilquiz/app/ui/activity/ModelImportActivity.java` | 0 | 1 | 2 | PERMISSION_REQUEST_CODE |
| `src/main/java/com/oilquiz/app/ui/activity/OCRActivity.java` | 0 | 1 | 2 | CAMERA_PERMISSION_REQUEST |
| `src/main/java/com/oilquiz/app/ui/activity/QuestionGenerateActivity.java` | 1 | 0 | 10 | getServiceTypeDisplay |
| `src/main/java/com/oilquiz/app/ui/activity/QuizActivity.java` | 1 | 0 | 14 | getValidOptions |
| `src/main/java/com/oilquiz/app/ui/activity/StartQuizActivity.java` | 1 | 0 | 5 | dpToPx |
| `src/main/java/com/oilquiz/app/ui/activity/StudyPlanActivity.java` | 1 | 0 | 22 | showPlanOptions |
| `src/main/java/com/oilquiz/app/ui/activity/TBSFilePreviewActivity.java` | 1 | 0 | 16 | isNetworkAvailable |
| `src/main/java/com/oilquiz/app/ui/activity/WeatherDetailActivity.java` | 3 | 0 | 34 | firstSentence, openFxLink, tempLevelText |
| `src/main/java/com/oilquiz/app/ui/activity/WeatherWebViewActivity.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ui/adapter/AttachmentAdapter.java` | 0 | 1 | 1 | TAG |
| `src/main/java/com/oilquiz/app/ui/adapter/OnlineModelAdapter.java` | 2 | 0 | 28 | formatLastUsed, formatLatency |
| `src/main/java/com/oilquiz/app/ui/export/TemplateSelectionActivity.java` | 1 | 0 | 19 | updateUIForTemplateType |
| `src/main/java/com/oilquiz/app/util/AILogger2.java` | 0 | 1 | 2 | FULL_DATE_FORMAT |
| `src/main/java/com/oilquiz/app/util/ObbHelper.java` | 0 | 1 | 1 | MAIN_EXPANSION_VERSION |
| `src/main/java/com/oilquiz/app/util/QWeatherIconMapper.java` | 1 | 0 | 16 | createEmojiDrawable |
| `src/main/java/com/oilquiz/app/util/WebGeneratorUtil.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/util/export/CSVExporter.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/util/export/JavaExporter.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/util/export/PythonExporter.java` | 0 | 1 | 2 | PROGRESS_UPDATE_INTERVAL |
| `src/main/java/com/oilquiz/app/util/export/template/TemplateManager.java` | 0 | 1 | 1 | SCHEMA_VERSION |
| `src/main/java/com/oilquiz/app/util/preview/X5FilePreviewManager.java` | 2 | 0 | 78 | getMimeType, getUriForFile |
| `src/main/java/com/oilquiz/app/util/quiz/QuizSessionManager.java` | 1 | 0 | 7 | containsQuestion |
| `src/main/java/com/oilquiz/app/util/render/ExcelUtil.java` | 1 | 0 | 14 | optStringMulti |
| `src/main/java/com/oilquiz/app/util/render/HTMLRenderEngine.java` | 2 | 0 | 58 | extractTextFromHtml, extractTitleFromHtml |
| `src/main/java/com/oilquiz/app/util/render/MarkdownRenderEngine.java` | 0 | 1 | 1 | MAX_LINES |
| `src/main/java/com/oilquiz/app/util/render/TextRenderEngine.java` | 0 | 1 | 1 | MAX_LINES |
| `src/main/java/com/oilquiz/app/weather/QWeatherSdkManager.java` | 3 | 0 | 77 | appendField, generateJwtToken, parseAirV7Response |
| `src/main/java/com/oilquiz/app/weather/WeatherDataParser.java` | 0 | 1 | 2 | TAG |
| `src/main/java/com/oilquiz/app/webview/FileRedirectManager.java` | 0 | 1 | 2 | TAG |

## 3b. 连带孤儿：迭代到不动点（第二轮，−253 行）

第一轮删完会**新暴露**一批失去唯一调用者的私有成员，所以不能只跑一遍。迭代过程：dry-run → apply → dry-run，共 4 轮才归零（第 3 轮 35 行、第 4 轮 4 行、第 5 轮 0）。累计第二轮 = 9 个文件、253 行、`ins=0`，编译与 1736 条单测再次全绿。

| 文件 | 成员 | 为什么第二轮才出现 |
| --- | --- | --- |
| `ai/agent/ToolResultInterpreter.java` | firstStr（26 行） | 它的唯一调用者在第一轮被删（`countResults`/`truncate` 同批） |
| `ai/agent/software/engine/AgentLoopEngine.java` | convertStrftime（8 行） | 同上（`formatCurrentTime` 的下游） |
| `ai/model/ModelStateCache.java` | restoreKVCache（35 行） | 恢复侧本就有意停用，注释见第 6 节；保存侧活着 |
| `ai/service/AIService.java` | calculateHistoryTokenCount（13 行）、updateInferenceStats（20 行）、calculatePerformanceLevel（9 行）、applyPerformanceOptimization（25 行） | `updateProgress`/`startInferenceTiming`/`autoTuneBasedOnCapabilities` 等上游在第一轮被删 |
| `ai/service/AIServiceInitializer.java` | INTEGRITY_THRESHOLD（2 行） | 上游 `isComplete` 被删 |
| `ai/tool/NetworkSearchTool.java` | URL_PATTERN（1 行） | 上游 `extractUrls` 被删 |
| `ai/util/NetworkUtil.java` | ACCEPT_ENCODING_VALUES（6 行） | 上游 `getRandomAcceptEncoding` 被删 |
| `infra/GlobalExceptionHandler.java` | saveLog（52 行）、cleanupOldFiles（18 行）、formatBytes（15 行）、MAX_LOG_FILES、MAX_LOG_FILE_SIZE | 上游 `saveCrashLog`/`saveErrorLog` 被删，整套自管日志落盘随之成孤儿 |
| `ui/activity/AIImportActivity.java` | openAllFilesAccessSetting（16 行） | 上游 `requestPublicStoragePermission` 被删；活的"所有文件访问"设置入口另有两处（`AgentWorkspaceView:499`、`PermissionManagerTool:411`），所以是重复而非缺口 |

## 4. 逐条复核：看着"承载能力"的，为什么仍判没价值

判据只有一条：**除了这个没人调的实现，仓库里是否已经有活的权威实现在做同一件事**。有 → 它是重构遗留的第二套写法，删；没有 → 那是没起步的功能，属于第 5 节，不该靠留一个空函数来"保线索"。以下 13 组逐个查过（行号为当前 HEAD）。

| 删除项 | 活的权威 / 证据 | 判定 |
| --- | --- | --- |
| `AIService.applyXiaomi14Optimizations` / `applyAdrenoOptimizations` / `autoTuneBasedOnCapabilities` / `checkGpuSupport` / `safeMemoryPoolSize` / `N_CTX_LOW_MEMORY` / `N_CTX_HIGH_END` | 采样与 n_ctx 由 `UserModelParamsManager` + 装载策略决定；HTP/OpenCL 侧已按实测调满 | 没价值。原样复活会覆盖手动调好的值；要做应按当前引擎现实重写 |
| `AIService.compressHistory` | `generate/generateStream` 里的 `adjustedMaxTokens`（1552/1665）是活的预算路径 | 没价值 |
| `GlobalExceptionHandler.saveCrashLog` / `saveErrorLog`（95 行带横幅的崩溃报告） | `uncaughtException` 实际调 `AppLogger.logCrash`（237）；那份横幅报告从未落过盘 | 没价值（但记一笔：那个"漂亮崩溃报告"一直是死的） |
| `SmartQuizApplication.getCrashCount` / `incrementCrashCount` | 死亡归因走 `recordJavaCrashForAttribution`（547，由 GlobalExceptionHandler:56 调用） | 没价值 |
| `AppLogger.trimLogFile` / `MAX_LOG_LINES` / `MAX_CRASH_LOG_LINES` | 活的轮转是 `rotateLogFile`（464）+ `trimLogFileInternal`（505），按大小 1MB/5MB 而非行数 | 没价值 |
| `AgentService.buildDynamicToolSchemas` / `hasToolSchema` | 权威是 `AIToolManager.getOpenAIToolDefinitions()`（676），`AgentService.getAgentOpenAIToolDefinitions`（1535）就转调它 | 没价值。MCP 注入取向未定，但那题是"注入哪些工具"，不是"再造一个 builder" |
| `AIWeatherManager.getApiHost` / `getQWeatherApiKey` / `fetchAllIndices` / `getMinutelyAITool` | 活代码直接用 `apiHost` 字段拼 v1/v7 端点（AIWeatherManager 66–92，注释写明公共主机 403） | 没价值 |
| `QWeatherSdkManager.generateJwtToken` / `parseAirV7Response` / `appendField` | 签名与解析在 SDK 路径里完成，这三个无消费者 | 没价值 |
| `ExcelSheetPicker.chineseDigitToIndex` | 同文件 445–455 有活的中文数字→A~J 内联实现 | 没价值 |
| `AIInferenceCore.buildPromptFromMessages` | 同类 145–211 有活的逐条构造 `PromptBuilder.Message` 的路径 | 没价值 |
| `AgentLoopEngine.resolveCallId` / `resolveArgsObject` | 2103 处"解析 tool_call 事件（id/name/arguments）"是活的 | 没价值 |
| `GpuCapabilityDetector.checkVulkanSupport` / `checkFP16Support` / `detectApiVersion` | 现在这条路是 `detectGpuInfo()`（29 起）读 GPU profile JSON 的**声明值**（`json.optBoolean("supportsFP16", false)`、`supportsVulkan`）+ 型号表兜底；被删的三个是**运行时探测**，从未接进这条路径 | 判没价值（保留删除），但它不是"重复实现"而是"实测被声明表顶掉"——见第 6 节的缺口 |
| `TemplateSelectionActivity.updateUIForTemplateType` | `template_type_toggle` 被显式 `setVisibility(GONE)`（77–78） | 没价值（属"假选项"类：控件藏着 + 处理函数没人调） |

其余 ~80 条同属两类：工具类的 `TAG`/请求码/超时等被替换掉的常量、以及各 Activity/Adapter 里被新路径顶掉的私有小工具（`dpToPx`、`rounded`、`setRowText`、`formatSize`、`optStringMulti` 等），逐条都符合"本文件只出现一次"。

## 5. 捞回并接上的（一）：模型详情的延迟，它暴露了一个假数据

（第二处接回是 `linux_shell`，在第 6b 节——它不是"看着像死码"，而是整类被扫描时撞出来的不可达能力。）

`OnlineModelAdapter` 的模型详情弹框原本无条件显示 `"延迟: " + item.latencyMs + "ms"`。查数据链：

- 实时喂数据的是 `updateData(List<OnlineModelConfig>, String activeId)`（203），这条路里 `latencyMs` 写死 0，代码注释自己写着 `// latency unknown initially`（304–305）；
- 能接收实测值的 `updateModelStatus(String, int, long)`（404）**全仓零调用方**（其他文件里的同名命中是 `AIServiceStatusActivity`/`ModelImportActivity` 各自的私有方法，属同名撞车）；
- 真正会探测的是 `ModelListFetcher.testConnection(apiUrl, apiKey)`（743），但它的结果没回流到模型列表。

也就是说那行 `0ms` 是拿"没测"冒充实测。`608bd69f` 的处理：未探测时明确写 **"未探测"**，有值时才用 `formatLatency`（毫秒→秒）——`formatLatency` 是本轮唯一被判定"有真实消费者"而捞回接上的格式化函数。

同批不捞回并顺手清掉的：`formatLastUsed`（`lastUsedTime` 一个显示消费者都没有，按"不留没生产者的渲染器"处理），以及它引用的 4 条资源 `last_used_days/hours/minutes/just_now`（中英繁共 12 行）。后四条还与 `ModelRegistry` 里活的 JSON 键 `last_used_date` 近似撞名，正是最容易把人看错的那类残留。

## 6. 未接线的生产能力（不是死码，是缺口，等你拍板）

| 缺口 | 现状证据 | 缺的那一步 |
| --- | --- | --- |
| 在线模型列表的"延迟" | 探测有（`ModelListFetcher.testConnection:743`）、回写口有（`OnlineModelAdapter.updateModelStatus:404`）、格式化有（本轮捞回的 `formatLatency`），中间没人接；"测试连接"菜单项现在只是转成 `onFetchModelsClick`（拉模型列表），不测也不回填 | 点"测试连接"时跑一次探测并计时，回填 `updateModelStatus`。需要把 API key 传到适配器（现在 `DisplayItem` 只有 apiUrl），所以算一次小改动 |
| 在线模型列表的"最近使用" | `DisplayItem.lastUsedTime` 与 `OnlineModel.lastUsedTime` 字段在，实时路径写死 0，`new OnlineModel(` 全仓零构造点（`OnlineModel` 这个嵌套类连同 `buildDisplayItemsFromOnlineModel`、`updateData(List<OnlineModel>)` 属死支） | 先定"要不要显示最近使用"，再定数据从哪来（现在没有任何地方记录每个在线模型的使用时刻） |
| 服务状态页的"当前模型文件" | `AIServiceStatusActivity.scanNpuModelFile` 已删，且现在**没有任何活的等价实现**——状态页从来没有这一行 | 决定要不要给这行；要做就从引擎侧取真实加载路径，别拿预设文件名当实测 |
| 学习计划的条目操作 | `StudyPlanActivity.showPlanOptions` 已删，文件里没有活的 `PopupMenu`/长按菜单等价实现 | 决定条目该有哪些动作（编辑/删除/重排），再补 |
| 机型自动调优 | 整条链（第 4 节第 1 行）从未执行过 | 如果还想要"自动"，需要按 HTP/OpenCL 现状重新设计并实测，不是把旧函数接回去 |
| 模板类型切换 | RadioGroup 被显式隐藏 + 处理函数死掉 | 要么删控件与残留，要么让它真驱动界面（现在是"两按钮同去处"那种假区分） |
| 崩溃报告横幅 | 删掉的两套写法（`saveCrashLog/saveErrorLog`）从未生效 | 如果想要可分享的崩溃报告，应在 `AppLogger.logCrash` 那一处加格式，不是留第二套 |
| GPU 能力（Vulkan/FP16）现在是"配置文件说了算" | `GpuCapabilityDetector.detectGpuInfo()` 读 profile JSON 的 `supportsFP16/supportsVulkan`（缺省 false）与型号表；同类的三个运行时探测函数一直没人接，本轮已删（第 4 节） | 真机口径应只信引擎自己打的 backend switch 行；要"实测"就得把探测接回 `detectGpuInfo`，否则配置写错就是静默错判 |
| **本地路径的历史 token 预算** | `AIService.calculateHistoryTokenCount` 从未被调用（本轮连带删除）；活的预算实现只在在线侧 `OnlineAgentEngine`（92、1235–1264）与 `AgentLoopEngine`（636、2317、2393） | 这就是引擎基础设施台账里"仍缺上下文预算"那条的具体落点：本地侧要接，得新写（旧函数只是估算思路，可作参考），别指望"接回来"就能用 |
| **性能分级调优** | `calculatePerformanceLevel` / `applyPerformanceOptimization`（本轮第三轮连带删）从未接入推理参数设置 | 与"机型自动调优"同源，属于没起步的自动分级；要做就按 HTP/OpenCL 实测基线重设，不是复活旧阈值表 |

### 6b. 本轮接回的一条：`linux_shell`（已实现的能力，只差一行注册）

死类交叉核对时撞出来的，性质和第 7 节其他候选不同——它不是该删的死类，而是**该接的活类**：

- `OnlineToolManager` 的 system 意图映射（269）与两处 include 列表（415/425）都写着 `linux_shell`；
- `SystemResourceTool` 的**动作描述本身**在教模型用它：`shell_command` 写"与 linux_shell(action=exec) 同一套环境"，`termux_exec` 写"不确定先调 linux_shell(action=termux_wake) 或 termux_status""长任务改用 linux_shell(action=termux_submit)"；
- 工作区指南 `AgentWorkspace` 有整整一节（965–1000）讲它的工具集、路由、termux 引擎；
- 还有 `src/androidTest/.../SystemResourceToolDeviceTest` 在用；
- `LinuxShellTool`（248 行）实现完整、`@Tool(value="linux_shell")`、`getName()` 就是 `linux_shell`、环境复用 `SystemResourceTool`。

唯独 `AIToolManager` 少了 `registerToolFactory("linux_shell", ...)` 这一行，所以模型照文案调用只会拿到"工具不存在"，而 `route`/`tools`/`termux_wake`/`termux_status`/`termux_submit` 这几个动作在别处也没有实现。

上一轮的处置是把 `linux_shell` 从【工具目录】里摘掉，并留下注释"全仓没有 registerToolFactory，列在目录里等于教模型点名一个不存在的工具"——**那是藏症状**：注入层、工具描述、指南文案、测试全都还指着它，摘目录只会让能力消失。本轮按"修产出侧而非逐个消费端"补注册（`8dbaf52e`），并把目录那一行加回去（`linux_shell` 不在 `coreTools`，属"按需发现"档，加回来文案才不是幽灵）、改写那段过期注释。代价是工具定义库多一个可发现工具（注入面 +1，前缀重算一次），按"能力优先"的口径这不是理由。

验证：`AIToolManager.class` 常量池已引用 `LinuxShellTool`；`assist.txt` 只多了目录里那一段（`takeover.txt` 无变化）；1736 tests / 1 skipped / 0 failures；装机 `878e30c423c8b3586cd4c7e5af6dff81`。**未验**：真机上让模型跑一次 `linux_shell(action=tools)` 才算闭环。



### 6c. 刻意停用，别当缺口去补

`ModelStateCache` 是个反例，值得单独记：它的类注释原来列了 5 项"功能"，其中**恢复侧两项从未接线**——但不是忘了接：`AIService` 构造函数里那段 `restoreModelState` 是**被注释掉的**（约 176–186 行，理由写在注释里：进程被杀后 START_STICKY 重启会再自动装载模型，撞上同一处崩溃就成了崩溃循环，所以改成用户进 AI 页时按需装载）。所以 `restoreKVCache`/`restoreChatContext` 是这套有意停用之后的孤儿，本轮删掉；活的 KV 记忆恢复在 `LlamaHelper`（从字节数组恢复 + 基于记忆续写）。类注释已按实情改写（保留"接上了哪三件"与"哪两件是刻意停用"）。

这条的教训：**看到"零引用的恢复/回滚路径"先查是不是刻意停用**，别顺手把它接上——那正好会把崩溃循环接回来。

## 7. 整类零引用：闸放行的 63 个已删（-14,179 行），6 个卡在闸上留着

判据与实跑都在 `docs/scripts/dead_class_verify.py`（定位声明→查入口注解→整词检索，含 c/cpp/h/cmake）
，再加一道"名字在别处还有没有命中"的闸（`grep -rlw <Name> src build.gradle app`，排除 `*.md` 与自身文件）。
2026-11-06 深夜执行：63 个放行、已删，纯删除形状（14,179 行，diff insertions=0），
编译过、全量 1746/0/0/1、删完重跑私有成员扫描归零。

**6 个卡在闸上没删**（有命中就要人判，机器不替）：

- `GpuAdaptiveTuner` — GpuAdaptiveTuner.java
- `AgentExecutionEngine` — AgentExecutionEngine.java
- `TypeConverter` — TypeConverter.java
- `AIFileExporter` — AIFileExporter.java
- `ModelHotSwitcher` — ModelHotSwitcher.java
- `GgmlProbe` — GgmlProbe.java

命中来源分两种：`GgmlProbe` 命中在 `src/main/cpp/ggml-probe.cpp`（native 侧真按这个名字有牵连，
删前得读那段 C++ 确认是引用还是注释）；`TypeConverter` 命中在打包进来的 LibreOffice 资产
（`program/services.rdb`，和已剔除的 `Translator` 同类）；另外四条（`GpuAdaptiveTuner`、
`AgentExecutionEngine`、`AIFileExporter`、`ModelHotSwitcher`）命中在 `src/main/assets/pages/
question-renderer.html`——那个页面里出现这些词得单独看一眼是不是字符串约定，不能当零引用处理。

资源侧这轮跑了（`docs/scripts/res_orphan_scan.py`）：**186 个候选**（anim 7、drawable 108、其余为 layout/color/menu/xml）。

**但这 186 个不是删除清单，是队列**——判据比 Java 侧多三处必须过的坎，脚本现在只做到了"标注"：
1. ViewBinding（本工程开着）：布局 `foo_bar.xml` 在代码里以 `FooBarBinding` 出现，整条链上不出现 `R.layout.foo_bar`，
   已把 `<Camel>Binding` 纳入检索，否则活布局会被判成孤儿；
2. 运行时拼名：代码里有 248 个以下划线结尾的字符串前缀，任何以它们开头的资源都不能按整词检索定生死；
3. **`getIdentifier` 动态取名**：本仓库有三处按字符串取 drawable（`QuestionAdapter:290`、`QuizActivity:1014`、
   `QWeatherIconMapper:358`——最后那个是天气图标包按码名映射），所以 drawable 这一整类只靠静态检索
   判孤儿一定不准，脚本对它标"需人工"而不是放行。

结论：anim/layout/menu/xml/color 里那些"名字不像拼出来的、且 @ 引用与 Binding 都查不到"的可以进删除队列，
drawable 那 108 个要先按 `QWeatherIconMapper` 的名字表和 `imageUri` 传参路径核一轮才能动。

## 8. 怎么复现这套扫描

工具已入库：`docs/scripts/deadcode_scan.py`（不参与构建）。

```bash
python docs/scripts/deadcode_scan.py          # dry-run：打印文件/成员/行数计划，不落盘
python docs/scripts/deadcode_scan.py --apply  # 按刚才的计划删除
# 关键：删完必须再跑一次 dry-run，还有输出就再 --apply，直到输出 files 0 —— 第一轮删掉上游调用者后，
# 下游私有成员才变成零引用（本轮就是这样跑了 5 轮才归零）
```

规则（写代码前先照着定，别凭感觉 grep）：

1. 候选 = 声明行匹配 `private …name(` 或 `private static final TYPE NAME =`；
2. 计数 = 该标识符在**全文件**的出现次数（含 `obj.name`、`this::name`、字符串字面量、注释），≤1 才算死；
3. 排除注解入口：`@JavascriptInterface`/`@Override`/`@Subscribe`/`@Nullable`/`@NonNull`（后两个是我加的保守项，可能挡掉少量真死码）；
4. 排除持久化相关字段：`@SerializedName`/`@ColumnInfo`/`@Expose`/`@PrimaryKey`/Room；**实例字段本轮一律不动**；
5. 定界删除必须用**跨行状态机**判注释/字符串，再用括号配平找方法尾，护栏四条：收尾行按字节等 `    }`（不能 `strip()=='}'`）、区间内断言方法体特征串、区间长度上限、重叠则整文件跳过；先 dry-run 打印计划再 `--apply`；
6. 验收：`git diff --numstat` 对纯删除任务必须 insertions=0；然后编译器 + 全量单测。

本轮踩到的两个坑，别在下一轮重犯（都已当场抓回、未进提交）：把方法收尾判成 `strip()=='}'` 会命中内层缩进的 `}`，一次误删了 `WebViewActivity` 约 3200 行；给常量复用方法定界函数会算出 177 行的"TAG 常量"。

## 9. 怎么单独恢复某一条

```bash
# 看某文件被删的原文（- 行就是被删的代码）
git show 8e2ce6b1 -- src/main/java/com/oilquiz/app/ai/service/AIService.java

# 取删除前那一版的整份文件
git show 8e2ce6b1^:src/main/java/com/oilquiz/app/ai/service/AIService.java > /tmp/before.java

# 只把某个方法接回去：从上面那份原文里摘出方法体，再补上调用点（否则又是一次"没生产者"）
```

`formatLatency` 就是走这条路径捞回并接上的（`608bd69f`）。

## 10. 本轮提交序列与当前状态

| 提交 | 内容 | 规模 |
| --- | --- | --- |
| `bc39200d` | feat(browser)：接通站点鉴权与证书处置，清掉没人走的传统初始化路径 | 14 文件，+619/−214 |
| `8e2ce6b1` | chore(dead-code)：第一轮，零引用的私有方法与私有常量 | 100 文件，−1827（纯删除） |
| `608bd69f` | fix(ui)：模型详情延迟不再拿 0ms 冒充实测 + 删 `last_used_*` 残留 | 4 文件，+14/−13 |
| `c46bb05d` | chore(dead-code)：第二轮连带孤儿，迭代到不动点 + ModelStateCache 注释改写 | 9 文件，+14/−260 |
| `8dbaf52e` | fix(tool)：linux_shell 补回注册（见第 6b 节）+ 目录加回 + 过期注释改写 + golden 目录行 | 3 文件，+10/−3 |
| 本文档 | docs + `docs/scripts/deadcode_scan.py` | — |

死代码净规模：第一轮 100 个方法 + 65 个常量（−1827 行，纯删除），第二轮 11 个方法 + 8 个常量（成员 −253 行；该笔提交 +14/−260，差额是 ModelStateCache 的类注释改写）。两轮合计 **207 个成员、约 2080 行**。装机版本 `878e30c423c8b3586cd4c7e5af6dff81`（设备侧 `base.apk` md5 已回读一致，25113PN0EC / Android 17）。

远端（`git ls-remote` 当场回读，不是看 push 的输出）：`github` 与 `origin`(gitee) 的这条分支都停在 `61d1f9d6`，本地 `669040b2`，**领先 6 条、全部未推**（`bc39200d`、`8e2ce6b1`、`608bd69f`、`c46bb05d`、`8dbaf52e`、`669040b2`）。相对 `61d1f9d6` 的合计规模：108 文件，+519/−2103。两条远端都是本地 HEAD 的祖先，推时是纯快进、不需要 `--force`。

## 11. 已验 / 未验

已验：`compileDebugJavaWithJavac` 与全量 **1736 tests / 1 skipped / 0 failures / 0 errors**（每轮删除后都重跑，1 skipped 是存量）；两轮 diff 均为纯删除形状（`--numstat` insertions=0，第二轮的 +14 是注释改写）；扫描判据跑到不动点；装机包 md5 与设备侧一致；第 4 节每条都给了活的权威位置与行号；第 7 节候选跨 `src/main/java`、`res`、`AndroidManifest.xml`、`src/main/python` 检索，并用两种独立实现（全文件正则 vs 全仓整词 grep）互相印证（结果一致，76 个类 / 约 17.6k 行）。

未验：本轮**没有做真机行为验证**。删除本身是纯可达性操作，但三件事需要在机器上点一次才算闭环——① 模型选择页 → 某模型 → "查看详情"，看延迟那行现在显示"未探测"；② `linux_shell` 接回注册后，让模型跑一次 `linux_shell(action=tools)` 确认能返回内置工具清单（`route`/`termux_wake`/`termux_status`/`termux_submit` 也顺带可达）；③ 崩溃恢复链路（`GlobalExceptionHandler` 自管日志被整删，实际落盘走 `AppLogger.logCrash`）在真机制造一次崩溃后确认日志仍在。第 7 节的 75 个整类候选**一个都没删**，其中 DI/反射/手动跑测试/仅测试引用四类假阳性尚未逐个确认。

### 追加处置（2026-11-06 晚，空 catch 批 4c 同轮）

- `ai/agent/ToolExecutionOrchestrator`（243 行）**已删**。类注释自证"没有任何调用点"，且 `Host`
  无人实现；真实链路是 `AIChatActivity` 里的同名两份实现（`executeToolWithRecovery` 2633 起、
  `retryCompositeStepExecution` 3421 起）。判据：全仓 grep 只剩它自己 + 历史文档；编译器为准。
- `util/AILogger2`（670 行）已整类删除（见空 catch 台账第 15 节）；`AILogger` 的可视化那半
  （1033→657 行）同轮删除（第 16 节）——两处都是"零调用 + 零生产者 + 有在跑的替代品"。
