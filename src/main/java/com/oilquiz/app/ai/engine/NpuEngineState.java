package com.oilquiz.app.ai.engine;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * NPU 推理引擎状态机 —— 与 llama.cpp 侧的 {@link com.oilquiz.app.ai.service.AIServiceState}
 * **同构**（同样的阶段集合、同样的字段与方法名），因此 UI 可以用同一套代码读两条引擎。
 *
 * <p>阶段（与 AIServiceState.ServiceStage 一一对应，括号内为 NPU 侧的实际含义）：
 * <pre>
 *   UNINITIALIZED         未加载
 *   NATIVE_LIBRARY_LOADING  GenieX 插件加载（libgeniex_plugin_llama_cpp，实测约 14s）
 *   MODEL_FILE_PREPARING   路径解析 + 内存预算预检（planNCtx / 是否可装下）
 *   MODEL_LOADING          权重加载（实测约 6s）
 *   GPU_INITIALIZATION     加速单元初始化（NPU = Hexagon HTP / compute_unit=HTP0）
 *   CPU_FALLBACK           量化算子退 CPU（K-quant 等非 HTP 类型）
 *   CHAT_CONTEXT_CREATING  上下文创建（注：NPU 侧实际上在 create() 内部完成，
 *                          不再单独上报该阶段——它没有独立耗时可报）
 *   INITIALIZED            就绪
 *   ERROR                  失败
 * </pre>
 *
 * <p>与 llama.cpp 一致的另一点：**生成中**不属于加载阶段，这里沿用
 * {@code INITIALIZED}，生成状态由界面自身的生成条展示（与本地路径行为一致）。
 */
public final class NpuEngineState {

    /** 与 AIServiceState.ServiceStage 同名的阶段集合 */
    public enum Stage {
        UNINITIALIZED,
        NATIVE_LIBRARY_LOADING,
        MODEL_FILE_PREPARING,
        MODEL_LOADING,
        GPU_INITIALIZATION,
        CPU_FALLBACK,
        CHAT_CONTEXT_CREATING,
        INITIALIZED,
        ERROR
    }

    /**
     * 推理（生成）阶段 —— 与 llama.cpp 的 {@code LlamaHelper.getGenPhase()} 同名同义：
     * IDLE / PREPROCESS（prefill）/ THINKING（思考链）/ GENERATING（正文）。
     * 加载阶段（Stage）描述"引擎是否可用"，推理阶段描述"当前这条消息在做什么"，两者正交。
     */
    public enum InferencePhase {
        IDLE, PREPROCESS, THINKING, GENERATING
    }

    public interface Listener {
        void onNpuStateChanged(Stage stage, String message, int progressPercent);
    }

    private static final String TAG = "NpuEngineState";
    private static final NpuEngineState INSTANCE = new NpuEngineState();

    // ---- 与 AIServiceState 同名的字段 ----
    private volatile Stage currentStage = Stage.UNINITIALIZED;
    private volatile String stageMessage = "";
    private volatile int progressPercent = 0;
    private volatile long startTime = 0;
    private volatile String currentModelName = null;
    private volatile String errorMessage = null;
    private volatile long estimatedTimeMs = 0;
    /**
     * 进入当前阶段的时刻（用于 {@link #getStageLabel()} 显示"已耗时 X 秒"）。
     *
     * <p>WHY（2026-10-07 实测）：NPU 加载权重那一段（{@code LlmWrapper.builder().build()}）
     * 是**一次阻塞调用**，实测 6~10 秒，且 SDK 三层（Java / JNI / native）都**没有加载进度回调**，
     * 所以这段时间界面上只有一行不动的"加载权重 45%"，观感与死机无异。
     * 既然拿不到真进度，就让标签**带上正在走的秒数**：{@code getStageLabel()} 是被轮询调用的，
     * 动态算耗时天然就是心跳，不需要额外起定时器。</p>
     */
    private volatile long stageStartedAt = 0;
    private final Object lock = new Object();
    // ---- 推理状态机（对齐 llama.cpp GenPhase）----
    private volatile InferencePhase inferencePhase = InferencePhase.IDLE;
    private volatile int prefillPercent = 0;
    private volatile int generatedTokens = 0;
    private volatile float lastTps = 0f;
    private volatile long inferenceStartMs = 0;
    private final List<Listener> listeners = new ArrayList<>();

    private NpuEngineState() {
    }

    public static NpuEngineState get() {
        return INSTANCE;
    }

    // ==================== 与 AIServiceState 同名的方法 ====================

    public Stage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(Stage stage, String message) {
        setCurrentStage(stage, message, progressPercent);
    }

