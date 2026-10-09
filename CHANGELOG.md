# 变更日志

## [2026-10-09] Hexagon NPU 后端打通 + 工具调用泄漏修复 + Agent UI 状态修复 + 设备信息页重做

本轮让**本工程自己的 llama.cpp 真正跑在 Hexagon NPU 上**（此前 NPU 能力只在 GenieX AAR 里），
并修掉三个由解析/状态匹配缺陷导致的 UI 故障，最后重做了设备信息页。

### 1) Hexagon NPU 后端打通（f914ffae, a809b47）

- **上游 vendored llama.cpp 改动**（`ggml/src/ggml-hexagon/`）：
  - `PREBUILT_LIB_DIR` 给非空默认值 —— SDK 的 `hexagon_fun.cmake` 里
    `string(FIND ${PREBUILT_LIB_DIR} "toolv81" ...)` 在变量为空时退化成参数不足直接报错。
  - `add_dependencies(${TARGET_NAME} ${HTP_PROJECTS})` —— Gradle 的 externalNativeBuild 调用的是
    `ninja <target>` 而非 `ninja all`，HTP skel 的 ExternalProject 从不在构建图里，表现为
    `Error copying file ... libggml-htp-v73.so`。
  - 允许 HTP 子工程使用更新的 CMake —— 它要求 3.22.2，而 Android SDK 随附 3.22.1。
  - `setenv("ADSP_LIBRARY_PATH", lib_dir, 1)`（由 `GGML_HEXAGON_LIB_DIR` 传入）——
    cdsprpcd（`vendor_cdsprpcd`）读不了应用私目录，上游的相对 URI
    `file:///libggml-htp-vNN.so` 无法解析。
- **工程侧**：
  - `HEXAGON_SDK_ROOT` / `HEXAGON_TOOLS_ROOT` 必须作为 **CMake 变量**传入（ggml-hexagon 读变量不读环境变量）。
  - 删除 `setenv("GGML_BACKEND_PATH", libDir)` —— 该变量语义是单个 out-of-tree 后端 .so 的路径，
    不是目录，导致每次启动打印 `load_backend: failed to load ...: Is a directory`。
  - 仅在 `BUILD_SHARED_LIBS OR GGML_BACKEND_DL` 时才拷贝 `libggml-hexagon.so`；本工程两者皆 OFF，
    该后端是静态库、已链入 `libllama-jni.so`，旧写法把 7MB 的 `.a` 改名成 `.so` 塞进包体。
- **结果**：`HTP0` 成为第 4 个 ggml 设备，`offloaded 43/43 layers to HTP0`，
  prefill ~1500 tok/s、decode 29-34 tok/s（OpenCL 为 ~205 / ~15.6）。

### 2) 后端选择与默认值（f914ffae, e9d1b212）

- 默认后端 `DEFAULT_BACKEND = "hexagon"`；两处读取统一用该常量
  （`DeviceInfoActivity` 原来自行读偏好且把默认值写死成 `"auto"`，会与 AI 服务页显示不一致）。
- **`auto` 改为解析出单个设备**，优先级 `Hexagon > OpenCL > Vulkan`。旧实现把
  `Vulkan0` 与 `GPUOpenCL` 一起放进 `model_params.devices`，模型加载时
  **SIGSEGV**（`signal 11` → `ggml_backend_dev_get_props` → `llama_model_load_from_file`）。
- 显式后端找不到时**回退单个设备**，不再留空 `devices`（留空等于让 llama.cpp 枚举全部设备，同一风险）。
- 新增 `nativeGetResolvedBackend()`：native 在选设备时记录**实际选中**的后端并回传，
  于是界面在偏好为 `auto` 时也能显示 `NPU 43层`。写入点加独立互斥锁
  （推理线程写、UI 读，`std::string` 并发读写会撕裂）。

### 3) 工具调用原文泄漏进主回复（f914ffae）

- **根因**：llama.cpp **刻意**把 `<tool_call>` 登记进 `thinking_end_tags`
  （`common/parsers/qwen3-coder.cpp`），而 `classifyToolCallTags` 把"在思考标签表里"
  当作"不是工具标签"的判据 → 开标签集为空 → `stripToolCallChunk()` 首行
  `if (empty) return chunk;` 直接返回 → **剥离整个是 no-op** → `function=location>` 等裸片进正文。
  修法：新增 `isKnownToolMarker()`，只在标记确实不是工具调用标记时才按思考标签排除。
- **流式解析改走 PEG 解析器**：`chatSend` 原先靠枚举模型特定标记判定工具调用，
  漏掉了 Qwen 的 `<tool_call><function=...>` 形态；现用
  `common_chat_parse(fullResponse, /*is_partial=*/true, makeChatParserParams(...))`。
- **连续特殊 token 卡死**：`OutputRouter` 先判开标签就 `return`，同一 chunk 里的闭标签
  永远轮不到 → `isInToolCall` 永久 `true` → 后续正文全被吞。改为先处理闭标签。
- **去掉硬编码包裹**：`OutputRouter` 的工具调用标签改由 `ToolCallTagConfig` 提供；
  思考标签统一走 `ThinkingTagConfig`（`AgentLoopEngine` / `AgentSoftwareLayer` /
  `GenerationStreamController` / `NpuEngineRouter`，最后一个原先按模型名猜标签）。

### 4) Agent 与 UI 状态卡死（e9d1b212）

- **工具卡片永久"执行中"**：`AgentChatHandler` 对 start 与 complete **各生成一个不同的合成 id**
  （`"software_" + System.nanoTime()`），而 `appendAgentToolCall` 以 id 精确匹配 running 卡片
  → 永远匹配不到 → **新建第二张卡片**，第一张停在 running。修法：不再伪造 id，
  并改为**两遍匹配**（先按 id，再按工具名回退）。
- **气泡内步骤状态永久"思考中"**：`onComplete` 在 `completeGeneration` **之后**才写
  "✅ 执行完成"，而此时 `currentStreamingMessageId` 已被清空 →
  `resolveStreamingIndex()` 返回 -1 → 写入被静默丢弃。修法：在 `completeGeneration`
  之前捕获索引，并给 `setAgentStepStatus` 加显式索引重载。
- **思考开关对本地 Agent 无效**：`enableThinking && !localAgentRoute` 强制关闭，
  而普通对话路径（同模型同模板）实测 `enable_thinking=1` 完全正常。已去掉该强制。

### 5) 设备信息页重做（e9d1b212）

- **`GPU 型号` 显示的是主板代号**（`ro.product.board` 在本机是 `canoe`）。
  `DeviceDetector.getGPUModel()` 改为优先驱动查询，再退系统属性；连带修好
  `isAdrenoGPU()` 与「GPU 系列」行（原为 `UNKNOWN`，现为 `A8XX`）。
- **「加速设备检测」列出全部 ggml 设备**（backend / name / type / desc + 已注册后端集合）。
  原实现只查 OpenCL —— 因为那段直接用 `clGetDeviceInfo`。
- 后端行改用 `getResolvedBackend()` 并标注「偏好 X，需重载生效」。
- 「浮点支持」改为紧凑表格：原先用只有左括号的片段 `" (向量宽度: "` 拼接，每项各占一行且错位。
- 刷新按钮复位移入 `refreshDone`，避免工作线程在 post 之前抛异常导致按钮永久禁用。
- `onCreate` + `onResume` 不再重复跑检测（`loading` 标志去重）。
- 新增「推理运行时」区块；**按归属分组**显示 —— `nativeGetContextSize/UsedTokens` 读的是
  `NativeChatContext`，而 `nativeGetKvCacheStats` 读的是 `s_helperContext`，
  两者混在一处显示会自相矛盾。
- `nativeGetDeviceCount()` 原为 `return 0` 的桩实现，现返回真实数量。
- 「GPU 后端」单选标签改为 `OpenCL`（它选的就是 OpenCL）。

### 6) API 服务页

- 新增 NPU（Hexagon）单选；状态行显示**实际载入**的后端并提示「需重载」。
- 后端显示名映射集中到 `LlamaHelper.backendLabel()`，供 AI 服务页与设备信息页共用
  （此前两页各写一份三元链，设备信息页那份还带错了默认值）。

### 7) 遗留（未解决）

- `ggml_backend_dev_get_props()` 在本机触发 SIGSEGV，API 用法已核实无误，崩溃位于某个后端的
  `iface.get_props` 实现内。**未启用**，故设备信息页暂不展示显存与能力位。
  已排除的可能：API 签名与 C 层实现无误、`props` 已零初始化、`host_buffer_type` 的 C 包装自带
  NULL 防护、`GGML_VIRTGPU` 默认 OFF（该后端未编入，与本次崩溃无关）。
  下一步方向：线程上下文（调用发生在后台线程，设备上下文在加载线程创建）、逐设备隔离定位、
  带符号调试（Release `-O2` 已剥离符号，需 `RelWithDebInfo` 或保留 `.symtab`）。
  当前 `nativeGetDeviceCaps` 只使用已验证可用的接口
  （`ggml_backend_dev_count / _get / _name / _description / _type / _backend_reg`）。

### 8) 已知问题归档（本轮未修，均已定位/留证，待后续处理）

以下三项在本轮排查中确认存在、拿到了证据，但按要求**归档不修**。

**① 用户消息重复入库 —— 同一内容被创建两次（两条 id 不同）**

- 现象：会话存档里用户消息相邻成对出现。实测样本（`conversations/ce1664eb-….json`）15 条消息、
  15 个互不相同的 id，即**不是复制同一条，而是真的创建了两条**：
  `表格对比下 土星 金星 木星`、`你好`、`写成html样式` 各出现两次。
- 决定性线索：`turnId` 序列为 `T1, T2, T1, T2, T1, T2, T3, T4, T5` ——
  **T1/T2 在两个批次里各出现一次**。`addUserMessage` 每轮都执行
  `currentTurnId = ChatIdDispatcher.applyMasterId()`，说明**新消息没有推进 turn**，
  派发器被重置（或首次发放时机不对）后又从 T1 开始编号。
- 已排除：存档写入不是元凶 —— `writeMessagesPreservingHead` 的头/尾拼接只在
  `unloadedFrontCount > 0` 时触发，而 `HISTORY_PAGE_SIZE = 100`、会话仅 15 条，
  `sessSkipped = max(0, 15-100) = 0`，head 根本不会写。
- 下一步：查 `ChatIdDispatcher.applyMasterId()` 的重置时机与 `currentTurnId` 的赋值链，
  确认是否有两处各自创建用户消息。

**② 「输出html」那次模型回了「我无法获取您之前讨论的上下文信息」（35 字符）**

- 现象：先问「表格对比下 土星 金星 木星 输出html」，模型回 35 字符的"无法获取上下文"，
  完全没有历史。同一会话里其它轮次上下文正常。
- 线索：该请求命中的是**上下文尚未就绪**的时刻（附近有
  `⏳ 创建对话上下文 [95%] 已耗时: 6.9秒` 的系统消息），疑似上下文构建未完成就发起了推理。
- 下一步：查发送前是否等待上下文构建完成；`ensureModelLoaded` 只保证模型加载，
  不保证会话上下文（`nCtx` / 历史注入）就绪。

**③ Markwon 表格重叠（`TableRowSpan` 高度与宽度测于不同时刻）**

- 现象：流式生成过程中表格内文字互相覆盖、越生成越糊；生成结束后重建显示正常。
  09:36 录屏逐帧确认是**真实渲染缺陷，不是截图/缩放伪影**（无损全分辨率下同样存在）。
- 根因（对着 Markwon 4.6.2 `TableRowSpan` 源码核实）：
  - `getSize()` 的行高取自**缓存的 layouts**（旧宽度下算出的高度），并据此给 `fm.ascent`；
  - `draw()` 在宽度变化时才 `makeNewLayouts()`，**但不更新 height**，仅 `invalidator.invalidate()`。
  - ⇒ 宽度一变，单元格在新宽度下多折几行 → 超出行高 → 溢出到相邻行。
- **与模型输出无关**：09:27 那次「单行表格」曾让我误判为模型能力问题，但 09:47 同模型输出了
  规范多行表格（73 个竖线、50 个换行、分隔行完整），**证明模型输出正常，问题在渲染**。
- 升级路线已排除：Markwon 全模块 `latest = release = 4.6.2`（Maven Central），
  GitHub `pushed_at = 2024-04-17`，上游停更，无处可升。
- 可选修法：
  - **(A) 窄口径 fork `ext-tables`** —— `recreateLayouts()` / `makeNewLayouts()` / `layouts`
    均为 **private**，外部无法在 `getSize` 里提前重建，只能把该类连同
    `TableSpan`/`TableTheme` 一起 vendor（Apache-2.0 允许），把重建提前到测量阶段；
  - **(B) 外部保证宽度稳定** —— 流式期间锁定表格宽度、结束后按真实宽度重渲染一次，
    使测量与绘制天然同频。

## [2026-10-05] NPU Agent 引擎（事件协议对齐 + 回调链修复）+ NPU 容量设计（内存预算/nCtx/GGUF 解析）+ UI 引擎感知 + 预设与设备清理

本轮把 NPU 从"能出字"推到"可作为对话与 Agent 的生成后端"，并补上此前一直缺的**容量决策**与**回调/UI 绑定**。过程中修掉了多个**由本会话自身引入的回归**（已在下方逐条标注原因，便于回溯）。

### 1) 真实入口与闸门修复（关键）

- **对话与 Agent 的真实入口是 `LlamaHelper.chatJson`（JSON 事件流），不是 `chatSend`/`generate*`。** 此前把 NPU 接在 `chatSend`/`generate*` 上的工作全部无效（那些入口在流式对话里根本不会被走到）。现已把 `ModelExecutionBridge` 的 3 处 `chatJson` 调用切到 `NpuEngineRouter.chatJson`。
- **NPU 模式下的本地服务守卫是最后一道闸门**：`ModelExecutionBridge` 在 `!aiService.isInitialized()` 时会 `initializeSafe()` 失败 → `notifyError("AI服务初始化失败")` 并 `return false`。NPU 模式本就跳过本地预加载 → 该守卫必然失败 → **请求永远到不了 NPU**（表现为"AI服务初始化失败" + NPU 状态永远"待加载" + 发送后长时间无响应）。现 NPU 引擎开启时直接放行并记日志：`NPU 引擎已启用，跳过本地服务初始化检查（由 NpuEngineRouter 负责生成）`。
- 说明：该修复曾在一次 `git checkout -- ModelExecutionBridge.java`（因撤回脚本正则改坏文件而做的恢复）中被意外冲掉，随后已补回；`chatJson` 路由与 import 也已一并重新施加。

### 2) NPU Agent 引擎：与 `AgentLoopEngine` 的事件协议对齐

结论：**不需要另造引擎** —— `AgentLoopEngine`（2625 行）本身即通用工具循环（工具 json 构建、FC 提示词、`<tool_call>` 多形态解析、并行执行、tool 消息回填、轮次控制），且已在调用 `NpuEngineRouter.chatJson`。因此"NPU Agent 引擎" = 本地引擎（复用）+ NPU 生成后端 + 协议对齐 + NPU 特有补齐：

| 事件 | 消费方要求 | 处理 |
| --- | --- | --- |
| `meta` | `ThinkingTagConfig.fromJson`：`thinking_start_tag` + `thinking_end_tags[]` | **新增**：按模型选择 ` thinking` / `<think>`（Qwen3.5 用 `<think>`），首个 token 前下发 |
| `token` | `content` + `is_tool_call` | 已有；思考态由 `ThinkStreamer` 增量拆分 |
| `thinking` / `reasoning` | `onThinkingUpdate`（`BridgeJsonCallback` 有 `thinkingStreamed` 去重） | **双发**以兼容两种消费方 |
| `tool_call` | `parseToolCallEvent`：`id` / `name` / `arguments`(**JSON 字符串**) | 形状已对齐；并改为**增量下发**（`emitNewToolCalls`，按 `name+arguments` 去重，避免工具重复执行） |
| `complete` / `error` | 统计由桥接层本地计算（`tokenCount` + `startTime`），无需额外事件 | 已有 |

- **历史裁剪**：NPU 无 native 的 KV/prefix 复用（每轮全量重算），新增 `trimMessagesForNCtx()`，按内存预算规划出的 nCtx × 3.5 字符/token × 75% 预算裁剪历史，**并在预算中扣减 tools schema 长度**（此前漏算，59 个工具的定义可达上万 token，会把 prompt 顶爆 nCtx）。
- **轮次诊断**：每轮结束记 `NPU Agent 轮次汇总: 正文 N 字符, tool_call=K, 含<tool_call>标签=…` —— 用于唯一判定"模型没吐工具调用"还是"协议不对齐"。

### 3) 两个由本会话引入的回归（已撤回）

- **普通路径"升级为 Agent"**：曾在 `BridgeJsonCallback` 中，收到 `tool_call` 后用**同一个 messageId/callback** 再次触发 Agent 生成 → 两条流写同一条消息，而 ChatAdapter 按 messageId 绑定单条流式消息 → 表现为**空白助手气泡 + 卡"思考中" + "生成已停止"**。已撤回（普通路径只记录日志，工具执行统一由 Agent 路径负责）。
- **普通路径注入 59 个工具 schema**：导致 prompt 爆长且模型整轮只吐工具调用、正文为空（渲染空白）。已撤回，工具定义只走 Agent 路径。

### 4) NPU 容量设计（设备实测 + 算账）

设备实测：SM8850（Hexagon V81），`MemTotal = 11.29 GB`（12GB 机型），`MemAvailable ≈ 4.1–4.5 GB`，App `largeHeap=true`，`TOTAL PSS ≈ 424 MB`。速度呈**带宽受限**：4B-Q4_0 实测 18.99 t/s × 2.38 GB ⇒ 有效带宽 ≈ 45 GB/s。

Qwen3.5 为**混合注意力**（官方 config 实测：32 层中仅每 4 层为 `full_attention`，`num_key_value_heads=4`、`head_dim=256`）⇒ KV 仅 **32 KB/token**（经典结构 144 KB/token，便宜 4.5 倍）：4096→131MB、8192→262MB、16384→524MB、32768→1.05GB。

