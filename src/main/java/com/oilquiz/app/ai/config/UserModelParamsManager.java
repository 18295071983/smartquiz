package com.oilquiz.app.ai.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户自定义模型参数管理器（参数面板持久化）。
 * <p>
 * 覆盖自动配置（InferenceConfigManager / ResourceConfig）：
 * <ul>
 *   <li>contextSize：上下文长度，-1 = 自动</li>
 *   <li>temperature：温度，0 = 用模型默认（0.7）
 *       <b>2026-10-11 拆成两段</b>：decision（模型决策轮，产出 tool_call，要求结构化稳定）
 *       与 answer（最终回答/普通对话，自然语言，可放宽），见 getDecisionTemperature /
 *       getAnswerTemperature。单值 setTemperature 仍兼容，同时写入两段</li>
 *   <li>gpuLayers：GPU 卸载层数，-1 = 自动（同步写 model_state_cache.gpu_layers_manual，
 *       复用 AIService 现成的手动覆盖机制）</li>
 * </ul>
 */
public class UserModelParamsManager {

    private static final String PREFS = "user_model_params";
    private static final String KEY_CTX = "context_size_manual";
    private static final String KEY_TEMP = "temperature";
    /** 模型决策轮温度（产出 tool_call；过高会让结构化 JSON 出错） */
    private static final String KEY_TEMP_DECISION = "temperature_decision";
    /** 最终回答/普通对话温度（纯自然语言；可略高于决策轮） */
    private static final String KEY_TEMP_ANSWER = "temperature_answer";
    private static final String KEY_GPU = "gpu_layers_manual";
    private static final String KEY_AUTO = "use_auto";

    /** 未配置（自动模式）时的决策轮温度：与拆分前一致（原 AgentLoopEngine 硬编码 0.7），保证行为不回归 */
    public static final float DEFAULT_DECISION_TEMPERATURE = 0.7f;
    /** 未配置（自动模式）时的回答轮温度：同样 0.7，保证拆分本身不改变既有行为 */
    public static final float DEFAULT_ANSWER_TEMPERATURE = 0.7f;

    /** 温度钳制下限：低于此值小模型工具调用过度保守（实测 0.6 即"只敢用默认 action/参数"） */
    public static final float MIN_TEMPERATURE = 0.1f;
    /** 温度钳制上限：再高 chatJson 结构化输出开始崩（AgentLoopEngine R8-2） */
    public static final float MAX_TEMPERATURE = 1.5f;

    private final Context context;
    private final SharedPreferences prefs;
    private static volatile UserModelParamsManager instance;

