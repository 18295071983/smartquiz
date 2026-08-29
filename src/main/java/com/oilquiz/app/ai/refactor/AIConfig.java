package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.content.SharedPreferences;

public class AIConfig {
    private static final String PREFS_NAME = "ai_config";
    private final SharedPreferences prefs;

    /**
     * 优化模式枚举
     * 上下文取值兼顾稳定性：Qwen3-4B 每 token KV≈90KB，CPU 推理时
     * 16384 上下文峰值内存 ~4.8GB 会被系统杀进程（实测），故回落安全档位。
     * 内存池仅作预算上限，实际按 n_ctx 分配，无需随上下文缩减。
     */
    public enum OptimizationMode {
        /** 极速模式：最小资源占用，最快响应，适合低端设备 */
        TURBO(0, "极速模式", 4096, 64, 1024, 2, false),
        /** 均衡模式：资源与质量平衡，默认模式（12K：深度思考+多轮工具对话需要更大上下文） */
        BALANCED(1, "均衡模式", 12288, 128, 2048, 3, true),
        /** 性能模式：更大上下文，更好回复质量 */
        PERFORMANCE(2, "性能模式", 16384, 256, 2560, 4, true),
        /** 极限模式：最大资源利用，适合高端设备（受 ResourceConfig 16K 封顶约束） */
        ULTIMATE(3, "极限模式", 16384, 512, 3072, 4, true);

        public final int id;
        public final String displayName;
        public final int contextSize;
        public final int batchSize;
        public final int memoryPoolMB;
        public final int maxThreads;
        public final boolean gpuEnabled;

        OptimizationMode(int id, String displayName, int contextSize, int batchSize,
                         int memoryPoolMB, int maxThreads, boolean gpuEnabled) {
            this.id = id;
            this.displayName = displayName;
            this.contextSize = contextSize;
            this.batchSize = batchSize;
            this.memoryPoolMB = memoryPoolMB;
            this.maxThreads = maxThreads;
            this.gpuEnabled = gpuEnabled;
        }

        public static OptimizationMode fromId(int id) {
            for (OptimizationMode m : values()) {
                if (m.id == id) return m;
            }
            return BALANCED; // 默认
        }
    }

    private int maxTokens = 16384;
    private float temperature = 0.7f;
    private float topP = 0.9f;
    private int topK = 40;
    private String systemPrompt = "请用中文回答。";
    private boolean cacheEnabled = true;
    private boolean autoModeEnabled = false; // 禁用自动模式切换，所有模式由用户手动选择
    private boolean intentRecognitionEnabled = true;
    private boolean agentEnabled = true; // 启用 Agent 模式
    private boolean useJsonProtocol = true;      // 本地推理 JSON 协议开关（spec §10.2 回退用）
    private boolean localAgentEnabled = false;   // 本地 Agent 复活入口开关（spec §3.1.1，R3-1）
    private boolean fcEnabled = false;   // 本地 Agent 模型自主 FC 循环开关（Qwen 原生格式对齐，路径 A）
    private OptimizationMode optimizationMode = OptimizationMode.BALANCED;