据此实现：
- **`planNCtx()`**：`availMem − 权重 − App预留(0.4GB) − 安全余量(0.3GB) = 可给 KV`，在 `{32768, 16384, 8192, 4096, 2048}` 取最大可行档；`ModelConfig(nCtx = plannedNCtx, nGpuLayers = -1)`（此前**写死 4096**，为早期保守默认，非硬件限制）。
- **加载前预检**：规划返回 -1 时**拒绝加载**并提示（如"可用内存不足：可用 xxxMB，该模型约 xxxMB，建议改用 4B-Q4_0"），把"被系统 LMK 杀掉/闪退"变成明确提示。
- **`GgufMeta`（新增）**：解析 GGUF 头部（v2/v3、13 种值类型、数组跳过）取 `architecture` / `block_count` / `attention.head_count(_kv)` / `attention.key_length` / `embedding_length` / `context_length` / `full_attention_interval`，并识别混合结构（`.ssm.`/`.linear_` 键）；缺 `general.parameter_count` 时**累加张量表**得参数量。
- **容量结论**：本机安全上限 ≈ 4B-Q4_0（2.38GB，实测通过）；5–6B 吃紧；**9B（5.38GB）超出可用内存 → 预设已删除**。

### 5) UI 引擎感知（全部实测/截图验证）

| 位置 | 改动 |
| --- | --- |
| 主界面「状态」卡（`MainActivity.tvAiStatus`） | 新增 NPU 分支：`NPU 待加载/加载中/已就绪/不可用` + 模型名 + t/s，绿色（原先一律走 llama.cpp 语义 → 红字"未加载"） |
| 聊天页顶部状态栏（`ServiceStatusManager`） | `INITIALIZED` 分支 NPU 化（去掉"本地推理就绪 · "前缀）+ 🧠 图标 |
| 顶部 token 徽标（`TokenStatsTextBuilder`） | NPU 分支：`🧠 N tokens · x.x t/s`（原数据源只反映本地 llama.cpp 会话，恒为 `0 tokens`） |
| AI 服务状态页 | 引擎/推理库/当前模型/上下文各行按引擎切换；模型灯按"已加载/有文件/无文件"置绿/黄/红；**GPU 层数与 GPU 后端在 NPU 模式置灰禁用**；架构信息从 gguf 解析（参数量/层数/注意力头/Embedding/训练上下文/内存MB/模型文件/推理速度/Token数/上下文统计）；并给页面自身的 3 个刷新方法（`updateModelArchitectureInfo`/`updateContextStats`/`updateRealtimeMetrics`）加 NPU 早退，消除"待加载↔未加载"来回跳变 |
| NPU 模型选择（`ModelSelectorActivity`） | NPU 对话框新增「**选择 NPU 模型…**」：自动（Q4_0 优先）或指定模型库中的某个 gguf；持久化 `npu_engine_prefs/npu_model`，切换后自动 release 旧权重并重载；行内显示"指定：xxx / 自动选模型" |
| 状态详情对话框 | `模型: <将要使用的模型>（待加载）`、`App 模型库: files/ai_models → <模型>`；删除临时侧载目录行 |

### 6) 预设与设备清理

- `models_presets.json`：**9 条，全部 Q4_0**（含 `qwen3.5-0.8b/2b/4b`、`qwen3-0.6b/1.7b/4b`、`glm-edge-1.5b`、`internlm2.5-1.8b`、`minicpm-v4.6`）；期间新增过的 4B/9B NPU 预设与 Q8_0 预设按"只保留 Q4 + 本机可用"原则删除。
- `AIServiceInitializer.DEFAULT_MODEL_ID` 由已删的 `qwen3.8-4b-distill` 改为 `qwen3.5-4b`。
- **清理调试脚手架**：`NpuLlmChat` 删除硬编码的 `files/gguf/qwen3-0.6b|1.7b` 侧载登记（App 只认模型库 `files/ai_models`）；设备侧删除 `files/gguf/`（1.4GB）+ 两个孤立 `.part`（711MB + 0.6MB）→ `files` 由 ~2.7GB 降至 ~1.1GB。

### 7) GenieX 对 Qwen3.5 的支持性取证

- GenieX 自带 `libllama.so` 含 `qwen35` / `qwen35moe` 架构名；其 DSP skel `libggml-htp-v81.so` 含 Qwen3.5 混合架构所需算子：`gated_delta` / `delta_net` / `ssm` / `conv` / `cumsum` / `argsort`（与仓库自带 `htp/gated-delta-net-ops.c`、`ssm-conv.c`、`cumsum-ops.c` 同源）⇒ **模型层面被支持**。

### 8) 待验证 / 未完成

- NPU 工具调用**端到端**仍需真机验证（先决条件：设备上需存在**完整**模型；本轮结束时 `files/ai_models` 仅有未完成 `.part`）。
- `git push` 仍受网络限制（本地已积累多个未推送提交）。

### 9) 续（同日）：打通 NPU Agent 工具闭环 + 聊天页预加载 + 常用工具保底

**四道 NPU 门禁全部撤除**（历史遗留的"NPU 没有工具能力"乐观假设，逐个发现并清除）：
1. 顶层拦截：`AIChatActivity.processChatMessageNormal` 的 NPU 分支
2. 桥接层守卫：`ModelExecutionBridge` 在 `!aiService.isInitialized()` 时 `notifyError("AI服务初始化失败")` 并 `return false` → 请求到不了 NPU（同时也是"发送后长时间无响应"的原因）；NPU 模式下改为一律放行
3. Agent 初始化门禁：`initAgentChatHandler()` 里 `if (isNpuEngineOn()) return;` → handler 永远 null → 每次都"🤖 Agent 引擎未就绪，已降级为普通对话"
4. 路由门禁：`processChatMessage()` 的 `!npuEngineOn && (在线 || 本地Agent开启)` → NPU 下恒假 → 永远走普通对话（日志表现为 `tools=0字符`、`messages=1`）

**工具结果的结构化传递（关键）**：反编译 GenieX AAR 确认 `ChatMessage` 原生支持 `toolCalls` / `toolCallId` / `toolName`（等价 llama.cpp 的 `common_chat_msg`），`ToolCall(id, name, arguments)`；`LlmWrapper.applyChatTemplate(messages, tools, enableThinking, addGenerationPrompt)`。
- 原先只传 `ChatMessage(role, content)` → 工具轮压成普通文本 → 模型接不上 → **卡在工具执行之后**
- 中途我引入过"文本化降级"（`tool` → `user` + `【工具结果】`前缀），方向错误，已撤除
- 现改为 `sendChatAsync(..., messagesJson)`：原样解析消息并构造带全部 5 个字段的 `ChatMessage`（`toolCalls` 必须传 `emptyList()`，GenieX 声明为非空）

**常用工具保底（对齐在线 Agent 的 `CORE_TOOLS` 设计）**：`DEFAULT_CORE_TOOLS` 由 5 个补到 8 个 —— 新增 `ai_weather`、`smart_research`、`memory`（`tool_registry` 原本就在）。原先 `ai_weather` 不在保底集，而 `sessionTools` 只在会话首条消息播种一次 → 模型整轮看不到天气工具 → **"我无法获取天气信息"的根因**。

**小模型精简规则**：NPU 引擎下规则 4 不再要求"先 workspace(list) → 读《核心工具速查.md》→ tool_registry 查参数"的多轮工具发现，改为"工具清单与参数已在 tools 定义中给出，直接按定义调用"；llama.cpp 路径规则不变。

**聊天页预加载（方案 B）**：`AIChatActivity.onResume` 后台线程 `npu-preload` 触发 `ensureLoadedAsync` —— 只在聊天页、不进启动路径（保留"原生 abort 不致启动秒退"的原始约束）。实测进聊天页约 34s 完成加载（插件 14s + 模型 6s + 初始化），之后首次发送无需等待；常驻 PSS 845MB（含 App 基础 ~424MB）。
- ⚠️ 首版误放在主线程 → `ensureInit()`（GenieX 插件加载 ~14s，同步）阻塞主线程 → `Input dispatching timed out` ANR；已改后台线程并复验无 ANR。

**事件协议补齐**：`meta`（思考标签）、`thinking` + `reasoning` 双发、`tool_call` 增量下发（按 name+arguments 去重）、轮次诊断日志（正文字符数 / tool_call 数 / 是否含 `<tool_call>` 标签）、历史裁剪预算改为 token 计量（CJK 1.5 字符/token、其它 4 字符/token）并在裁剪前先做内存规划（避免用兜底 8192）。

**nCtx 去硬编码**：兜底值 4096 → 8192；候选档位加入 32768（Qwen3.5 混合架构 KV 仅 32KB/token）；状态页上下文统计改用 `NpuLlmChat.plannedNCtxValue()`（实测规划：权重 1158MB / 可用 4751MB / KV 12288B → nCtx=32768）。

### 10) 续（同日，下半场）：运行时托管 / 源码核查修正 / 多模态与 NPU 原生清单 / P0 生命周期 / 投机解码（实测后关闭）

**① NPU 运行时托管给 AIService + UI 单一状态源**
- `AIService` 新增：`isNpuLoaded()` / `getNpuStateName()` / `ensureNpuLoadedAsync(InitializeCallback)`（后台单线程 `npu-load`、幂等、180s 超时）/ `releaseNpu()`；以及 UI 只读代理 `isNpuEngineEnabled/getNpuModelName/getNpuPreferredModelName/getNpuLastTokens/getNpuLastTps/getNpuLastElapsedMs/getNpuPlannedNCtx`
- 三个入口切到服务：`NpuEngineRouter` 首次请求兜底、`InferenceRouter:89` 预加载（保留原 `LoadListener` 签名）、`AIChatActivity` 进聊天页预加载
- **UI 里 `NpuLlmChat` 直连清零**：`AIServiceStatusActivity`(83 处)、`ServiceStatusManager`(14 处)、`TokenStatsTextBuilder`(3 处，NPU 分支搬到带 Context 的重载)、`MainActivity`(1 处) 全部改读 `AIService`
- 红线保持：`initialize()/initializeSafe()/preloadModel()` 在 NPU 模式下仍早退 → **不在冷启动路径加载**（原生 abort 会导致"打开秒退"）

**② 源码核查后的三项修正（推翻了本会话早期的错误结论）**
- 反编译 GenieX AAR + 读官方 demo（`geniex_chat_android`）确认：`applyChatTemplate(chatList)` → `generateStreamFlow(formattedText)` **每轮提交完整 prompt**，**KV 前缀复用由 SDK 内部自动完成**（插件字符串 `prefix reuse: |A|={}, |G|={}, increment={} bytes`）
  → 因此**撤销**了本会话做过的"只发增量后缀"设计（会触发 `prefix reuse failed: prompt does not match last generation`），`npu_incremental` 偏好永久关闭
- **接入 GenieX `LlmWrapper.reset()`**：`resetIncrementalSession()` 真实调用 `reset()`，并在**新对话/清空会话**（`AIChatActivity.clearChat`）与**切换模型**（`ModelSelectorActivity.reloadNpuModel`）时调用 —— 官方 demo 有此调用，我们此前全项目 0 处
- `GenerationConfigSample` 证实官方也只填 maxTokens（sampler 用 bridge 默认）→ 我们原有写法与官方一致

**③ 多模态（GGUF VLM）**
- 取证：GGUF 形态下视觉塔是**独立的 mmproj 文件**（官方 demo `VlmCreateInput.mmproj_path` + `GgufVisionReader` 读视觉几何；社区 `mmproj-Qwen3VL-4B-Instruct-F16.gguf` 独立存在）
- `NpuLlmChat.loadModel` 新增 **VLM 加载分支**：`paths.mmproj_path` 非空 → `VlmWrapper.builder().vlmCreateInput(VlmCreateInput(model_path, mmproj_path, config, runtime_id, compute_unit))`（照官方 449-458）
- 预设：新增 `qwen3-vl-4b-instruct`（`unsloth/Qwen3-VL-4B-Instruct-GGUF`，Q4_0，catalog 原生 `type=vlm`），并补 `mmprojUrl/backupMmprojUrl/mmprojSizeMB`（`ModelPreset` 本就支持这些字段，下载器无需改）
- **未完成**：生成侧仍只走 `LlmWrapper` → 多模态需要把 `sendChatAsync` 生成块重构为 wrapper 无关（方案 A），再传 `VlmContent("image", 路径)`（图片路径由 `injectMediaPathsToConfig` 注入 config）

**④ 预设与 NPU 原生清单**
- 删除非 GenieX 原生 3 条：`minicpm-v4.6`（非原生 VLM，GenieX 只认 Qwen3-VL/Qwen2.5-VL）、`glm-edge-1.5b`、`internlm2.5-1.8b`（不在官方 catalog）
- 新增 **NPU 原生格式（qairt）清单** `assets/models_presets_npu_native.json`（独立文件 + `ModelPresetConfig` 双清单合并，运行时日志 `NPU 原生(qairt)清单已合并: 3 条`）：`Qwen3-4B`、`Qwen3-4B-Instruct-2507`、`Qwen2.5-VL-7B-Instruct`（后者标注本机不适用：7B 超可用内存）
- `ModelPreset` 扩展 GenieX 目录字段：`geniexModelName/geniexRuntime/geniexType/geniexQuant/hub/chipset/applicable/unapplicableReason/supportsVision/...`

**⑤ P0 生命周期与可观测性**
- **取消**：`NpuLlmChat.stopGeneration()` → GenieX `stopStream()`（`LlmWrapper`/`VlmWrapper` 均有），接到聊天页「停止」按钮
- **推理期 WakeLock**：`AIService.acquireInferenceLock()/releaseInferenceLock()`，`NpuEngineRouter` 在 NPU 生成前后持锁/放锁（与 llama.cpp 路径对齐，防灭屏/切后台挂起）
- **量化提示**：`GgufMeta` 增加张量类型直方图 `tensorTypeCounts` + `htpCompatNote()`（HTP 白名单：F32/F16/Q4_0/Q4_1/Q8_0/IQ4_NL/MXFP4；K-quant 会退 CPU）；张量表遍历改为无条件执行（同时补参数量）；状态页架构卡片展示该提示

**⑥ 投机解码（`spec_*`）：已接入 → 实测崩溃 → 默认关闭**
- 取证：插件含完整实现 `setup_speculative/teardown_speculative/decode_speculative/build_speculative_params` + `common_speculative_*`；失败会自动 `falling back to plain decoding`
- 实现：draft 按主模型**自动匹配**（Qwen3.5→0.8B、Qwen3→0.6B，必须同词表；模糊匹配模型库键名），`ModelConfig(spec_type="draft", spec_draft_model=…, spec_n_max=8)`；`needsReloadForSpec()` 在"模型已常驻但库里新出现 draft"时自动 release+重载；`planNCtx` 把 draft 体积计入内存预算
- **结论：本机实测"主模型 + draft"双模型加载会崩溃** → **默认已关闭**（`specEnabled=false`，偏好 `npu_spec_enabled` 默认 false，设备残留偏好已清除）。代码与自动匹配能力保留但休眠，不再默认启用

**⑦ 本会话自引入并已修复的问题（如实记录）**
- `BridgeJsonCallback` 的"升级为 Agent"（同一 messageId 二次生成）→ 打乱 ChatAdapter（空白气泡 + 卡"思考中"）→ 已撤回
- 普通路径注入 59 个工具 schema → prompt 爆长且只吐工具调用 → 已撤回（工具只走 Agent 路径）
- 预加载误放主线程 → `ensureInit()`（插件加载 ~14s）阻塞 → ANR → 已改后台线程
- `git checkout` 恢复 `ModelExecutionBridge` 时冲掉 NPU 守卫（`AI服务初始化失败`）→ 已补回
- 多处正则/缩进导致文件损坏（`NpuEngineRouter`、`AgentLoopEngine` 规则4、`AIServiceStatusActivity` meta 类型）→ 均已按行修复

## [2026-10-04] NPU 引擎与 llama.cpp「无缝切换、功能不降级」+ 预设全面 Q4 化 + 三项渲染修复

本轮把 NPU（GenieX/Hexagon HTP）从"只在对话页可用"做成**本地推理的统一可选后端**，同时保证切换后功能不缩水；并把模型下载/搜索全链路收口到 Q4_0（HTP 原生加速的唯一甜点量化）。

### 1) 无缝切换：能力感知路由 + 故障自动回退（新增 `ai/engine/NpuEngineRouter.java`）

本地推理此前有 10+ 个入口**直接**调 `LlamaHelper`（`ALChat` / `InferenceQueue`×2 / `InferenceRouter`×2 / Agent 思考链×5 / 题库导入），所以上一次的 NPU 接入只在对话页生效。现在：

- 新增路由器，**按能力分流**：普通文本生成走 NPU；需要**思考链 / 工具调用 / 多模态**的请求留在 llama.cpp（避免能力缩水）。
- 已切到路由器的普通生成入口：`ALChat`、`InferenceQueue`（2 处）、`InferenceRouter` 本地兜底（2 处）。
- **故障回退**：NPU 未加载/加载失败/推理异常 → 自动改走 `LlamaHelper`，用户最多觉得"这次慢一点"，不会得到"功能不可用"。
- 开关镜像：`NpuEngineRouter.init()` 在 `SmartQuizApplication.onCreate` 读一次持久化开关推给 `NpuLlmChat.setEngineEnabled()`（**不触发任何原生初始化**，不碰启动路径）；`InferenceRouter.setNpuEnabled()` 同步写入，保证冷启动/运行期一致。
- 实测日志：`NPU 引擎开关(镜像) = true`。

### 2) 思考链在 NPU 上不再失效（根因：参数没传）

GenieX 0.8.0 的真实签名（`javap` 取证）：

```
LlmWrapper.applyChatTemplate(ChatMessage[] messages, String tools, boolean addGenerationPrompt, boolean enableThinking, Continuation)
```

官方示例只传 3 个参数 → `enableThinking` 默认 **false**；我们原先同样只传 3 个，所以"深度思考"在 NPU 上等于没开。现全链路透传：深度思考开关 → `npuConfig.enableThinking` → `InferenceRouter.generateNpuStream` → `NpuLlmChat.sendChatAsync(..., thinking)` → `applyChatTemplate(..., thinking)`；同时预留 `toolsJson` 参数（工具调用接的时候直接用）。

