package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.oilquiz.app.infra.AppLogger;

/**
 * 深度思考开关管理器。
 *
 * 深度思考从"二选一模式"改造为**独立开关**：
 * - 普通对话是基础，深度思考可独立开启/关闭（持久化）
 * - 开启后：注入思考指令到 system 提示词 + API 请求传 thinking 参数（reasoning_content）
 * - 关闭后：普通对话，不注入思考指令、不传 thinking 参数
 *
 * 保留 {@link ChatMode} 枚举仅为兼容旧调用（普通/深度思考映射到开关状态）。
 */
public class ChatModeManager {

    private static final String TAG = "ChatModeManager";
    private static final String PREF_DEEP_THINKING = "chat_deep_thinking_enabled";

    /** 兼容旧 API 的枚举（NORMAL=开关关，DEEP_THINKING=开关开） */
    public enum ChatMode {
        NORMAL("普通", "normal", "💬"),
        DEEP_THINKING("深度思考", "deep_thinking", "🧠");

        public final String displayName;
        public final String modeId;
        public final String icon;

        ChatMode(String displayName, String modeId, String icon) {
            this.displayName = displayName;
            this.modeId = modeId;
            this.icon = icon;
        }

        public static ChatMode fromModeId(String modeId) {
            if (modeId == null) return NORMAL;
            for (ChatMode mode : values()) {
                if (mode.modeId.equals(modeId)) {
                    return mode;
                }
            }
            return NORMAL;
        }
    }

    private static ChatModeManager instance;
    private final Context context;
    private volatile boolean deepThinkingEnabled = false;

    private ChatModeManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        loadPreferences();
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        deepThinkingEnabled = prefs.getBoolean(PREF_DEEP_THINKING, false);
    }

    private void saveDeepThinking(boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREF_DEEP_THINKING, enabled).apply();
    }

    public static ChatModeManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ChatModeManager.class) {
                if (instance == null) {
                    instance = new ChatModeManager(context);
                }
            }
        }
        return instance;
    }

    // ==================== 深度思考开关（新主 API） ====================

    /** 深度思考是否开启 */
    public boolean isDeepThinkingEnabled() {
        return deepThinkingEnabled;
    }

    /** 开关深度思考（持久化）。返回切换前后的状态变化。 */
    public boolean setDeepThinkingEnabled(boolean enabled) {
        if (enabled == deepThinkingEnabled) return false;
        deepThinkingEnabled = enabled;
        saveDeepThinking(enabled);
        AppLogger.aiD(TAG, "Deep thinking " + (enabled ? "ENABLED" : "DISABLED"));
        return true;
    }

    // ==================== 兼容旧 API（模式 → 开关映射） ====================

    /** 兼容：当前模式（NORMAL=关，DEEP_THINKING=开） */
    public ChatMode getCurrentMode() {
        return deepThinkingEnabled ? ChatMode.DEEP_THINKING : ChatMode.NORMAL;
    }

    /** 兼容：手动切换模式（映射到开关状态） */
    public void setManualMode(ChatMode mode) {
        setDeepThinkingEnabled(mode == ChatMode.DEEP_THINKING);
    }

    /**
     * 生成模式切换指令，用于注入到 system 提示词。
     * 仅深度思考开启时有思考指令；关闭无指令（返回空串，调用方跳过注入）。
     */
    public static String getModeSwitchInstruction(ChatMode oldMode, ChatMode newMode) {
        if (oldMode == newMode) return "";
        String instruction = getModeSpecificInstruction(newMode);
        if (instruction == null || instruction.isEmpty()) {
            return ""; // 普通模式不需要特殊指令
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[系统指令 - 模式切换]\n\n");
        sb.append("对话模式已从「").append(oldMode.displayName).append("」切换到「").append(newMode.displayName).append("」。\n\n");
        sb.append(instruction);
        sb.append("\n\n请确认已理解，继续与用户对话。");
        return sb.toString();
    }

    /** 模式特定指令：仅深度思考有；普通模式无 */
    private static String getModeSpecificInstruction(ChatMode mode) {
        switch (mode) {
            case DEEP_THINKING:
                return "你现在进入深度思考模式。对于复杂问题，请先进行系统性的分析推理，再给出最终答案。\n" +
                       "思考阶段要求：\n" +
                       "1. 拆解问题，明确核心要点\n" +
                       "2. 从多个角度分析，考虑各种可能性\n" +
                       "3. 逐步推理，验证逻辑链条\n" +
                       "最终回答要求：\n" +
                       "1. 结论先行，简洁明确\n" +
                       "2. 只保留关键论据和核心逻辑";
            case NORMAL:
            default:
                return "";
        }
    }
}
