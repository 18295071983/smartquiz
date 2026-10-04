package com.oilquiz.app.ai.chat.stat;

import android.content.Context;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.util.ChatTextUtils;
import com.oilquiz.app.ai.stats.TokenStatsManager;

/**
 * Token 统计显示文本构建器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity updateTokenStatsUI 的文本拼接抽取：请求级输入/输出、
 * Agent 缓存命中率、上下文用量（窗口/已用/剩余）、会话累计总量。
 * 只负责构建显示文本，UI 渲染由宿主完成。
 */
public final class TokenStatsTextBuilder {

    private TokenStatsTextBuilder() {}

    /** Agent 侧统计输入 */
    public static class AgentStats {
        public int cacheHitTokens;
        public int promptTokens;
        /** [0]=窗口, [1]=已用（Engine 透传） */
        public int[] contextWindowInfo;
    }

    /** 构建 Token 统计显示文本（含缓存命中率与上下文用量追加） */
    public static String build(Context context, TokenStatsManager.TokenStats stats,
                               AgentStats agent) {
        // NPU-TOKEN-BADGE-SVC: 徽标在有 Context 的重载里读服务（单一状态源）
        com.oilquiz.app.ai.service.AIService npuSvc =
                com.oilquiz.app.ai.service.AIService.getInstance(context);
        if (npuSvc != null && npuSvc.isNpuEngineEnabled()) {
            int npuTokens = npuSvc.getNpuLastTokens();
            float npuTps = npuSvc.getNpuLastTps();
            return "\uD83E\uDDE0 " + npuTokens + " tokens"
                    + (npuTps > 0 ? String.format(java.util.Locale.US, " \u00b7 %.1f t/s", npuTps) : "");
        }

        return build(new ContextStringProvider(context), stats, agent);
    }

    /** 构建 Token 统计显示文本（SPI 版：文案经 StringProvider，不持有 Context） */
    public static String build(com.oilquiz.app.ai.spi.StringProvider strings,
                               TokenStatsManager.TokenStats stats,
                               AgentStats agent) {
        // NPU-TOKEN-STATS: NPU 引擎下顶部 token 徽标显示 NPU 的统计
        // （该数据源只反映本地 llama.cpp 会话，NPU 模式会一直是 0 tokens）
        String text = String.format(strings.get(R.string.h_986cd3e8),
                stats.requestPromptTokens, stats.requestCompletionTokens);
        if (agent != null) {
            if (agent.cacheHitTokens > 0 && agent.promptTokens > 0) {
                int hitRate = (int) Math.round(agent.cacheHitTokens * 100.0 / agent.promptTokens);
                text += String.format(strings.get(R.string.h_83afc322), hitRate);
            }
            if (agent.contextWindowInfo != null && agent.contextWindowInfo.length == 3
                    && agent.contextWindowInfo[0] > 0) {
                int window = agent.contextWindowInfo[0];
                text += String.format(strings.get(R.string.h_d7a0f347),
                        ChatTextUtils.formatCtxWindow(window),
                        Math.min(100.0, agent.contextWindowInfo[1] * 100.0 / window));
            }
        }
        if (stats.sessionTotalTokens > 0) {
            text += String.format(strings.get(R.string.h_b557980d), stats.sessionTotalTokens);
        }
        return text;
    }

    /** Context 桥接实现（兼容旧调用点） */
    private static final class ContextStringProvider implements com.oilquiz.app.ai.spi.StringProvider {
        private final Context context;
        ContextStringProvider(Context context) { this.context = context.getApplicationContext(); }
        @Override public String get(int resId) { return context.getString(resId); }
        @Override public String get(int resId, Object... args) { return context.getString(resId, args); }
    }
}