### 3) 引擎互斥：运行时不再占两份模型

原先只有"冷启动跳过本地预加载"这一层保护，运行期切换会**两份权重同时常驻**（0.6B 0.4GB / 4B 2.4GB，外加两份 KV cache）。现在：

| 场景 | 处理 |
| --- | --- |
| 开 NPU（`enableNpuEngine`） | 先 `LlamaHelper.release()` 释放本地权重，再加载 NPU |
| 关 NPU（`disableNpuEngine`） | `NpuLlmChat.release()` 释放 GenieX 权重与会话 |
| NPU 失败回退 llama.cpp | 回退前先释放 NPU（`NpuEngineRouter` 2 处流式回退） |

并加 `logNativeMem()` 打点（`nativeHeap=x.xx GB`）便于回归验证。真机实测（NPU 引擎开启、无本地模型）：`Native Heap 25MB / Java Heap 21MB / Graphics 28MB / TOTAL PSS ≈ 292MB` —— 权重是 **mmap 文件页**（Clean，可回收可共享），不计入 Native Heap，因此"两份"只会在上述场景出现，现已堵住。唯一无法避免的是**两份原生库代码段**（GenieX 自带 ggml/llama + 我们的 `libllama-jni.so`），各十几 MB，属共享库映射，可忽略。

### 4) 预设/下载/搜索全链路只保留 Q4_0

HTP 只原生加速 **Q4_0 / Q4_1 / Q8_0 / IQ4_NL / MXFP4 / F16**；项目原预设几乎全是 Q4_K_M，会导致"下了却跑不到 NPU（掉 CPU）"。处理：

- 脚本按预设仓库**自动换成 Q4_0**（其次 IQ4_NL）：`qwen3.5-0.8b / qwen3.5-2b / qwen3.5-4b / glm-edge-1.5b / internlm2.5-1.8b / minicpm-v4.6` 共 6 条，主源 hf-mirror + 备源 modelscope + 体积一并重写。
- 仓库里没有 Q4_0/IQ4_NL 的 **5 条直接删除**：`qwen3-vl-2b-thinking`、`deepseek-r1-1.5b`、`qwen3.8-4b-distill`、`minicpm5-1b`、`minicpm3-4b`（连带修引用：`AIServiceInitializer.DEFAULT_MODEL_ID` 由 `qwen3.8-4b-distill` 改为 `qwen3.5-4b`，`AIChatActivity` 两处推荐文案同步更新）。
- 新增 4B 的 NPU 预设 `qwen3-4b-q4_0-npu`（2.38GB，官方 Qwen3-4B-Q4_0，推荐）；一度新增的 `qwen3.8-4b-distill-q8_0-npu` 随后按"只保留 Q4"删除（该仓库只有 Q4_K_M/Q5_K_M/Q8_0）。
- 下载页**在线推荐列表**统一过滤：`models.removeIf(m -> m.quantization == null || !m.quantization.startsWith("Q4"))`；**搜索预填文件名**由 `-q4_k_m.gguf` 改为 `-q4_0.gguf`。
- 自定义 URL 新增**下载前提示**：`ModelDownloadManager.guessQuantizationFromUrl(url)` 识别量化，非 Q4 时弹确认框（"该量化不支持 NPU 加速…会回落到 CPU：功能可用但明显更慢"，仍然下载/取消），确认后才走 `addCustomModel()`。

最终 APK 实测：**预设 9 条，量化全部 Q4_0**。

### 5) 独立「NPU 推理」页删除，入口重指向

删除 `NpuInferActivity` / `activity_npu_infer.xml` / `arrays_npu.xml` 与 Manifest 声明，全仓 0 处残留引用；入口改指更有用的地方：状态详情弹窗的"NPU 推理"按钮去掉、NPU 详情弹窗改开**模型下载页**、AI 服务状态页按钮文案改为「选择推理引擎（含 NPU）」（指向模型选择页）。NPU 的日常用法收敛为：模型选择页开关引擎 + 模型下载页下 Q4_0 模型 + 对话页直接用。

### 6) 渲染三项修复（壁纸扩展 / 滑动重绘 / 状态栏区域）

- **"滑动就重绘打架"根因**：`EdgeToEdgeHelper.syncWindowBackground()` 把**同一个 `Drawable` 实例**同时挂到 root、`Window`、`DecorView` 三个尺寸不同的 owner 上，每次重绘互相重设 bounds。现改为各自持有**独立实例**（新增 `independentCopy()`：优先 `ConstantState`，`LayerDrawable` 逐层递归重建，末选纯色）。
- **位图缓存**：`AppWallpaperManager` 加缓存 + `invalidateWallpaperCache()`（挂在 `setMode` / `setLibraryPath` / `markWallpaperRefreshed`），避免 onResume/+300ms/+1200ms 三次都重新解码整屏壁纸；缓存键刻意不用系统壁纸指纹（那会每次读文件，主线程 I/O）。
- **带标题栏页面状态栏区也延伸**：`applyInsets()` 由"给 AppBar 内部加 padding"（标题栏背景顶到状态栏）改为**给 AppBar 加 top margin 整体下移**，状态栏区显示页面背景/壁纸；不支持 margin 的容器保留 padding 降级；`ORIG_MARGIN` 保证 insets 回调重复触发是绝对增量。同步修改 `isLightStatusArea()`（改采样页面根背景，否则图标深浅算反），并删除已失效的 `SKIP_APPBAR_INSET` 白名单。

### 7) 「把 NPU 做成自己 llama.cpp 的 ggml 后端」：根因更正 + 可选开关就位

上一节（2026-10-03）结论文档写的是"ABI 兼容但插件注册 0 设备"，本轮**找到真正原因并更正**：

- 我们这份 llama.cpp 的加载协议是**新式**：`ggml-backend-reg.cpp:220 load_backend()` 要求插件导出 `ggml_backend_init`（并可选 `ggml_backend_score`，返回 0 则静默跳过；随后校验 `reg->api_version == GGML_BACKEND_API_VERSION`）。
- GenieX 的 `libggml-hexagon.so` 导出的是**老式** `ggml_backend_hexagon_reg`（`llvm-nm` 取证）→ 第 3 步就失败 → HTP 从未注册（此前观察到的"返回 0"是把返回值当 int 读的误读）。
- **正确做法**：用仓库自带的 `ggml/src/ggml-hexagon/`（新版协议，含完整 DSP skel 源码 `htp/*.c`）自己编，上游文档写明 `GGML_HEXAGON=ON` + Hexagon SDK → HTP0 就是与 Vulkan/OpenCL/CPU 平级的 ggml 设备，`-ngl` 可卸载（"Hexagon NPU behaves as a GPU device when it comes to -ngl"）。
- 已把接入准备好（默认关、现有构建零影响，已验证 BUILD OK）：`src/main/cpp/CMakeLists.txt` 中**设了 `HEXAGON_SDK_ROOT` 就自动 `GGML_HEXAGON=ON`**，ON 时链入 `ggml-hexagon` 并把 `libggml-hexagon.so` 与 `libggml-htp-v73/75/79/81.so` 拷进 `jniLibs/arm64-v8a/`。
- **唯一缺口**：Hexagon SDK。CI 免登录仓库 `snapdragon-toolchain/hexagon-sdk` 只有 `amd64-lnx`（Linux ELF）与 `arm64-wos`（ARM64 Windows），**无 x86_64-Windows** 资产；官方 Windows 版需 Qualcomm 账号；本机无 Docker/WSL，QAIRT 内只有 DSP 预编译库无编译器。下一步二选一：装 WSL2 用 Linux 版（可用 `ghfast.top` / `gh-proxy.com` 加速下载，实测 HTTP 200 / 662MB），或登录官方下 Windows 版。

## [2026-10-03] 补充实验：能否让 App 原有的 llama.cpp 直接吃 NPU？（结论：ABI 兼容，但插件注册 0 设备）

> ⚠️ **本节结论已被 [2026-10-04] 第 7 条更正**：并非"ABI 兼容却被拒"，而是**插件加载协议不一致** —— 我们这份 llama.cpp 要 `ggml_backend_init`（新协议），GenieX 导出的是 `ggml_backend_hexagon_reg`（老协议），所以 `load_backend()` 在第 3 步失败、HTP 从未注册。当时的"插件注册 0 设备"与"`ggml_backend_load` 返回 0"都是误读（返回值被当 int 读）。正解是用仓库自带的 `ggml/src/ggml-hexagon/` 自己编。

需求来自"NPU 要跟 App 的聊天引擎（llama.cpp）接上"。App 里有两份 llama.cpp：我们自己的 `libllama-jni.so`（CPU/OpenCL/Vulkan，Agent/题库导入/VLM 等全功能在用）与 GenieX AAR 自带的 `libllama.so` + `libggml-hexagon.so`（跑 HTP）。若能把后者作为**我们那份**的一个 ggml 后端挂上去，所有功能都能吃 NPU。

实测（`src/main/cpp/ggml-probe.cpp`，独立极简 CMake 目标，只 dlopen `libllama-jni.so` 的符号来调用，不重链 195MB 大库）：
1. **符号全通**：`libggml-hexagon.so` 需要的 **32 个 ggml C API 符号，`libllama-jni.so` 全部导出**（该库共导出 9843 个符号）；我们的库里确实带后端注册表（`ggml_backend_registry::load_backend`、`ggml_backend_reg_count/reg_get`、`ggml_backend_reg_dev_count/reg_dev_get`）。
2. **运行期被接受**：`ggml_backend_load("<nativeLibDir>/libggml-hexagon.so")` 返回 **0**（成功），`ggml_backend_load_all()` 也返回 0 —— 说明 `GGML_BACKEND_API_VERSION` 校验通过，ABI 不存在硬冲突。
3. **但注册 0 个设备**：加载前后，我们这份 llama.cpp 的后端始终只有 `Vulkan`(1 设备) / `OpenCL`(1) / `CPU`(1)。设了 `ADSP_LIBRARY_PATH`/`DSP_LIBRARY_PATH` 指向 nativeLibraryDir（插件里写死了 `file:///libggml-htp-v%u.so?htp_iface_skel_handle_invoke&_modver=1.0&_dom=adsp`）也没变。
   → 插件依赖 **GenieX 自己的 DSP 会话引导**（他们日志里的 `Auto-resolved HTP runtime path`），单独挂到第三方 llama.cpp 上不足以让 HTP 设备出现。

**结论与取舍**：目前"NPU 上的 llama.cpp"就是 **GenieX AAR 自带的那份**；App 原生那份 `libllama-jni.so` 继续负责 CPU/OpenCL/Vulkan 与全部工具链功能。要让**同一份**引擎既用 HTP 又保留工具链，只有两条路：
- **B1（工作量大、风险高）**：把自己的 llama.cpp 重编为**动态 ggml**（`GGML_BACKEND_DL=ON` + 共享 ggml 库）并链到 GenieX 的 `libggml-base.so`/`libggml.so`，使插件注册进同一注册表；同时复刻其 DSP 引导。需要重编 195MB 大库，且 GPU 后端共存有回归风险。
- **B2（推荐）**：保持两份，把需要 NPU 的功能逐个接到 GenieX 上（对话已接，题库导入/Agent 可续接），UI 明确区分引擎。

`GgmlProbe` 与 `ggml-probe` 目标**保留为诊断工具**（不联网、不改配置、启动路径不调用）；原来的 `/data/local/tmp/ggml_probe` 自动触发钩子已移除。

## [2026-10-03] NPU 主流程接入完成：改用 Qualcomm GenieX SDK，对话页问答直接跑 Hexagon NPU（实测 72–78 tok/s）

**结论先说**：旧的手搓 QAIRT/QNN + Genie 路线（下面那节）只做到"自检"，**已整体退休**；换成 Qualcomm 官方 **GenieX Android SDK**（`com.qualcomm.qti:geniex-android:0.8.0`，本地 AAR 放 `libs/`），现在**对话页正常提问就直接在 NPU 上出字**。

真机实证（Xiaomi 25113PN0EC / SM8850 / Hexagon v81，Qwen3-0.6B-Q4_0 本地侧载）：
```
llama_kv_cache: layer 0..27: dev = HTP0
sched_reserve:  HTP0 compute buffer size = 298.75 MiB   CPU compute buffer size = 8.01 MiB
sched_reserve:  graph: nodes = 986, splits = 2
GenieXSdk: prefill_speed=803.98 tok/s, decoding_speed=77.78 tok/s
```
- 对比官方示例 App 的默认配置（HTP0 27MB / CPU 299MB、33 tok/s）：这里 `ModelConfig(nCtx=4096, nGpuLayers=-1)` 把整张图几乎全推给 Hexagon，CPU 只剩 8MB 兜底 → **快一倍**。
- 多轮上下文生效：第二轮 `prefix match: past_prompt_tokens=235, match_len=217`（KV 前缀复用，不重算）。
- **Q4_0 才是 NPU 甜点**：从 `libggml-htp-v81.so` 符号表确认 HTP 端原生支持 Q4_0/Q4_1/Q8_0/IQ4_NL/MXFP4/F16；**K-quant（Q4_K_M 等）不在列表里 → 会掉 CPU**，测 NPU 性能别用 K-quant。

**为什么要换（旧路线的三个死结）**：① 手搓路线要 QNN context binary，本地 ONNX→QNN 转换 14 次尝试都倒在 QNN 不支持的算子/Reshape 推导上（`npu_demo/qnn_convert*.log`）；② 拿占位模型走 `GenieDialog_create` 会在原生层 abort（Java 抓不到，表现为点一下整个 App 闪退）；③ 自编 DSP skel 在零售机需要签名。GenieX 官方 AAR 自带**预签名 HTP skel + ggml-hexagon + QNN 2.45 全套**，这三个问题一次性消失。

**关键改动清单**（本轮）：
1. **依赖**：`implementation files('libs/geniex-android-0.8.0.aar')`（78MB，自带 arm64-v8a native + 模型管理，不需要 NDK/CMake）。
2. **删掉旧的随包 QNN/Genie 库 + npu-jni**：`libQnnHtp.so`/`libQnnSystem.so`/`libQnnHtpV81Skel.so`/`libQnnHtpV81Stub.so`/`libGenie.so`/`libnpu-jni.so`、`src/main/cpp/npu-jni.cpp`、`src/main/cpp/qnn/`、`NpuHelper.java`、状态页「NPU 自检」。
   - **为什么必须删**：GenieX 的 `libgeniex*.so` 是**运行期按文件名 dlopen** `libQnnHtp.so`，而 AGP 会让本地 `jniLibs` 覆盖 AAR 里的同名文件 → 若不删，它拿到的是我们那份 2.43 老 QNN，却要加载 2.45 工具链编译的 context binary（版本不兼容）。删后 APK 里是 GenieX 的 2.45 全套（libQnnHtp 2.82MB 等）。
   - `libomp.so` 两边都有 → `pickFirst "**/libomp.so"` 保留我们那份（`llvm-readelf -d` 确认 `libllama-jni.so` 的 DT_NEEDED 里有它，而 GenieX 的库不需要）。
3. **`NpuLlmChat.kt`**（新，Kotlin 封装）：init / 模型管理（hub 拉取 + **本地侧载**）/ 加载 / 流式生成 / 多轮 `sendChatAsync(roles, contents)` / 统计。
   - **本地侧载（LOCALFS）**：目录必须在 **App 内部存储**（`filesDir/gguf/...`）。放过 `/sdcard/Android/data/<pkg>/files/` → App 读取 `Permission denied`（那层 FUSE 权限不由 App 掌控）。
   - SDK 的模型管理器只认自己写的 `geniex.json` 清单 → `getPaths()` 为空时用"扫目录里的 `*.gguf`"兜底。
   - **`GenieXSdk.init()` 必须先调**：JNI 注册在 init 里，漏了会抛 `UnsatisfiedLinkError: No implementation found for ... ModelManager.getPaths`（界面误报成"模型路径不正确"）。
4. **推理路由 `InferenceRouter`**：新增 `InferenceType.NPU("NPU（GenieX）")` + 引擎开关（持久化）→ `generateStream/generate/generateSync` 分流，`stopInference()` 也停 NPU。
5. **对话页接入（本轮最关键的一处）**：`AIChatActivity.processChatMessageNormal()` 的本地分支最终调的是 **`modelBridge.execute(...)` → AIService（llama.cpp）**，**根本不经过 `InferenceRouter`** —— 所以最初在路由层做的 NPU 分流一次都没执行到（表现为"发送后仍去加载本机不存在的本地模型"）。现在在**那个出口**加 NPU 分支，把 GenieX 的流式 token 接到界面同一条 `BridgeCallback` 链上。
6. **把所有"把 NPU 用户拖回本地模型"的旁路都跳过**（否则界面报「模型文件不存在」/卡在「准备模型文件」）：
   - `ensureModelLoaded()` 的本地模型文件存在性检查；
   - `onCreate` 的"没有本地服务就退出"检查；
   - `processChatMessageNormal()` 的 `aiService == null` 弹窗 + `modelBridge.isNativeStateValid()` 失败的**自动恢复**（3 处，会触发 `AIService: Failed to locate model file` 并卡死）；
   - 本地 Agent 分支（依赖 llama.cpp 工具链）；Agent 引擎"等待模型就绪"的每 10 秒告警；
   - `SmartQuizApplication.preloadAIServiceInternal()` 启动预加载本地 GGUF（NPU 下白占内存）。
7. **UI 入口**：`ModelSelectorActivity`（对话页「模型」按钮进来的页面，**真正在用的那个**；旧的 `ModelSelectionActivity` 全仓无人调用）新增一行 **「🧠 NPU 引擎（GenieX）」** → 开启/关闭/重载/打开 NPU 推理页，行尾实时显示 `已启用 · local/qwen3-0.6b-q4_0 · 65.7 t/s`。
8. **`NpuInferActivity`**（独立推理页）：模型下拉改为"本地侧载 0.6B / 1.7B + 镜像拉取 1.7B + qairt 4B + 8B"，并按 runtime 分支（qairt 必须 `ModelConfig(nCtx=0, nGpuLayers=0)` + `chipset=SM8850`，否则 AI Hub 预编译包永远加载失败）；下载进度改为真实字节百分比（原来恒 99%）。
9. **待办**：① APK 瘦身（`libQnnHtpPrepare.so` 未压缩 83.67MB、`libQnnHtpV79*` ~15MB 对 v81 设备用不到，可 `jniLibs.excludes` 省 35–45MB）；② 把 NPU 状态显示到对话页状态栏；③ qairt（AI Hub 预编译 4B）路径实测。

