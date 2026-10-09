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
    /** 思考强度档位（与 DeepSeek 官方 reasoning_effort 取值一致：none/low/high/max） */
    private static final String PREF_THINKING_EFFORT = "chat_thinking_effort";

    /**
     * 思考强度档位 —— 取代原来"只有开/关"的布尔。
     *
     * <p>取值与 DeepSeek 官方 {@code reasoning_effort} 的 <b>Possible values</b> 完全一致
     * （{@code none/low/high/max}），因此可直接下发给该参数，无需再做映射。
     * 其余服务商按各自思考参数形态消费该档位（如 Gemini 的 {@code thinking_level}、
     * OpenAI o 系的 {@code reasoning_effort}）。</p>
     *
     * <p>官方原文（api-docs.deepseek.com/zh-cn/api/create-chat-completion）：
     * "控制思考模式开关与思考强度。{@code none} 关闭思考模式；{@code low}/{@code high}/{@code max}
     * 开启思考模式。默认强度为 {@code high}。"</p>
     */
    public enum ThinkingEffort {
        NONE("关闭", "none"),
        LOW("低", "low"),
        HIGH("高", "high"),
        MAX("最高", "max");

        /** 中文显示名（UI 用） */
        public final String displayName;
        /** 下发给 API 的取值（与官方取值一致） */
        public final String wireValue;

        ThinkingEffort(String displayName, String wireValue) {
            this.displayName = displayName;
            this.wireValue = wireValue;
        }

        public boolean isEnabled() {
            return this != NONE;
        }

        /** 解析持久化值；非法/为空回落到 HIGH（官方默认强度） */
        public static ThinkingEffort fromWire(String wire) {
            if (wire != null) {
                for (ThinkingEffort e : values()) {
                    if (e.wireValue.equalsIgnoreCase(wire.trim())) return e;
                }
            }
            return HIGH;
        }
    }

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
    /**
     * 当前思考强度档位 —— **唯一事实源**。
     * "深度思考是否开启"由它派生（{@code effort.isEnabled()}），不再单独维护布尔字段，
     * 避免两处状态不一致。
     */
    private volatile ThinkingEffort thinkingEffort = ThinkingEffort.HIGH;

    private ChatModeManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        loadPreferences();
    }

    private void loadPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean legacyEnabled = prefs.getBoolean(PREF_DEEP_THINKING, false);
        // 强度优先；未写入过强度时按旧布尔偏好回落（关→NONE，开→官方默认 high）
        String storedEffort = prefs.getString(PREF_THINKING_EFFORT, null);
        if (storedEffort == null || storedEffort.isEmpty()) {
            thinkingEffort = legacyEnabled ? ThinkingEffort.HIGH : ThinkingEffort.NONE;
        } else {
            thinkingEffort = ThinkingEffort.fromWire(storedEffort);
            if (!legacyEnabled) thinkingEffort = ThinkingEffort.NONE;
        }
    }

    private void saveThinkingEffort(ThinkingEffort effort) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(PREF_THINKING_EFFORT, effort.wireValue)
                .putBoolean(PREF_DEEP_THINKING, effort.isEnabled())
                .apply();
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

    // ==================== 思考强度（主 API，取代"只有开关"） ====================

    /** 当前思考强度档位（{@link ThinkingEffort#NONE} 表示关闭；永不为 null） */
    public ThinkingEffort getThinkingEffort() {
        return currentEffort();
    }

    /**
     * 设置思考强度档位（持久化）。返回与设置前相比是否发生变化。
     *
     * <p>这是"深度思考"的**唯一事实源**：{@code NONE} 即关闭，其余即开启并指定强度。
     * 旧的布尔开关 API 由它派生，保证两套调用方看到同一状态。</p>
     */
    public boolean setThinkingEffort(ThinkingEffort effort) {
        if (effort == null) effort = ThinkingEffort.HIGH;
        if (effort == thinkingEffort) return false;
        ThinkingEffort previous = thinkingEffort;
        thinkingEffort = effort;
        saveThinkingEffort(effort);
        AppLogger.aiD(TAG, "Thinking effort " + previous.wireValue + " -> " + effort.wireValue);
        return true;
    }

    // ==================== 深度思考开关（派生自强度档位） ====================

    /** 档位读取（永不为 null）：异常/未初始化时回落官方默认 HIGH */
    private ThinkingEffort currentEffort() {
        ThinkingEffort e = thinkingEffort;
        return e != null ? e : ThinkingEffort.HIGH;
    }

    /** 深度思考是否开启（等价于强度档位 != NONE；档位为空按未开启处理，避免 NPE） */
    public boolean isDeepThinkingEnabled() {
        ThinkingEffort e = thinkingEffort;
        return e != null && e.isEnabled();
    }

    /**
     * 开关深度思考（持久化）。返回状态是否发生变化。
     *
     * <p>关闭 → 档位置为 {@link ThinkingEffort#NONE}；开启 → 恢复到上次的强度档位
     * （旧偏好只记录布尔时，恢复到官方默认 {@code high}）。</p>
     */
    public boolean setDeepThinkingEnabled(boolean enabled) {
        boolean current = isDeepThinkingEnabled();
        if (enabled == current) return false;
        if (!enabled) {
            setThinkingEffort(ThinkingEffort.NONE);
        } else {
            // 从 NONE 恢复：沿用当前档位（若已是 NONE/未初始化则取 HIGH），
            // 历史只有布尔偏好时即为 HIGH（官方默认强度）
            ThinkingEffort cur = thinkingEffort;
            ThinkingEffort target = (cur == null || cur == ThinkingEffort.NONE)
                    ? ThinkingEffort.HIGH : cur;
            setThinkingEffort(target);
        }
        AppLogger.aiD(TAG, "Deep thinking " + (enabled ? "ENABLED" : "DISABLED"));
        return true;
    }

    // ==================== 兼容旧 API（模式 → 开关映射） ====================

    /** 兼容：当前模式（NORMAL=关，DEEP_THINKING=开） */
    public ChatMode getCurrentMode() {
        return isDeepThinkingEnabled() ? ChatMode.DEEP_THINKING : ChatMode.NORMAL;
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
        sb.append("[mode-switch]\n");
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