    /** 本次进程内显式覆盖（内存态，不落盘）：setRunTemperature 用，优先级最高 */
    private volatile float runDecisionTemp = 0f;
    private volatile float runAnswerTemp = 0f;

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
            // 恢复自动：清除手动覆盖（含两段温度）
            clearContextOverride();
            clearGpuOverride();
            clearTemperatureOverride();
        }
    }

    /** 清除两段温度覆盖（决策轮 + 回答轮 + 兼容单值键） */
    public void clearTemperatureOverride() {
        prefs.edit().remove(KEY_TEMP).apply();
        prefs.edit().remove(KEY_TEMP_DECISION).apply();
        prefs.edit().remove(KEY_TEMP_ANSWER).apply();
        clearRunTemperature();
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

    /**
     * 决策轮温度（缓存后立即生效：温度不进 KV 前缀，无需重载模型）。
     * 优先级：per-run 覆盖 > 持久化值（自动模式则忽略）> 默认值。
     */
    public float getDecisionTemperature() {
        float perRun = runDecisionTemp;
        if (perRun > 0f) return clampTemperature(perRun);
        return resolveTemperature(KEY_TEMP_DECISION, DEFAULT_DECISION_TEMPERATURE);
    }

    /** 回答轮温度。注意：自动模式下恒定返回默认值（0.7），回答轮无"模型自选"概念。 */
    public float getAnswerTemperature() {
        float perRun = runAnswerTemp;
        if (perRun > 0f) return clampTemperature(perRun);
        return resolveTemperature(KEY_TEMP_ANSWER, DEFAULT_ANSWER_TEMPERATURE);
    }

    private float resolveTemperature(String key, float fallback) {
        try {
            if (prefs.contains(key) && !isAuto()) {
                float v = prefs.getFloat(key, fallback);
                if (v > 0f) return clampTemperature(v);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 本次运行的温度覆盖（内存态，不落盘、不改自动/手动标志）；传 0 或负数表示不覆盖 */
    public void setRunTemperature(float decision, float answer) {
        runDecisionTemp = decision > 0f ? clampTemperature(decision) : 0f;
        runAnswerTemp = answer > 0f ? clampTemperature(answer) : 0f;
    }

    /** 清除本次运行的温度覆盖，回到持久化值 */
    public void clearRunTemperature() {
        runDecisionTemp = 0f;
        runAnswerTemp = 0f;
    }

    /** 温度钳制到 [0.1, 1.5]；防止 UI 传入 0/负数/过高值破坏结构化输出 */
    public static float clampTemperature(float value) {
        if (Float.isNaN(value)) return DEFAULT_DECISION_TEMPERATURE;
        return Math.max(MIN_TEMPERATURE, Math.min(MAX_TEMPERATURE, value));
    }

    /**
     * 纯静态便捷入口：设置本次运行的温度覆盖（内存态，不落盘、不改自动标志）。
     * 供调试/参数面板在拿到 Context 后一行调用；进程重启自动失效。
     * 例：{@code UserModelParamsManager.applyRunTemperature(ctx, 0.7f, 0.9f)}
     */
    public static void applyRunTemperature(Context ctx, float decision, float answer) {
        try {
            getInstance(ctx).setRunTemperature(decision, answer);
        } catch (Exception ignored) {
        }
    }

    /**
     * 当前生效的温度与来源（决策轮/回答轮），供设置页展示与排查。
     * 来源取值：run-override（本次运行覆盖）/ manual（持久化手动值）/ auto（自动模式默认）
     */
    public String describeEffectiveTemperature() {
        return "decision=" + getDecisionTemperature() + "(" + temperatureSource(KEY_TEMP_DECISION,
                runDecisionTemp) + ")"
                + ", answer=" + getAnswerTemperature() + "(" + temperatureSource(KEY_TEMP_ANSWER,
                runAnswerTemp) + ")";
    }

    private String temperatureSource(String key, float perRun) {
        if (perRun > 0f) return "run-override";
        if (prefs.contains(key) && !isAuto()) return "manual";
        return "auto";
    }

    /** 温度（单值 API，兼容旧调用方）；0 表示用模型默认（0.7） */
    public float getTemperature() {
        return prefs.getFloat(KEY_TEMP, 0f);
    }

    /**
     * 设置温度（单值，兼容旧调用方）：同时写入决策轮与回答轮，保持两段一致。
     * 需要分别调时用 setDecisionTemperature / setAnswerTemperature。
     */
    public void setTemperature(float value) {
        setDecisionTemperature(value);
        setAnswerTemperature(value);
    }

    /** 设置决策轮温度（持久化；传 0 或负数 = 清除该覆盖） */
    public void setDecisionTemperature(float value) {
        prefs.edit().putBoolean(KEY_AUTO, false).apply();
        if (value > 0f) {
            float clamped = clampTemperature(value);
            // 兼容单值键（旧调用方读 getTemperature()），与两段专用键写入同一个钳制后的值
            prefs.edit().putFloat(KEY_TEMP, clamped).apply();
            prefs.edit().putFloat(KEY_TEMP_DECISION, clamped).apply();
        } else {
            prefs.edit().remove(KEY_TEMP).apply();
            prefs.edit().remove(KEY_TEMP_DECISION).apply();
        }
    }

    /** 设置回答轮温度（持久化；传 0 或负数 = 清除该覆盖） */
    public void setAnswerTemperature(float value) {
        prefs.edit().putBoolean(KEY_AUTO, false).apply();
        if (value > 0f) {
            prefs.edit().putFloat(KEY_TEMP_ANSWER, clampTemperature(value)).apply();
        } else {
            prefs.edit().remove(KEY_TEMP_ANSWER).apply();
        }
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

    /** 是否已设置任何手动参数（含两段温度中的任意一段） */
    public boolean hasManualParams() {
        return getContextSize() > 0 || getTemperature() > 0 || getGpuLayers() >= 0
                || prefs.contains(KEY_TEMP_DECISION) || prefs.contains(KEY_TEMP_ANSWER);
    }

    /** 一键恢复自动 */
    public void resetAll() {
        prefs.edit().clear().apply();
        syncGpuToModelStateCache(-1);
        clearRunTemperature();
    }
}