    public AIConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPreferences();
    }

    private void loadFromPreferences() {
        try {
            maxTokens = prefs.getInt("max_tokens", 16384);
        } catch (ClassCastException e) {
            prefs.edit().remove("max_tokens").putInt("max_tokens", 16384).apply();
            maxTokens = 16384;
        }
        
        try {
            temperature = prefs.getFloat("temperature", 0.7f);
        } catch (ClassCastException e) {
            prefs.edit().remove("temperature").putFloat("temperature", 0.7f).apply();
            temperature = 0.7f;
        }
        
        try {
            topP = prefs.getFloat("top_p", 0.9f);
        } catch (ClassCastException e) {
            prefs.edit().remove("top_p").putFloat("top_p", 0.9f).apply();
            topP = 0.9f;
        }
        
        try {
            topK = prefs.getInt("top_k", 40);
        } catch (ClassCastException e) {
            prefs.edit().remove("top_k").putInt("top_k", 40).apply();
            topK = 40;
        }
        
        systemPrompt = prefs.getString("system_prompt", "请用中文回答。");
        
        try {
            cacheEnabled = prefs.getBoolean("cache_enabled", true);
        } catch (ClassCastException e) {
            prefs.edit().remove("cache_enabled").putBoolean("cache_enabled", true).apply();
            cacheEnabled = true;
        }
        
        try {
            autoModeEnabled = prefs.getBoolean("auto_mode_enabled", true);
        } catch (ClassCastException e) {
            prefs.edit().remove("auto_mode_enabled").putBoolean("auto_mode_enabled", true).apply();
            autoModeEnabled = true;
        }
        
        try {
            intentRecognitionEnabled = prefs.getBoolean("intent_recognition_enabled", true);
        } catch (ClassCastException e) {
            prefs.edit().remove("intent_recognition_enabled").putBoolean("intent_recognition_enabled", true).apply();
            intentRecognitionEnabled = true;
        }
        
        try {
            agentEnabled = prefs.getBoolean("agent_enabled", true);
        } catch (ClassCastException e) {
            prefs.edit().remove("agent_enabled").putBoolean("agent_enabled", true).apply();
            agentEnabled = true;
        }

        try {
            useJsonProtocol = prefs.getBoolean("use_json_protocol", true);
        } catch (ClassCastException e) {
            prefs.edit().remove("use_json_protocol").putBoolean("use_json_protocol", true).apply();
            useJsonProtocol = true;
        }

        try {
            localAgentEnabled = prefs.getBoolean("local_agent_enabled", false);
        } catch (ClassCastException e) {
            prefs.edit().remove("local_agent_enabled").putBoolean("local_agent_enabled", false).apply();
            localAgentEnabled = false;
        }

        try {
            fcEnabled = prefs.getBoolean("local_fc_enabled", false);
        } catch (ClassCastException e) {
            prefs.edit().remove("local_fc_enabled").putBoolean("local_fc_enabled", false).apply();
            fcEnabled = false;
        }
        
        try {
            optimizationMode = OptimizationMode.fromId(prefs.getInt("optimization_mode", OptimizationMode.BALANCED.id));
        } catch (ClassCastException e) {
            prefs.edit().remove("optimization_mode").putInt("optimization_mode", OptimizationMode.BALANCED.id).apply();
            optimizationMode = OptimizationMode.BALANCED;
        }
    }

    private void saveToPreferences() {
        prefs.edit()
            .putInt("max_tokens", maxTokens)
            .putFloat("temperature", temperature)
            .putFloat("top_p", topP)
            .putInt("top_k", topK)
            .putString("system_prompt", systemPrompt)
            .putBoolean("cache_enabled", cacheEnabled)
            .putBoolean("auto_mode_enabled", autoModeEnabled)
            .putBoolean("intent_recognition_enabled", intentRecognitionEnabled)
            .putBoolean("agent_enabled", agentEnabled)
            .putBoolean("use_json_protocol", useJsonProtocol)
            .putBoolean("local_agent_enabled", localAgentEnabled)
            .putBoolean("local_fc_enabled", fcEnabled)
            .putInt("optimization_mode", optimizationMode.id)
            .apply();
    }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; saveToPreferences(); }

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; saveToPreferences(); }

    public float getTopP() { return topP; }
    public void setTopP(float topP) { this.topP = topP; saveToPreferences(); }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; saveToPreferences(); }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; saveToPreferences(); }

    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; saveToPreferences(); }

    public boolean isAutoModeEnabled() { return autoModeEnabled; }
    public void setAutoModeEnabled(boolean autoModeEnabled) { this.autoModeEnabled = autoModeEnabled; saveToPreferences(); }

    public boolean isIntentRecognitionEnabled() { return intentRecognitionEnabled; }
    public void setIntentRecognitionEnabled(boolean intentRecognitionEnabled) { this.intentRecognitionEnabled = intentRecognitionEnabled; saveToPreferences(); }

    public boolean isAgentEnabled() { return agentEnabled; }
    public void setAgentEnabled(boolean agentEnabled) { this.agentEnabled = agentEnabled; saveToPreferences(); }

    public boolean isUseJsonProtocol() { return useJsonProtocol; }
    public void setUseJsonProtocol(boolean useJsonProtocol) { this.useJsonProtocol = useJsonProtocol; saveToPreferences(); }

    public boolean isLocalAgentEnabled() { return localAgentEnabled; }
    public void setLocalAgentEnabled(boolean localAgentEnabled) { this.localAgentEnabled = localAgentEnabled; saveToPreferences(); }

    public boolean isFcEnabled() { return fcEnabled; }
    public void setFcEnabled(boolean fcEnabled) { this.fcEnabled = fcEnabled; saveToPreferences(); }

    public OptimizationMode getOptimizationMode() { return optimizationMode; }
    public void setOptimizationMode(OptimizationMode mode) { this.optimizationMode = mode; saveToPreferences(); }

    public int getContextSize() { return optimizationMode.contextSize; }
}
