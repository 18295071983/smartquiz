package com.oilquiz.app.ai.engine;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * NPU 推理引擎状态机（与 llama.cpp 侧的 {@code AIServiceState} 同构，便于 UI 统一读取）。
 *
 * <p>状态：IDLE（未加载）→ LOADING（加载中，带进度）→ READY（就绪）→ GENERATING（生成中）
 * → READY；任意状态可进 ERROR；release() 回 IDLE。
 *
 * <p>转移规则由 {@link #transition(Stage, String, int)} 强制：
 * <ul>
 *   <li>LOADING 只允许从 IDLE / ERROR / READY 进入（重复 LOADING 忽略）；</li>
 *   <li>GENERATING 必须已 READY（否则忽略并告警，避免未加载就生成）；</li>
 *   <li>ERROR 可从任意状态进入，并记录错误信息；</li>
 *   <li>IDLE 只在 release 时进入，同时清空错误与进度。</li>
 * </ul>
 */
public final class NpuEngineState {

    public enum Stage {
        IDLE, LOADING, READY, GENERATING, ERROR
    }

    public interface Listener {
        void onNpuStateChanged(Stage stage, String message, int progressPercent);
    }

    private static final String TAG = "NpuEngineState";
    private static final NpuEngineState INSTANCE = new NpuEngineState();

    private volatile Stage stage = Stage.IDLE;
    private volatile String message = "未加载";
    private volatile String errorMessage = "";
    private volatile int progressPercent = 0;
    private volatile String modelName = "";
    private volatile long lastChangeMs = System.currentTimeMillis();
    private final List<Listener> listeners = new ArrayList<>();

    private NpuEngineState() {
    }

    public static NpuEngineState get() {
        return INSTANCE;
    }

    public Stage getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public String getModelName() {
        return modelName;
    }

    public long getLastChangeMs() {
        return lastChangeMs;
    }

    public boolean isReady() {
        return stage == Stage.READY || stage == Stage.GENERATING;
    }

    /** 中文状态名（供 UI 直接显示，替代各处 switch） */
    public String getStageName() {
        switch (stage) {
            case LOADING:
                return "加载中";
            case READY:
                return "就绪";
            case GENERATING:
                return "生成中";
            case ERROR:
                return "不可用";
            default:
                return "待加载";
        }
    }

    /**
     * 带进度的显示标签（UI 直接用，无需自己拼）：如「加载中 15%」「就绪」「生成中」。
     * 进度只在 LOADING 且 0&lt;progress&lt;100 时显示，避免"就绪 100%"这类冗余。
     */
    public String getStageLabel() {
        String base = getStageName();
        int p = progressPercent;
        if (stage == Stage.LOADING && p > 0 && p < 100) {
            return base + " " + p + "%";
        }
        return base;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 状态转移；返回是否真的发生了变化 */
    public synchronized boolean transition(Stage next, String msg, int progress) {
        if (next == null) {
            return false;
        }
        Stage cur = stage;
        if (next == cur && (msg == null || msg.equals(message))) {
            return false;
        }
        // ---- 转移合法性校验 ----
        if (next == Stage.GENERATING && cur != Stage.READY && cur != Stage.GENERATING) {
            Log.w(TAG, "非法转移被忽略: " + cur + " -> GENERATING（模型未就绪）");
            return false;
        }
        if (next == Stage.READY && cur == Stage.IDLE) {
            Log.w(TAG, "非法转移被忽略: IDLE -> READY（应先 LOADING）");
            return false;
        }
        stage = next;
        message = (msg == null || msg.isEmpty()) ? defaultMessage(next) : msg;
        progressPercent = Math.max(0, Math.min(100, progress));
        lastChangeMs = System.currentTimeMillis();
        if (next == Stage.ERROR) {
            errorMessage = message;
        } else if (next != Stage.LOADING) {
            errorMessage = "";
        }
        Log.i(TAG, "状态: " + cur + " -> " + next + " (" + progressPercent + "%) " + message);
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onNpuStateChanged(stage, message, progressPercent);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    /** 记录当前模型名（加载成功时调用） */
    public void setModelName(String name) {
        modelName = name == null ? "" : name;
    }

    /** 释放/切换模型时回到 IDLE */
    public synchronized void reset() {
        modelName = "";
        transition(Stage.IDLE, "未加载", 0);
    }

    private static String defaultMessage(Stage s) {
        switch (s) {
            case LOADING:
                return "加载中";
            case READY:
                return "就绪";
            case GENERATING:
                return "生成中";
            case ERROR:
                return "不可用";
            default:
                return "未加载";
        }
    }
}
