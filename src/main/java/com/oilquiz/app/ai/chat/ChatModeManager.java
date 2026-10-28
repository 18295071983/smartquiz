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
        MINIMAL("极低", "minimal"),
        LOW("低", "low"),
        MEDIUM("中", "medium"),
        HIGH("高", "high"),
        XHIGH("极高", "xhigh"),
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
            ThinkingEffort e = fromWireOrNull(wire);
            // 兼容旧调用方：认不出来时按官方默认 high，而不是静默当成"关闭"。
            // 新代码请直接用 wire（见 ChatModeManager#getThinkingEffortWire），
            // 不要经过这里 —— 枚举永远追不上各家的私有取值。
            return e != null ? e : HIGH;
        }

        /**
         * 严格解析：命中已收录档位才返回枚举，否则返回 <b>null</b>。
         *
         * <p>为什么需要它：旧版 {@link #fromWire} 对认不出的取值一律返回 HIGH，于是
         * 服务商声明的 {@code medium / xhigh / minimal} 之类会被<b>折叠成"高"</b> ——
         * 选择器里出现重复项、用户点不到中档、请求里也永远发不出这些值，
         * 表现就是"各家档位被混为一谈"。</p>
         */
        public static ThinkingEffort fromWireOrNull(String wire) {
            if (wire == null) return null;
            String w = wire.trim();
            if (w.isEmpty()) return null;
            for (ThinkingEffort e : values()) {
                if (e.wireValue.equalsIgnoreCase(w)) return e;
            }
            return null;
        }

        /**
         * 档位显示名：已收录的用中文名，**未收录的原样显示 wire**。
         * 这样某家私有取值（如 {@code auto}）出现在声明列表里时，界面照样可选、可辨识，
         * 不会因为枚举没跟上就消失或被改名。
         */
        public static String displayNameForWire(String wire) {
            if (wire == null || wire.trim().isEmpty()) return HIGH.displayName;
            String w = wire.trim();
            ThinkingEffort e = fromWireOrNull(w);
            return e != null ? e.displayName : w;
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

    }

    private static ChatModeManager instance;
    private final Context context;
    /**
     * 当前思考强度档位 —— **唯一事实源，存的是下发给 API 的 wire 字符串**。
     *
     * <p>"深度思考是否开启"由它派生（非 {@code none} 即开启），不再单独维护布尔字段，
     * 避免两处状态不一致。</p>
     *
     * <p><b>为什么不是枚举</b>：枚举成员会把可选取值锁死。各家声明的档位集合并不一样
     * （DeepSeek {@code low/high/max}、OpenAI {@code minimal/low/medium/high/xhigh}、
     * DashScope 另有 {@code xhigh/max}、Kimi {@code low/medium/high/max}），此前字段是
     * {@code ThinkingEffort}，读回来时认不出的值被 {@code fromWire} 折叠成 HIGH ——
     * 于是"中/极高/极低"在界面上消失、在请求里也永远发不出去，各家的档位被混为一谈。
     * 现在可选集合完全跟随该模型声明的档位。</p>
     */
    private volatile String thinkingEffortWire = ThinkingEffort.HIGH.wireValue;

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
            // 只写过旧布尔偏好的历史数据：关→none，开→官方默认 high
            thinkingEffortWire = legacyEnabled
                    ? ThinkingEffort.HIGH.wireValue : ThinkingEffort.NONE.wireValue;
        } else {
            // 原样保留（含枚举没收录的各家私有取值）；旧偏好明确记为"关"时以关为准
            thinkingEffortWire = storedEffort.trim();
            if (!legacyEnabled) thinkingEffortWire = ThinkingEffort.NONE.wireValue;
        }
    }

    private void saveThinkingEffortWire(String wire) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(PREF_THINKING_EFFORT, wire)
                .putBoolean(PREF_DEEP_THINKING, isThinkingWire(wire))
                .apply();
    }

    /** wire 是否代表"开启思考"（非空且不为 none）—— 开关态的唯一派生口径 */
    private static boolean isThinkingWire(String wire) {
        return wire != null && !wire.trim().isEmpty()
                && !ThinkingEffort.NONE.wireValue.equalsIgnoreCase(wire.trim());
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

    /**
     * 当前思考强度的 <b>wire 取值</b>（下发给 API 的原样字符串，永不为 null）。
     * 新代码请用这个，不要用 {@link #getThinkingEffort()} 枚举 —— 枚举认不出的各家私有取值
     * 会在转换时被折叠成 HIGH。
     */
    public String getThinkingEffortWire() {
        String w = thinkingEffortWire;
        return (w == null || w.trim().isEmpty()) ? ThinkingEffort.HIGH.wireValue : w.trim();
    }

    /**
     * 按 wire 取值设置思考强度（持久化）。返回与设置前相比是否发生变化。
     *
     * <p>取值集合来自"该模型被服务商声明的档位"（{@code effort.supported_levels}，
     * 或 providers.json 的模型级声明），所以这里<b>不做枚举白名单校验</b> —— 校验在
     * 选择器（只列声明过的档位）与下发闸门（{@code isEffortLevelSupported}）两处做，
     * 这里再拦一道只会把新服务商的合法取值挡掉。空白值按 {@code high} 处理。</p>
     */
    public boolean setThinkingEffortWire(String wire) {
        String next = (wire == null || wire.trim().isEmpty())
                ? ThinkingEffort.HIGH.wireValue : wire.trim();
        String previous = getThinkingEffortWire();
        if (previous.equalsIgnoreCase(next)) return false;
        thinkingEffortWire = next;
        saveThinkingEffortWire(next);
        AppLogger.aiD(TAG, "Thinking effort " + previous + " -> " + next);
        return true;
    }

    /** 当前思考强度档位（枚举视图，仅供旧调用方；未收录取值会回落 HIGH） */
    public ThinkingEffort getThinkingEffort() {
        return currentEffort();
    }

    // ==================== 深度思考开关（派生自强度档位） ====================

    /** 档位的枚举视图（永不为 null）：未收录的私有取值回落为 HIGH */
    private ThinkingEffort currentEffort() {
        return ThinkingEffort.fromWire(getThinkingEffortWire());
    }

    /** 深度思考是否开启（等价于强度档位 != none；空白按未开启处理，避免 NPE） */
    public boolean isDeepThinkingEnabled() {
        return isThinkingWire(thinkingEffortWire);
    }

    /**
     * 开关深度思考（持久化）。返回状态是否发生变化。
     *
     * <p>关闭 → 档位置为 {@code none}；开启 → 恢复到上次的强度档位（历史只有布尔偏好、
     * 或上次就是 none 时，取官方默认 {@code high}）。</p>
     */
    public boolean setDeepThinkingEnabled(boolean enabled) {
        boolean current = isDeepThinkingEnabled();
        if (enabled == current) return false;
        if (!enabled) {
            setThinkingEffortWire(ThinkingEffort.NONE.wireValue);
        } else {
            String cur = thinkingEffortWire;
            String target = isThinkingWire(cur) ? cur : ThinkingEffort.HIGH.wireValue;
            setThinkingEffortWire(target);
        }
        AppLogger.aiD(TAG, "Deep thinking " + (enabled ? "ENABLED" : "DISABLED"));
        return true;
    }

    // ==================== 兼容旧 API（模式 → 开关映射） ====================

    /** 兼容：当前模式（NORMAL=关，DEEP_THINKING=开） */
    public ChatMode getCurrentMode() {
        return isDeepThinkingEnabled() ? ChatMode.DEEP_THINKING : ChatMode.NORMAL;
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
