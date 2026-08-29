package com.oilquiz.app.ai.importing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.model.OnlineModelManager;

/**
 * 题库导入模型配置载体（原 v1 导入编排引擎精简版）。
 * <p>
 * 历史说明：早期 v1 版本（AIImportAgent 驱动、规则+AI 双路径解析、QA Gate 数量对账、
 * persistBatch 批量入库等）的导入主流程已被 v2 {@code com.oilquiz.app.ai.importing.v2.ImportMain}
 * （Python 桥接 + 分片入库 + 断点续传）完全取代，全项目已无任何调用方，相关死代码已删除。
 * <p>
 * 本类保留为「模型模式 + 模型信息」的轻量载体：
 * <ul>
 *   <li>{@code AIImportActivity} 通过它切换 / 展示在线、本地模型模式</li>
 *   <li>v2 {@code ImportMain} 构造 {@code ImportLlmEngine} 时传入本实例作为模型信息源</li>
 * </ul>
 */
public class AIImportOrchestrator {

    /** 模型调用模式 */
    public enum ModelMode {
        ONLINE_PREFERRED, ONLINE_ONLY, LOCAL_ONLY, AUTO
    }

    private final OnlineModelManager onlineModelManager;
    private final ALChat localChat = new ALChat();
    @SuppressWarnings("unused")
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @SuppressWarnings("unused")
    private volatile boolean cancelled = false;
    private ModelMode modelMode = ModelMode.AUTO;
    private volatile boolean localModelPaused = false;

    private java.util.function.Consumer<String> agentErrorCallback;

    public AIImportOrchestrator(Context context) {
        this.onlineModelManager = OnlineModelManager.getInstance(context.getApplicationContext());
    }

    public void setModelMode(ModelMode mode) {
        this.modelMode = mode;
        this.localModelPaused = false;
    }

    public ModelMode getModelMode() { return modelMode; }

    public String getCurrentModelInfo() {
        OnlineModelManager.OnlineModelConfig online = onlineModelManager.getActiveModel();
        switch (modelMode) {
            case ONLINE_ONLY:
                return online != null ? "在线: " + online.name : "在线(无可用模型)";
            case LOCAL_ONLY:
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "本地(未加载)";
            case ONLINE_PREFERRED:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型(降级)" : "本地(未加载)";
            case AUTO:
            default:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "无可用模型";
        }
    }

    public void cancel() { cancelled = true; }

    @SuppressWarnings("unused")
    public void setAgentErrorCallback(java.util.function.Consumer<String> callback) {
        this.agentErrorCallback = callback;
    }
}
