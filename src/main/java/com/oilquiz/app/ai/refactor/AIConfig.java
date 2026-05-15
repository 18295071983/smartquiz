package com.oilquiz.app.ai.refactor;

import android.content.Context;
import android.content.SharedPreferences;

public class AIConfig {
    private static final String PREFS_NAME = "ai_config";
    private final SharedPreferences prefs;

    private int maxTokens = 512;
    private float temperature = 0.7f;
    private float topP = 0.9f;
    private int topK = 40;
    private String systemPrompt = "请用中文回答。";
    private boolean cacheEnabled = true;
    private boolean autoModeEnabled = true;
    private boolean intentRecognitionEnabled = true;

    public AIConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromPreferences();
    }

    private void loadFromPreferences() {
        maxTokens = prefs.getInt("max_tokens", 512);
        temperature = prefs.getFloat("temperature", 0.7f);
        topP = prefs.getFloat("top_p", 0.9f);
        topK = prefs.getInt("top_k", 40);
        systemPrompt = prefs.getString("system_prompt", "请用中文回答。");
        cacheEnabled = prefs.getBoolean("cache_enabled", true);
        autoModeEnabled = prefs.getBoolean("auto_mode_enabled", true);
        intentRecognitionEnabled = prefs.getBoolean("intent_recognition_enabled", true);
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
}
