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
        String text = String.format(context.getString(R.string.h_986cd3e8),
                stats.requestPromptTokens, stats.requestCompletionTokens);
        if (agent != null) {
            if (agent.cacheHitTokens > 0 && agent.promptTokens > 0) {
                int hitRate = (int) Math.round(agent.cacheHitTokens * 100.0 / agent.promptTokens);
                text += String.format(context.getString(R.string.h_83afc322), hitRate);
            }
            if (agent.contextWindowInfo != null && agent.contextWindowInfo.length == 3
                    && agent.contextWindowInfo[0] > 0) {
                int window = agent.contextWindowInfo[0];
                text += String.format(context.getString(R.string.h_d7a0f347),
                        ChatTextUtils.formatCtxWindow(window),
                        Math.min(100.0, agent.contextWindowInfo[1] * 100.0 / window));
            }
        }
        if (stats.sessionTotalTokens > 0) {
            text += String.format(context.getString(R.string.h_b557980d), stats.sessionTotalTokens);
        }
        return text;
    }
}
