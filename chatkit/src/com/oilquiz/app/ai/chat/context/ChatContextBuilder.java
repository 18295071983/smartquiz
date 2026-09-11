package com.oilquiz.app.ai.chat.context;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.spi.AppServices;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天上下文构建器（全局可复用）。
 *
 * 从 AIChatActivity 上下文组装逻辑抽取的纯逻辑组件：token 预算、历史条目收集、
 * 工具/思考痕迹、提示词变更标记。不依赖 Activity，通过 {@link Config} 注入环境事实
 * （在线判定 / 本地上下文 / 模型配置 / Agent 窗口），可在任何页面复用同一套
 * KV 预算与历史压缩策略。
 *
 * 用法：
 * <pre>
 * ChatContextBuilder builder = new ChatContextBuilder(context, new ChatContextBuilder.Config() { ... });
 * List&lt;String[]&gt; history = builder.collectHistoryByBudget(messages, lastUserIdx, true);
 * </pre>
 */
public class ChatContextBuilder {

    // ==================== 常量（与对话页原值一致） ====================

    /** 防御性硬上限：即使上下文预算很大，也最多携带这么多条历史 */
    public static final int HISTORY_MAX_ENTRIES_HARD_CAP = 60;
    /** 工具结果保留档位（数据优先：最近完整，旧结果渐进收缩） */
    public static final int TOOL_RESULT_KEEP_MAX = 6000;
    public static final int TOOL_RESULT_MID_MAX = 2500;
    public static final int TOOL_RESULT_OLD_MAX = 1200;
    /** 历史要点里单条工具结果的最大字符数 */
    public static final int KEY_POINT_RESULT_MAX = 300;
    /** 思考内容进上下文的最大字符数 */
    private static final int THINKING_INCLUDE_MAX = 512;
    /** 预算耗尽时保留的历史要点条数 */
    private static final int EVICTED_POINTS_MAX = 4;

    // ==================== 环境事实注入 ====================

    /** 构建上下文所需的环境事实（由宿主注入，不依赖具体页面） */
    public interface Config {
        /** 是否使用在线模型（在线不设上下文预算限制） */
        boolean isOnlineModel();
        /** 本地模型实际上下文大小（n_ctx），<=0 表示未知 */
        int getNativeContextSize();
        /** 基于上下文引用计算的安全参考值，<=0 表示未知 */
        int getSafeContextReference(int ctx);
        /** 模型配置上下文大小（兜底），<=0 表示未知 */
        int getModelContextSize();
        /** 本地 Agent 开关 */
        boolean isLocalAgentEnabled();
        /** 系统提示词 */
        String getSystemPrompt();
        /** Agent 引擎上下文窗口（tokens），<=0 表示未知 */
        int getAgentContextWindow();
    }

    private final Config config;

    /** 上一条上下文构建时的提示词签名（检测设置变化以注入变更标记） */
    private volatile String lastPromptSignature;
    /** 预算耗尽时被挤出历史的"要点"（最新优先，供调用方注入上下文） */
    private List<String> evictedContextPoints = new ArrayList<>();

    public ChatContextBuilder(Context context, Config config) {
        AppServices.ensure(context);
        this.config = config;
    }

    // ==================== token 预算 ====================