    /** 设置阶段（带进度）；非法转移会被忽略并告警 */
    public synchronized void setCurrentStage(Stage stage, String message, int progress) {
        if (stage == null) {
            return;
        }
        Stage cur = currentStage;
        // 只在"重复设置同一阶段且文案相同"时跳过
        if (stage == cur && message != null && message.equals(stageMessage)
                && progress == progressPercent) {
            return;
        }
        // 非法转移：未加载（UNINITIALIZED）不允许直接 INITIALIZED
        if (stage == Stage.INITIALIZED && cur == Stage.UNINITIALIZED) {
            Log.w(TAG, "非法转移被忽略: UNINITIALIZED -> INITIALIZED（应先经历加载阶段）");
            return;
        }
        currentStage = stage;
        stageMessage = (message == null || message.isEmpty()) ? defaultMessage(stage) : message;
        progressPercent = Math.max(0, Math.min(100, progress));
        stageStartedAt = System.currentTimeMillis();
        if (stage == Stage.ERROR) {
            errorMessage = stageMessage;
        } else if (stage != Stage.UNINITIALIZED) {
            errorMessage = null;
        }
        Log.i(TAG, "阶段: " + cur + " -> " + stage + " " + stageMessage);
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onNpuStateChanged(currentStage, stageMessage, progressPercent);
            } catch (Throwable ignored) {
            }
        }
    }

    public String getStageMessage() {
        return stageMessage;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progress) {
        synchronized (lock) {
            progressPercent = Math.max(0, Math.min(100, progress));
        }
    }

    public long getStartTime() {
        return startTime;
    }

    public void startTiming() {
        startTime = System.currentTimeMillis();
    }

    public long getElapsedTimeMs() {
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }

    public void setCurrentModelName(String modelName) {
        currentModelName = modelName;
    }

    public String getCurrentModelName() {
        return currentModelName;
    }

    public void setError(String error) {
        errorMessage = error;
        setCurrentStage(Stage.ERROR, error == null ? "失败" : error, 0);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getEstimatedTimeMs() {
        return estimatedTimeMs;
    }

    public void setEstimatedTimeMs(long ms) {
        estimatedTimeMs = ms;
    }

    // ==================== 推理状态机（对齐 llama.cpp GenPhase） ====================

    public InferencePhase getInferencePhase() {
        return inferencePhase;
    }

    /** 阶段名（与 llama.cpp 的 phase 字段完全一致：IDLE/PREPROCESS/THINKING/GENERATING） */
    public String getInferencePhaseName() {
        return inferencePhase.name();
    }

    /** 中文标签（UI 用） */
    public String getInferencePhaseLabel() {
        switch (inferencePhase) {
            case PREPROCESS:
                return "处理提示" + (prefillPercent > 0 && prefillPercent < 100 ? " " + prefillPercent + "%" : "");
            case THINKING:
                return "思考中";
            case GENERATING:
                return "生成中";
            default:
                return "空闲";
        }
    }

    /** 开始一轮推理：PREPROCESS（prefill 阶段，进度未知时先给 0） */
    public synchronized void beginInference() {
        inferencePhase = InferencePhase.PREPROCESS;
        prefillPercent = 0;
        generatedTokens = 0;
        lastTps = 0f;
        inferenceStartMs = System.currentTimeMillis();
        Log.i(TAG, "推理阶段: -> PREPROCESS");
        notifyListeners();
    }

    /** 首个思考 token：PREPROCESS -> THINKING */
    public synchronized void onThinkingStarted() {
        if (inferencePhase != InferencePhase.THINKING) {
            prefillPercent = 100;
            inferencePhase = InferencePhase.THINKING;
            Log.i(TAG, "推理阶段: -> THINKING");
            notifyListeners();
        }
    }

    /** 首个正文 token：PREPROCESS/THINKING -> GENERATING */
    public synchronized void onGeneratingStarted() {
        if (inferencePhase != InferencePhase.GENERATING) {
            prefillPercent = 100;
            inferencePhase = InferencePhase.GENERATING;
            Log.i(TAG, "推理阶段: -> GENERATING");
            notifyListeners();
        }
    }

    /** 每收到一个 token 调用（累计计数，供状态条显示） */
    public void onToken() {
        generatedTokens++;
    }

    /** 一轮推理结束（正常/取消/失败都回 IDLE，并记录 tokens/tps） */
    public synchronized void endInference(int tokens, float tps) {
        generatedTokens = tokens > 0 ? tokens : generatedTokens;
        lastTps = tps;
        prefillPercent = 0;
        inferencePhase = InferencePhase.IDLE;
        Log.i(TAG, "推理阶段: -> IDLE (" + generatedTokens + " tokens, "
                + String.format(java.util.Locale.US, "%.2f", lastTps) + " t/s)");
        notifyListeners();
    }

    public int getPrefillPercent() {
        return prefillPercent;
    }

    public int getGeneratedTokens() {
        return generatedTokens;
    }

    public float getLastTps() {
        return lastTps;
    }

    public long getInferenceElapsedMs() {
        return inferenceStartMs > 0 ? System.currentTimeMillis() - inferenceStartMs : 0;
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onNpuStateChanged(currentStage, stageMessage, progressPercent);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 供状态条读取的 JSON（字段与 llama.cpp 的 getGenPhase 对齐） */
    public String getInferenceJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("phase", getInferencePhaseName());
            o.put("running", inferencePhase != InferencePhase.IDLE);
            o.put("prefill", prefillPercent);
            o.put("tokens", generatedTokens);
            o.put("tps", lastTps);
            return o.toString();
        } catch (Throwable t) {
            return "{\"phase\":\"IDLE\"}";
        }
    }

    // ==================== NPU 侧便利方法（UI 直接用） ====================

    public boolean isReady() {
        return currentStage == Stage.INITIALIZED;
    }

    /**
     * 状态栏文案：**短句 + 实时秒数**，如「模型加载中 6s」「AI 已就绪」。
     *
     * <p><b>为什么不用百分比</b>（2026-10-07 实测）：那些 10/45/100 都是各阶段入口写死的常量，
     * 不是测量值；而最耗时的"加载权重"是 {@code LlmWrapper.builder().build()} 一次阻塞调用
     * （实测 5.7~9.8 秒），SDK 三层（Java / JNI / native）都没有加载进度回调。
     * 把常量显示成百分比等于**把常量伪装成进度**，只会让人误以为"卡在 45%"。
     * 故只说清在做什么，配一个**真实可得**的秒数（调用方每秒刷新，秒数会走 → 表明没死）。</p>
     *
     * <p>文案刻意保持极短：状态栏一行还要放模型名等信息，过长会被截断。</p>
     */
    public String getStageLabel() {
        String doing;
        switch (currentStage) {
            case NATIVE_LIBRARY_LOADING:
                doing = "引擎启动中";
                break;
            case MODEL_FILE_PREPARING:
                doing = "准备模型中";
                break;
            case MODEL_LOADING:
                doing = "模型加载中";
                break;
            case GPU_INITIALIZATION:
            case CPU_FALLBACK:
                doing = "初始化中";
                break;
            case CHAT_CONTEXT_CREATING:
                doing = "准备上下文中";
                break;
            case INITIALIZED:
                return "AI 已就绪";
            case ERROR:
                return "AI 不可用";
            default:
                return "AI 待加载";
        }
        long startedAt = stageStartedAt;
        if (startedAt > 0) {
            long sec = (System.currentTimeMillis() - startedAt) / 1000L;
            if (sec >= 1) {
                return doing + " " + sec + "s";
            }
        }
        return doing;
    }

    /** 当前是否处于加载/初始化阶段（供 UI 决定是否需要每秒刷新心跳） */
    public boolean isStageBusy() {
        switch (currentStage) {
            case NATIVE_LIBRARY_LOADING:
            case MODEL_FILE_PREPARING:
            case MODEL_LOADING:
            case GPU_INITIALIZATION:
            case CPU_FALLBACK:
            case CHAT_CONTEXT_CREATING:
                return true;
            default:
                return false;
        }
    }

    public String getStageName() {
        switch (currentStage) {
            case NATIVE_LIBRARY_LOADING:
                return "加载引擎";
            case MODEL_FILE_PREPARING:
                return "准备模型";
            case MODEL_LOADING:
                return "加载权重";
            case GPU_INITIALIZATION:
                return "初始化 NPU";
            case CPU_FALLBACK:
                return "部分算子退 CPU";
            case CHAT_CONTEXT_CREATING:
                return "创建上下文";
            case INITIALIZED:
                return "就绪";
            case ERROR:
                return "失败";
            default:
                return "待加载";
        }
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 释放/切换模型：回到 UNINITIALIZED */
    public synchronized void reset() {
        currentModelName = null;
        startTime = 0;
        progressPercent = 0;
        errorMessage = null;
        setCurrentStage(Stage.UNINITIALIZED, "未加载", 0);
    }

    private static String defaultMessage(Stage s) {
        switch (s) {
            case NATIVE_LIBRARY_LOADING:
                return "加载引擎";
            case MODEL_FILE_PREPARING:
                return "准备模型";
            case MODEL_LOADING:
                return "加载权重";
            case GPU_INITIALIZATION:
                return "初始化 NPU";
            case CPU_FALLBACK:
                return "部分算子退 CPU";
            case CHAT_CONTEXT_CREATING:
                return "创建上下文";
            case INITIALIZED:
                return "就绪";
            case ERROR:
                return "失败";
            default:
                return "未加载";
        }
    }
}