## [2026-10-03] NPU 接入（第一轮，已被上面 GenieX 方案取代）：Qualcomm QAIRT/QNN + Genie 进 App（真机跑通 device/context，只差 QNN 格式模型）

背景：`D:\qualcomm\qairt\2.43.0.260128` 是 Qualcomm AI Runtime（QAIRT/QNN）2.43.0 完整 SDK（3.5GB）。项目原先那条 Hexagon 路线（llama.cpp `ggml-hexagon`；[build_snapdragon.bat](build_snapdragon.bat) 指向并不存在的 `Hexagon_SDK\6.4.0.2`）从未产出过库，而且零售机加载自编 DSP skel 需要签名；QAIRT 提供的是官方预签名 skel，所以改走 QNN/Genie。

设备实测（Xiaomi 25113PN0EC，`SM8850` / canoe / Android 16 / arm64-v8a）：
1. `qnn-platform-validator`（shell 域）：DSP/GPU 硬件在位 → **Hexagon Architecture V81** → DSP 单元测试 **Passed**。
2. App 域（`libnpu-jni.so` + `NpuHelper`）：`libGenie.so` 加载成功（**Genie API 1.15.0**）、QNN provider `HTP_QTI_AISW`、`deviceGetPlatformInfo` 读到 **arch=v81 / socModel=0x57 / signedPD=yes / vtcmMB=8**、`backendCreate rc=0`、**`QnnDevice_create rc=0` → `QnnContext_create rc=0`**（device 连 config 都不用给，NULL 即可）。
3. **踩坑与真因**（值得记）：一开始 `QnnDevice_create` 对 7 种 config（null / soc / arch / soc+arch / signedpd / +unsignedpd / platform-info）**全部返回 14001 = QNN_DEVICE_ERROR_INVALID_CONFIG**，看起来像"配置永远不对"。真正的原因是两个环境问题，跟 device config 毫无关系：
   - ① **Android 10+ linker namespace 不暴露 vendor 库**：`libQnnHtpV81Stub.so` 的 DT_NEEDED 里有 `libcdsprpc.so`（fastrpc→DSP 的通路），没在清单里声明时 dlopen 报 `library "libcdsprpc.so" not found ... in namespace clns-10` → stub 加载失败 → QNN 一路笼统报成 14001。
   - ② **DSP 侧 skel 需要 `ADSP_LIBRARY_PATH`**：App 进程没人替我们设（shell 下测试是我们手设的），不设时 transport 起不来（`QNN_TRANSPORT_CONFIG crc32 failed` / `Failed to load skel`）。
   - 教训：`backendCreate`/`deviceCreate` 之前一直传 `nullptr` logger，等于把 QNN 的嘴堵上，只剩一个数字。接上 `QnnLog_create`（tag `QnnNative`）与 `GenieLog_create`（tag `GenieNative`）后**一眼就看到真因**。另：`errorGetVerboseMessage` 对这类错误码返回空，没用。

本次改动（App 侧只新增，不动 llama.cpp/Vulkan 老路径）：
1. **`AndroidManifest.xml`**：`<application>` 下新增 `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`（与已有的 `libOpenCL.so` 并列）。**只声明 `/vendor/etc/public.libraries.txt` 里真实存在的库** —— 这一行是 QNN 能用起来的前提，也是踩过坑的地方：
   - 曾顺手加了 `libdmabufheap.so` / `libion.so`，但本机 public.libraries.txt 里**没有**这两个 → 链接器建应用名字空间失败 → **App 启动即 SIGABRT 秒退**，且 Java 层完全无日志（`FATAL EXCEPTION` 都没有，只有 `Zygote: Process xxx exited due to signal 6 (Aborted)`）。已移除，只留 `libcdsprpc.so`。
2. 随包运行库（`src/main/jniLibs/arm64-v8a/`，约 24MB）：`libGenie.so`、`libQnnHtp.so`、`libQnnHtpV81Stub.so`、`libQnnSystem.so`、**`libQnnHtpV81Skel.so`**（Hexagon skel，DSP 侧需要真实文件路径）；`build.gradle` 的 `keepDebugSymbols` 加 `**/libQnn*.so`、`**/libGenie.so`（skel 带 Qualcomm 签名目录，被 llvm-strip 动过会加载失败）。项目本来就有 `useLegacyPackaging = true` → 运行库解压到 `nativeLibraryDir`，真实路径条件天然满足。
3. 头文件 vendored 到 `src/main/cpp/qnn/include/{QNN,Genie}`（2MB）；新增 CMake 目标 **`npu-jni`**（`src/main/cpp/npu-jni.cpp`）：运行库全部 `dlopen`（缺库不拖垮 App）；`nativeSetDspLibraryPath()` 在任何 QNN 调用前设 `ADSP_LIBRARY_PATH`；QNN/Genie 日志各自接进 logcat（`QnnNative` / `GenieNative`）。
4. Java `NpuHelper`（`com.oilquiz.app.ai.jni`）：`isRuntimeAvailable / hasRuntimeFiles / runtimeFilesDetail / ensureDspPath / selfTest / genieProbe / summary`；AI 服务状态详情（本地）新增「**NPU 自检**」按钮 + NPU 信息段（现在如实显示 `Genie API 1.15.0 · HTP v81 · QNN device/context 已通（缺 QNN 格式模型）`）。
   **启动路径不再碰 NPU**：`MainActivity` 里那段"每次启动后台探测一次"已注释掉（`maybeProbeNpu()` 保留但默认不调用）——QNN/Genie 在原生层偶尔会 abort（signal 6），放在启动路径上就是"一打开就秒退"，而且 Java 层抓不到；自检只在用户点按「NPU 自检」时跑。
5. **「NPU 自检」按钮把 App 点崩过（已修，值得记）**：早期版本里自检会跑一步"拿占位模型去 `GenieDialog_create`"，QNN/Genie 在**原生层直接 abort（SIGABRT）**——Java 层 try/catch 拦不住，表现就是用户点一下**黑屏/整个 App 消失**（crash buffer 里连 tombstone 都没有）。
   - 试过的隔离方案（失败，已回退）：把自检页放独立进程 `android:process=":npu"`。结果 `:npu` 进程**连我们的代码都没跑到就死**——本 App 的那堆 ContentProvider/初始化（Chaquopy Python、本地模型预加载…）在第二进程里启动即挂，光靠 `Application.onCreate` 提前 return 也救不回来。
   - 最终做法：**自检只跑安全的那部分**——随包运行库清单 / Genie 版本 / HTP 架构 / `backend → device → context`（这些在进程内跑过几十次都稳定）；`GenieDialog_create` 探测从自检里摘掉，`NpuHelper#genieProbe()` 保留但**明令禁止接 UI**，等真模型就位并单独隔离验证后再接。
   - 验证：临时在启动路径打开自检跑一遍（结果正确、进程存活 20s+），随后关闭启动探测并重装。
6. 现状/下一步：环境已通，**只缺 QNN 格式模型**。`GenieDialog_create` 仍 `ERROR_GENERAL`，Genie 日志走到 `ctx-new` 之后（即解析模型那步）静默失败——探测时目录里只有 1MB 全零占位 bin + `{}` tokenizer。要真跑推理，需要 AI Hub 下载或本地 `qairt-converter` + `qairt-quantizer` + `qnn-context-binary-generator` 转换出 `ctx-bins` + `tokenizer.json`，再接到 App 的本地引擎选择上（llama.cpp 保留）。


## [2026-10-03] remote_dsh v5：dsh 客户端升级到 0.2.0-rc.2 后，桥接改走原生 stdio ACP

用户升级 dsh 客户端（`DeepSeek Harness.exe` 0.1.5 → **0.2.0-rc.2**，内置 `dsh` 同步升到 0.2.0-rc.2）后问"远程控制电脑还能用吗"。实测结论：**电脑端 ACP 主通道断了**——启动脚本里那句 `dsh --profile acp serve --host 127.0.0.1 --port 7800 --token xxx` 在新版直接报 `error: unknown option '--host'`；7800 没人监听，桥接 `ACP.alive()` 探到 `WinError 10061 连接被拒绝`，手机端 `run/start/history` 全部失败（只有 `shell` 直连命令还能用）。

根因：
1. 0.2.0 的 acp profile **只提供 stdio**（`dsh --profile acp --help` → "Serve automation clients over Agent Client Protocol stdio"），HTTP serve 是 0.1.5 时代第三方插件 `dsh-acp-server`（声明依赖 `@deepseek-ai/dsh-* ^0.1.5-rc.2`）提供的；新 acp profile 的 bundle 只有 `dsh-base + dsh-acp-app`，不再装它。
2. 主 home 里装好插件的 acp profile 也已不在（`~/.workbuddy/dsh-home/profiles` 只剩 `desktop`）。

改法（App 端接口零改动）：**桥接自己 spawn `dsh --profile acp`，用换行分隔 JSON-RPC 讲 ACP v1**——无插件、不占端口、不需要 ACP token。
1. `tools/dsh_bridge_server.py` 与 `src/main/assets/remote_dsh/dsh_bridge_server.py`（导出源，两份必须一致）重写 ACP 传输层：`initialize / session/new / session/prompt`，流式文本收 `session/update → agent_message_chunk`，并按 `--permission` 自动应答 `session/request_permission`（写文件/跑命令不再挂死）。v5 起 `/status` 增报 `acp_transport=stdio`、`acp_profile`。
2. 两个真 bug（实测踩到并修）：① **持锁等 initialize 自我死锁**——读线程要靠同一把锁投递响应，`ensure()` 持锁等待导致"等待超时(60s) 且 stderr 为空"；现在只有拉起/清理两小段持锁。② 子进程死亡要立刻报错，不留调用方干等满超时。另清理 `DSH_*` 会话身份变量，避免从 DSH shell 里启动时嵌套 agent 误认为同一次会话。
3. **嵌套 agent 沙箱修复（关键，否则手机端"跑命令"仍然全废）**：dsh 的沙箱/审批策略读环境变量 `DSH_PERMISSION_MODE`（acp profile 默认 `workspace-write`）。Windows 上 workspace-write 要 materialize 工作目录的 ACL 临时授权，实测失败——手机让它跑 `echo`，电脑端回 `Error: sandbox-local windows-acl temp grant materialization failed`，命令根本没跑。现在桥接显式设 `DSH_PERMISSION_MODE`，新增 `--permission-mode read-only|workspace-write|danger-full-access`，**默认 `danger-full-access`**（远程控制电脑本来就要读写/跑命令，手机侧由桥接 token 把关；收紧可显式改小）。实测改默认后同一任务从"沙箱报错"变成 2 秒回 `perm-ok`。
4. 启动脚本回归单窗口：`start_dsh_bridge.bat/.sh`（assets 与 tools 两份）、`tools/start_tunnel_bridge.ps1` 不再起 ACP 服务窗口/传 `--acp-token/--acp-base`（这两个参数保留解析但已废弃，老脚本不会报错）；新增 `--no-open` 便于脚本化启动；README 同步（含"报 unknown option '--host' = 用了旧脚本"与"沙箱挡住 = 加 `--permission-mode danger-full-access`"两条排查项）。
5. App 侧仅文案/注释同步（`RemoteDshTool`、`RemoteDshConnectActivity`、`AgentWorkspace` 工具清单 v4→v5），接口与动作不变；`/status` 增报 `permission_mode`。
6. 验收：新增 `tools/tests/bridge_stdio_e2e.py`（单元：权限应答 allow/deny/未知请求兜底；A 阶段：/status → start → 两轮 prompt 验多轮续接 → history → /exec → 跑命令真拿到 `perm-ok`；B 阶段：`--permission-mode workspace-write` 下权限请求被自动应答且不挂死）**22/22 通过**；`tools/tests/acp_stdio_probe.mjs` 为纯协议探针；导出脚本用 shim 探针跑通（dsh 版本回显、参数正确、不再出现 7800）。
7. 注意：新代码包在 APK assets 里，需重新构建安装后从 App「导出电脑端程序」才能拿到；`--permission-mode` 默认不受沙箱限制属安全取舍（README 安全说明已写明）。

## [2026-10-03] AI 新增 ssh_exec 工具（JSch 纯 Java 版）+ edge-to-edge 全面屏适配

### ssh_exec 工具（agent 连接任意 SSH 主机）
1. 新增 AI 工具 `ssh_exec`：SSH 连接**任意远程主机**（电脑/云服务器/路由器/NAS——任何开 sshd 的机器）执行单条命令；非交互、25 秒超时；返回 stdout/stderr/退出码；连接失败按错误类型给排查提示。
2. 实现演进（本窗口）：内置 OpenSSH 二进制 + sshpass/SSH_ASKPASS 取巧 → **JSch 纯 Java 实现**（`com.github.mwiede:jsch:2.27.7`）：
   - 密码认证：**JSch 原生**，开箱即用，彻底不依赖 Termux/sshpass/askpass；
   - 密钥认证：key_file 指定，缺省用 App 内 `~/.ssh/id_ed25519`，不存在时返回引导（shell_command 一键生成 + 公钥放目标机 authorized_keys）；
   - 8s 连接超时 + 25s 总超时，读线程 + channel 轮询，stdout/stderr 分离。
3. 全链路接入：@Action/@Param 描述、AIToolManager 定义、必填预检（host/command/user）、对话引导流程（新增"SSH 连接远程主机"选项 + 主机/用户名/端口/命令四步输入）、结果展示。
4. **依赖坑（必读）**：jsch 2.28.7/2.27.7 的 jar 内含 **Java 24 专用后量子 ML-KEM class**（`META-INF/versions/24/`，字节码 major 68）——Jetifier 扫描整个 jar 报 `Unsupported class file major version 68`。解法：`gradle.properties` 加 `android.jetifier.ignorelist=jsch` 跳过转换（纯 Java 库无需 AndroidX 转换；Kotlin/D8 只读 Java 8 基础类）。**Java 24 整体升级评估结论：不建议**——AGP 8.x 的 D8/R8 不支持 major 68、Gradle 8.13 需升 8.14+、ML-KEM 手机 SSH 用不上，收益≈0。
5. 内置 SSH 终端此前已删除（10-01：外部 SSH 工具正常、内置页面连上卡/断），ssh_exec 是工具化命令执行，与终端无关。

### edge-to-edge 全面屏适配（小米底部小白条）
1. 按官方文档（targetSdk 35+ 强制 e2e、Dialog 同样强制）重构 `EdgeToEdgeHelper`：apply() 改 `systemBars()|displayCutout()` 组合；applyInsets() 底部取 `max(navigationBars, systemGestures)`（防个别 MIUI 手势条 inset 报 0）；新增 `applyDialog(Dialog)`（透明双栏 + 根 padding + 深浅图标采样）。
2. 全项目核查底部贴边弹窗仅两个：SpeechModelSelectorDialog / OCRModelSelectorDialog（均 Gravity.BOTTOM）→ 接入 applyDialog()；CitySearchDialog 等居中弹窗不触导航栏未改；Material BottomSheetDialog 官方自动兼容。
3. 闪烁根因修复：普通界面在 e2e 下自绘了与系统栏重叠的背景、又随系统栏 inset 反复重绘 → 与白条抢显示；统一改为系统栏透明 + 根容器按 insets padding（状态栏/手势条/导航栏不再重叠）。

## [2026-10-01 ~ 10-02] VNC/图形界面整体删除 + Termux 一键准备收敛 + 内置包签名修复

1. **VNC/图形界面功能整体删除**（代码+UI+依赖）：远程 VNC 不稳、容器内 apt/图形依赖反复失败、用户拍板不再要；`com.oilquiz.app.vnc` 客户端、启动器、`~/ubuntu-gui` 脚本、相关 UI 与文档段落全部移除。
2. **Termux 一键准备收敛为 6 步**：通道 → 存储 → proot → 容器 → 收尾（原 8 步删掉 python3 自动安装与图形界面两步）；容器内 python3 改为需要时手动 `apt-get install python3`。
3. **dpkg 中断修复**：收尾步 apt 前自动 `dpkg --configure -a`（幂等，修复此前安装被打断的遗留——不修后面所有 apt 都会拒绝干活）。
4. **挖出的真 bug**：step4 生成安装脚本时 heredoc 换行符变成字面 `\n`（源替换转义写错），导致 apt 源没写进去、命令错乱；该步删除后坏脚本随之消失（step5 是正确写法）。
5. **内置包签名修复**：内置 Termux:API/Boot 原为 GitHub debug 签名版，与 F-Droid 签名的 Termux 主应用不兼容被系统拒绝安装；换 F-Droid 官方签名版；proot/内置包同步更新。
6. **内置 SSH 终端删除**：用户实测外部 SSH 工具连接正常、App 内置 SSH 页面连上卡/断，通道不稳定；内置页面整体删除（含 JSch 依赖），后续由 AI `ssh_exec` 工具承担远程命令能力。
7. **proot/rootfs 内置**：proot-distro 与 ubuntu-base 内置仍偶发失败，最终走联网+清华源方案跑通；python3 不再自动装（用户拍板）。

## [2026-09-30] Termux 一键准备向导化 + Termux:API/Boot 内置 + MediaStore 存储迁移 + 主题适配

