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
 *   CHAT_CONTEXT_CREATING  上下文创建（nCtx，实测 32768）
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
    private final Object lock = new Object();
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
        if (stage == Stage.ERROR) {
            errorMessage = stageMessage;
        } else if (stage != Stage.UNINITIALIZED) {
            errorMessage = null;
        }
        Log.i(TAG, "阶段: " + cur + " -> " + stage + " (" + progressPercent + "%) " + stageMessage);
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

    // ==================== NPU 侧便利方法（UI 直接用） ====================

    public boolean isReady() {
        return currentStage == Stage.INITIALIZED;
    }

    /** 中文短标签（加载阶段带进度）：如「加载中 15%」「就绪」「失败」 */
    public String getStageLabel() {
        String base = getStageName();
        if ((currentStage == Stage.NATIVE_LIBRARY_LOADING || currentStage == Stage.MODEL_FILE_PREPARING
                || currentStage == Stage.MODEL_LOADING || currentStage == Stage.GPU_INITIALIZATION
                || currentStage == Stage.CHAT_CONTEXT_CREATING)
                && progressPercent > 0 && progressPercent < 100) {
            return base + " " + progressPercent + "%";
        }
        return base;
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
