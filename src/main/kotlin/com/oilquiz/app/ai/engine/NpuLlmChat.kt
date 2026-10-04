package com.oilquiz.app.ai.engine

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * GenieX（Qualcomm 官方）端侧 LLM 推理封装：Hexagon NPU / Adreno GPU / CPU。
 *
 * app 原有 NPU 接入（NpuHelper + npu-jni.cpp）只完成运行时自检（SM8850 = Hexagon V81），
 * 推理需 QNN context binary 且手搓 GenieDialog 有原生 abort 风险。GenieX 是官方统一
 * 推理运行时（Maven 一条依赖、自带 arm64 native 库 + 模型下载管理），llama_cpp 运行时
 * 可跑任意 GGUF（compute_unit=npu 即走 Hexagon NPU），qairt 运行时跑 AI Hub 预编译。
 *
 * 本类只封装 GenieX 与状态统计，不改动现有 ALChat（llama.cpp/OpenCL）推理路径。
 * 耗时操作（下载/加载/推理）在内部协程（Dispatchers.IO）执行，Java 侧调用不阻塞。
 *
 * Java 调用序列（后台线程）：
 *   NpuLlmChat.init(context);
 *   NpuLlmChat.downloadModel(context, modelName, "Q4_0", "MODELSCOPE", null, listener);
 *   NpuLlmChat.loadModel(modelName, "npu", loadListener);
 *   NpuLlmChat.sendMessageAsync("你好", 2048, genListener);
 */
object NpuLlmChat {
    private const val TAG = "NpuLlmChat"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 运行状态（给界面显示） */
    enum class State { IDLE, DOWNLOADING, READY, LOADING, GENERATING, ERROR }

    @Volatile private var state: State = State.IDLE
    @Volatile private var llm: LlmWrapper? = null
    @Volatile private var currentModel: String = ""

    /** Application context（ensureInit 用）与 SDK 初始化标记 */
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false

    /**
     * 引擎开关的**静态镜像**（不依赖 Context）。
     *
     * <p>为什么需要：要在 `LlamaHelper` 这一层做"无缝切换"，就必须能不带 Context 判断引擎是否开启。
     * 由 {@code InferenceRouter.setNpuEnabled()} 与 {@code SmartQuizApplication.onCreate} 两处写入，
     * 与 SharedPreferences 里的持久化值保持一致。
     */
    @Volatile private var engineEnabled = false

    @JvmStatic
    fun setEngineEnabled(enabled: Boolean) {
        engineEnabled = enabled
        Log.i(TAG, "NPU 引擎开关(镜像) = $enabled")
    }

    @JvmStatic
    fun isEngineEnabled(): Boolean = engineEnabled

    /** 当前是否有**已登记可用**的本地模型（App 模型库或侧载目录里真有 gguf） */
    @JvmStatic
    fun hasUsableModel(): Boolean {
        for (v in localFiles.values) {
            if (File(v).isFile) return true
        }
        for (d in localDirs.values) {
            if (scanGguf(File(d)) != null) return true
        }
        return false
    }

    /** 当前是否已加载好一个模型（READY / GENERATING） */
    @JvmStatic
    fun isLoaded(): Boolean {
        val st = state
        return (st == State.READY || st == State.GENERATING) && llm != null
    }

    // 上次推理统计
    @Volatile private var lastTokens = 0
    @Volatile private var lastElapsedMs = 0L
    @Volatile private var lastTps = 0f

    // 下载统计
    @Volatile private var downloadProgress = 0f

    /**
     * 本地侧载登记表：modelName -> 含 *.gguf 的目录（必须在 App 内部存储，见 [registerLocalModel]）。
     *
     * 为什么需要它：GenieX 的模型管理器只认自己写的 geniex.json 清单（只有走 hub pull 才会生成），
     * 手机连不上 HF 时 getPaths() 永远返回 null。自备 GGUF 侧载时我们用这份表兜底。
     */
    private val localDirs = ConcurrentHashMap<String, String>()

    /**
     * 模型名 -> 具体的 gguf 文件路径。
     *
     * 两个来源都汇总到这里（优先于目录扫描）：
     * ① 目录侧载（[registerLocalModel]）；
     * ② **App 模型库** `filesDir/ai_models/`（[registerAppModelLibrary]）——
     *    即项目原有模型下载功能的落盘位置，这样用户用现有下载页下好就能直接被 NPU 引擎用上，
     *    不需要 adb 侧载。
     */
    private val localFiles = ConcurrentHashMap<String, String>()