1. **一键准备向导化重构**：从"一股脑传 setup.sh"改为向导状态机（TermuxEnvSetupActivity）：分步检测（Termux 是否安装/授权/allow-external-apps → 存储 → proot → 容器 → 收尾），每步独立检测、不满足引导用户点按钮复制代码到 Termux，前面未完成不自动执行后面；支持监控日志容器、会话切换提示（toast）。
2. **Termux:API / Termux:Boot 内置 + 选装入口**：两个配套应用 APK 内置到 assets，一键准备界面可选安装；Boot 用于开机自启 Termux 会话。
3. **统一 StorageWriter（MediaStore 优先）**：所有工具类存储迁移到 MediaStore API（/sdcard/OilQuiz 根目录工作区绕不开时引导授权）；解决 targetSdk 36 下公共目录写入问题。
4. **文件同步与残留清理**：私有目录 → 公共目录迁移同步；AI 对话页面的日志/对话记录/使用记录可管理清理（防 App 占用存储越来越大）。
5. **主题适配**：agent 管理界面文字增加边框/底色（防壁纸导致看不清）；深色模式适配；硬编码颜色清理。

## [2026-09-29] 面板没用的插件 + websockify 归属（并记录一个未解的偶发问题）

用户问「目前 xfce 有什么问题」，体检后修了两条：

1. **面板里的 pulseaudio 插件换成 systray** ✓
   容器里**没装 pulseaudio**，但面板配置里有该插件 → 最近 400 行日志里 **77 次**
   「Disconnected from the PulseAudio server. Attempting to reconnect in 5 seconds...」纯刷屏；
   同时配置里**没有 systray** → WPS 之类的托盘图标不显示。
   做法：把 `plugin-8` 就地由 `pulseaudio` 改成 `systray`（plugin-ids 里的位置原样保留），
   并写进外壳脚本、**在 xfce4-panel 启动之前**执行（面板退出时会把自己的配置写回去覆盖）。
   实测：`panel plugin-8=value="systray"` ✓，重启后最近 200 行 **PulseAudio 刷屏 = 0** ✓。

2. **websockify 的归属问题** ✓（这条是修 1 的过程中撞出来的真 bug）
   `kill_stale` 每次启动/重启都会把 websockify 清掉，而 **「桌面已就绪（GUI_ALREADY_UP）」那条分支
   不会再把它拉起来** → 真机现象：Xvnc/桌面都在跑，但 **6080 没人听、noVNC 网页 http=000**，手机上看不到画面。
   修了三处：
   - 启动器加 `ensure_websockify()`：**直接探 6080**（比 pgrep 可靠），不通才拉起；
   - `kill_stale` 的匹配放宽：原来只认 `argv[1] 以 /websockify 结尾`，**裸命令 `websockify` 漏网** →
     残留实例占着 6080，新起的 bind 失败（日志 `OSError: [Errno 98] Address already in use`）；
   - 外壳会话里加 **websockify 看门狗**（5 秒一轮：页面不通才拉起，通了就只探活），
     让它归那条长活会话所有、能自愈。
   实测：杀 websockify → `000` → 跑一次启动器 → **`200`** ✓。

3. **未解问题（如实记录，别当成已修）**：真机上偶发**整条图形会话消失**
   （Xvnc/外壳/组件一起没，Xvnc 日志里最后只有正常客户端断开，Termux 进程本身还活着 1 小时以上，
   没有 OOM/被杀记录）。今天遇到 3 次。另外 6080 释放有竞态，看门狗要等下一轮（5 秒）才能抢到端口。
   缓解：启动器 `UP()` 会判"没有活着的 Xvnc"从而整条重建 ✓（实测能自动恢复）；
   根因还没定，下次要抓的是"谁把 Xvnc 收走的"（需要在消失瞬间采样 `/proc/*/stat` + logcat `am_kill`）。

### 9/29 同日其余变更（按提交记录补全）

**① targetSdk 36 升级 + edge-to-edge 全面适配（一天内成套落地）**
- 升级 Android 16：AGP 8.4.0 → 8.9.2，compileSdk/targetSdk 36，buildTools 36，NDK 26.1 固定；移除 `windowOptOutEdgeToEdgeEnforcement`（API 36 已移除该属性），全部界面由 EdgeToEdgeHelper 真适配接管。
- `EdgeToEdgeHelper` 真 edge-to-edge 全局适配：`decorFits=false` + 根容器 insets padding + 深浅图标自动切换，Application 生命周期统一接入，白名单跳过 VNC/AI 对话/透明悬浮窗。
- 沉浸收官：背景/壁纸延伸进系统栏 + 图标自适应（db257db3）；工具集 AppBar 保持原位（状态栏区回归渐变背景延伸而非白色矩形块）；AI 对话深蓝渐变背景全量文字可读（root 层/统计胶囊/空状态/输入框/消息操作栏/时间戳模型信息全部浅色）。

**② 一键准备界面重设计 + 加固（当日 12 条提交）**
- 配置页重设计：卡片化三步操作 + 步骤徽章 + 状态卡手动刷新（零逻辑改动）；按钮视觉升级（主步骤带图标、完成态勾选图标+次色底、次级按钮各配图标）；文案缩短防换行；辅助按钮改竖向单行全宽。
- 全部按钮条件启用矩阵：Termux 未装/权限未授/前置未完成时置灰防乱点；辅助按钮改"点击弹小字"（常亮可点，条件不满足 toast 提示原因不执行，不再置灰）。
- 辅助操作新增四项：进 Ubuntu 终端/查看环境日志/重启图形界面/停止图形界面；进 Ubuntu 终端改为唤起 Termux 前台+手动输入（避免交互会话占死 RUN_COMMAND 通道导致其他按钮无响应）。
- rootfs 检测加公共目录写探针兜底；两个误报修复：① exportedRootfs 在分区存储下 File API 读不到公共目录（真机 Permission denied，文件实为 MediaStore 落盘）→ 加 MediaStore 查询兜底；② Termux 0.118+ 不再授 READ/WRITE_EXTERNAL_STORAGE → 加 MANAGE 检查+新版模型判定。
- 一键准备脚本 33KB 截断：拆掉两个大段 base64（ubuntu-gui 全文/中文修复），改由 App 落盘公共目录、setup.sh 运行时取回，脚本降至 ~8.4KB；自动下发前先落盘 setup.sh/ubuntu-gui.sh/zh_fix.sh（之前只下发文本，sdcard 无 zh_fix.sh 导致 step 3.5 中文化被跳过，真机日志实锤）。

**③ 工具与对话（当日 3 条）**
- 新增 AI 工具 `desktop_screenshot`：抓取 VNC 桌面画面（复用内置 VncClient 连 5900 抓帧存 PNG，image_grid 对话直显，无需容器装包）。
- `termux_exec` 两处加固：全局串行锁防并发串扰；简单容器命令自动注入容器优先 PATH（proot 继承 Termux PATH 致 git/gcc 命中错二进制）。
- AI 对话修复：新对话/清空后异步历史加载回灌顶掉新消息 → `historyLoadStale` 作废未完成加载。

## [2026-09-28] 修「桌面双击图标报 Launch Error」：文件管理器服务没人应答

用户让我看他手机里 App 的 AI 对话记录，记录里有张截图 —— 双击桌面上的 `WPS表格.desktop` 报
`This feature requires a file manager service to be present (such as the one supplied by thunar)`。
App 自带 AI 那句诊断（外壳脚本没起 thunar 守护）**是对的，而且当时确实还没修** ✗。真机核实：

1. `thunar` 进程数 = **0**；日志里是
   `Activating service 'org.freedesktop.FileManager1' requested by xfdesktop` →
   `Activated service ... failed: Process ... exited with status 1`。
2. 根因跟当天 notifyd 那次是**同一个**：**D-Bus 按需激活出来的实例拿不到 `DISPLAY`** ——
   激活环境用的是总线自己的环境，里面没有 DISPLAY，实例起来就 `cannot open display:` 退 1。
3. 修法两条：总线起来后 `dbus-update-activation-environment DISPLAY XAUTHORITY LANG LC_ALL`
   （让所有激活实例都拿到 DISPLAY）；再显式起 `thunar --daemon`（不依赖激活）。
   脚本还会在**同一条会话内**用 `GetNameOwner` 自查两个服务名有没有注册并写进日志备查；
   清场时把旧 thunar 守护一起清掉（它挂在**上一条**总线上，新会话的客户端找不到它）。
4. 真机验证（App 走一遍启动流程后）：`thunar=1`、外壳与面板同一条总线、日志出现
   `OILQUIZ_SHELL 文件管理器服务已注册 ✓ org.xfce.FileManager` 与
   `... ✓ org.freedesktop.FileManager1`，日志末尾**没有** FileManager 激活失败，noVNC 200 ✓
5. 顺带把那个"startxfce4 计数 = 2"的疑点查清：`pgrep -af startxfce4` 的**真实列表是空的** ——
   前几次都是我的计数被外层 proot 命令行里的字面量污染出来的**假阳性** ✗（也说明独占兜底一直在生效）。

## [2026-09-27] 「菜单里点没反应」根因：两套桌面会话抢屏幕（外加更正几次"总线已死"的误判）

用户报「我菜单里点没反应啊，你检查下菜单」。查下来是两个独立问题，外加我自己几次误判：

1. **同一个 X 上跑着两套 XFCE 会话** ✗：`ensure_desktop()` 另起了一条
   `dbus-run-session -- startxfce4`，`ensure_shell()` 又起了 `.quiz_shell.sh` 那条 ——
   于是出现**两个面板**（`xfce4-panel` 的单实例检测走会话总线，两条总线互相看不见，
   各自都认为自己唯一），而**窗口管理器只有 `.quiz_shell.sh` 那条有**
   （`startxfce4` 在 proot 里退回 Failsafe，不拉 xfwm4）。两个面板都贴屏幕顶部重叠，
   用户点到哪一份全看谁在上面。
   修法：`ensure_shell()` 改成按「外壳脚本在不在」判断（原来按 `xfce4-panel` 判断，
   面板还没起来时就会重复起一条）；`ensure_desktop()` 在外壳已运行时**只清理**抢屏幕的
   `startxfce4` / `xfce4-session`（含被 D-Bus 激活起来的）；调用顺序改成先 `ensure_shell` 再 `ensure_desktop`。
   实测组件数：`xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1 xfce4-session=0` ✓
2. **更正我前面几次"总线已死"的误判** ✗✗（真机隔离实验）：
   **同一条会话里** `dbus-send --session … ListNames` **✓ 通**；换一个
   `proot-distro login` 会话去连**同一条**总线 **✗ 不通**（socket 文件明明在、`-S` 也判真）。
   原因是 proot 下 D-Bus 鉴权要用 `SO_PEERCRED` 读对端身份，另一个 proot 实例拿不到，
   客户端会一直卡在 AUTH 直到超时（报的就是 `Did not receive a reply … the reply timeout expired`）。
   X 之所以能跨会话用，是因为 Xvnc 带了 `-ac`（根本不查授权）。
   **结论：总线 + 全部客户端必须待在同一个 proot 会话里**，不许再从别的会话去"测"总线。
3. **外壳改由 `dbus-run-session` 持有总线**（`GUI_INNER_COMMAND` 与 `ensure_shell` 都改成
   `dbus-run-session -- bash .quiz_shell.sh`），脚本内保留一个**同会话验活**的兜底：
   连不通就自己 `--nofork` 重开一条 —— GTK 拿不到可用总线时会自己 autolaunch 一条 `--fork` 的
   临时总线，那种 daemon 随一次性会话被 `--kill-on-exit` 回收 → 面板就挂在死总线上。
   脚本把结果写进 `/tmp/quiz_shell.log`：`OILQUIZ_SHELL bus=… 应答=活` ✓
4. **顺手查清"点第一项没反应"**：Whisker 菜单第一项是「网络浏览器」、第二项「邮件阅读器」，
   容器里**既没有浏览器也没有邮件客户端** —— 日志里直接写着 `Couldn't find a suitable web browser!`。
   真机用 xdotool 发**真鼠标事件**点第 4 项「使用命令行」，Thunar 窗口随即出现（OCR 确认）✓，
   说明菜单的启动通路本身是好的，不好使的是那两项没有对应程序。
5. **踩到的一个坑**：把 `>> /tmp/quiz_shell.log` 写在 **Termux 侧**那条命令上会直接
   `Permission denied`（Android 的 `/tmp` 不可写）→ 整条外壳会话根本没起来（组件数全 0）。
   重定向必须写在**容器内**执行的那条命令里。
9. **外壳脚本里再加一道"独占兜底"**：连 `startxfce4` 那条会话也一起清掉
   （`pkill -9 -f 'startxfce[4]'`）。原因是真机上仍偶发出现"外壳会话 + startxfce4 会话"并存，
   两边的面板都贴屏幕顶部，用户点到的那份可能挂在另一条总线上。这条脚本每次启动都由 App
   用 base64 重写（`ensure_shell_b64`），所以兜底永远是最新版本，不依赖"一键准备"。
10. **终态真机验证**（当前这台机器上）：
    - `startxfce4` 进程 **0 个**；`Xvnc=1 xfwm4=1 xfce4-panel=1 xfce4-notifyd=1 xfdesktop=1 xfce4-session=0`；
    - **面板与外壳脚本在同一条总线上**（`/tmp/dbus-x0KkmNkdhz` 两边一致）；
    - noVNC 网页 `http://127.0.0.1:6080/vnc.html` → **200**；
    - 用 xdotool 发**真鼠标事件**逐行点菜单：`y=230` → **Thunar 起来了** ✓；
      `y=130` 只拉起 `xfce4-mime-helper`（那一项是「网络浏览器」，容器里没装浏览器，
      所以看起来"点了没反应"——这正是用户最初那个观感的一部分）。

6. **启动器只在「一键准备」时写过一次** ✗ —— App 升级后 `ensure_shell` / `ensure_desktop` 的改动
   **根本没到设备上**（设备上那份 41 KB 的 `~/ubuntu-gui` 还是老逻辑，依旧会另起 `startxfce4`）。
   现在 `startGuiInTermux` / `restartGuiInTermux` 每次都会把**当前版本**的启动器写到公共下载目录
   （`Download/OilQuiz/termux_env/ubuntu-gui.sh`，跟 `setup.sh` 同一条通道），再用一条短命令
   `cp` 进 `$HOME` 并执行（/sdcard 是 noexec，必须先拷出来）。启动器从此跟 App 同版本。
7. **外壳加了单实例锁**（`/tmp/oilquiz_shell.pid`）：真机上出现过多条外壳会话，它们会互相
   `pkill` 组件、把面板重新挂到新总线上，最后"谁在屏幕上看运气"。后起的会话现在直接退出。
8. **`xfce4-notifyd` 的裸命令是错的** ✗：Ubuntu 24.04 把可执行文件放在
   `/usr/lib/<多架构>/xfce4/notifyd/xfce4-notifyd`，**不在 PATH 里** —— 脚本里写 `xfce4-notifyd &`
   会 `command not found` 静默失败（真机查到的就是"通知守护一直没起来"）。
   现在按真实路径启动（带 `command -v` 兜底）。同理 `gui_pkgs_ok()` 里的
   `command -v xfce4-notifyd` 判定**永远为假**，会导致老环境每次重跑「一键准备」都白装一遍 —— 一并改成查真实路径。

## [2026-09-27] 换源到清华 ports + 装图形包管理器 Synaptic（并把两件事固化进一键准备）

用户问「有包管理器吗，有包商店吗」，随后「那就换 顺便安装 synaptic」。

1. **换源**：容器里原来是官方 `http://ports.ubuntu.com/ubuntu-ports`（arm64 专用源）——
   而一键准备里的换源 sed 只匹配 `archive/security.ubuntu.com`，所以**一直没换动** ✗。
   现已改为 `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports`，实测 `apt update` 拉
   **36.7 MB / 16 秒（≈2.2 MB/s）** ✓（这次是全量重拉，看得出速度）。原文件备份为 `*.bak-oilquiz`。
2. **装了 synaptic**（图形包管理器）：`/usr/sbin/synaptic` ✓、菜单项 ✓；在桌面上实测能起来，
   而且**界面是中文** ✓（OCR：`您应该定期刷新软件包信息…共列出 912 个软件包，已安装 622 个，已破坏 0 个`）。
   已装包数 618 → 622。
3. **固化进一键准备**：两处换源 sed（`ZH_FIX_SH` 与 step 4）都补上 `ports.ubuntu.com/ubuntu-ports` 映射；
   step 5 的 apt 列表与 `gui_pkgs_ok()` 前置检查都加上 `synaptic`（否则老环境重跑会跳过、永远补不上）。
4. **限制说明**：proot 容器里 **snap / flatpak 都用不了**（无 systemd、无 bubblewrap），
   所以"软件商店"只能基于 apt 仓库（可装 **91,290** 个包）。

## [2026-09-27] 「一键准备」查漏补缺：删掉 step 6 里残留的旧脚本块 + 把新组件纳入前置检查

用户问「一键配置这个功能都配置好了吗」。逐段核对后发现两处真问题，已修：

1. **step 6 里混进了一段旧脚本**（一个没有 heredoc 开头的 `case "$1" in … esac`，外加一行孤立的 `QUIZ_GUI_EOF`）——
   它会被当成正常脚本执行，里面用的 `PORTUP` / `$INNER` / `$LOG` 在该上下文里都没定义；
   `$INNER` 为空时还会拿空命令去起会话。已删除并在原位留了说明。
2. **`gui_pkgs_ok()` 只检查旧的 5 项**（Xvnc / startxfce4 / 中文字体 / websockify / noVNC 网页）——
   老环境重跑「一键准备」会**跳过 step 5**，永远补不上新增的
   `xfce4-notifyd / xfce4-taskmanager / xfce4-screenshooter / ristretto / xarchiver`。已补进检查项。
3. 现在「一键准备」完整覆盖：（0）allow-external-apps（1）存储权限（2）proot-distro（3）Ubuntu 容器
   （3.5）容器中文化 + 北京时间 + 面板开始菜单（4）容器内完整 Python（5）TigerVNC / XFCE / 中文字体 /
   noVNC+websockify / 六个新组件（6）写 `~/ubuntu`、`~/ubuntu-gui` 并做最终验证。
   另有 `.quiz_shell.sh`、`.quiz_kill_stale.py` 两个助手由图形界面启动流程写出
   （`ensure_shell_b64` / `kill_stale`），一键准备不必重复写。
