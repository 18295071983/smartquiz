package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.oilquiz.app.infra.AppLogger;

/**
 * 聊天模式管理器（精简版）。
 *
 * 模式：普通 / 深度思考（仅两个，用户手动切换，无自动识别）。
 * 职责：持久化当前模式、切换模式、生成模式切换指令（注入 system 提示词）。
 */
public class ChatModeManager {

    private static final String TAG = "ChatModeManager";
    private static final String PREF_CURRENT_MODE = "chat_current_mode";

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
    private volatile ChatMode currentMode = ChatMode.NORMAL;

    private ChatModeManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        loadPreferences();
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String modeId = prefs.getString(PREF_CURRENT_MODE, ChatMode.NORMAL.modeId);
        currentMode = ChatMode.fromModeId(modeId);
    }

    private void saveCurrentMode() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(PREF_CURRENT_MODE, currentMode.modeId).apply();
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

    public ChatMode getCurrentMode() {
        return currentMode;
    }

    /** 手动切换模式（持久化） */
    public void setManualMode(ChatMode mode) {
        if (mode == null || mode == currentMode) return;
        ChatMode oldMode = currentMode;
        currentMode = mode;
        saveCurrentMode();
        AppLogger.aiD(TAG, "Mode switched: " + oldMode.displayName + " -> " + mode.displayName);
    }

    /**
     * 生成模式切换指令，用于注入到 system 提示词。
     * 仅深度思考有思考指令；普通模式无指令（返回空串，调用方跳过注入）。
     */
    public static String getModeSwitchInstruction(ChatMode oldMode, ChatMode newMode) {
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
