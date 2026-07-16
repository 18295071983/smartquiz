package com.oilquiz.app.ai.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.oilquiz.app.infra.AppLogger;

public class ChatModeManager {

    private static final String TAG = "ChatModeManager";
    private static final String PREF_CURRENT_MODE = "chat_current_mode";
    private static final String PREF_AUTO_MODE_ENABLED = "chat_auto_mode_enabled";

    public enum ChatMode {
        NORMAL("普通", "normal", "💬"),
        DEEP_THINKING("深度思考", "deep_thinking", "🧠"),
        CREATIVE("创意写作", "creative", "✍️"),
        AGENT("Agent", "agent", "🤖");

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

    public interface OnModeChangeListener {
        void onModeChanged(ChatMode newMode, boolean isAuto);
        void onModeSwitchRequested(ChatMode requestedMode, boolean duringGeneration);
        void onAutoModeChanged(boolean enabled);
    }

    private static ChatModeManager instance;
    private final Context context;
    private volatile OnModeChangeListener modeChangeListener;
    private volatile ChatMode currentMode = ChatMode.NORMAL;
    private volatile boolean autoModeEnabled = false;
    private volatile boolean isGenerating = false;
    private volatile ChatMode pendingMode = null;

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

    public void setOnModeChangeListener(OnModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    public ChatMode getCurrentMode() {
        return currentMode;
    }

    public boolean isAutoModeEnabled() {
        return autoModeEnabled;
    }

    /**
     * 自动模式已禁用，所有模式由用户手动选择
     */
    public void setAutoModeEnabled(boolean enabled) {
        AppLogger.aiD(TAG, "Auto mode is disabled. All modes are manually selected.");
    }

    public void setManualMode(ChatMode mode) {
        if (mode == null || mode == currentMode) return;
        
        if (isGenerating) {
            pendingMode = mode;
            if (modeChangeListener != null) {
                modeChangeListener.onModeSwitchRequested(mode, true);
            }
            AppLogger.aiD(TAG, "Mode switch pending: " + mode.displayName);
        } else {
            switchToMode(mode);
        }
    }

    public void setGeneratingState(boolean generating) {
        this.isGenerating = generating;
        if (!generating && pendingMode != null) {
            switchToMode(pendingMode);
            pendingMode = null;
        }
    }

    public boolean hasPendingModeChange() {
        return pendingMode != null;
    }

    public ChatMode getPendingMode() {
        return pendingMode;
    }

    public void clearPendingMode() {
        pendingMode = null;
    }

    private void switchToMode(ChatMode mode) {
        if (mode == currentMode) return;
        
        ChatMode oldMode = currentMode;
        currentMode = mode;
        saveCurrentMode();
        
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(mode, false);
        }
        AppLogger.aiD(TAG, "Mode switched: " + oldMode.displayName + " -> " + mode.displayName);
    }

    /**
     * 返回当前用户选择的模式。
     * 模式完全由用户手动选择，不做自动识别。
     */
    public ChatMode determineMode(String userMessage) {
        return currentMode;
    }

    /**
     * 自动模式判断 — 已禁用，所有模式由用户手动选择
     */
    public boolean shouldAutoSwitch(String userMessage) {
        return false;
    }

    public String getModeSystemPrompt(ChatMode mode) {
        switch (mode) {
            case DEEP_THINKING:
                return "你是一个善于深度思考的AI助手。对于每个问题，你需要进行多角度分析，展示完整的推理过程。回答格式：\n" +
                       "1. 问题理解\n2. 关键分析\n3. 推理过程\n4. 最终结论\n" +
                       "请用结构化的方式展示你的思考过程。";
            case CREATIVE:
                return "你是一个创意写作助手。根据用户需求，创作各类文章、故事、诗歌等文学作品。\n" +
                       "请确保：\n1. 内容原创，有创意\n2. 语言生动，富有感染力\n3. 结构清晰，逻辑通顺";
            case AGENT:
                return "你是一个智能Agent助手。你可以调用各种工具来完成用户的任务。\n" +
                       "请根据用户需求：\n1. 分析任务并分解步骤\n2. 选择合适的工具执行\n3. 整合结果并给出反馈\n" +
                       "可用工具包括：文件操作、网络搜索、数据库查询、位置服务、天气查询、翻译等。";
            case NORMAL:
            default:
                return "你是一位友好、专业的AI助手。回答准确简洁，保持礼貌耐心，必要时提供示例。请以自然易懂的方式回应。";
        }
    }

    public static class ModeContextPrompts {
        public final String globalPrompt;
        public final String systemPrompt;
        public final String normalPrompt;

        public ModeContextPrompts(String globalPrompt, String systemPrompt, String normalPrompt) {
            this.globalPrompt = globalPrompt != null ? globalPrompt : "";
            this.systemPrompt = systemPrompt != null ? systemPrompt : "";
            this.normalPrompt = normalPrompt != null ? normalPrompt : "";
        }
    }

    public ModeContextPrompts getContextPromptsForMode(ChatMode mode) {
        String globalPrompt = "你是一个AI助手，请用中文回答。";
        String systemPrompt = getModeSystemPrompt(mode);
        String normalPrompt = "";
        return new ModeContextPrompts(globalPrompt, systemPrompt, normalPrompt);
    }

    public ModeContextPrompts getUnifiedContextPrompts() {
        String globalPrompt = "你是一个AI助手，请用中文回答。";
        String systemPrompt = "你是一位全能AI助手，具备以下能力：\n" +
            "1. 普通对话：友好、专业地回答问题\n" +
            "2. 深度思考：逐步推理，展示思考过程\n" +
            "3. 创意创作：写作、诗歌、故事等\n" +
            "4. 任务执行：分析问题、调用工具、完成任务\n\n" +
            "请根据用户需求灵活选择最合适的响应方式。";
        String normalPrompt = "根据对话上下文，以自然、友好的方式回应用户。";
        return new ModeContextPrompts(globalPrompt, systemPrompt, normalPrompt);
    }
}