4. **门禁**：把 SCRIPT_TEMPLATE 从 Java 源码抽出来跑 `bash -n` → **退出码 0** ✓
   （这种"残留块"就是靠它才发现得了）；并抽查 13 个关键点全部命中 ✓
   （中文相关字面量如 locale-gen / Asia/Shanghai / whiskermenu 在 ZH_FIX_SH 里，经 `__ZH_FIX_B64__` 注入 ✓）。

## [2026-09-27] 桌面外壳改用「共用常驻 D-Bus 总线」启动（顺带更正一次误判）

用户问「基本功能够用了吧」。核对时我一度以为面板没画出来（`xwininfo ... | grep xfce4-panel` 只看到 10x10），
后来发现**是我的检查方式错了**：真正的面板窗口**没有名字**，要按 class 用
`xdotool search --class xfce4-panel` 才看得到 —— 实测 **1280x27 @ 0,0** ✓
（桌面 1280x720、整屏截图 28100 色、启动器菜单正常）。

不过排查中确实抓到一个真问题并修掉了：

1. **`xfce4-panel` 会 fork 到后台**，而 `dbus-run-session -- xfce4-panel` 的直接子进程一退出就会**把总线拆掉** ——
   真机报 `xfce4-panel: Name org.xfce.Panel lost on the message dbus, exiting` 加
   `There is already a running instance`，面板因此会时好时坏。
   （`xfwm4` / `xfce4-notifyd` / `xfdesktop` 不 fork，所以它们一直没问题。）
2. **修法**：新增容器侧脚本 `~/.quiz_shell.sh`（App 用 base64 写出）：用 `dbus-daemon --session --fork`
   起一条**独立常驻**的总线（地址存 `/tmp/oilquiz_bus_addr`），四个组件（wm / notifyd / panel / desktop）
   都挂它；`ensure_shell` 改成 `bash ~/.quiz_shell.sh <角色>`，顺序 **WM → notifyd → 面板 → 桌面**，
   面板每次重建（避免旧实例挂在已被拆掉的总线上互相顶掉）。
3. **验证**：冷启动（杀掉全部外壳进程）→ `~/ubuntu-gui restart` →
   `xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1`；面板真窗口 **1280x27**；
   整屏截图 **28100 色**；点左上角启动器弹出中文菜单
   （使用命令行 / 收藏夹 / 最近使用 / 全部应用程序 / 互联网 / 开发 / 设置 / 图形 / 系统）✓。

## [2026-09-27] 补齐远程桌面缺的组件 + 通知区域（systray）回归面板

用户问「看看还有哪些组件漏了」，随后「装上」。

1. **审计结果**：面板配置里用到的插件 `.so` **一个不缺** ✓；缺的是软件层组件 ——
   `xfce4-notifyd`（通知守护）、`xfce4-taskmanager`、`xfce4-screenshooter`、`ristretto`（看图）、
   `xarchiver` + `thunar-archive-plugin`（解压/压缩）、`catfish`（搜索）、`parole`（播放器）、
   `xfce4-clipman`（剪贴板）、`xfce4-power-manager`（容器里意义不大）。
   当时在跑的守护只有 `xfwm4 / xfce4-panel / xfdesktop / xfsettingsd / xfconfd`。
2. **已装**（6 个，全部带中文词典 ✓，菜单项 40 → 45）：
   `xfce4-notifyd xfce4-taskmanager xfce4-screenshooter ristretto xarchiver thunar-archive-plugin`。
3. **通知区域（systray）加回面板** ✓：当初它崩溃正是**因为没有通知守护**；装上 `xfce4-notifyd` 后
   把 `plugin-6`（systray）加回 `panel-1` 的 `plugin-ids`，实测**通知区域 applet 正常加载、面板稳定不崩** ✓。
4. **做成持久**：`ensure_shell` 增加"缺就补 `xfce4-notifyd`"，并**排在面板之前**
   （否则面板启动时通知区域仍会因为找不到守护而崩）；一键准备的 apt 列表也补上这 6 个包。
5. **真机验证**：把四样全杀掉（`xfwm4=0 xfce4-notifyd=0 xfce4-panel=0 xfdesktop=0`）→
   `~/ubuntu-gui restart` → **`xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1`，通知区域=2** ✓；
   启动器菜单可开、中文项正常 ✓。

## [2026-09-27] 远程桌面「启动器点不到」真因：**没有窗口管理器**（已修，并做成每次启动自动补齐）

用户让检查「远程 ubuntu GUI 启动器的功能是否正常」。结论：**不正常**，但根因不在启动器，而在**桌面外壳没被拉起来**。

1. **现象**：`xfce4-panel=0 / xfwm4=0 / xfdesktop=0`；X 里**所有窗口都是 `10x10+10+10`** ——
   这是**没有窗口管理器**的典型症状：XFCE 组件建了窗口但没人给它们 map/定位，于是面板看不见、也点不到。
2. **为什么会没有**：proot 里 XFCE 的会话管理器（`xfce4-session`）拉不起客户端 ——
   用户级会话文件只剩 `Failsafe` 且 `Client0..4_Command` 全为 `empty`；而 `xfwm4` 又必须要有 xfconf
   （D-Bus 自动激活在 proot 下不稳，拿不到就 `Xfconf could not be initialized` 直接退出）。
   这条链任一环断了就退回"什么都不启动"。
3. **修法（已验证）**：不再指望会话管理器。启动脚本新增 `ensure_shell`：启动后检查
   `xfwm4` / `xfce4-panel` / `xfdesktop`，**缺哪样补哪样**，顺序 WM → 面板 → 桌面，
   各自用**独立的 `dbus-run-session`**（proot 下这条最稳；`xfwm4` 关合成 `--compositor=off`，VNC 无 GL）。
   会话自己能起来时它是空操作，不会重复启动。
4. **真机验证**：
   · 手动杀掉三件套（`xfwm4=0 panel=0 xfdesktop=0`）→ `~/ubuntu-gui restart` →
     **`xfwm4=1 xfce4-panel=1 xfdesktop=1`，`_NET_WM_NAME = "Xfwm4"`** ✓ 自动补齐成立；
   · 面板回来后可点开启动器，中文菜单：`收藏夹 / 最近使用 / 全部应用程序 / 附件 / 互联网 / 开发 / 设置 / 图形 / 系统` ✓；
   · 从菜单点「终端模拟器」真的起来了（`xfce4-terminal` 进程出现）✓。
5. 过程说明：期间我误改坏过设备脚本一次（`ensure_wm` 引号转义），已修回并 `bash -n` 通过；
   用户要求"改回去"时也把那次提交 `git revert` 了（`2d52c318`），本次是在干净基础上重做的。

## [2026-09-27] 「输入指针没有捕获」真因：noVNC 把自己的「只读模式」记在了 localStorage

用户报「输入指针没有捕获啊，审查下代码」，并怀疑是浮层吞了操作。逐段查完后，真因是 **noVNC 自己的设置持久化**：

1. **真因（有实据）**：翻 WebView 的 localStorage（`app_webview/Default/Local Storage/leveldb`）看到
   `http://127.0.0.1:6080` 下面存着 **`view_only = "true"`**。而 noVNC 读设置的过程**不做布尔转换**：
   ```js
   initSetting(name, defVal) {
       let val = WebUtil.getConfigVar(name);                        // query 参数，原始字符串
       if (val === null) val = WebUtil.readSetting(name, defVal);   // localStorage，也是原始字符串
   }
   ```
   于是字符串 `"true"` 被直接赋给 `UI.rfb.viewOnly` → **noVNC 静默丢弃全部指针与键盘输入**。
   （传 `view_only=false` 也没用：JS 里字符串 `"false"` 同样是真值。）
2. **修法（最小改动）**：`onPageFinished` 里检查一次，发现这条记忆就 `localStorage.removeItem('view_only')`
   并重载，让默认的布尔 `false` 生效；每次进页面都会自愈。
   实测：leveldb 里该键**最后一条记录已变成删除**（此前紧跟 `true`），状态条也从 `err` 变成
   `noVNC 已连接 127.0.0.1:5900`。
3. 顺带修：状态条那个读 noVNC 状态的小探针原来写的是 `window.UI`，但 noVNC 1.3 的 `app/ui.js` 是
   **ES 模块**（`const UI = …; export default UI`），**没有 window.UI** → 一直返回 `err`。
   改用**动态 import 取同一个模块实例**（ES 模块按 URL 单例）。
4. **不是浮层吞的**（回答用户最初怀疑）：浮层只有 `wrap_content` 状态条与 44dp 的 ≡，触摸监听也只在它们自己身上；
   `dumpsys`/MIUIInput 都能看到触摸到达 Activity 窗口。之前那次「本页 5 / 画面 0」的测量很可能是戳在状态条区域造成的，
   不足以下结论 —— 所以按用户要求把那些诊断代码全部回滚，只留这个有实据的最小修复。
5. 回滚说明：`git checkout -- .` 清掉了诊断代码；顺手 `git clean -fd` 删掉的都是未跟踪的生成物/空目录
   （`src/jniLibs`、`src/main/assets/weather`、`src/main/cpp/-p` 等），`assembleDebug` 验证 **BUILD SUCCESSFUL**，不影响构建。

## [2026-09-27] 审查「输入指针没有捕获」：三个真 bug（其中一个把 6080 彻底搞死）

用户报「输入指针没有捕获啊，审查下代码」。按链路逐段查，结论是**服务端没问题，App 侧有三个 bug**。

1. **先证明服务端是好的**（避免误判方向）：容器里起 `xev`，用裸 RFB 客户端（握手 → SetPixelFormat → PointerEvent）
   点它的窗口，xev 收到 **ButtonPress=11 / ButtonRelease=10 / MotionNotify=34**，事件是 `button 1, same_screen YES`
   —— Xvnc 的指针输入链路完全正常。
   （顺带踩坑：RFB 的 SetPixelFormat 是 **20 字节** = 1 类型 + **3 填充** + 16 格式，我第一次漏了填充，服务端直接断我连接。）
2. **真 bug ①：每次启动/重启都新起 websockify、旧的从不清理**。实测堆到 **9 个**，最后监听进程 accept 卡死 ——
   **5900/6080 双双 timeout（不是 refused）**，手机端画面停在最后一帧、点哪儿都没反应，
   这正是用户看到的「输入指针没有捕获」。新增容器内 `kill_stale`：按 `/proc/<pid>/cmdline` 的
   **argv[1] 是否以 /websockify 结尾**精确识别（避免 `pkill -f` 把执行它的会话一起杀掉），启动与重启路径都先清一遍。
3. **真 bug ②：我自己的心跳在打 websockify**。`VncWebActivity` 原来每 4 秒开一个裸 TCP 连接探 6080，
   而 websockify **每个连接 fork 一个子进程**（日志里的 `new handler Process`）—— 这些「连上但不发请求」的子进程
   全挂着不退（实测存活 7 个），最后把监听拖死。改成页面加载后**只读 noVNC 自己的 JS 状态**
   （`UI.rfb._rfbConnectionState`），一个 socket 都不开。
4. **真 bug ③：状态条的拖动监听被覆盖**。`FloatingDrag.attach(bar…)` 之后又调 `resetTimerOnTouch(bar)`，
   两者都是 `setOnTouchListener`、后设的生效 → 状态条拖不动（只有 ≡ 能拖）。调整为先 resetTimer 再 attach。
5. 顺手补：WebView 加 `setFocusable/setFocusableInTouchMode/requestFocus`（键盘输入需要焦点），
   以及 `onPageFinished` / `onReceivedError` 回调（页面加载失败会直接说清楚，不再只是黑屏）。
6. **修完实测**：存活 websockify = **1**、5900 出 `RFB 003.008`、6080 监听、`vnc.html` HTTP 200；
   手机端 noVNC 正常显示 XFCE 桌面（OCR：`文件(F) 编辑(E) 视图(V) 转到(G) 书签(B) 帮助(H)`、
   `警告：您正在使用超级帐户…`）。

## [2026-09-27] 按用户选择：留在 XFCE，卸载 MATE

用户回「4 把 mate 删了」（选「留在 XFCE」，并删掉 MATE）。

1. 容器里 `apt-get purge -y 'mate-*' caja caja-common marco` + `autoremove`：剩余 mate/caja/marco 包 **0 个**，
   `/usr/share/xsessions/` 只剩 `xfce.desktop`，容器释放约 **1GB**（已用 177G → 176G）。
2. App 侧把 MATE 相关内容全摘掉：一键准备的 apt 列表、`gui_pkgs_ok` 的 mate-session 检查、
   `desktop` 子命令的 mate 分支、`ensure_desktop` 的 mate 检测与 pkill（源码里 `grep mate` = **0**）。
   默认会话保持 `startxfce4`；切换能力保留（`bash ~/ubuntu-gui desktop xfce4|lxqt`）。
3. 设备上的 `~/ubuntu-gui` 同步（mate 引用 13 → 2），删掉 `~/.quiz_desktop`（走默认 XFCE）。
4. 卸载后回归验证：`xfce4-session=2 / xfce4-panel=2 / lxqt=0`、桌面 `1920x881` 截图 **4926 色**（内容正常）、
   noVNC 网页 `HTTP 200`、新 APK 安装 `Success`。

## [2026-09-27] 桌面换成 MATE，并做成可切换（mate / xfce4 / lxqt 一条命令）

用户说「把 ubuntu 的桌面换一下，看看有没有合适的，大点也没事」。

1. 装了 **MATE 1.26**（mate-desktop-environment + mate-terminal + caja）。实测进程齐活：mate-session / marco /
   mate-panel ×5 / caja；中文词典 10 个（caja、mate-panel、mate-session-manager…）；面板标题已经是
   「顶部面板 / 底部面板」。
2. 踩坑与修法（都是真机踩出来的）：
   · `ubuntu-mate-default-settings` 解包卡在 `orca.desktop.dpkg-new: Permission denied`（proot 下 dpkg 建临时文件被拒）
     → 先 `rm` 掉冲突文件再 `dpkg -i` 就过了；
   · 我一度用 `--force-remove-reinstreq` 把它拔了 → `mate-session-manager` 依赖不满足，3 个包卡在 `iU`
     → 按上面重装后 `dpkg --configure -a` 全部配置完成；
   · **在容器里 setsid 起的桌面会话会随那条 login 退出被杀**（我一开始截到的"空桌面"就是这个原因），
     必须像 App 那样从 Termux 侧 `setsid nohup proot-distro login ... exec dbus-run-session -- $SESSION`。
3. **桌面可切换**：`~/.quiz_desktop` 存会话命令，`bash ~/ubuntu-gui desktop mate|xfce4|lxqt` 切换，
   `~/ubuntu-gui status` 显示当前桌面。`ensure_desktop` 修了一个真 bug：**当前跑的会话与配置不一致时先杀掉旧会话**
   （原来只判断"有没有会话在跑"，于是切了桌面还是旧的在跑 —— 真机实测 MATE 与 XFCE 同时在跑、两套面板打架）。
   一键准备里也加了 MATE（apt 列表 + `gui_pkgs_ok` 检查 mate-session）。
4. **比 XFCE 差的地方（如实说）**：MATE 的**通知区域 applet 会崩**并在启动时弹一次中文报错框
   （dconf 里是 `applet-iid='NotificationAreaAppletFactory::NotificationArea'`，可以摘掉）；没装壁纸包，桌面是纯色。
   XFCE 这套在 proot 下明显更稳（整个会话都在用）。随时 `bash ~/ubuntu-gui desktop xfce4 && bash ~/ubuntu-gui restart` 切回。

## [2026-09-27] 浮层再进化：松手吸附最近边缘 + 长按收起成小圆点（再长按恢复）

用户回「可以」，同意上一条里提的两个行为。在已有「拖动 + 记忆位置 + 越界 clamp」之上加：

1. **松手吸附到最近的左右边缘**（160ms 动画），不会停在屏幕正中挡着桌面；纵向不吸附、停在手指位置，
   吸附后的坐标同样写进 SharedPreferences。未附着窗口时（测试环境）直接 setX，保证行为可断言。
2. **长按收起**：noVNC 页长按状态条或 ≡ → 浮层收成 **30dp 半透明小圆点**贴着边（状态条隐藏、画面完全干净），
   收起状态也存 prefs；**再长按小圆点恢复**；点一下 ≡ 会自动从小圆点恢复并展开状态条（都有 Toast 提示）。
   原生模式页长按顶部胶囊 = 回到默认位置（新增 `FloatingDrag.reset`）。
3. 真机用例 `FloatingDragDeviceTest` 扩到两个：
   `dragSnapsToNearestEdgeAndPositionIsRemembered`（跟手 + 左/右边缘吸附 + 记忆 + 越界 clamp + 原地算点击）、
   `longPressFiresAndIsNotTreatedAsTap`（按住超过系统长按时长触发长按，且不被误判成点击）—— 真机 `OK (2 tests)`。

## [2026-09-27] VNC 浮层可以拖了：状态条与悬浮 ≡ 跟着手指走，位置记得住

用户问「动态按钮不能拖动啊，为何是固定位置」—— 之前这两个浮层是用 `layout_gravity` 钉在左上角的，
根本没接拖动。

1. 新增 `FloatingDrag`（可复用）：拖动**超过 touchSlop 才算拖**（否则仍按点击处理，按钮不会点不动）、
   位置写进 `SharedPreferences`（`vnc_prefs` 的 `<key>_x/_y`，下次进来还在老地方）、
   拖动中与转屏后都会 **clamp 回屏幕内**（不会甩到看不见的地方）。
2. 接上去的位置：noVNC 外壳页的**状态条**（拖状态文字/空白处，里面的按钮照常点）和**悬浮 ≡**（整个都能拖）；
   原生模式页的**顶部状态胶囊**同样可拖；两个页面转屏后都重新 clamp。
3. 真机用例 `FloatingDragDeviceTest`：直接合成 MotionEvent 派发给 View（这台 MIUI 明确拒绝 shell 注入触摸 ——
   `SecurityException: Injecting input events requires the INJECT_EVENTS permission`，所以 adb 没法模拟拖动），
   断言四件事：**跟手 300/200px**、**位置写进 SharedPreferences**、**越界 clamp 到 800/1900**、
   **原地一下仍算点击**。真机结果 `OK (1 test)`。

