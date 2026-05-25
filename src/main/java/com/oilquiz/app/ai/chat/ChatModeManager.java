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
        CREATIVE("创意写作", "creative", "✍️");

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
    private volatile boolean autoModeEnabled = true;
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
        autoModeEnabled = prefs.getBoolean(PREF_AUTO_MODE_ENABLED, true);
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

    public void setAutoModeEnabled(boolean enabled) {
        this.autoModeEnabled = enabled;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREF_AUTO_MODE_ENABLED, enabled).apply();
        if (modeChangeListener != null) {
            modeChangeListener.onAutoModeChanged(enabled);
        }
        AppLogger.aiD(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));
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
            switchToMode(mode, false);
        }
    }

    public void setGeneratingState(boolean generating) {
        this.isGenerating = generating;
        if (!generating && pendingMode != null) {
            switchToMode(pendingMode, false);
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

    private void switchToMode(ChatMode mode, boolean isAuto) {
        if (mode == currentMode) return;
        
        ChatMode oldMode = currentMode;
        currentMode = mode;
        saveCurrentMode();
        
        if (modeChangeListener != null) {
            modeChangeListener.onModeChanged(mode, isAuto);
        }
        AppLogger.aiD(TAG, "Mode switched: " + oldMode.displayName + " -> " + mode.displayName + (isAuto ? " (auto)" : " (manual)"));
    }

    /**
     * AI 智能识别模式
     * 根据消息内容自动判断适合的模式
     */
    public ChatMode determineMode(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return ChatMode.NORMAL;
        }

        String lower = userMessage.toLowerCase();
        int scoreThinking = 0;
        int scoreCreative = 0;

        // 深度思考关键词
        if (containsAny(lower, "为什么", "为何", "原因", "分析", "解释", "原理", "逻辑",
                "思考", "推理", "证明", "推导", "论证", "探讨", "研究", "深入",
                "本质", "核心", "关键", "如何实现", "怎么做到")) {
            scoreThinking += 3;
        }
        if (containsAny(lower, "比较", "对比", "区别", "差异", "优缺点", "哪个更好",
                "如何选择", "建议", "评估", "判断", "看法", "观点")) {
            scoreThinking += 2;
        }

        // 创意写作关键词
        if (containsAny(lower, "写", "创作", "编写", "撰写", "续写", "改写", "模仿",
                "帮我写", "写一篇", "写一个", "生成一")) {
            scoreCreative += 3;
        }
        if (containsAny(lower, "故事", "小说", "诗歌", "散文", "文章", "作文", "报告",
                "演讲", "致辞", "剧本", "歌词", "广告", "文案", "邮件", "书信")) {
            scoreCreative += 2;
        }
        if (containsAny(lower, "浪漫", "科幻", "奇幻", "悬疑", "恐怖", "搞笑",
                "感人", "励志", "幽默", "童话", "武侠", "爱情")) {
            scoreCreative += 2;
        }

        // 数学/科学计算类倾向于思考
        if (containsAny(lower, "计算", "数学", "公式", "方程", "求解", "证明",
                "物理", "化学", "生物", "推理")) {
            scoreThinking += 2;
        }

        // 编程倾向于思考
        if (containsAny(lower, "代码", "程序", "函数", "算法", "bug", "调试",
                "优化", "重构", "架构", "设计模式")) {
            scoreThinking += 2;
        }

        // 返回得分最高的模式
        if (scoreThinking > scoreCreative && scoreThinking >= 2) {
            return ChatMode.DEEP_THINKING;
        } else if (scoreCreative > scoreThinking && scoreCreative >= 2) {
            return ChatMode.CREATIVE;
        }
        return ChatMode.NORMAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动模式判断（仅在自动模式开启时调用）
     */
    public boolean shouldAutoSwitch(String userMessage) {
        if (!autoModeEnabled) {
            return false;
        }
        ChatMode suggested = determineMode(userMessage);
        return suggested != currentMode;
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
