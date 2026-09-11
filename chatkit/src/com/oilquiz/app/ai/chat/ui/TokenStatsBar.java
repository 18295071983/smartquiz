package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.util.ChatTextUtils;
import com.oilquiz.app.ai.stats.TokenStatsManager;

/**
 * Token 统计条（全局可复用 View 渲染组件）。
 *
 * 从 AIChatActivity showTokenStats / updateTokenStatsUI /
 * updateStreamingTokenStats 的渲染逻辑抽取：
 * 流式实时统计（在线 API completion / 本地 native TPS 自动切换数据源）+
 * 完成态统计（请求级输入/输出 + Agent 缓存命中率 + 上下文占用 + 会话总量）。
 *
 * <p>数据源通过 {@link StatsSource} 注入；文本格式化复用
 * {@link ChatTextUtils#formatCtxWindow}。不持有 Activity 引用。</p>
 *
 * <pre>
 * TokenStatsBar bar = new TokenStatsBar(context, tvTokenStats, source);
 * bar.show(true);
 * bar.updateFromStreaming(totalTokens, tps);   // 流式中每批
 * bar.updateFromStats(stats);                  // 完成态
 * </pre>
 */
public class TokenStatsBar {

    /** 统计/模型状态读取（页面注入适配器） */
    public interface StatsSource {
        boolean isUsingOnlineModel();
        boolean isGenerating();
        /** 在线 API completion tokens（本次请求） */
        int getOnlineCompletionTokens();
        /** 在线 API prompt tokens（本次请求） */
        int getOnlinePromptTokens();
        /** native 阶段吞吐（LlamaHelper.getPhaseSpeed） */
        float getPhaseSpeed();
        /** native 推理速度（LlamaHelper.getInferenceSpeed） */
        float getNativeInferenceSpeed();
        /** native token 数（LlamaHelper.getTokenCount） */
        int getNativeTokenCount();
        /** 阶段 JSON（LlamaHelper.getGenPhase），可 null */
        String getGenPhase();
        /** 本地累计 token 兜底值 */
        long getStreamingTokenCount();
        // ---- Agent 在线缓存统计（可返回 0 / null 表示不可用） ----
        int getLastCacheHitTokens();
        int getLastPromptTokens();
        int[] getContextWindowInfo();
    }

    private final Context context;
    private final TextView tvTokenStats;
    private final StatsSource source;

    public TokenStatsBar(Context context, TextView tvTokenStats, StatsSource source) {
        this.context = context.getApplicationContext();
        this.tvTokenStats = tvTokenStats;
        this.source = source;
    }

    public void show(boolean show) {
        if (tvTokenStats != null) {
            tvTokenStats.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 流式实时统计（从 StreamingUpdateManager 回调）。
     * 仅更新 UI，不更新 TokenStatsManager（避免重复累加）。
     * 数据源自动切换：在线 API completion → 在线估算 → 本地 native TPS/Token。
     */
    public void updateFromStreaming(int totalTokens, float tokensPerSecond) {
        if (tvTokenStats == null) return;
        tvTokenStats.setVisibility(View.VISIBLE);

        boolean useOnline = source.isUsingOnlineModel();
        float displayTps;
        int displayTokens;
        String sourceTag;

        if (useOnline && source.getOnlineCompletionTokens() > 0) {
            displayTokens = source.getOnlineCompletionTokens();
            displayTps = tokensPerSecond;
            sourceTag = "🌐";
        } else if (useOnline) {
            displayTokens = totalTokens > 0 ? totalTokens : (int) source.getStreamingTokenCount();
            displayTps = tokensPerSecond;
            sourceTag = "🌐";
        } else {
            float phaseSpeed = source.getPhaseSpeed();
            float nativeTps = source.getNativeInferenceSpeed();
            int nativeTokens = source.getNativeTokenCount();
            displayTps = phaseSpeed > 0 ? phaseSpeed : (nativeTps > 0 ? nativeTps : tokensPerSecond);
            displayTokens = nativeTokens > 0 ? nativeTokens : totalTokens;
            sourceTag = "⚡";
            try {
                String pj = source.getGenPhase();
                if (pj != null && !pj.isEmpty()) {
                    String ph = new org.json.JSONObject(pj).optString("phase", "");
                    if ("THINKING".equals(ph)) sourceTag = "🧠";
                    else if ("GENERATING".equals(ph)) sourceTag = "⚡";
                }
            } catch (Throwable ignored) {}
        }

        if (source.isGenerating() && displayTps > 0) {
            String statsText = String.format("%s %.1f t/s | %d tokens", sourceTag, displayTps, displayTokens);
            tvTokenStats.setText(statsText);
        } else {
            if (useOnline && (source.getOnlinePromptTokens() > 0 || source.getOnlineCompletionTokens() > 0)) {
                String statsText = String.format(context.getString(R.string.h_c5d9a5e1),
                        source.getOnlinePromptTokens(), source.getOnlineCompletionTokens());
                tvTokenStats.setText(statsText);
            } else {
                String statsText = String.format("✅ %d tokens", displayTokens);
                tvTokenStats.setText(statsText);
            }
        }
    }

    /**
     * 完成态统计（TokenStatsManager 汇总）：请求级输入/输出 + Agent 缓存命中率
     * + 上下文用量 + 会话总量；请求 token 为 0 时隐藏。
     */
    public void updateFromStats(TokenStatsManager.TokenStats stats) {
        if (tvTokenStats == null || stats == null) return;
        if (stats.requestTotalTokens > 0) {
            tvTokenStats.setVisibility(View.VISIBLE);
            String text = String.format(context.getString(R.string.h_986cd3e8),
                    stats.requestPromptTokens, stats.requestCompletionTokens);
            if (source.getLastPromptTokens() > 0 && source.getLastCacheHitTokens() > 0) {
                int hit = source.getLastCacheHitTokens();
                int in = source.getLastPromptTokens();
                int hitRate = (int) Math.round(hit * 100.0 / in);
                text += String.format(context.getString(R.string.h_83afc322), hitRate);
            }
            try {
                int[] ctx = source.getContextWindowInfo();
                if (ctx != null && ctx.length == 3 && ctx[0] > 0) {
                    text += String.format(context.getString(R.string.h_d7a0f347),
                            ChatTextUtils.formatCtxWindow(ctx[0]),
                            Math.min(100.0, ctx[1] * 100.0 / ctx[0]));
                }
            } catch (Throwable ignored) {}
            if (stats.sessionTotalTokens > 0) {
                text += String.format(context.getString(R.string.h_b557980d), stats.sessionTotalTokens);
            }
            tvTokenStats.setText(text);
        } else {
            tvTokenStats.setVisibility(View.GONE);
        }
    }
}