## [2026-09-27] 图形界面（VNC）横竖屏适配：resize=remote + 旋转后重协商 + 手动「横屏」按钮

用户要求「进行横竖屏适配」。之前 noVNC 用的是 `resize=scale` —— 只是把固定的 1280x720 桌面**硬缩进**屏幕，
竖屏下只剩中间一条、字还发虚。改成真正的适配：

1. **`resize=remote`**：noVNC 用 SetDesktopSize 请求 Xvnc 把远端桌面改成与手机窗口一致的分辨率，
   XFCE 自动重排。真机实测（同一台手机，靠自动旋转切方向）：
   | 方向 | 手机截图 | Xvnc 桌面分辨率 |
   |---|---|---|
   | 横屏 | 2656x1220 | **817x375** |
   | 竖屏 | 1220x2656 | **375x817** |
   比值都是 2.18 —— 桌面分辨率跟着 WebView 的 CSS 视口走，不是拉伸放大。
2. **旋转后显式重协商**：本页带 `configChanges=orientation|screenSize` 不会重建，WebView 那次 resize
   事件不一定触发重新协商，所以 `onConfigurationChanged` 里延迟 700ms 重载一次页面（本地连接，重连 < 2s），
   并把状态条叫回来显示「横屏/竖屏：正在重新适配远端桌面…」。
3. **手动方向**：状态条新增「横屏/竖屏」按钮（系统自动旋转锁着时也能切）与「系统栏」开关；
   状态条改成两排（上排状态 + 收起，下排 5 个短标签按钮），竖屏也不会挤爆。
4. 原自研客户端（原生模式）本来就有 `syncDesktopSize` + 「横屏」按钮，两条路行为一致。
5. 真机用例同步：`vncWebShellReady` 断言 `resize=remote`，并新增 `btn_vnc_web_rotate` / `btn_vnc_web_bars` 两个控件断言。

## [2026-09-27] VNC 界面改用现成的 noVNC（MPL-2.0）：「外壳式」实现，不再自己画界面

用户问「可以使用别人的源代码吗。你自己设计的不行啊」。先把三家的 LICENSE 拉下来核对：

| 项目 | 协议 | 能否进这个 **MIT** 项目 |
|---|---|---|
| bVNC / aRDP（iiordanov） | **GPLv3**（LICENSE 原文） | ❌ 搬进来整个 App 必须转 GPLv3 并开源 |
| android-vnc-viewer / LibVNC | GPLv2 | ❌ 同上 |
| **noVNC（官方）** | **MPL-2.0**（core 库） | ✅ 可用，保留版权声明即可 |

用户选「内置 noVNC」。实现分工（关键：**noVNC 一行代码都不进我们的 APK**，它由容器里 Ubuntu 的
`novnc` 包提供，我们只是"用"它 —— 连 MPL 的分发义务都不涉及）：

1. **容器侧**：`apt install novnc websockify`（Ubuntu 24.04 是 noVNC 1.3.0）。`GUI_INNER_COMMAND`
   改成 `Xvnc … &` + `exec websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900` ——
   **一个进程同时干两件事**：发布 noVNC 网页 + 把 WebSocket 桥到 Xvnc；没装 websockify 时退回 `wait`
   只跑 Xvnc，不会把图形界面搞死。`gui_pkgs_ok` 也加了 websockify/novnc 检查，老环境再点一次「一键准备」即补上。
2. **App 侧**：新增 `VncWebActivity`（WebView 外壳）+ `activity_vnc_web.xml`，只保留一条中文状态条
   （状态 / 启动图形界面 / 重连 / 原生模式 / 收起），8 秒不动自动收起，只留左上角 ≡。
   协议、渲染、输入、缩放、设置面板、剪贴板**全部由 noVNC 承担**，连中文界面都是它自带的
   （`app/locale/zh_CN.json`，随设备语言生效）。工具入口默认走这个页面，原自研客户端保留为「原生模式」。
3. **真机验证（链路整条打通）**：
   ```
   HTTP 200 15212 bytes（/vnc.html，含 app/ui.js）
   握手: HTTP/1.1 101 Switching Protocols
   RFB 横幅: b'RFB 003.008\n'   服务端海报正常: True
   ```
   另加两条真机用例：`vncWebShellReady`（外壳页布局 + URL 参数 autoconnect/resize/path/port）、
   `novncServedByContainer`（真的去 6080 取 vnc.html 200，并做一次 WebSocket 握手读到 RFB 横幅）。
8. **顺手修掉两个真 bug（都是这轮真机踩出来的）**：
   · **僵尸进程骗过健康检查**：`pgrep -x Xvnc` 会匹配到僵尸（`30739 Z Xvnc`），于是
     `~/ubuntu-gui start` 回 `GUI_ALREADY_UP`，用户那边 5900 根本没监听。`UP()` 现在读
     `/proc/<pid>/status` 的 `State:` 字段，只认 R/S/D/T/t/W/X/I 这些活状态。
   · **websockify 抢不到端口会把整个桌面带死**：原来 `exec websockify` 当会话主进程，若 6080 被
     上一轮的实例占住，它启动即失败 → proot 会话结束 → Xvnc 被 `--kill-on-exit` 带走 →
     5900 没人监听（恰好又触发上面那个僵尸误判）。现在会话寿命只跟随 Xvnc（`wait $XVNC`），
     websockify 降级成后台助手，失败也只写 `/tmp/quiz_websockify.log`，桌面照常活着。
9. **真机截图核对（noVNC 在手机里真的连上了）**：截图前后取 `~/.quiz_gui.log`，服务端明确记录到
   noVNC 的客户端会话：`connecting to: 127.0.0.1:5900` → `Connections: accepted` →
   `Client needs protocol version 3.8` → `Client pixel format depth 24 (32bpp) little-endian bgr888`；
   手机截图顶部 500px 的 OCR 读到桌面内容（`OilQuiz GUI`、`2026-9-27`、`17:xx`）。

## [2026-09-27] 图形界面页控件重排：参考 RealVNC / bVNC，画面优先 + 分组 + 自动收起

用户说「vnc界面的按钮你好好管理下，参考下别人的」。老版是一条常驻的横向滚动按钮栏（15 个按钮挤一行，
非要横滑才能找到），既挡画面又难用。按主流 VNC 客户端的三条惯例重排：

1. **画面优先**：桌面铺满整屏（VncView 从 weight 改成 match_parent），控件全部改成浮层；
   手指一碰画面就自动收起面板（新增 `VncView.OnCanvasTouchListener`），8 秒不动也自动收起。
2. **分组 + 等宽 + 不滚动**：底部面板四组 —— 连接（端口/启动图形界面/连接/断开）、
   输入（键盘/右键/粘贴/Ctrl/Alt/Shift）、显示（适应/缩小/放大/旋转/系统栏/更多）、
   更多（滚轮↑/滚轮↓/Esc/Tab/Enter/帮助）；每组一行、按钮 weight=1 等宽，**删掉了 HorizontalScrollView**。
3. **状态可视 + 可用性跟着连接走**：右键/适应/系统栏/Ctrl/Alt/Shift 用选中态（checkable + 透明度）表示，
   不靠状态文字去猜；没连上时「断开」置灰，连上后「连接/启动图形界面」置灰。
4. 新增交互：Ctrl/Alt/Shift 修饰键开关（开着=一直按住，配合点击可发 Ctrl+点击；发普通键或断开前自动松开）、
   Esc/Tab/Enter 常用键、「帮助」弹窗（手势 + 键盘 + 面板说明，替代原来常驻的一行小字）。
5. 顶部状态胶囊：一行状态 + ≡ 展开/收起（原来状态行和端口框各占一整行高度）。
6. 真机用例 `vncLayoutInflates` 扩展：新增 8 个控件 id 断言，并断言「没有横向滚动容器」「更多行默认收起」
   「面板每行按钮等宽」「至少 3 行分组」。
7. 静态核对已过：布局无 HorizontalScrollView、每行最多 6 个等宽按钮、所有旧 id 保留；
   `BUILD SUCCESSFUL`（含 androidTest 编译）。**设备侧用例与截图待手机空闲时补跑**
   （当时用户正在使用手机，前台是别的 App，未抢前台、未跑 instrumentation）。

## [2026-09-27] 界面为何是英文：Ubuntu 精简镜像把翻译词典整类排除了（时区一并对齐北京时间）

用户问「就问一句为何不是中文，而且时区也不对」。查清后是**容器根文件系统的包管理配置**问题，不是环境变量。

1. **根因（真机证据）**：`/etc/dpkg/dpkg.cfg.d/excludes` 里有一行
   `path-exclude=/usr/share/locale/*/LC_MESSAGES/*.mo` —— 装包时所有程序自带的翻译词典都被跳过、根本没落盘。
   · `dpkg -V xfce4-panel` 报 **65 个 missing**，其中 63 个是各语种 `.mo`；
   · 而 `dpkg -L xfce4-panel` 的语种列表里 `zh_CN` 明明在 —— 不是上游没翻译，是本地被排除了；
   · `ls /usr/share/locale/zh_CN/LC_MESSAGES` = **0 个文件**（`/usr/share/locale-langpack/zh_CN` 有 413 个，
     所以终端/coreutils 是中文，而 XFCE/Thunar 这些「词典只在自己包里」的组件永远英文）。
2. **修复**：解除该排除 → 已装的包 dpkg 不会补写 `.mo`，于是用 `apt-get download + dpkg-deb -x` 把
   xfce4-panel/xfwm4/xfdesktop4/libxfce4ui/xfconf/exo/thunar/appfinder 的中文词典手动抠回 `/usr/share/locale`。
   实测 `zh_CN` 词典 0 → 10 个（`xfce4-panel.mo`、`thunar.mo`、whisker 都在）。
3. **时区**：`/etc/timezone=Asia/Shanghai` + `/etc/localtime` 软链；容器 `date` →
   `2026年 09月 27日 星期日 16:27:21 CST`（中文星期 + 北京时间，与手机一致）。
4. **语言环境**：`locale-gen` 生成 `zh_CN.UTF-8`，写入 `/etc/default/locale`、`/etc/environment`、
   `/etc/profile.d/00-quiz-locale.sh`；XFCE 会话启动行显式带 `LANG/LANGUAGE/LC_ALL=zh_CN.UTF-8`
   （改的是 `ensure_desktop`：之前没带，即使有词典也不会生效）。
5. **用现成的中文启动器**（用户提示「别自己写」）：装上 `xfce4-whiskermenu-plugin`；系统自带的
   `xfce4-appfinder` 与面板「所有应用程序」菜单在词典补回后**自己就变中文** —— 截图 OCR 实测
   `Thunar 文件管理器`、`用文件管理器浏览文件系统`、`Xfce 终端`、`Xfce 设置`。手写的那版已删除。
6. **固化进「一键准备」**：新增容器脚本 `ZH_FIX_SH`（幂等，72 行）与「3.5/6 中文界面与北京时间」步骤；
   图形界面启动/重启脚本新增 `ensure_zh()`（0.2 秒快速守卫，只在缺词典时才修复并重启会话）。
7. **验证**：`bash -n` 通过（`.quiz_zh_fix.sh`、`ubuntu-gui`）；真机 `bash ~/ubuntu-gui start` → `GUI_ALREADY_UP`；
   桌面截图 OCR 中文正常；`BUILD SUCCESSFUL`。
8. **现成的中文开始菜单挂上面板**（用户说「改」）：Whisker Menu 装好后插到顶部面板最左边、顶替原来的菜单。
   踩到两个坑并已解决：① 手动解包只拷了插件、没拷依赖 → 缺 `libgtk-layer-shell.so.0` → 面板弹
   「插件"Whisker 菜单"意外地离开了面板」（60 秒内重启多次后被面板自动从配置里删掉）；改为
   `apt-get install` 并补装 `libgtk-layer-shell0 libgarcon-1-0 libgarcon-gtk3-1-0`，`ldd` 缺库数归零后正常；
   ② 必须在 `startxfce4` 之前改面板配置，否则运行中的 xfconfd 会写回旧配置覆盖 —— `ensure_zh` 现在
   **先停会话再修复**，守卫也加了「Whisker 已挂」这一项（约 0.2 秒）。
   实测点开菜单：`关于 Xfce`、`应用程序查找器 / 查找和启动在您系统上安装的应用程序`、
   `文件管理器 / 浏览文件系统`、`文本编辑器设置 / 配置 Mousepad 文本编辑器` —— 全中文。

## [2026-09-27] 「一键准备」把能自动的全自动：通道自检 + 一行修复 + 自动复检（并讲清哪一步无法自动）

用户问「Termux 连不上了，能在一键配置时自动配置吗」。先把能力边界说清楚，再把能自动的全部自动化。

1. **为什么做不到 100% 自动**：`allow-external-apps`（Termux 允许外部应用调用它）这个开关只能由 Termux 自己
   写进 `~/.termux/termux.properties`：
   · Android 不允许 App A 写 App B 的私有目录（Termux 是另一个 UID）；
   · Termux 的 RUN_COMMAND 会主动拒绝没开这个开关的调用（Termux 的安全设计，不是 bug）；
   · MIUI 又禁掉了 adb 代授（`pm grant` 抛 SecurityException），设备也没有 root/Shizuku；
   · 本项目里的 `AccessibilityHelper` 只是无障碍描述辅助，**没有声明 AccessibilityService**，
     所以 App 也无法用无障碍服务代替用户在 Termux 里打字。
   结论：「首次打开这个开关」必须用户执行一次；App 能做的是把它压到"一行命令 + 粘贴回车"。
2. **本次做成的自动化**：
   · 新增「**自检并修复通道**」按钮：真发一条命令并等 Termux 广播回执（唯一可信判据），失败原因分类明确 ——
     没装 Termux / 没授 RUN_COMMAND 权限 / 系统拒绝 / Termux 20s 没回执（= allow-external-apps 没开或被系统冻结）。
   · 修复命令从"整段 5KB 脚本"压成**一行**：App 把脚本全文写到 `Download/OilQuiz/termux_env/setup.sh`，
     用户只需粘 `bash /sdcard/Download/OilQuiz/termux_env/setup.sh`；以后脚本改了这行命令也不用变
     （设备用例断言"文件内容 == 当前生成脚本"）。
   · 自动复制到剪贴板 + 自动打开 Termux；用户粘完切回本页时 onResume **自动复检**（不用再点）。
   · 通道通了以后「自检」还会打印环境现状：proot-distro 有无、容器列表、上次准备结果 `fail=0/1`、
     `allow-external-apps` 是否已写、`~/ubuntu-gui` 是否存在 —— 排障不用再猜。
3. **新增签名冲突预警**（很可能是"连不上"的真因）：内置包是 **F-Droid 官方签名**，早期装的是 **GitHub debug 包**，
   签名不同 → 系统会拒绝覆盖安装（表现为"装了没反应/安装失败"）。现在安装前会比对已装 Termux 的签名指纹
   （SHA-256 与内置包常量），不一致时明确给两条路：继续用现有 Termux（推荐，只需一次粘贴）/ 卸载重装内置包
   （**容器与数据会一起没**）。
4. **无设备也能验的部分（已验）**：从 Java 源码机械抽取全部 Termux 侧脚本，用真 bash（Git Bash）做语法门禁：
   `setup.sh`(179 行) / `gui_start.sh` / `gui_stop.sh` / `gui_status.sh` / `diag.sh` **全部 `bash -n` 退出 0**。
5. 新增 3 个真机用例（等设备回来跑）：通道自检必须通并拿到诊断、短命令文件内容==当前脚本全文、签名指纹格式与结论。
6. 待设备回归复验：上述 3 个新用例 + 之前两项（准备脚本 `ash -n`、VNC 帧内容非全黑）。
## [2026-09-27] 新增「图形界面（VNC）」：内置 RFB 客户端，在答题宝里显示 Linux 桌面

用户问「vnc 可以内置到 app 中吗」。结论：**客户端能内置，服务端进不了 App 进程**（targetSdk 35 不能 execve
私有目录二进制，与 Termux 同理），但服务端可以装在已有的 Ubuntu 容器里 —— APK 基本不涨（+几十 KB 代码），
服务端体积（约 78MB deb）落在容器里。

1. **App 侧（全自研，不引第三方库）**：
   · `com.oilquiz.app.vnc.VncClient`：RFB 3.8 客户端（版本/安全类型握手 → ServerInit → SetPixelFormat
     32bpp/depth24/小端 → SetEncodings [CopyRect, Hextile, Raw, DesktopSize] → 收帧循环；
     Raw/Hextile/CopyRect/DesktopSize 解码；PointerEvent/KeyEvent/ClientCutText/ServerCutText）。
     不引 android-vnc-viewer / LibVNC —— 它们都是 GPL，链接会把整个 App 拖成 GPL。
   · `VncView`：绘制帧缓冲 + 缩放/平移；单指=左键拖动（轻点即单击）、双指滑=滚轮、双指捏合=缩放、
     「右键」按钮后下一次点击=右键；软键盘经 `onCreateInputConnection` 逐字翻译成 keysym。
   · `VncKeysym`：Android KeyEvent/字符 → X11 keysym（Latin-1 就是字符码；特殊键查表；Ctrl/Alt 单独发送）。
   · `VncActivity` + `activity_vnc.xml`：状态栏（连接状态/端口/重连计数）+ 工具栏（启动图形界面/连接/断开/
     键盘/右键/适应/放大/缩小/粘贴到远端）+ 使用说明。入口：工具集 → 设置与数据 → **图形界面（VNC）**；
     环境准备页也加了「启动图形界面（VNC）」按钮（起服务端后直接开页面）。
2. **容器侧**：一键准备的第 5 步新增 `apt-get install -y --no-install-recommends xvfb x11vnc x11-utils
   x11-apps procps xdotool imagemagick`（实测 78MB/73 个 deb，arm64 全部可得），并写出 `~/ubuntu-gui`
   启停脚本（start/stop/status）。App 也能直接下发同样的启动命令（与脚本共用同一份容器内命令，
   由 `GUI_INNER_COMMAND` 单点定义 + 单元断言保证不漂移）。
