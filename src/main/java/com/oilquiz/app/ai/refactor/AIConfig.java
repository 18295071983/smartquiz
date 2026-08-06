package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.content.SharedPreferences;

public class AIConfig {
    private static final String PREFS_NAME = "ai_config";
    private final SharedPreferences prefs;

    /**
     * 优化模式枚举
     */
    public enum OptimizationMode {
        /** 极速模式：最小资源占用，最快响应，适合低端设备 */
        TURBO(0, "极速模式", 4096, 64, 256, 2, false),
        /** 均衡模式：资源与质量平衡，默认模式 */
        BALANCED(1, "均衡模式", 8192, 128, 512, 3, true),
        /** 性能模式：更大上下文，更好回复质量 */
        PERFORMANCE(2, "性能模式", 12288, 256, 1024, 4, true),
        /** 极限模式：最大资源利用，适合高端设备 */
        ULTIMATE(3, "极限模式", 16384, 512, 2048, 4, true);

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

    private int maxTokens = 4096;
    private float temperature = 0.7f;
    private float topP = 0.9f;
    private int topK = 40;
    private String systemPrompt = "请用中文回答。";
    private boolean cacheEnabled = true;
    private boolean autoModeEnabled = false; // 禁用自动模式切换，所有模式由用户手动选择
    private boolean intentRecognitionEnabled = true;
    private boolean agentEnabled = true; // 启用 Agent 模式
    private OptimizationMode optimizationMode = OptimizationMode.BALANCED;

    public AIConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPreferences();
    }

    private void loadFromPreferences() {
        try {
            maxTokens = prefs.getInt("max_tokens", 4096);
        } catch (ClassCastException e) {
            prefs.edit().remove("max_tokens").putInt("max_tokens", 4096).apply();
            maxTokens = 4096;
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

    public OptimizationMode getOptimizationMode() { return optimizationMode; }
    public void setOptimizationMode(OptimizationMode mode) { this.optimizationMode = mode; saveToPreferences(); }

    public int getContextSize() { return optimizationMode.contextSize; }
}