    /** 粗略估算文本 token 数：中文约 2 字符/token 的保守估计 + 角色开销 */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return 4 + (text.length() + 1) / 2;
    }

    /**
     * 可给历史上下文使用的 token 预算。
     * 在线模型：不设限制（在线引擎自身按模型窗口动态预算）；
     * 本地模型：实际运行上下文优先，预留 -1024 余量保持前缀稳定利于 KV 缓存命中。
     */
    public int getContextBudgetTokens() {
        if (config.isOnlineModel()) {
            return Integer.MAX_VALUE / 2;
        }
        int ctx = 0;
        try {
            int actual = config.getNativeContextSize();
            if (actual > 0) ctx = actual;
        } catch (Throwable ignored) {}
        if (ctx <= 0) ctx = config.getModelContextSize();
        int agentWindow = config.getAgentContextWindow();
        if (agentWindow > 0) {
            ctx = ctx > 0 ? Math.min(ctx, agentWindow) : agentWindow;
        }
        if (ctx <= 0) ctx = 8192;
        int safeRef = ctx;
        try {
            int ref = config.getSafeContextReference(ctx);
            if (ref > 0) safeRef = ref;
        } catch (Throwable ignored) {}
        return Math.max(1024, safeRef - 1024);
    }

    /** 历史条数硬上限：本地 60；在线不设上限 */
    public int getHistoryMaxEntries() {
        return config.isOnlineModel() ? Integer.MAX_VALUE : HISTORY_MAX_ENTRIES_HARD_CAP;
    }

    // ==================== 工具/思考痕迹 ====================

    /**
     * 从 AI 消息的工具卡片组件提取工具痕迹（工具名 + 结果）。
     * 只留已完成的 success/failed 卡片。返回 null 表示无工具痕迹；不修改原消息。
     */
    public String buildToolTrace(ChatMessage m, int maxResultChars) {
        if (m.components == null || m.components.isEmpty()) return null;
        if (maxResultChars <= 0) maxResultChars = TOOL_RESULT_OLD_MAX;
        StringBuilder sb = new StringBuilder();
        for (ComponentData c : m.components) {
            if (c == null || c.type == null || !"tool_call".equals(c.type)) continue;
            if (c.props == null) continue;
            String status = c.props.optString("status", "");
            if (!"success".equals(status) && !"failed".equals(status)) continue;
            String toolName = c.props.optString("toolName", "");
            String result = c.props.optString("result", "");
            if (toolName.isEmpty() && result.isEmpty()) continue;
            sb.append("• ").append(toolName.isEmpty() ? "工具" : toolName);
            if (!result.isEmpty()) {
                String r = result.trim().replace('\n', ' ').replace('\r', ' ');
                if (r.length() > maxResultChars) r = r.substring(0, maxResultChars) + "…";
                sb.append("：").append(r);
            }
            sb.append('\n');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 消息是否携带工具调用组件 */
    public boolean hasToolComponents(ChatMessage m) {
        if (m == null || m.components == null || m.components.isEmpty()) return false;
        for (ComponentData c : m.components) {
            if (c != null && "tool_call".equals(c.type)) return true;
        }
        return false;
    }

    /** 组装消息进入上下文的文本（AI 消息附工具/思考痕迹，截断防膨胀）。返回 null 表示不应进上下文 */
    public String buildContextContent(ChatMessage m, boolean includeThinking, int toolResultCap) {
        if (m == null) return null;
        String base = m.getContent();
        if (base == null) base = "";
        StringBuilder sb = new StringBuilder(base);
        if (m.type == ChatMessage.MessageType.AI) {
            String trace = buildToolTrace(m, toolResultCap);
            if (trace != null) {
                sb.append("\n\n[工具调用]\n").append(trace);
            }
            if (includeThinking && m.thinkingContent != null && !m.thinkingContent.trim().isEmpty()) {
                String th = m.thinkingContent.trim();
                if (th.length() > THINKING_INCLUDE_MAX) th = th.substring(0, THINKING_INCLUDE_MAX) + "…";
                sb.append("\n\n[思考过程]\n").append(th);
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ==================== 历史收集 ====================

    /**
     * 在 token 预算内倒序收集历史条目（返回 {role, content} 正序列表）。
     * 数据优先：最近工具结果完整（6000），旧结果按档位渐进压缩；预算耗尽时把被挤掉的
     * 最近对话做成"历史要点"（{@link #getEvictedContextPoints()}），供调用方注入上下文。
     */
    public List<String[]> collectHistoryByBudget(List<ChatMessage> history,
                                                 int currentUserIdx,
                                                 boolean includeThinking) {
        List<String[]> temp = new ArrayList<>();
        evictedContextPoints = new ArrayList<>();
        int budget = getContextBudgetTokens();
        int used = 0;
        int toolTier = 0;
        int maxEntries = getHistoryMaxEntries();
        for (int i = currentUserIdx - 1; i >= 0 && temp.size() < maxEntries; i--) {
            ChatMessage m = history.get(i);
            if (m == null) continue;
            if (m.type != ChatMessage.MessageType.USER && m.type != ChatMessage.MessageType.AI) continue;
            if (m.status == ChatMessage.MessageStatus.GENERATING
                    || m.status == ChatMessage.MessageStatus.FAILED
                    || m.status == ChatMessage.MessageStatus.ERROR) continue;
            int cap = TOOL_RESULT_KEEP_MAX;
            if (m.type == ChatMessage.MessageType.AI && hasToolComponents(m)) {
                cap = toolTier == 0 ? TOOL_RESULT_KEEP_MAX
                        : (toolTier == 1 ? TOOL_RESULT_MID_MAX : TOOL_RESULT_OLD_MAX);
                toolTier++;
            }
            String content = buildContextContent(m, includeThinking, cap);
            if (content == null) continue;
            int t = estimateTokens(content);
            if (used + t > budget) {
                int kept = 0;
                for (int k = i; k >= 0 && kept < EVICTED_POINTS_MAX; k--) {
                    ChatMessage m2 = history.get(k);
                    if (m2 == null) continue;
                    if (m2.type != ChatMessage.MessageType.USER && m2.type != ChatMessage.MessageType.AI) continue;
                    if (m2.status == ChatMessage.MessageStatus.GENERATING
                            || m2.status == ChatMessage.MessageStatus.FAILED
                            || m2.status == ChatMessage.MessageStatus.ERROR) continue;
                    if (m2.type == ChatMessage.MessageType.AI) {
                        String trace = buildToolTrace(m2, KEY_POINT_RESULT_MAX);
                        if (trace != null) {
                            evictedContextPoints.add("助手: " + trace);
                            kept++;
                            continue;
                        }
                    }
                    String c2 = m2.getContent();
                    if (c2 == null || c2.trim().isEmpty()) continue;
                    String snip = c2.replace('\n', ' ').replace('\r', ' ').trim();
                    if (snip.length() > 90) snip = snip.substring(0, 90) + "…";
                    evictedContextPoints.add((m2.type == ChatMessage.MessageType.USER ? "用户" : "助手") + ": " + snip);
                    kept++;
                }
                break;
            }
            temp.add(new String[]{m.getRole(), content});
            used += t;
        }
        java.util.Collections.reverse(temp);
        return temp;
    }

    /** 预算耗尽被挤掉的历史要点（最新优先，最多 4 条） */
    public List<String> getEvictedContextPoints() {
        return new ArrayList<>(evictedContextPoints);
    }

    // ==================== 提示词变更标记 ====================

    /** 当前提示词签名：思考开关 / 本地Agent开关 / 系统提示词 / 日期 */
    public String currentPromptSignature() {
        StringBuilder sb = new StringBuilder();
        boolean thinking = false;
        try {
            thinking = AppServices.models().isDeepThinkingEnabled();
        } catch (Throwable ignored) {}
        boolean agent = config.isLocalAgentEnabled();
        String sysPrompt = config.getSystemPrompt() != null ? config.getSystemPrompt() : "";
        String date = new java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA)
                .format(new java.util.Date());
        sb.append("thinking=").append(thinking ? 1 : 0);
        sb.append(";agent=").append(agent ? 1 : 0);
        sb.append(";prompt=").append(sysPrompt.hashCode());
        sb.append(";date=").append(date);
        return sb.toString();
    }

    /** 与上次相比，提示词设置是否有变化（返回变化说明；无变化返回 null） */
    public String describePromptChange(String prev, String cur) {
        if (prev == null || cur == null || prev.equals(cur)) return null;
        java.util.Map<String, String> prevMap = new java.util.HashMap<>();
        java.util.Map<String, String> curMap = new java.util.HashMap<>();
        for (String pair : prev.split(";")) {
            int idx = pair.indexOf('=');
            if (idx > 0) prevMap.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        for (String pair : cur.split(";")) {
            int idx = pair.indexOf('=');
            if (idx > 0) curMap.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        List<String> changes = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : curMap.entrySet()) {
            String old = prevMap.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) {
                switch (e.getKey()) {
                    case "thinking": changes.add(old != null ? "深度思考开关已变化" : "深度思考开关已开启"); break;
                    case "agent": changes.add(old != null ? "本地Agent开关已变化" : "本地Agent开关已开启"); break;
                    case "prompt": changes.add("系统提示词已更新"); break;
                    case "date": changes.add("日期已变化"); break;
                    default: break;
                }
            }
        }
        return changes.isEmpty() ? null : String.join("、", changes);
    }

    /** 若提示词设置相对上次发送有变化，生成系统标记消息（普通对话路径注入） */
    public String getPromptChangeMarkerIfAny() {
        String cur = currentPromptSignature();
        String desc = describePromptChange(lastPromptSignature, cur);
        if (desc == null) return null;
        return "[系统指令 - 对话设置已更新]\n\n" + desc
                + "。历史对话内容仍然有效，但请以当前设置理解对话、回答当前问题，不要沿用旧设置的规则。";
    }

    /** 记录当前签名（每次发送前调用） */
    public void snapshotPromptSignature() {
        lastPromptSignature = currentPromptSignature();
    }
}