3. **AI 侧**：`system_resource` 新增 `gui` 动作（start/stop/status）；更细的 GUI 操作可让 AI 用 `termux_exec`
   跑容器里的 `xdotool`（点击/输入）与 `import`（截图）。
4. **真机踩出来的五个坑（都已修，且都有对应断言）**：
   · **`-encodings` 不能传给 x11vnc**：Ubuntu 的 x11vnc 0.9.16 报 `*** unrecognized option(s) ***` 直接退出；
     编码本来就由客户端 SetEncodings 决定，客户端只报 copyrect/hextile/raw 即可。
   · **proot 会话一退出就带走所有子进程**：早先 `x11vnc -bg` 一挂后台，Xvfb 立刻跟着死；
     改成 x11vnc **前台常驻**（`exec x11vnc …`）+ Termux 侧 `setsid nohup` 挂住整段 proot 会话。
   · **`-noshm` 必须带**：proot 下 `shmget(scanline)` 被拒（Permission denied）。
   · **`-threads` 不能用**：0.9.16 线程模式下实测 x11vnc 会空转（34% CPU）且**不再监听端口**；
     改回单线程 + `-timeout 10` 防僵尸客户端。
   · **不要用裸 TCP 连接探测就绪**：连上就断会留下半开连接把单线程 x11vnc 堵死（现象：端口开着但
     永远不发版本横幅）；就绪判断改成「容器里有没有 x11vnc 进程」（`pgrep -x x11vnc`）。
   · （客户端侧）**`NetworkOnMainThreadException`**：触摸事件在 UI 线程直接 write/flush socket 会当场打死 App；
     改成输入事件入队 + 独立写线程，另给握手加 8 秒读超时，并对 x11vnc 偶发的不发横幅自动重连。
5. **真机验证**：
   · `VncClientDeviceTest`（真连 127.0.0.1:5900）：`第 1 次握手=true 尺寸=1280x720 name=localhost:1`，
     收到 FramebufferUpdate；连跑 3 次单测 + 全类 **5/5 OK**（Rfb 用例内置 5 次自动重连）。
   · 容器侧独立取证：ImageMagick `import -window root` 抓 X 根窗口 = **1280x720 / 204 色**（说明桌面真有窗口，
     不是黑屏）；python 探针收到 Raw 全帧 3,686,400 字节（=1280x720x4，与协议一致）。
   · 工具集入口的真实性用截图 OCR 取证：列表里出现「图形界面 (VNC)」。
## [2026-09-27] 修复「完整 Python 环境」一键准备在"已装过的机器"上必然失败（幂等 + 全程日志 + 真实取证）

用户报「Termux 有个问题」。Termux 自身没有 crash_logs、logcat 无 FATAL，终端文字既不在 logcat 也不在无障碍树，
于是**截图 + 电脑端 Windows OCR** 取证（本模型不能看图，OCR 是唯一读屏通道），拿到真实报错：

```
⚠️  未授予存储权限：读不到本地包 /sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz
Error: container 'ubuntu' already exists. Specify a different name with --name NAME
[Process completed (code 1)]
```

1. **根因（实测钉死）**：`proot-distro 5.9.0` 的 `list` 把人类可读列表打到 **stderr**（实测 stdout 0 字节 / stderr 76 字节）。
   旧守卫 `proot-distro list 2>/dev/null | grep -q ubuntu` 丢掉 stderr → 永远判成"没装" → 再 `install` →
   `container already exists` → `set -e` 当场退出 1。同机实测 `proot-distro list -q` 输出 7 字节在 stdout，
   这才是 5.9 唯一机器可读的形态；另实测 5.9 的容器真实路径是 `$PREFIX/var/lib/proot-distro/containers/<name>`
   （旧代码猜的 `installed-rootfs/` 在新版已不存在）。
2. **三处修正**：
   · **幂等判定三重**：容器目录 + `list -q` + `list 2>&1`，命中即跳过下载与安装（不再撞 already exists）；
   · **去掉 `set -e`**：逐步骤记 `❌`，结尾统一 `exit $FAIL`；容器内 Python 先探测、已完整就跳过 apt（省 2~4 分钟）；
   · 容器内安装用 `-n ubuntu` 显式命名，`~/ubuntu` 入口改用 heredoc 写入（避免嵌套引号）。
3. **可排障**：整段输出 `tee` 到 Termux 的 `~/.quiz_env_setup.log`，并写 `~/.quiz_env_setup.status`（末行 `fail=0/1`）。
   以后"看看 Termux 日志"就有确定文件可看。
4. **存储权限如实说明**：实测 Termux 未授权时 `/sdcard` 读拒绝、`~/storage` 不存在，本地 29MB 包用不上；
   界面状态区新增「Termux 存储权限」一行，脚本明确提示"执行 `termux-setup-storage` 并点允许，再点一次一键准备"，
   本次则联网下 30MB（清华镜像）。
5. **真机验证（机械抽取源码里的模板去真跑，不是字符串断言）**：
   ```
   ---- 3/5 Ubuntu 容器 ----
   ✅ 容器 ubuntu 已存在，跳过下载与安装（重复运行不会破坏已有环境）
   ---- 4/5 容器内完整 Python ----
   ✅ 容器内 Python 已完整，跳过 apt（省 2~4 分钟）
   ===== 全部完成 ✅ =====
   EXITCODE=0   （.quiz_env_setup.status: 2026-09-27 12:00:15 fail=0）
   ```
   同一场景旧脚本退出 1、新脚本退出 0。`TermuxEnvSetupDeviceTest` 增 9 条回归断言（含"不得只看 stdout""不得用 set -e"）
   + 1 个真实下发用例。
6. 附带澄清：`locks/ubuntu.lock` 残留**无害**（实测并发第二个 `proot-distro login` 正常成功、退出 0）。
## [2026-09-27] 新增「完整 Python 环境（Termux + Ubuntu）」一键准备（内置官方安装包）
1. 用户要求把完整 Python 环境**内置**。已实现：App 内置两个**官方产物**（`assets/termux_env/`），
   并提供「工具集 → 设置与数据 → 完整 Python 环境」界面三步走：
   · ① 安装 Termux（内置 APK，系统安装器确认一次）；
   · ② 导出 Ubuntu 根文件系统到 `Download/OilQuiz/termux_env/`；
   · ③ 在 Termux 里准备容器（装 proot-distro → 用导出的本地 tar.gz 建容器 → 容器内换清华源并 apt 装
     `python3-full python3-tk python3-venv python3-pip python3-setuptools` → 建 `~/ubuntu` 入口并打印验证）。
2. **内置的是官方包，不是网上随便找的**（用户此前自己下载的是 2020 年的旧包，安装被拒：`INSTALL_FAILED_VERSION_DOWNGRADE: 101 older than current 1002`）：
   · `termux-0.118.3-fdroid.apk` **108.6 MB**，来自 F-Droid 官方仓库（清华镜像 com.termux_1002.apk），**官方签名、非 debuggable**，GPLv3；
   · `ubuntu-base-24.04.5-base-arm64.tar.gz` **28.5 MB**，Ubuntu 官方 ubuntu-cdimage。
   我原先提案用 33.5MB 的 GitHub arm64 debug 包，最终改用 108.6MB 官方包——理由：debug 包是 debug 签名且 debuggable，
   用户以后**无法从 F-Droid 正常升级**（签名不同）且安全性更低。APK 因此从 617.7MB 增至约 755MB；
   若需要瘦身，删掉这两个 asset 即可，界面会提示「未内置」（代码仍可走下载通道扩展）。
3. **为什么不把 Termux 合并进 App**：Android 只允许 `targetSdk < 29` 的应用执行自己私有目录里的二进制，
   proot/apt 正是靠这一点才能跑（Termux 故意把 targetSdk 钉在 28）；答题宝是 `targetSdk 35`（build.gradle 里就有这条注释），
   所以只能「内置安装包 + 引导安装」。Manifest 里 `REQUEST_INSTALL_PACKAGES` 与 `com.termux.permission.RUN_COMMAND` 早已声明，FileProvider 也已配好。
4. 自动化程度：第 ③ 步优先用 Termux 的 `RUN_COMMAND` 自动下发（打开可见会话显示进度）；
   需要用户**手点一次**「允许」（小米禁止 adb 代授 `pm grant`，实测抛 SecurityException），
   且 Termux 侧 `allow-external-apps=true` 默认关闭——脚本第 0 步会自己写这个配置，
   但**首次**必须由用户手动跑一次（界面提供「复制手动命令」兜底，粘进 Termux 即可，脚本一次性把两件事都做掉）。
5. 许可合规：两份产物均非本项目代码，已在 `docs/THIRD_PARTY_NOTICES.md` 与资产副本中新增声明
   （Termux GPLv3 + 源码地址、Ubuntu 镜像许可与来源），并在 assets 里附 `README.txt` 说明来源/版本/用途。
6. 真机用例 `TermuxEnvSetupDeviceTest`（5 例）：内置包大小校验（>100MB / >25MB）、导出到公共目录、
   **准备脚本用 App 自带 busybox 的 `ash -n` 做真语法检查**、未授权时下发返回明确原因、布局控件齐全。
7. 实现过程中踩到/自查出的三件事（都已修）：
   · **AGP 会自动解包 assets 里 `.gz` 结尾的文件**：实测打包后条目从 `ubuntu-base-….tar.gz` 变成 `ubuntu-base-….tar`（101.8MB），
     按原名读资产直接失败（导出 0 字节）。改法：资产用中性后缀 `.targz.bin`，**导出给用户时还原成标准 `.tar.gz`**（proot-distro 按扩展名识别归档格式）。
   · **又抓到一次假绿**：`PythonEnvAuditDeviceTest` 里我加的断言被写成 `""ssl""`（双引号，Java 语法错）→ 该文件其实一直编译不过，
     而上一次「OK (1 test)」跑的是旧测试包。已修正，并在后续构建里**明确核对 `BUILD SUCCESSFUL` 字样**。
   · 准备脚本加了健壮性分支：Termux 若没有存储权限、读不到导出的本地包，**自动回退清华镜像下载**（实测下载仅 4.8 秒）。
8. 真机验证（`TermuxEnvSetupDeviceTest` **6/6 通过**）：
   · 内置包：`[EXP] 内置 Termux APK = 108 MB, Ubuntu rootfs = 28 MB`；
   · 导出：`[EXP] 导出: /storage/emulated/0/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz  29936675 字节`；
   · 脚本合法性：`[EXP] ash -n exit=0`（用 App 自带 busybox 做真语法检查，不是字符串断言之）；
   · **App→Termux 下发链路（一键准备的命脉）**：下发被接受 → adb 侧核对 Termux 私有目录 `_quiz_push.txt` 内容 = `PUSH_OK` ✓；
   · 界面布局 7 个控件齐全；未授权时下发会明确返回「需授予权限」。
9. APK 体积：617.7MB → **754.3MB**（+136.6MB = Termux 108.6 + rootfs 28.5，压缩后约 +137MB）。
   要瘦身只需删掉 `src/main/assets/termux_env/` 里两个大文件，界面会显示「未内置」（脚本仍有镜像回退分支，功能不受影响，只是回到联网下载）。

## [2026-09-27] 在真机上走通「完整 Python」路线：Termux + proot-distro Ubuntu（含自动化方法与三处坑）
1. 用户问「怎么走通」。目标：拿到带 tkinter/curses/readline 的真 Linux Python（App 内置的是 Chaquopy，Android 上永远没有这几样）。
2. 真机执行步骤（全部已在本机完成并验证）：
   · 装 **Termux 0.118.3（GitHub debug 包，targetSdk 28）**：选 debug 包是因为它 `android:debuggable=true`，
     可用 `adb shell run-as com.termux` **直接驱动**，绕开小米封掉的 `pm grant` 与输入注入（实测 `pm grant com.oilquiz.app com.termux.permission.RUN_COMMAND` 抛 SecurityException）；
     首次启动后 bootstrap 解压到 `files/usr`，实测 `run-as .../bash -lc 'echo EXEC_OK'` → EXEC_OK，证明 **Android 16 下 targetSdk 28 应用仍可执行私有目录二进制**（这是整条路线的前提）。
   · `pkg update`（自动选清华镜像）→ `pkg install -y proot-distro`（5.9.0，带 proot/clang/llvm）。
   · **坑①**：proot-distro 5.9 默认从 **Docker Hub** 拉镜像（`install ubuntu`）→ 国内卡死（进程挂在设备上、容器名被占，登录会报 `container is busy (PID: install)`）。
     解法：杀掉进程 + 清 `containers/ubuntu` 与 `locks`，改用 **URL 装根文件系统**：`proot-distro install -n ubuntu https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` → **4.8 秒**（28.5MiB）。
   · 容器内换清华源 → `apt-get update && apt-get install -y --no-install-recommends python3-full python3-tk python3-venv ca-certificates python3-pip python3-setuptools`。
   · **坑②**：Ubuntu 24.04 是 **Python 3.12**，`distutils` 已被移除（想 import 它必失败，别把它当「缺失」）；用 `setuptools` 替代。
   · **坑③**：给容器写脚本时**不要嵌套引号**（我从 PowerShell 拼 `proot-distro login ubuntu -- bash -c '...'` 踩了两次：JSON 转义把换行变成字面 `\n`）。
     可靠做法：**本地写好脚本 → base64 → 设备端 `base64 -d` 落盘 → 让容器 `bash /path/script.sh` 执行**。
3. 结果（真机实测）：Ubuntu **24.04.5 LTS**，Python **3.12.3**，

## [2026-09-26] 内置工具箱/命令路由 + 媒体工具箱 + Python 文档库全家桶（当日 20+ 提交）

**① shell 工具箱与命令路由**
- shell_command 移除全部护栏（改用 action=shell_mode 显式开关 readonly）+ 内置 busybox 工具箱（400+ 命令）+ https 下载服务（wget/curl 支持 https）+ Termux 桥 + 内置 openssl（真 TLS）/openssh（bionic 构建）
- 命令路由可视化开关（设置页）：内置→系统→busybox→toybox 顺序可调（action=route 运行时调整），不再担心覆盖系统命令；内置 17 个常用工具；独立出 linux_shell 工具 + Python 入口（android_shell）

**② 媒体工具箱（media_toolkit）**
- 内置 ffmpeg/ffprobe + media_toolkit 本地媒体工具箱（Python android_media 接口，engine=ffmpeg 回退 + filter 滤镜链；不需要 ffmpeg 也能用基础能力）
- 许可声明随包分发（LGPL 合规）；能力边界文案收敛（不越界承诺）；工作区内置文档改自动发现（新增指南不再改白名单）

**③ Python 文档库全家桶 + 工具修复**
- 预装 4 个文档库（python-docx/pptx/pypdf/xlsxwriter，纯 Python wheel 本地装）——App 内 Python 可读写 Word/PPT/PDF；补齐可选依赖：cryptography（native，支持加密 PDF）+ 6 个纯 py 包
- python_file_ops parse 支持 Word/PPT/PDF（docx 段落+表格/pptx 文本框+表格+备注/pdf 加密处理）；5 处工具描述/模块清单同步
- python 引擎修复：模块级 import tempfile（缺了所有执行路径崩溃）、全局串行化防竞态、stderr 真文件缓冲（tqdm/rich 不再崩）
- python_chart 相对路径只读文件系统 / analyze_data 只认 JSON / matplotlib 中文字体 三坑修复（android_helper 新增 cjk_font_path/setup_matplotlib_cjk）
- 新增 tools/tests/javac_check.ps1 秒级 Java 编译校验 + 修严重 bug（参数绑定会误删源码——已两次误删并 git 恢复）

## [2026-09-25] remote_dsh（手机远程控制电脑）+ pip 工具链 + 抖音下载 v3.2

**① remote_dsh（手机远程控制电脑）**
- 新增 remote_dsh 工具：电脑端 dsh_bridge_server.py 桥接服务（Bearer token 鉴权，调 dsh headless）+ App 端 run/get_status/set_config + 注册与意图
- v2 官方会话通道（session.create/prompt/history 多轮续接，headless 降级保留）；v2.1 扫码一键配对（DecoratedBarcodeView 扫码页 + 电脑端/pair 二维码页）；v3 ACP 官方通道（dsh 0.1.5，主 home ACP serve 7800）
- 备份目录含凭据，从 git 移除并 ignore（dsh-home-backup/）

**② pip 工具链**
- Chaquopy 运行时内置 pip 模块（App 内 python 环境有 pip 了）；PIP_SELFTEST 初始化自检（定位运行时 pip 问题）
- pip_install 默认镜像源改阿里云（tuna 参数显式映射清华）；工具描述写清正确用法（禁止 python -m pip 子进程，用 pip.main 编程式或 pip_install 自研下载器）
- python_execute stderr 改真文件缓冲（修复 tqdm/rich 写入 StringIO 崩溃）+ barcode getsize 补丁固化到加载时

**③ 其他**
- 抖音下载内置工具 v3.2 + 文件预览扩展 + WebView 安全/会话优化

## [2026-09-23 ~ 09-24] Agent 引擎对齐 dsh 架构 + 本地推理加速

- 在线 AI 引擎对齐 dsh 架构：工具实时注册/动态工具结构化/统计统一 + 缓存 usage 全字段直读
- 本地推理加速：KV 前缀稳定/最小 prompt/核心工具速查常驻（file_reader/workspace）+ 加载瓶颈定位
- Agent 调试增强（9/21）：详细 token 日志 + 思考 token UI 显示

## [2026-09-21] 天气模块重构 + 极光时钟子项目（表盘体系大迭代）

- 天气模块 + 沉浸舞台重构 + 表盘数据绑定修复
- 极光时钟（子项目，独立 Windows 应用/表盘体系）：表盘大迭代（3D 立方/文字钟/辉光管/矩阵/数字针/3D 翻/LED/冷光/流彩/脉冲/轨迹等 13+ 表盘新增、重设计、删除回归）、四模式显示统一（竖屏/沉浸/横屏/横屏沉浸）、沉浸模式全屏兜底与设置重构、TTS 增强（真人语音音色/语音引擎选择/单击报时/半点报时）、Windows exe 壳（Electron + 内置本地服务，离线可用）；删除 exe 生成功能（aurora_exe 工程及产物）