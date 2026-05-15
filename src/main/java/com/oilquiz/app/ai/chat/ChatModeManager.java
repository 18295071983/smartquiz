package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.infra.AppLogger;

import java.util.regex.Pattern;

public class ChatModeManager {

    private static final String TAG = "ChatModeManager";
    private static final String PREFS_NAME = "chat_mode_prefs";
    private static final String KEY_CURRENT_MODE = "current_mode";
    private static final String KEY_AUTO_MODE_ENABLED = "auto_mode_enabled";

    public enum ChatMode {
        NORMAL("普通", "normal"),
        DEEP_THINKING("深度思考", "deep"),
        AGENT("Agent", "agent"),
        CREATIVE("创作", "creative");

        public final String displayName;
        public final String modeId;

        ChatMode(String displayName, String modeId) {
            this.displayName = displayName;
            this.modeId = modeId;
        }

        public static ChatMode fromModeId(String modeId) {
            if (modeId == null) return NORMAL;
            for (ChatMode mode : values()) {
                if (mode.modeId.equals(modeId)) return mode;
            }
            return NORMAL;
        }
    }

    public interface OnModeChangeListener {
        void onModeChanged(ChatMode newMode, boolean isAuto);
        void onModeSwitchRequested(ChatMode requestedMode, boolean duringGeneration);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private ChatMode currentMode;
    private ChatMode pendingMode = null;
    private boolean autoModeEnabled;
    private boolean isGenerating = false;
    private OnModeChangeListener modeChangeListener;

    public ChatModeManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.autoModeEnabled = prefs.getBoolean(KEY_AUTO_MODE_ENABLED, true);
        String savedMode = prefs.getString(KEY_CURRENT_MODE, ChatMode.NORMAL.name());
        try {
            this.currentMode = ChatMode.valueOf(savedMode);
        } catch (IllegalArgumentException e) {
            this.currentMode = ChatMode.NORMAL;
        }
    }

    public void setOnModeChangeListener(OnModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    public ChatMode getCurrentMode() {
        return currentMode;
    }

    public boolean isAutoModeEnabled() {
        return autoModeEnabled;
    }

    public void setAutoModeEnabled(boolean enabled) {
        this.autoModeEnabled = enabled;
        prefs.edit().putBoolean(KEY_AUTO_MODE_ENABLED, enabled).apply();
        AppLogger.ai(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(currentMode, enabled);
        }
    }

    public void setManualMode(ChatMode mode) {
        if (mode == null) return;

        if (isGenerating) {
            pendingMode = mode;
            AppLogger.ai(TAG, "Mode switch requested during generation: " + mode.displayName);
            if (modeChangeListener != null) {
                modeChangeListener.onModeSwitchRequested(mode, true);
            }
            return;
        }

        applyModeChange(mode, false);
    }

    public void setGeneratingState(boolean generating) {
        this.isGenerating = generating;
        if (!generating && pendingMode != null) {
            AppLogger.ai(TAG, "Applying pending mode change: " + pendingMode.displayName);
            applyModeChange(pendingMode, false);
            pendingMode = null;
        }
    }

    private void applyModeChange(ChatMode mode, boolean isAuto) {
        this.autoModeEnabled = isAuto;
        this.currentMode = mode;
        prefs.edit()
            .putBoolean(KEY_AUTO_MODE_ENABLED, isAuto)
            .putString(KEY_CURRENT_MODE, mode.name())
            .apply();
        AppLogger.ai(TAG, "Mode changed to: " + mode.displayName + " (auto=" + isAuto + ")");
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(mode, isAuto);
        }
    }

    public ChatMode determineMode(String userMessage) {
        if (!autoModeEnabled) {
            return currentMode;
        }

        ChatMode determinedMode = analyzeMessageComplexity(userMessage);
        if (determinedMode != currentMode) {
            AppLogger.ai(TAG, "Auto-switched mode: " + currentMode.displayName + " -> " + determinedMode.displayName);
            this.currentMode = determinedMode;
            prefs.edit().putString(KEY_CURRENT_MODE, determinedMode.name()).apply();
            if (modeChangeListener != null) {
                modeChangeListener.onModeChanged(determinedMode, true);
            }
        }
        return determinedMode;
    }

    private ChatMode analyzeMessageComplexity(String message) {
        if (message == null || message.isEmpty()) {
            return ChatMode.NORMAL;
        }

        if (isCreativeTriggerPattern(message)) {
            return ChatMode.CREATIVE;
        }

        if (isAgentTriggerPattern(message)) {
            return ChatMode.AGENT;
        }

        if (isDeepThinkingTriggerPattern(message)) {
            return ChatMode.DEEP_THINKING;
        }

        if (requiresDeepThinking(message)) {
            return ChatMode.DEEP_THINKING;
        }

        return ChatMode.NORMAL;
    }

    private boolean isCreativeTriggerPattern(String message) {
        String[] creativeTriggers = {
            "^写.*诗", "^创作.*", "^编.*故事", "^写.*小说",
            ".*诗歌.*", ".*散文.*", ".*小说.*", ".*剧本.*",
            ".*歌词.*", ".*广告语.*", ".*口号.*"
        };
        for (String pattern : creativeTriggers) {
            if (Pattern.matches(pattern, message)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAgentTriggerPattern(String message) {
        String[] agentTriggers = {
            "^帮我.*(查询|搜索|查找|分析|计算|翻译|生成|执行|完成|调用)",
            "^执行.*任务", "^完成.*任务",
            "^查询.*", "^搜索.*", "^调用.*工具",
            ".*同时.*查询.*", ".*先.*然后.*再.*",
            ".*步骤.*执行.*", ".*分析.*数据.*并.*",
            ".*读取.*文件.*分析.*", ".*代码.*生成.*并.*"
        };
        for (String pattern : agentTriggers) {
            try {
                if (Pattern.matches(pattern, message)) {
                    return true;
                }
            } catch (Exception e) {
                AppLogger.ai(TAG, "Invalid agent trigger pattern: " + pattern);
            }
        }
        return false;
    }

    private boolean isDeepThinkingTriggerPattern(String message) {
        String[] deepThinkingTriggers = {
            "^为什么.*", "^如何.*", "^解释.*", "^分析.*",
            "^比较.*", "^评价.*", "^推理.*", "^证明.*",
            ".*原因.*", ".*逻辑.*", ".*原理.*"
        };
        for (String pattern : deepThinkingTriggers) {
            if (Pattern.matches(pattern, message)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresDeepThinking(String message) {
        String lower = message.toLowerCase();
        return message.length() > 100 ||
               lower.contains("为什么") ||
               lower.contains("如何") ||
               lower.contains("解释") ||
               lower.contains("分析") ||
               lower.contains("比较") ||
               lower.contains("原理") ||
               lower.contains("逻辑");
    }
}