    /** 下载进度/完成回调（Java 可实现） */
    interface DownloadListener {
        fun onProgress(percent: Float, modelName: String)
        fun onCompleted(modelName: String)
        fun onError(code: Int, message: String)
    }

    /** 加载回调 */
    interface LoadListener {
        fun onLoaded(modelName: String)
        fun onError(message: String)
    }

    /** 流式生成回调 */
    interface GenerateListener {
        fun onToken(text: String)
        fun onCompleted(tokens: Int, tps: Float, elapsedMs: Long)
        fun onError(message: String)
    }

    /** 幂等初始化（可放 Application.onCreate / 每次进入界面时调） */
    @JvmStatic
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            GenieXSdk.getInstance().init(context.applicationContext)
            initialized = true
            Log.i(TAG, "GenieXSdk 已初始化")
        } catch (t: Throwable) {
            state = State.ERROR
            Log.e(TAG, "GenieXSdk 初始化失败", t)
        }
    }

    /**
     * 保证 GenieX 原生库已加载。
     *
     * 2026-10-03 踩坑：GenieX 的 JNI 注册发生在 GenieXSdk.init() 里。之前只有「NPU 推理」页
     * 的 onCreate 调过 init，于是从**对话页/模型选择页**开启 NPU 时，任何 SDK 调用都会抛
     * `UnsatisfiedLinkError: No implementation found for ... ModelManager.getPaths`，
     * 界面就报"模型路径不正确"。所有碰 SDK 的入口都要先走这里。
     */
    private fun ensureInit() {
        if (initialized) return
        appContext?.let { init(it) }
    }

    @JvmStatic
    fun getState(): State = state

    @JvmStatic
    fun getStateName(): String = state.name

    @JvmStatic
    fun getCurrentModel(): String = currentModel

    @JvmStatic
    fun getLastTps(): Float = lastTps

    @JvmStatic
    fun getLastTokens(): Int = lastTokens

    @JvmStatic
    fun getLastElapsedMs(): Long = lastElapsedMs

    @JvmStatic
    fun getDownloadProgress(): Float = downloadProgress

    /** 该模型是否已下载到本机（短查询，runBlocking 内部完成；含本地侧载兜底） */
    @JvmStatic
    fun isModelDownloaded(modelName: String): Boolean {
        return try {
            runBlocking { ModelManagerWrapper.getPaths(modelName) != null } || localPathsFallback(modelName) != null
        } catch (t: Throwable) {
            Log.w(TAG, "isModelDownloaded 查询失败: $t")
            localPathsFallback(modelName) != null
        }
    }

    /**
     * 登记一个"本地侧载"模型：目录里放好 `*.gguf` 即可，不联网、不需要 SDK 清单。
     *
     * ⚠️ 目录必须在 **App 内部存储**（如 `context.filesDir/gguf/qwen3-0.6b`）。
     * 放 `/sdcard/Android/data/<pkg>/files/...` 会被系统挡（实测 Permission denied），
     * 因为那层 FUSE 权限不由 App 掌控。
     *
     * @return 目录有效（存在且含 *.gguf）时返回找到的 gguf 绝对路径，否则 null
     */
    @JvmStatic
    fun registerLocalModel(modelName: String, dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) {
            Log.w(TAG, "registerLocalModel: dirPath 为空（$modelName）")
            return null
        }
        val gguf = scanGguf(File(dirPath)) ?: run {
            Log.w(TAG, "registerLocalModel: 目录无效或没有 *.gguf: $dirPath")
            return null
        }
        localDirs[modelName] = File(dirPath).absolutePath
        localFiles[modelName] = gguf.absolutePath
        Log.i(TAG, "本地侧载已登记: $modelName -> ${gguf.absolutePath}")
        return gguf.absolutePath
    }

    /** 从 context.filesDir 下的相对目录登记（更省事：界面只需要给相对路径） */
    @JvmStatic
    fun registerLocalModelInFiles(context: Context, modelName: String, relDir: String): String? =
        registerLocalModel(modelName, File(context.filesDir, relDir).absolutePath)

    /**
     * 直接登记一个具体的 gguf 文件（App 模型库用）。
     *
     * @return 文件存在且是 .gguf 时返回 true
     */
    @JvmStatic
    fun registerLocalModelFile(modelName: String, ggufPath: String?): Boolean {
        if (ggufPath.isNullOrBlank()) return false
        val f = File(ggufPath)
        if (!f.isFile || !f.name.endsWith(".gguf", ignoreCase = true)) {
            Log.w(TAG, "registerLocalModelFile: 不是有效的 gguf: $ggufPath")
            return false
        }
        localDirs[modelName] = (f.parentFile?.absolutePath ?: "")
        localFiles[modelName] = f.absolutePath
        Log.i(TAG, "本地模型文件已登记: $modelName -> ${f.absolutePath}")
        return true
    }

    /** App 模型库目录 —— 项目原有模型下载功能的落盘位置（ModelDownloadManager 写在 files/ai_models） */
    @JvmStatic
    fun appModelDir(context: Context): File = File(context.filesDir, "ai_models")

    /**
     * 扫描 **App 模型库**（{@code filesDir/ai_models} 目录下的 .gguf 文件）并登记为 NPU 可用模型。
     *
     * 意义：复用项目原有的模型下载功能（含国内镜像切换/断点续传）—— 用户在现有下载页下好，
     * NPU 引擎这里直接就能挑来用，不必 adb 侧载。
     *
     * @return 本次登记的模型名列表（形如 `applib/Qwen3-1.7B-Q4_0`）
     */
    @JvmStatic
    fun registerAppModelLibrary(context: Context): List<String> {
        val found = ArrayList<String>()
        val dir = appModelDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
        if (files.isNullOrEmpty()) {
            Log.i(TAG, "App 模型库为空: ${dir.absolutePath}")
            return found
        }
        for (f in files) {
            val name = "applib/" + f.name.removeSuffix(".gguf")
            if (registerLocalModelFile(name, f.absolutePath)) found.add(name)
        }
        Log.i(TAG, "App 模型库扫描: ${files.size} 个 gguf，已登记 ${found.size} 个: $found")
        return found
    }

    /**
     * 从已登记（含 App 模型库）的模型里挑一个最适合 NPU 的。
     *
     * 打分规则对齐实测结论：**Q4_0 才吃满 HTP**（Q4_K/Q5/Q6/Q8 会掉 CPU），大一点的质量更好。
     */
    @JvmStatic
    fun preferredNpuModel(): String? {
        if (localFiles.isEmpty()) return null
        fun score(name: String): Int {
            val f = File(localFiles[name] ?: return -10000).name.lowercase()
            var s = 0
            if (f.contains("q4_0")) s += 100 else if (f.contains("q4_k") || f.contains("q5_") ||
                f.contains("q6_") || f.contains("q8_") || f.contains("f16")
            ) s -= 60
            if (f.contains("1.7b")) s += 40
            if (f.contains("0.6b")) s += 30
            if (f.contains("2b")) s += 35
            if (f.contains("qwen3")) s += 20
            // App 模型库（用户用现有下载功能下的）优先于排查用的侧载脚手架
            if (name.startsWith("applib/")) s += 80
            return s
        }
        return localFiles.keys.maxByOrNull { score(it) }
    }

    /** 扫目录里第一个 *.gguf */
    private fun scanGguf(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
    }

    /**
     * 本地兜底：getPaths() 为空时，用登记过的本地模型/目录自造一份 ModelPaths 交给 llama.cpp。
     * （等价于官方示例里"一个含 *.gguf 的目录"这种 LOCALFS 布局。）
     */
    private fun localPathsFallback(modelName: String): ModelPaths? {
        localFiles[modelName]?.let { path ->
            val f = File(path)
            if (f.isFile) return buildPaths(modelName, f)
        }
        val dirPath = localDirs[modelName] ?: return null
        val dir = File(dirPath)
        val gguf = scanGguf(dir) ?: return null
        localFiles[modelName] = gguf.absolutePath
        return buildPaths(modelName, gguf)
    }

    private fun buildPaths(modelName: String, gguf: File): ModelPaths = ModelPaths(
        model_path = gguf.absolutePath,
        model_dir = gguf.parentFile?.absolutePath ?: "",
        model_name = modelName,
        runtime_id = "llama_cpp",
        model_type = ModelType.LLM,
        mmproj_path = null,
        tokenizer_path = ""
    )

    /** 已缓存模型清单（"org/repo" 列表） */
    @JvmStatic
    fun listModels(): List<String> {
        return try {
            runBlocking { ModelManagerWrapper.list() }
        } catch (t: Throwable) {
            Log.w(TAG, "listModels 失败: $t")
            emptyList()
        }
    }

    /**
     * 准备模型：本地侧载优先，其次走 hub 下载（断点续传）。
     *
     * @param hubName HubSource 名：AUTO / HUGGINGFACE / MODELSCOPE / AIHUB / LOCALFS。
     *                国内优先 MODELSCOPE（手机实测可通，HF 连不上）。
     * @param chipset qairt 模型（ai-hub-models 预编译仓库）必填：SM8750 / SM8850；llama_cpp GGUF 传 null。
     * @param localPath 非空 = 本地侧载：指向**App 内部存储**下含 `*.gguf` 的目录，不联网。
     */
    @JvmStatic
    fun downloadModel(
        context: Context,
        modelName: String,
        precision: String?,
        hubName: String,
        chipset: String?,
        localPath: String?,
        listener: DownloadListener?
    ) {
        // ① 本地侧载：登记即就绪（不依赖 SDK 清单，也不需要网络）
        if (!localPath.isNullOrBlank()) {
            val gguf = registerLocalModel(modelName, localPath)
            if (gguf != null) {
                currentModel = modelName
                state = State.READY
                downloadProgress = 1f
                listener?.onProgress(1f, modelName)
                listener?.onCompleted(modelName)
                Log.i(TAG, "本地侧载就绪: $modelName -> $gguf")
                return
            }
        }

        // ② 已在本地（hub 拉过或已登记）
        if (isModelDownloaded(modelName)) {
            currentModel = modelName
            state = State.READY
            downloadProgress = 1f
            listener?.onProgress(1f, modelName)
            listener?.onCompleted(modelName)
            return
        }

        // ③ 走 hub 下载
        state = State.DOWNLOADING
        downloadProgress = 0f
        scope.launch {
            try {
                val hub = try {
                    HubSource.valueOf(hubName.uppercase())
                } catch (e: IllegalArgumentException) {
                    HubSource.AUTO
                }
                ModelManagerWrapper.pullFlow(
                    ModelPullInput(
                        model_name = modelName,
                        precision = precision,
                        hub = hub,
                        chipset = chipset,
                        local_path = localPath
                    )
                ).collect { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            // 真实字节进度：对 event.files 的 total_bytes / downloaded_bytes 求和
                            // （旧实现写死 0.99f，界面永远卡在 99%）
                            val total = event.files.sumOf { if (it.total_bytes > 0) it.total_bytes else 0L }
                            val done = event.files.sumOf { it.downloaded_bytes }
                            val p =
                                if (total > 0) {
                                    (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.999f)
                                } else {
                                    0f
                                }
                            downloadProgress = p
                            listener?.onProgress(p, modelName)
                        }
                        ModelManagerWrapper.PullEvent.Completed -> {
                            downloadProgress = 1f
                            currentModel = modelName
                            state = State.READY
                            Log.i(TAG, "模型下载完成: $modelName")
                            listener?.onProgress(1f, modelName)
                            listener?.onCompleted(modelName)
                        }
                        is ModelManagerWrapper.PullEvent.Error -> {
                            state = State.ERROR
                            Log.e(TAG, "模型下载失败: ${event.code} ${event.message}")
                            listener?.onError(event.code, event.message ?: "未知错误")
                        }
                    }
                }
            } catch (t: Throwable) {
                state = State.ERROR
                Log.e(TAG, "下载异常", t)
                listener?.onError(-1, t.message ?: "下载异常")
            }
        }
    }

    /**
     * 加载模型（内部在 IO 协程完成，经回调返回）。
     *
     * @param runtimeId "llama_cpp"（跑 GGUF，支持 NPU/GPU/CPU）或 "qairt"（跑 AI Hub 预编译包，仅 NPU）。
     *                  不传则按 llama_cpp。
     * @param computeUnit "npu" / "gpu" / "cpu" / null（null = npu，骁龙上推荐）；qairt 忽略此项。
     */
    @JvmStatic
    fun loadModel(modelName: String, runtimeId: String?, computeUnit: String?, listener: LoadListener?) {
        ensureInit()
        val runtime = runtimeId?.takeIf { it.isNotBlank() } ?: "llama_cpp"
        if (!isModelDownloaded(modelName)) {
            listener?.onError("模型未下载: $modelName")
            return
        }
        if (runtime == "qairt" && !computeUnit.isNullOrBlank() && !computeUnit.equals("npu", true)) {
            listener?.onError("QAIRT 运行时只支持 NPU（当前选择: $computeUnit）")
            return
        }
        state = State.LOADING
        scope.launch {
            try {
                // getPaths 为空时用本地侧载兜底（SDK 只认自己写的 geniex.json 清单）
                val paths = ModelManagerWrapper.getPaths(modelName) ?: localPathsFallback(modelName)
                if (paths == null) {
                    state = State.ERROR
                    listener?.onError("模型路径解析失败（既没下载、也没登记本地侧载目录）")
                    return@launch
                }
                // QAIRT 拒绝非零 n_ctx / n_gpu_layers（两者在 AI Hub 包里编译期就固定了）；
                // llama_cpp 走 NPU 时 nGpuLayers=-1 表示"全部层交给最快设备（Hexagon HTP）"。
                val conf =
                    if (runtime == "qairt") {
                        ModelConfig(nCtx = 0, nGpuLayers = 0)
                    } else {
                        ModelConfig(nCtx = 4096, nGpuLayers = -1)
                    }
                val result = LlmWrapper.builder()
                    .llmCreateInput(
                        // 注意：0.8.0 的 LlmCreateInput 去掉了 model_name（0.3.5 有），只剩 5 个参数
                        LlmCreateInput(
                            model_path = paths.model_path,
                            tokenizer_path = paths.tokenizer_path,
                            config = conf,
                            runtime_id = runtime,
                            compute_unit = computeUnit
                        )
                    )
                    .build()
                result.onSuccess { wrapper ->
                    llm?.stopStream()
                    llm = wrapper
                    currentModel = modelName
                    state = State.READY
                    Log.i(TAG, "模型加载完成: $modelName (runtime=$runtime, compute=$computeUnit)")
                    listener?.onLoaded(modelName)
                }.onFailure { e ->
                    state = State.ERROR
                    Log.e(TAG, "模型加载失败", e)
                    listener?.onError(e.message ?: "模型加载失败")
                }
            } catch (t: Throwable) {
                state = State.ERROR
                Log.e(TAG, "加载异常", t)
                listener?.onError(t.message ?: "加载异常")
            }
        }
    }

    /**
     * 流式生成（chat template 自动套用）。协程内完成，token 实时回调。
     */
    @JvmStatic
    fun sendMessageAsync(prompt: String, maxTokens: Int, listener: GenerateListener?) {
        sendChatAsync(arrayOf("user"), arrayOf(prompt), maxTokens, false, listener)
    }

    /** 多轮对话流式生成（不带思考开关，等价于 thinking=false） */
    @JvmStatic
    fun sendChatAsync(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        listener: GenerateListener?
    ) {
        sendChatAsync(roles, contents, maxTokens, false, listener)
    }

    /**
     * 多轮对话流式生成（主流程接入用）。
     *
     * @param roles    "system" / "user" / "assistant"
     * @param contents 与 roles 等长的文本
     * @param thinking 是否启用**思考链**。GenieX 0.8.0 把 `enable_thinking` 放在
     *                 `applyChatTemplate(messages, tools, addGenerationPrompt, enableThinking)` 的
     *                 第 4 个参数上（官方示例只传了 3 个 → 默认关闭）。这里透传，深度思考在
     *                 NPU 上不再失效。
     * @param toolsJson 工具/函数定义（JSON 字符串），null 表示不带工具
     */
    @JvmStatic
    @JvmOverloads
    fun sendChatAsync(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        thinking: Boolean,
        listener: GenerateListener?,
        toolsJson: String? = null
    ) {
        ensureInit()
        val wrapper = llm
        if (wrapper == null) {
            listener?.onError("模型未加载")
            return
        }
        if (roles.isEmpty() || roles.size != contents.size) {
            listener?.onError("对话历史参数不合法")
            return
        }
        state = State.GENERATING
        scope.launch {
            try {
                val chat = ArrayList<ChatMessage>(roles.size)
                for (i in roles.indices) {
                    chat.add(ChatMessage(roles[i], contents[i]))
                }
                // 第 3 个参数 addGenerationPrompt=false（与官方示例一致），
                // 第 4 个参数 = enable_thinking（我们之前漏传，导致"深度思考"在 NPU 上无效）
                val templated = wrapper.applyChatTemplate(chat.toTypedArray(), toolsJson, false, thinking)
                templated.onSuccess { t ->
                    var tokens = 0
                    val text = StringBuilder()
                    val startMs = System.currentTimeMillis()
                    wrapper.generateStreamFlow(
                        t.formattedText,
                        GenerationConfig(maxTokens = maxTokens)
                    ).collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> {
                                tokens++
                                text.append(result.text)
                                listener?.onToken(result.text)
                            }
                            is LlmStreamResult.Completed -> {
                                val elapsed = System.currentTimeMillis() - startMs
                                lastTokens = tokens
                                lastElapsedMs = elapsed
                                lastTps = if (elapsed > 0) tokens * 1000f / elapsed else 0f
                                state = State.READY
                                Log.i(TAG, "生成完成: $tokens tokens / ${elapsed}ms / ${"%.2f".format(lastTps)} t/s")
                                listener?.onCompleted(tokens, lastTps, elapsed)
                            }
                            is LlmStreamResult.Error -> {
                                state = State.READY
                                Log.e(TAG, "生成错误", result.throwable)
                                listener?.onError(result.throwable.message ?: "生成错误")
                            }
                        }
                    }
                }.onFailure { e ->
                    state = State.READY
                    listener?.onError(e.message ?: "chat template 失败")
                }
            } catch (t: Throwable) {
                state = State.READY
                Log.e(TAG, "生成异常", t)
                listener?.onError(t.message ?: "生成异常")
            }
        }
    }

    /**
     * **同步**生成（给 `LlamaHelper` 这类阻塞式调用方无缝切换用）。
     *
     * <p>无状态差异：与流式路径共用同一套 chat template / 模型，只是把 token 累积起来返回。
     * 任何失败都抛异常，调用方据此**回退 llama.cpp**（保证不因 NPU 故障而功能不可用）。
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IllegalStateException::class)
    fun generateBlocking(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        timeoutMs: Long = 10 * 60 * 1000L,
        thinking: Boolean = false
    ): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        val text = StringBuilder()
        val err = arrayOfNulls<String>(1)
        sendChatAsync(roles, contents, maxTokens, thinking, object : GenerateListener {
            override fun onToken(t: String) {
                text.append(t)
            }

            override fun onCompleted(tokens: Int, tps: Float, elapsedMs: Long) {
                latch.countDown()
            }

            override fun onError(message: String) {
                err[0] = message
                latch.countDown()
            }
        })
        val ok = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("NPU 推理被中断", e)
        }
        if (!ok) throw IllegalStateException("NPU 推理超时")
        err[0]?.let { throw IllegalStateException("NPU 推理失败: $it") }
        return text.toString()
    }

    /**
     * 按需加载：主流程（对话页）切到 NPU 引擎时调用。
     *
     * 模型来源按优先级：
     * ① **App 模型库** `filesDir/ai_models/`（项目原有下载功能的落盘处，含国内镜像/断点续传）；
     * ② 本地侧载目录 `filesDir/gguf/qwen3-0.6b|1.7b`（排查/离线用）。
     * 两者统一打分挑最优（Q4_0 优先），都没有则报错提示先下载模型。
     */
    @JvmStatic
    fun ensureLoadedAsync(context: Context, listener: LoadListener?) {
        // 关键：先 init（幂等）—— GenieX 的 JNI 注册在 GenieXSdk.init() 里完成。
        // 从对话页/模型选择页开启 NPU 时没人调过 init，缺了这行所有 SDK 调用都会抛
        // UnsatisfiedLinkError（界面表现为"模型路径不正确"）。
        init(context)
        if (state == State.READY && llm != null) {
            listener?.onLoaded(currentModel)
            return
        }
        // ① 复用项目原有下载功能：App 模型库里的 gguf 直接可用
        registerAppModelLibrary(context)
        // ② 本地侧载目录（不依赖界面是否访问过 NPU 页）
        registerLocalModelInFiles(context, Models.LOCAL_QWEN3_0_6B, "gguf/qwen3-0.6b")
        registerLocalModelInFiles(context, Models.LOCAL_QWEN3_1_7B, "gguf/qwen3-1.7b")

        val target = preferredNpuModel()
        if (target == null) {
            state = State.ERROR
            listener?.onError("还没有可用的本地模型：请到「模型下载」页下载 NPU 模型（选 Q4_0 那个），或把 GGUF 放进 files/gguf/")
            return
        }
        Log.i(TAG, "NPU 选用模型: $target -> ${localFiles[target]}")
        loadModel(target, "llama_cpp", "npu", listener)
    }

    /** 停止当前生成（配合界面的停止按钮） */
    @JvmStatic
    fun stopGenerate() {
        try {
            runBlocking { llm?.stopStream() }
        } catch (t: Throwable) {
            Log.w(TAG, "stopStream: $t")
        }
    }

    /** 释放当前模型（切模型前调用） */
    @JvmStatic
    fun release() {
        try {
            runBlocking { llm?.stopStream() }
        } catch (t: Throwable) {
            Log.w(TAG, "release stopStream: $t")
        }
        llm = null
        currentModel = ""
        state = State.IDLE
    }

    /** 设备/环境摘要（AI 服务状态页展示） */
    @JvmStatic
    fun statusSummary(context: Context): String {
        val sb = StringBuilder()
        sb.append("GenieX SDK 已接入（com.geniex.sdk）\n")
        sb.append("状态: ").append(state.name)
        if (currentModel.isNotEmpty()) sb.append(" · 模型: ").append(currentModel)
        sb.append('\n')
        if (lastTokens > 0) {
            sb.append("上次推理: ").append(lastTokens).append(" tokens / ")
                .append(lastElapsedMs).append(" ms / ")
                .append(String.format("%.2f t/s", lastTps)).append('\n')
        }
        sb.append("已缓存模型: ")
        try {
            val list = listModels()
            sb.append(if (list.isEmpty()) "无" else list.joinToString(", "))
        } catch (t: Throwable) {
            sb.append("查询失败")
        }
        sb.append('\n')
        // 本地侧载登记（不依赖 SDK 清单）
        if (localDirs.isNotEmpty()) {
            sb.append("本地侧载: ").append(localDirs.keys.joinToString(", ")).append('\n')
        }
        // 设备 SoC（GenieX 自动识别；NPU 需要 SM8750 / SM8850）
        try {
            val chip = runBlocking { ModelManagerWrapper.detectChipset() }
            if (!chip.isNullOrBlank()) {
                val supported = chip.contains("SM8750", true) || chip.contains("SM8850", true)
                sb.append("设备 SoC: ").append(chip)
                    .append(if (supported) "（Hexagon NPU 支持）" else "（未在 GenieX 验证列表：仅 8 Elite / 8 Elite Gen 5）")
                    .append('\n')
            }
        } catch (t: Throwable) {
            Log.w(TAG, "detectChipset: $t")
        }
        return sb.toString()
    }

    /** 当前设备是否在 GenieX 验证过的 NPU 机型列表里（SM8750 / SM8850） */
    @JvmStatic
    fun isNpuSupportedDevice(): Boolean {
        return try {
            val chip = runBlocking { ModelManagerWrapper.detectChipset() }
            !chip.isNullOrBlank() && (chip.contains("SM8750", true) || chip.contains("SM8850", true))
        } catch (t: Throwable) {
            Log.w(TAG, "isNpuSupportedDevice: $t")
            false
        }
    }

    /** 常用模型常量（供界面下拉选择） */
    object Models {
        /** 本地侧载 Qwen3-0.6B（目录 filesDir/gguf/qwen3-0.6b，Q4_0 约 364MB） */
        const val LOCAL_QWEN3_0_6B = "local/qwen3-0.6b-q4_0"
        /** 本地侧载 Qwen3-1.7B（目录 filesDir/gguf/qwen3-1.7b，Q4_0 约 1.0GB） */
        const val LOCAL_QWEN3_1_7B = "local/qwen3-1.7b-q4_0"
        /** 1.7B GGUF（llama_cpp / NPU），HF 仓库 unsloth/Qwen3-1.7B-GGUF，Q4_0 约 1.1GB */
        const val QWEN3_1_7B = "unsloth/Qwen3-1.7B-GGUF"
        /** 4B 官方预编译（qairt / NPU only），需 chipset=SM8850 */
        const val QWEN3_4B_INSTRUCT = "ai-hub-models/Qwen3-4B-Instruct-2507"
        /** 8B GGUF（llama_cpp / NPU），Q4_0 约 4.5GB（16GB RAM 设备可跑） */
        const val QWEN3_8B = "unsloth/Qwen3-8B-GGUF"
    }
}
