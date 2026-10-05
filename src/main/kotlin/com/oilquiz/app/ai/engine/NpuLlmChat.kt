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
import com.geniex.sdk.bean.ToolCall
import com.geniex.sdk.bean.VlmCreateInput
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.VlmWrapper
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

    /** 当前已加载模型名；未加载时给出将要用的首选模型名（用于"模型够大才把工具调用交给 NPU"判断） */
    @JvmStatic
    fun currentOrPreferredModelName(): String {
        val cur = currentModel
        if (cur.isNotBlank()) return cur
        return try {
            preferredNpuModel() ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /** 显式指定的 NPU 模型名（空 = 自动挑最优）；持久化在 npu_engine_prefs/npu_model */
    @Volatile private var preferredOverride: String = ""

    /** 用户在「模型选择」页指定 NPU 用哪个模型（null/空 = 恢复自动） */
    @JvmStatic
    fun setPreferredModelName(name: String?) {
        preferredOverride = name ?: ""
        val ctx = appContext
        if (ctx != null) {
            ctx.getSharedPreferences("npu_engine_prefs", Context.MODE_PRIVATE)
                .edit().putString("npu_model", preferredOverride).apply()
        }
        Log.i(TAG, "NPU 指定模型 = " + (if (preferredOverride.isEmpty()) "自动" else preferredOverride))
    }

    @JvmStatic
    fun preferredModelName(): String = preferredOverride

    /** 启动时从偏好恢复（由 NpuEngineRouter.init 调用） */
    @JvmStatic
    fun loadPreferredModelName(context: Context) {
        preferredOverride = context.getSharedPreferences("npu_engine_prefs", Context.MODE_PRIVATE)
            .getString("npu_model", "") ?: ""
    }

    /** 可选的 NPU 模型列表（App 模型库里的 gguf） */
    @JvmStatic
    fun listAvailableModels(context: Context): List<String> {
        registerAppModelLibrary(context)
        return ArrayList(localFiles.keys)
    }


    /** 当前规划出的上下文长度（由 planNCtx 写入，loadModel 使用） */
    /** VLM（多模态）会话句柄：mmproj 存在时按 VLM 加载 */
    @Volatile private var vlm: VlmWrapper? = null
    /** 待注入的本地图片路径（VLM 多模态）：由调用方 setPendingImagePaths 设置，用后即清 */
    @Volatile private var pendingImagePaths: List<String> = emptyList()

    /** 设置本次生成要携带的图片（VLM 专用；非 VLM 模型下忽略） */
    @JvmStatic
    fun setPendingImagePaths(paths: List<String>?) {
        pendingImagePaths = paths ?: emptyList()
        Log.i(TAG, "VLM 待注入图片: " + pendingImagePaths.size + " 张")
    }


    @Volatile private var plannedNCtx = 8192   // 兜底值；真实值由 planNCtx 按内存预算定

    /** 可用的系统内存（字节） */
    private fun availableMemBytes(ctx: Context): Long {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem
        } catch (t: Throwable) {
            -1L
        }
    }

    /** 每 token 的 KV 缓存字节数（按 gguf 规格估算；混合注意力只算全注意力层） */
    private fun kvBytesPerToken(m: com.oilquiz.app.ai.model.GgufMeta?): Long {
        if (m == null) return 0
        val layers = if (m.blockCount > 0) m.blockCount else 32
        val kvHeads = if (m.headCountKv > 0) m.headCountKv else (if (m.headCount > 0) m.headCount else 8)
        val headDim = if (m.headLength > 0) m.headLength
                      else if (m.embeddingLength > 0 && m.headCount > 0) m.embeddingLength / m.headCount
                      else 128
        val fullLayers = when {
            m.fullAttentionInterval > 0 -> maxOf(1L, layers / m.fullAttentionInterval)
            m.hasLinearAttention -> maxOf(1L, layers / 4)   // Qwen3.5 系：每 4 层 1 个全注意力
            else -> layers
        }
        return 2L * fullLayers * kvHeads * headDim * 2L     // K+V，f16
    }

    /**
     * 按内存预算规划上下文长度：availMem − 权重 − App 预留 − 安全余量 = 可给 KV，
     * 再在 {16384, 8192, 4096, 2048} 里取最大的可行档。装不下返回 -1。
     */
    private fun planNCtx(ctx: Context, gguf: File?): Int {
        if (gguf == null || !gguf.isFile) return 4096
        val avail = availableMemBytes(ctx)
        if (avail <= 0) return 4096
        val meta = com.oilquiz.app.ai.model.GgufMeta.read(gguf)
        val perToken = kvBytesPerToken(meta)
        // 内存规划计入 draft：投机模式下权重 = 主模型 + draft（两者由 GenieX 一起加载）
        var modelBytes = gguf.length()
        if (specEnabled) {
            try {
                val dn = draftModelFor(currentOrPreferredModelName())
                val dp = dn?.let { localFiles[it] }
                if (dp != null) {
                    val df = File(dp)
                    if (df.isFile) {
                        modelBytes += df.length()
                        Log.i(TAG, "内存规划计入 draft: +" + (df.length() / 1048576) + "MB（" + dn + "）")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "计入 draft 体积失败: " + t)
            }
        }
        val reserve = 400L * 1024 * 1024      // App 自身（实测 PSS ~420MB）
        val margin = 300L * 1024 * 1024       // 安全余量
        val forKv = avail - modelBytes - reserve - margin
        if (perToken <= 0) return if (forKv > 0) 4096 else -1
        // Qwen3.5 混合架构 KV 很便宜（32KB/token），允许开到 32768
        for (c in intArrayOf(32768, 16384, 8192, 4096, 2048)) {
            if (perToken * c <= forKv) {
                // NPU-NCTX-CAP: 按模型规模收敛上下文档位 —— 小模型给 32768 只增加内存压力/调度负担，
                // 没有实际收益（官方 demo 也是保守默认值）。
                val sizeCap = when {
                    modelBytes < 1_200_000_000L -> 8192      // ≤1.7B
                    modelBytes < 3_000_000_000L -> 16384     // 2B-4B
                    else -> 32768
                }
                val chosen = minOf(c, sizeCap)
                Log.i(TAG, "内存规划: 权重=" + (modelBytes / 1048576) + "MB, 可用=" + (avail / 1048576)
                        + "MB, KV/token=" + perToken + "B -> nCtx=" + chosen
                        + "（档位 " + c + ", 规模上限 " + sizeCap + "）")
                return chosen
            }
        }
        Log.w(TAG, "内存规划: 装不下。权重=" + (modelBytes / 1048576) + "MB, 可用=" + (avail / 1048576)
                + "MB, KV/token=" + perToken + "B")
        return -1
    }

    /**
     * 立即为"将要使用的模型"跑一次内存规划并写入 plannedNCtx。
     * 供适配层在**裁剪历史之前**调用，避免用到过期的兜底值（8192）。
     */
    @JvmStatic
    fun planForCurrentModel(context: Context) {
        try {
            registerAppModelLibrary(context)
            val name = preferredNpuModel() ?: return
            val f = localFiles[name]?.let { File(it) }
            val p = planNCtx(context, f)
            if (p > 0) {
                plannedNCtx = p
                Log.i(TAG, "内存规划(裁剪前): $name -> nCtx=$p")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "planForCurrentModel 失败: $t")
        }
    }

    // ==================== 投机解码（speculative decoding）====================
    // 依据：GenieX 插件内含 setup_speculative / decode_speculative / build_speculative_params，
    // 失败时自打印 "speculative decoding setup failed; falling back to plain decoding"（安全）。
    // 设计（按用户要求）：draft 文件**按主模型自动匹配**（同词表才有效），不暴露给用户选择；
    // 切换模型时由上层 reloadNpuModel() 释放并重新加载 → 自动重新匹配。
    // 2026-10-05 关闭（用户实测：主模型 + draft 双模型加载在本机崩溃）。
    // 保持代码与自动匹配能力，但默认**关闭**；如需实验，可在偏好里显式写 npu_spec_enabled=true。
    /** NPU 模式是否走 Agent（带工具 schema）。默认 false = 普通对话：
     *  工具定义每轮都要重复 prefill（实测 2021 token 占 prompt 72%，SDK 前缀复用不命中），
     *  关掉后 prompt 从 ~2789 token 降到 ~240 token，首字延迟显著下降。 */
    @Volatile private var npuAgentEnabled = false

    /** NPU 模式是否启用 Agent（工具调用）；偏好 npu_agent_enabled 可开启 */
    @JvmStatic
    fun isNpuAgentEnabled(): Boolean = npuAgentEnabled

    @JvmStatic
    fun setNpuAgentEnabled(enabled: Boolean) {
        npuAgentEnabled = enabled
        Log.i(TAG, "NPU Agent(工具)模式 = " + enabled)
    }

    @Volatile private var specEnabled = false
    /** 本次加载时实际使用的 draft 路径（null = 未启用投机）；用于判断"新下了 draft 需要重载" */
    @Volatile private var draftAtLoad: String? = null

    /** 主模型 → draft 模型 的自动匹配（必须同词表，否则投机无效）。
     *  用**模糊匹配**：模型库里注册名可能是 `applib/Qwen3.5-0.8B-Q4_0`、`Qwen3.5-0.8B-Q4_0` 等多种形式。 */
    private fun draftModelFor(modelName: String?): String? {
        val n = modelName?.lowercase() ?: return null
        val keys = localFiles.keys
        fun find(vararg pats: String): String? =
            keys.firstOrNull { k -> pats.any { p -> k.lowercase().contains(p) } }
        return when {
            // Qwen3.5 系列 → 0.8B 作 draft（同词表）
            n.contains("qwen3.5") || n.contains("qwen35") ->
                find("qwen3.5-0.8b", "qwen3.5-08b", "0.8b")
            // Qwen3（非 VL）系列 → 0.6B 作 draft（同词表）
            (n.contains("qwen3") && !n.contains("vl")) ->
                find("qwen3-0.6b", "0.6b")
            else -> null
        }
    }

    /** 当前是否可用投机解码（开关开 + 主模型有对应 draft 且已下载） */
    @JvmStatic
    fun specDraftPath(): String? {
        if (!specEnabled) return null
        val main = currentOrPreferredModelName()
        val draftName = draftModelFor(main) ?: run {
            Log.i(TAG, "投机未启用：主模型 " + main + " 无同词表 draft")
            return null
        }
        val p = localFiles[draftName] ?: run {
            Log.i(TAG, "投机未启用：draft 未下载（" + draftName + "）")
            return null
        }
        Log.i(TAG, "投机 draft 已匹配: 主模型=" + main + " → draft=" + draftName + " @ " + p)
        return p
    }


    /**
     * 是否需要"为投机解码而重载"：模型已加载，但模型库里**新出现了**可用的同词表 draft
     * （典型场景：用户刚下载完 draft，而主模型还常驻在内存里没重新加载）。
     * 上层在预加载/发消息前调用，返回 true 则先 release 再 load → 投机自动生效。
     */
    @JvmStatic
    fun needsReloadForSpec(): Boolean {
        if (!isLoaded()) return false
        val want = specDraftPath() ?: return false
        val loaded = draftAtLoad
        if (loaded == want) return false
        Log.i(TAG, "检测到 draft 变化，需要重载以启用投机: 已加载=" + loaded + " → 现在=" + want)
        return true
    }

    @JvmStatic
    fun setSpecEnabled(enabled: Boolean) {
        specEnabled = enabled
        Log.i(TAG, "NPU 投机解码开关 = " + enabled)
    }

    @JvmStatic
    fun isSpecEnabled(): Boolean = specEnabled

    /** 当前规划出的 NPU 上下文长度（内存预算反推，供 Agent 适配层裁剪历史） */
    @JvmStatic
    fun plannedNCtxValue(): Int = plannedNCtx

    // ==================== NPU-INCREMENTAL：会话增量喂 prompt ====================
    // 取证：LlmWrapper 只暴露 generateStreamFlow(String prompt, cfg) + reset()，说明底层上下文持久；
    // 而实测日志 prefix match: past_prompt_tokens size: 0 → 我们每轮喂全量，从未命中 KV 复用。
    // 若本次 prompt 以上次 prompt 为前缀，则只发送增量后缀，期望复用已有 KV（Agent 多轮提速）。
    @Volatile private var lastPrompt: String? = null
    @Volatile private var incrementalMode = false

    /** 开关增量喂 prompt（默认关；可用偏好 npu_incremental=true 在设备上开启） */
    @JvmStatic
    fun setIncrementalMode(enabled: Boolean) {
        incrementalMode = enabled
        if (!enabled) {
            lastPrompt = null
        }
        Log.i(TAG, "NPU 增量模式 = " + enabled)
    }

    @JvmStatic
    fun isIncrementalMode(): Boolean = incrementalMode

    /** 清空会话前缀（新对话 / 换模型 / 换模板时必须调用，否则增量基准错误） */
    /**
     * 重置会话上下文：调用 GenieX 的 LlmWrapper.reset()（官方 demo 在"新会话/重载模型"时调用，
     * MainActivity.kt:1136）。不调用会导致清空对话后 KV 里仍保留旧上下文（浪费 + 可能串味）。
     */
    /**
     * 中止当前 NPU 生成（对应聊天页「停止」按钮）。
     * 官方 LlmWrapper/VlmWrapper 都提供 suspend stopStream()（javap 已核实）。
     */
    @JvmStatic
    fun stopGeneration() {
        scope.launch {
            try {
                llm?.stopStream()
            } catch (t: Throwable) {
                Log.w(TAG, "llm.stopStream 失败: " + t)
            }
            try {
                vlm?.stopStream()
            } catch (t: Throwable) {
                Log.w(TAG, "vlm.stopStream 失败: " + t)
            }
            Log.i(TAG, "NPU 生成已请求中止")
        }
    }

    @JvmStatic
    fun resetIncrementalSession() {
        lastPrompt = null
        val w = llm
        if (w == null) {
            Log.i(TAG, "NPU 会话重置: 尚未加载模型，仅清基准")
            return
        }
        scope.launch {
            try {
                w.reset()
                Log.i(TAG, "NPU 会话已重置（GenieX reset 调用成功）")
            } catch (t: Throwable) {
                Log.w(TAG, "NPU 会话重置失败: " + t)
            }
        }
    }

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

    /** 从偏好读取增量模式开关（设备上改 npu_engine_prefs 即可，无需重编译） */
    @JvmStatic
    fun loadIncrementalPref(context: Context) {
        try {
            // 2026-10-05 源码核查后废弃：官方用法是每轮全量 prompt，复用由 SDK 内部做；
            // "只发后缀"会破坏 prefix reuse，故此开关**永久关闭**（保留 API 仅为兼容）。
            setIncrementalMode(false)
            // 投机解码开关（默认关；设备上可改 npu_engine_prefs 的 npu_spec_enabled 开启）
            setSpecEnabled(context.getSharedPreferences("npu_engine_prefs", Context.MODE_PRIVATE)
                    .getBoolean("npu_spec_enabled", false))
            // NPU 模式是否走 Agent（默认关 → 普通对话，prompt 更小更快）
            setNpuAgentEnabled(context.getSharedPreferences("npu_engine_prefs", Context.MODE_PRIVATE)
                    .getBoolean("npu_agent_enabled", false))   // 2026-10-05 默认关（双模型加载崩溃）
        } catch (t: Throwable) {
            Log.w(TAG, "loadIncrementalPref 失败: " + t)
        }
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
        loadIncrementalPref(context)
        try {
            appContext = context.applicationContext
            GenieXSdk.getInstance().init(context.applicationContext)
            initialized = true
            Log.i(TAG, "GenieXSdk 已初始化")
        } catch (t: Throwable) {
            state = State.ERROR
                    NpuEngineState.get().setError("加载失败")
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
        // 用户显式指定的模型优先（模型选择页选定并持久化）
        if (preferredOverride.isNotBlank()) {
            val op = localFiles[preferredOverride]
            if (op != null && File(op).isFile) return preferredOverride
        }
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

    /**
     * 构造 ModelPaths。**关键**：GGUF 形态下 VLM 的视觉塔是独立文件（mmproj），
     * 原先这里把 `mmproj_path` 硬编码为 null、`model_type` 固定 LLM → 下载来的 VL 模型
     * （走 app 模型库注册 → buildPaths）**永远不会走 VLM 路径**，看图功能形同虚设。
     *
     * 配对策略（对改名容错）：主模型所在目录里**任何名字含 `mmproj` 的 .gguf** 即认为是它的视觉塔。
     * 因此 mmproj 文件加后缀、重命名（只要保留 mmproj 字样）都仍能配对成功。
     */
    private fun buildPaths(modelName: String, gguf: File): ModelPaths {
        val mmproj = try {
            gguf.parentFile?.listFiles()?.firstOrNull {
                it.isFile && it.name.endsWith(".gguf", true) && it.name.contains("mmproj", true)
            }
        } catch (t: Throwable) {
            null
        }
        if (mmproj != null) {
            Log.i(TAG, "视觉塔已配对: " + mmproj.name + "（主模型 " + gguf.name + "）")
        }
        return ModelPaths(
            model_path = gguf.absolutePath,
            model_dir = gguf.parentFile?.absolutePath ?: "",
            model_name = modelName,
            runtime_id = "llama_cpp",
            model_type = if (mmproj != null) ModelType.VLM else ModelType.LLM,
            mmproj_path = mmproj?.absolutePath,
            tokenizer_path = ""
        )
    }

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
                NpuEngineState.get().startTiming()
                NpuEngineState.get().setCurrentStage(NpuEngineState.Stage.NATIVE_LIBRARY_LOADING, "加载引擎", 10)
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
                // 投机解码：draft 按主模型自动匹配（必须同词表）；GenieX 失败会自动 fallback 普通解码
                val draftPath = specDraftPath()
                draftAtLoad = draftPath
                val conf =
                    if (runtime == "qairt") {
                        ModelConfig(nCtx = 0, nGpuLayers = 0)
                    } else if (draftPath != null) {
                        Log.i(TAG, "启用投机解码: type=draft, draft=" + draftPath + ", n_max=8")
                        ModelConfig(
                            nCtx = plannedNCtx,
                            nGpuLayers = -1,
                            spec_type = "draft",
                            spec_draft_model = draftPath,
                            spec_n_max = 8,
                            spec_n_min = 0,
                            spec_p_min = 0.0f
                        )
                    } else {
                        ModelConfig(nCtx = plannedNCtx, nGpuLayers = -1)
                    }
                // ==================== VLM（多模态）分支 ====================
            // 判定依据与官方 demo 一致：ModelPaths.mmproj_path 非空即为 VLM（GGUF 形态下视觉塔独立成文件）。
            // 官方用法（geniex_chat_android/MainActivity.kt:449-458）：
            //   VlmWrapper.builder().vlmCreateInput(VlmCreateInput(model_path, mmproj_path, config,
            //       runtime_id, compute_unit, vit_device_id)).build()
            if (!paths.mmproj_path.isNullOrEmpty()) {
                Log.i(TAG, "检测到 mmproj → 按 VLM 加载: " + paths.mmproj_path)
                val vconf = if (runtime == "qairt") {
                    ModelConfig(nCtx = 0, nGpuLayers = 0)
                } else {
                    ModelConfig(nCtx = plannedNCtx, nGpuLayers = -1)
                }
                val vres = VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_path = paths.model_path,
                            mmproj_path = paths.mmproj_path ?: "",
                            config = vconf,
                            runtime_id = runtime,
                            compute_unit = computeUnit
                        )
                    )
                    .build()
                vres.onSuccess { wrapper ->
                    try {
                        vlm?.stopStream()
                    } catch (t: Throwable) {
                    }
                    vlm = wrapper
                    currentModel = modelName
                    state = State.READY
                    Log.i(TAG, "VLM 模型加载完成: " + modelName + " (runtime=" + runtime
                            + ", mmproj=" + paths.mmproj_path + ")")
                    listener?.onLoaded(modelName)
                }.onFailure { e ->
                    state = State.ERROR
                    Log.e(TAG, "VLM 模型加载失败", e)
                    listener?.onError(e.message ?: "VLM 模型加载失败")
                }
                return@launch
            }

            NpuEngineState.get().setCurrentStage(NpuEngineState.Stage.MODEL_LOADING, "加载权重", 45)
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
                    NpuEngineState.get().setCurrentModelName(modelName)
                    NpuEngineState.get().setCurrentStage(NpuEngineState.Stage.CHAT_CONTEXT_CREATING, "创建上下文", 85)
                    NpuEngineState.get().setCurrentStage(NpuEngineState.Stage.INITIALIZED, "就绪", 100)
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
     * @param thinking 是否启用**思考链**。GenieX 的 `applyChatTemplate(messages, tools, enableThinking, addGenerationPrompt = true)`
     *                 第 3 个参数就是思考开关（官方源码 LlmWrapper.kt 确认）。这里透传。
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
        toolsJson: String? = null,
        messagesJson: String? = null
    ) {
        ensureInit()
        val wrapper = llm
        if (wrapper == null) {
            listener?.onError("模型未加载")
            return
        }
        if (messagesJson == null && (roles.isEmpty() || roles.size != contents.size)) {
            listener?.onError("对话历史参数不合法")
            return
        }
        state = State.GENERATING
                NpuEngineState.get().setCurrentStage(NpuEngineState.Stage.INITIALIZED, "生成中", 100)
        scope.launch {
            try {
                // ===== 公共流消费（LLM 与 VLM 共用）：统计 token/耗时并转发回调 =====
                suspend fun consume(flow: kotlinx.coroutines.flow.Flow<LlmStreamResult>) {
                    var tokens = 0
                    val text = StringBuilder()
                    val startMs = System.currentTimeMillis()
                    flow.collect { result ->
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
                                Log.i(TAG, "生成完成: " + tokens + " tokens / " + elapsed + "ms / "
                                        + "%.2f".format(lastTps) + " t/s")
                                listener?.onCompleted(tokens, lastTps, elapsed)
                            }
                            is LlmStreamResult.Error -> {
                                state = State.READY
                                Log.e(TAG, "生成错误", result.throwable)
                                listener?.onError(result.throwable.message ?: "生成错误")
                            }
                        }
                    }
                }

                val chat = ArrayList<ChatMessage>(roles.size)
                if (messagesJson != null) {
                    // STRUCTURED-MSG：GenieX 的 ChatMessage 原生支持 toolCalls / toolCallId /
                    // toolName（等价 llama.cpp 的 common_chat_msg）→ 工具轮交给它的模板引擎渲染成
                    // 标准 tool 消息；此前只传 role+content 会把工具轮压成普通文本，模型接不上
                    // （表现：卡在工具执行之后）。
                    val arr = org.json.JSONArray(messagesJson)
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val role = m.optString("role", "user").ifEmpty { "user" }
                        val content = m.optString("content", "")
                        var calls: List<ToolCall> = emptyList()
                        val tcs = m.optJSONArray("tool_calls")
                        if (tcs != null && tcs.length() > 0) {
                            val list = ArrayList<ToolCall>()
                            for (k in 0 until tcs.length()) {
                                val tc = tcs.optJSONObject(k) ?: continue
                                val fn = tc.optJSONObject("function")
                                val id = tc.optString("id", "")
                                val name = if (fn != null) fn.optString("name", "")
                                           else tc.optString("name", "")
                                val args = (if (fn != null) fn.opt("arguments")
                                            else tc.opt("arguments"))?.toString() ?: "{}"
                                list.add(ToolCall(id, name, args))
                            }
                            if (list.isNotEmpty()) calls = list
                        }
                        val toolCallId = m.optString("tool_call_id", "")
                        val toolName = m.optString("name", "")
                        chat.add(ChatMessage(role, content, calls, toolCallId, toolName))
                    }
                    Log.i(TAG, "结构化消息: " + chat.size + " 条（含 toolCalls/toolCallId/toolName）")
                } else {
                    for (i in roles.indices) {
                        chat.add(ChatMessage(roles[i], contents[i]))
                    }
                }
                // 第 3 个参数 addGenerationPrompt=false（与官方示例一致），
                // 第 4 个参数 = enable_thinking（我们之前漏传，导致"深度思考"在 NPU 上无效）
                // addGenerationPrompt=true：与 llama.cpp 的 llama_chat_apply_template (add_ass=true) 对齐，
                // 追加 assistant 前缀，避免模型不进入回答模式（复读/格式变差）。
                // 参数顺序以 GenieX 官方源码 LlmWrapper.kt 为准：
                //   applyChatTemplate(messages, tools, enableThinking, addGenerationPrompt = true)
                // 之前两个布尔写反了 → enableThinking 恒为 true（思考模式永远开着，用户开关无效）、
                // addGenerationPrompt 恒为 false（不给 assistant 前缀，模型不进入回答模式）。2026-10-05 修正。
                // ==================== VLM（多模态）生成分支 ====================
                // 加载时若检测到 mmproj，句柄是 vlm（llm 为 null）→ 必须走 VlmWrapper 的模板与生成。
                // 官方用法（demo MainActivity.kt:715-767）：VlmChatMessage[] → applyChatTemplate(messages, tools, thinking)
                // → injectMediaPathsToConfig(messages, config)（把图片/音频路径注入 config）→ generateStreamFlow。
                val vw = vlm
                if (vw != null) {
                    val vmsgs = ArrayList<VlmChatMessage>(roles.size)
                    val imgPaths = pendingImagePaths
                    for (i in roles.indices) {
                        val parts = ArrayList<VlmContent>(2)
                        parts.add(VlmContent("text", contents[i]))
                        // 图片要作为 VlmContent("image", 路径) 进入消息，
                        // VlmWrapper.injectMediaPathsToConfig() 才会把它们提取进 GenerationConfig
                        // （只放 config 而不放消息是无效的）。挂到最后一条 user 消息上。
                        if (imgPaths.isNotEmpty() && i == roles.indices.last
                                && roles[i].equals("user", ignoreCase = true)) {
                            for (p in imgPaths) {
                                parts.add(VlmContent("image", p))
                            }
                            Log.i(TAG, "VLM 图片已附加到最后一条 user 消息: " + imgPaths.size + " 张")
                        }
                        vmsgs.add(VlmChatMessage(roles[i], parts))
                    }
                    pendingImagePaths = emptyList()   // 用后即清，避免污染下一轮
                    Log.i(TAG, "VLM 生成: messages=" + vmsgs.size + ", images=" + pendingImagePaths.size)
                    val vt = vw.applyChatTemplate(vmsgs.toTypedArray(), toolsJson, thinking)
                    vt.onSuccess { t ->
                        var cfg = GenerationConfig(maxTokens = maxTokens)
                        // 把图片/音频路径注入 config（无图时不改变配置）
                        cfg = vw.injectMediaPathsToConfig(vmsgs.toTypedArray(), cfg)
                        consume(vw.generateStreamFlow(t.formattedText, cfg))
                    }.onFailure { e ->
                        state = State.READY
                        listener?.onError(e.message ?: "VLM chat template 失败")
                    }
                    return@launch
                }


                val templated = wrapper.applyChatTemplate(chat.toTypedArray(), toolsJson, thinking, true)
                templated.onSuccess { t ->
                    // 模板预览：与 llama.cpp 侧对比最终喂给模型的文本是否一致（截 200 字符）
                    Log.i(TAG, "prompt模板预览(thinking=$thinking, tools=${toolsJson != null}): "
                            + t.formattedText.take(200).replace("\n", "\\n"))
                    // 官方语义（geniex_chat_android/MainActivity.kt:752-767）：每轮提交**完整 prompt**，
                    // KV 前缀复用由 GenieX 内部自动完成（插件日志 prefix reuse: |A|/|G|/increment）。
                    // 因此这里不做任何"只发增量后缀"的处理 —— 那会触发
                    // "prefix reuse failed: prompt does not match last generation"。
                    val toSend = t.formattedText
                    Log.i(TAG, "prompt发送: " + toSend.length + " 字符（全量，复用交给 SDK）")
                    consume(wrapper.generateStreamFlow(toSend, GenerationConfig(maxTokens = maxTokens)))
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
        registerLocalModelInFiles(context, Models.LOCAL_QWEN3_0_6B, "gguf/qwen3-0.6b")
        registerLocalModelInFiles(context, Models.LOCAL_QWEN3_1_7B, "gguf/qwen3-1.7b")

        val target = preferredNpuModel()

            // 加载前的内存预算规划：可用内存 − 权重 − App 预留 − 余量 = 可给 KV，反推 nCtx；
            // 装不下就直接拒绝（好过被系统 LMK 杀掉，用户看到的是"闪退"）。
            val planFile = localFiles[target]?.let { File(it) }
                ?: (preferredNpuModel()?.let { localFiles[it] }?.let { File(it) })
            val planned = planNCtx(context, planFile)
            if (planned < 0) {
                val availMb = availableMemBytes(context) / 1048576
                val needMb = (planFile?.length() ?: 0L) / 1048576
                Log.w(TAG, "拒绝加载: 可用 ${availMb}MB < 模型 ${needMb}MB (+缓存/余量)")
                listener?.onError("可用内存不足：可用 ${availMb}MB，该模型约 ${needMb}MB" + "，超出本机预算；建议改用 4B-Q4_0") 
                return
            }
            plannedNCtx = planned
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
        draftAtLoad = null
        NpuEngineState.get().reset()
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
