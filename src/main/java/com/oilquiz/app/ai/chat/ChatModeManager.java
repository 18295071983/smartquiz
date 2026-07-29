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
        AGENT("Agent", "agent", "🤖"),
        THINKING_ASSIST("思考辅助", "thinking_assist", "💡");

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
        return getModeSystemPromptStatic(mode);
    }
    
    /**
     * 统一基础提示词 - 适用于所有模式
     */
    public static String getBaseSystemPrompt() {
        return "你是一个智能AI助手，擅长理解用户需求并提供准确、有帮助的回答。\n" +
               "请用自然易懂的中文与用户对话，回答准确简洁，保持礼貌耐心。";
    }

    /**
     * 静态方法 - 获取模式系统提示词（无需 context）
     * 用于 UnifiedAgentEngine 等不需要实例的场景
     */
    public static String getModeSystemPromptStatic(ChatMode mode) {
        // 所有模式都使用统一基础提示词
        return getBaseSystemPrompt();
    }

    /**
     * 获取模式特定指令（用于注入到上下文）
     */
    public static String getModeSpecificInstruction(ChatMode mode) {
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
            case CREATIVE:
                return "你现在进入创意写作模式。请根据用户需求，创作各类文章、故事、诗歌等文学作品。\n" +
                       "请确保：\n1. 内容原创，有创意\n2. 语言生动，富有感染力\n3. 结构清晰，逻辑通顺";
            case AGENT:
                return "你现在进入Agent模式。你可以调用各种工具来完成用户的任务。\n" +
                       "请根据用户需求：\n1. 分析任务并分解步骤\n2. 选择合适的工具执行\n3. 整合结果并给出反馈\n" +
                       "当需要使用工具时，严格按照 TOOLS_CALL/TOOLS_END 格式输出。\n" +
                       "可用工具包括：文件操作、网络搜索、数据库查询、位置服务、天气查询、翻译等。";
            case THINKING_ASSIST:
                return "你现在进入思考辅助模式。你的角色不是直接回答问题，而是作为思考引导者：\n" +
                       "1. 通过提问引导用户深入思考，帮助用户自己找到答案\n" +
                       "2. 适时提供新的思考视角，帮助用户打破思维定式\n" +
                       "3. 在适当的时候引导用户反思，审视自己的假设和盲点\n" +
                       "4. 当用户思考到位后，帮助总结思考成果\n" +
                       "重要原则：\n" +
                       "- 不要直接给出答案，而是引导用户思考\n" +
                       "- 一次只关注一个方面，避免信息过载\n" +
                       "- 保持耐心，跟随用户的思考节奏\n" +
                       "- 用提问代替陈述，用引导代替告知";
            case NORMAL:
            default:
                return "";
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

    /**
     * 生成模式切换指令，用于注入到上下文中
     * 保留上下文的同时改变模型行为
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
