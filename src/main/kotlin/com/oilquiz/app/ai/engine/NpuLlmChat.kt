package com.oilquiz.app.ai.engine

import android.content.Context
import java.io.File

/**
 * GENIEX-REMOVED(2026-11-08)：GenieX SDK 已整条移除。
 *
 * <p>这个类只保留**同名 API 空壳**，避免 40+ 调用点一次性改签名；它不再 import
 * {@code com.geniex.sdk.*}，也不会加载/初始化任何 GenieX 库。实际 NPU 走
 * llama.cpp 的 ggml-hexagon（GGML_HEXAGON=ON，native auto 选 HTP）。
 *
 * <p>所有状态恒为“未启用/未加载”，因此 NpuEngineRouter 会直接回落 llama.cpp；
 * 不要在这里恢复 GenieX 逻辑。</p>
 */
object NpuLlmChat {

    private const val TAG = "NpuLlmChat"

    enum class State { IDLE, DOWNLOADING, READY, LOADING, GENERATING, ERROR }

    interface DownloadListener {
        fun onProgress(percent: Float, modelName: String)
        fun onCompleted(modelName: String)
        fun onError(code: Int, message: String)
    }

    interface LoadListener {
        fun onLoaded(modelName: String)
        fun onError(message: String)
    }

    interface GenerateListener {
        fun onToken(text: String)
        fun onCompleted(tokens: Int, tps: Float, elapsedMs: Long)
        fun onError(message: String)
    }

    // ==================== 状态（恒为停用） ====================

    @JvmStatic
    fun setEngineEnabled(enabled: Boolean) {
        // GenieX 已移除：即使旧偏好为 true 也不允许点亮。
    }

    @JvmStatic
    fun isEngineEnabled(): Boolean = false

    @JvmStatic
    fun isLoaded(): Boolean = false

    @JvmStatic
    fun getState(): State = State.IDLE

    @JvmStatic
    fun getStateName(): String = State.IDLE.name

    @JvmStatic
    fun getCurrentModel(): String = ""

    @JvmStatic
    fun currentOrPreferredModelName(): String = ""

    @JvmStatic
    fun getLastTps(): Float = 0f

    @JvmStatic
    fun getLastTokens(): Int = 0

    @JvmStatic
    fun getLastElapsedMs(): Long = 0L

    @JvmStatic
    fun getLastPromptTokens(): Int = 0

    @JvmStatic
    fun getLastCtxUsed(): Int = 0

    @JvmStatic
    fun getDownloadProgress(): Float = 0f

    @JvmStatic
    fun plannedNCtxValue(): Int = -1

    @JvmStatic
    fun isNCtxPlanned(): Boolean = false

    @JvmStatic
    fun needsReloadForSpec(): Boolean = false

    @JvmStatic
    fun hasUsableModel(): Boolean = false

    @JvmStatic
    fun isNpuSupportedDevice(): Boolean = false

    @JvmStatic
    fun isNpuAgentEnabled(): Boolean = false

    @JvmStatic
    fun setNpuAgentEnabled(enabled: Boolean) {}

    @JvmStatic
    fun isNpuVisionEnabled(): Boolean = false

    @JvmStatic
    fun setNpuVisionEnabled(enabled: Boolean) {}

    @JvmStatic
    fun setSpecEnabled(enabled: Boolean) {}

    @JvmStatic
    fun isSpecEnabled(): Boolean = false

    @JvmStatic
    fun setIncrementalMode(enabled: Boolean) {}

    @JvmStatic
    fun isIncrementalMode(): Boolean = false

    @JvmStatic
    fun setNpuPowerMode(mode: String?) {}

    @JvmStatic
    fun getNpuPowerMode(): String = ""

    @JvmStatic
    fun specDraftPath(): String? = null

    // ==================== 兼容空操作 ====================

    @JvmStatic
    fun init(context: Context) {}

    @JvmStatic
    fun loadPreferredModelName(context: Context) {}

    @JvmStatic
    fun setPreferredModelName(name: String?) {}

    @JvmStatic
    fun preferredModelName(): String = ""

    @JvmStatic
    fun setPendingImagePaths(paths: List<String>?) {}

    @JvmStatic
    fun planForCurrentModel(context: Context) {}

    @JvmStatic
    fun resetIncrementalSession() {}

    @JvmStatic
    fun stopGeneration() {}

    @JvmStatic
    fun stopGenerate() {}

    @JvmStatic
    fun release() {}

    @JvmStatic
    fun loadIncrementalPref(context: Context) {}

    @JvmStatic
    fun listAvailableModels(context: Context): List<String> = emptyList()

    @JvmStatic
    fun listModels(): List<String> = emptyList()

    @JvmStatic
    fun isModelDownloaded(modelName: String): Boolean = false

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
        listener?.onError(1, "GenieX 已移除；请使用 llama.cpp 本地模型")
    }

    @JvmStatic
    fun loadModel(modelName: String, runtimeId: String?, computeUnit: String?, listener: LoadListener?) {
        listener?.onError("GenieX 已移除；请使用 llama.cpp 本地模型")
    }

    @JvmStatic
    fun ensureLoadedAsync(context: Context, listener: LoadListener?) {
        listener?.onError("GenieX 已移除；NPU 走 llama.cpp HTP")
    }

    @JvmStatic
    fun registerLocalModel(modelName: String, dirPath: String?): String? = null

    @JvmStatic
    fun registerLocalModelInFiles(context: Context, modelName: String, relDir: String): String? = null

    @JvmStatic
    fun registerLocalModelFile(modelName: String, ggufPath: String?): Boolean = false

    @JvmStatic
    fun appModelDir(context: Context): File = File(context.filesDir, "ai_models")

    @JvmStatic
    fun registerAppModelLibrary(context: Context): List<String> = emptyList()

    @JvmStatic
    fun preferredNpuModel(): String? = null

    @JvmStatic
    fun sendMessageAsync(prompt: String, maxTokens: Int, listener: GenerateListener?) {
        listener?.onError("GenieX 已移除；请使用 llama.cpp 本地模型")
    }

    @JvmStatic
    fun sendChatAsync(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        listener: GenerateListener?
    ) {
        sendChatAsync(roles, contents, maxTokens, false, listener)
    }

    @JvmStatic
    @JvmOverloads
    fun sendChatAsync(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        thinking: Boolean,
        listener: GenerateListener?,
        toolsJson: String? = null,
        messagesJson: String? = null,
        temperature: Float = -1f,
        topP: Float = -1f,
        topK: Int = -1
    ) {
        listener?.onError("GenieX 已移除；请使用 llama.cpp 本地模型")
    }

    @JvmStatic
    @JvmOverloads
    fun generateBlocking(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        timeoutMs: Long = 10 * 60 * 1000L,
        thinking: Boolean = false
    ): String = ""

    @JvmStatic
    fun statusSummary(context: Context): String =
        "GenieX 已移除；NPU 由 llama.cpp ggml-hexagon（HTP）提供"
}
