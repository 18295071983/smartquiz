package com.oilquiz.app.ai.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户自定义模型参数管理器（参数面板持久化）。
 * <p>
 * 覆盖自动配置（InferenceConfigManager / ResourceConfig）：
 * <ul>
 *   <li>contextSize：上下文长度，-1 = 自动</li>
 *   <li>temperature：温度，0 = 用模型默认（0.7）</li>
 *   <li>gpuLayers：GPU 卸载层数，-1 = 自动（同步写 model_state_cache.gpu_layers_manual，
 *       复用 AIService 现成的手动覆盖机制）</li>
 * </ul>
 */
public class UserModelParamsManager {

    private static final String PREFS = "user_model_params";
    private static final String KEY_CTX = "context_size_manual";
    private static final String KEY_TEMP = "temperature";
    private static final String KEY_GPU = "gpu_layers_manual";
    private static final String KEY_AUTO = "use_auto";

    private final Context context;
    private final SharedPreferences prefs;
    private static volatile UserModelParamsManager instance;

    private UserModelParamsManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized UserModelParamsManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new UserModelParamsManager(ctx);
        }
        return instance;
    }

    /** 用户是否启用了"自动参数"（默认 true；关闭后使用手动值） */
    public boolean isAuto() {
        return prefs.getBoolean(KEY_AUTO, true);
    }

    public void setAuto(boolean auto) {
        prefs.edit().putBoolean(KEY_AUTO, auto).apply();
        if (auto) {
            // 恢复自动：清除手动覆盖
            clearContextOverride();
            clearGpuOverride();
        }
    }

    /** 上下文长度；-1 或 <=0 表示自动 */
    public int getContextSize() {
        return prefs.getInt(KEY_CTX, -1);
    }

    public void setContextSize(int value) {
        prefs.edit().putInt(KEY_CTX, value).apply();
        prefs.edit().putBoolean(KEY_AUTO, false).apply();
    }

    public void clearContextOverride() {
        prefs.edit().remove(KEY_CTX).apply();
    }

    /** 温度；0 表示用模型默认（0.7） */
    public float getTemperature() {
        return prefs.getFloat(KEY_TEMP, 0f);
    }

    public void setTemperature(float value) {
        prefs.edit().putFloat(KEY_TEMP, value).apply();
        prefs.edit().putBoolean(KEY_AUTO, false).apply();
    }

    /** GPU 卸载层数；-1 或 <0 表示自动 */
    public int getGpuLayers() {
        return prefs.getInt(KEY_GPU, -1);
    }

    public void setGpuLayers(int value) {
        prefs.edit().putInt(KEY_GPU, value).apply();
        prefs.edit().putBoolean(KEY_AUTO, false).apply();
        // 同步到 AIService 现成的手动覆盖 key（model_state_cache.gpu_layers_manual）
        syncGpuToModelStateCache(value);
    }

    public void clearGpuOverride() {
        prefs.edit().remove(KEY_GPU).apply();
        syncGpuToModelStateCache(-1);
    }

    private void syncGpuToModelStateCache(int value) {
        try {
            SharedPreferences ms = context.getSharedPreferences("model_state_cache", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = ms.edit();
            if (value >= 0 && value <= 64) {
                e.putInt("gpu_layers_manual", value);
            } else {
                e.remove("gpu_layers_manual");
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    /** 是否已设置任何手动参数 */
    public boolean hasManualParams() {
        return getContextSize() > 0 || getTemperature() > 0 || getGpuLayers() >= 0;
    }

    /** 一键恢复自动 */
    public void resetAll() {
        prefs.edit().clear().apply();
        syncGpuToModelStateCache(-1);
    }
}
