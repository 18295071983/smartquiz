package com.oilquiz.app.ai.chat.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.List;
import java.util.Locale;

/**
 * 会话统计胶囊条 —— 移植自 deepseek-harness 的 StatsPills 设计。
 * 常驻在输入框上方，实时汇总当前会话：生成耗时 / AI 轮次 / 工具调用 / Token 用量 / 费用；
 * 点击任一胶囊弹出明细对话框（对齐 dsh 的 stat-dialog：轮次、思考、工具、Token、耗时分解）。
 *
 * 数据完全来自当前会话的消息列表（chatHistory），不依赖 usage 库，翻页/恢复后自动一致。
 */
public class ChatStatsBar extends LinearLayout {

    private final TextView pillDuration;
    private final TextView pillTurns;
    private final TextView pillTools;
    private final TextView pillTokens;
    private final TextView pillCost;
    /** 2026-09-23：上下文占用 pill（原 tv_context_meter 并入统计条，避免贴右边缘被工具抽屉覆盖） */
    private final TextView pillContext;
    private Runnable contextPillClick = null;
    private List<ChatMessage> messages = null;

    public ChatStatsBar(Context context) {
        this(context, null);
    }

    public ChatStatsBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChatStatsBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(2), padV = dp(3);
        setPadding(padH, padV, padH, padV);
        pillContext = makePill("📊 --");
        pillContext.setVisibility(GONE); // 默认隐藏，setContextPercent 时显示
        pillContext.setOnClickListener(v -> {
            if (contextPillClick != null) contextPillClick.run();
        });
        addView(pillContext);
        pillDuration = makePill("⏱ --");
        pillTurns = makePill("🔁 0");
        pillTools = makePill("🛠 0");
        pillTokens = makePill("🔵 0 tok");
        pillCost = makePill("💰 --");
        addView(pillDuration);
        addView(pillTurns);
        addView(pillTools);
        addView(pillTokens);
        addView(pillCost);
        setOnClickListener(v -> showDetailDialog());
    }

    private TextView makePill(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(0xFFE5E7EB); // 深蓝渐变背景上用浅灰白
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(3), dp(10), dp(3));
        tv.setBackground(makePillBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private android.graphics.drawable.Drawable makePillBackground() {
        // 浅灰圆角胶囊：与顶部状态条 status_bar_background 视觉一致
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0x26FFFFFF); // 15% 白底，深蓝渐变背景上胶囊可见
        gd.setCornerRadius(dp(14));
        return gd;
    }

    /** 上下文占用 pill（2026-09-23）：低占用（<1%）显示实际 token 数更直观
     *  （如 deepseek 1M 窗口下 0% 无信息量）；used<=0 显示 "—" */
    /**
     * 上下文仪表 pill：占用百分比 + 用量/窗口（如 "📊 41% · 5.0k/12.3k"）。
     * windowTokens > 0 时同时显示大小和使用量（2026-10-07，本地 Agent 模式为 KV 真实占用）；
     * 无窗口信息时回退旧行为（纯百分比 / 低占用时 token 数）。
     */
    public void setContextPercent(int percent, long usedTokens, long windowTokens) {
        pillContext.setVisibility(VISIBLE);
        if (percent < 0 || usedTokens <= 0) {
            pillContext.setText("📊 --");
        } else if (windowTokens > 0) {
            pillContext.setText("📊 " + Math.min(100, percent) + "% · "
                    + formatTokens(usedTokens) + "/" + formatTokens(windowTokens));
        } else if (percent < 1) {
            pillContext.setText("📊 " + formatTokens(usedTokens));
        } else {
            pillContext.setText("📊 " + Math.min(100, percent) + "%");
        }
    }

    /** 上下文 pill 点击回调（弹上下文用量明细；父条点击仍弹会话统计） */
    public void setOnContextPillClick(Runnable r) {
        this.contextPillClick = r;
    }

    /** 以当前会话消息列表刷新统计；2026-09-23 常驻显示（空对话也显示统计条，不再隐藏） */
    public void update(List<ChatMessage> newMessages) {
        this.messages = newMessages;
        setVisibility(VISIBLE);

        // 空对话：显示基础 0 值 pill（轮次/工具/token），条常驻不空
        if (newMessages == null || newMessages.isEmpty()) {
            pillDuration.setVisibility(GONE);
            pillTurns.setVisibility(VISIBLE);
            pillTurns.setText("🔁 0");
            pillTools.setVisibility(VISIBLE);
            pillTools.setText("🛠 0");
            pillTokens.setVisibility(VISIBLE);
            pillTokens.setText("🔵 0 tok");
            pillCost.setVisibility(GONE);
            return;
        }

        int turns = 0, tools = 0, tokens = 0, thinkingRounds = 0;
        long durationMs = 0;
        double cost = 0;
        for (ChatMessage m : newMessages) {
            if (m == null) continue;
            ChatMessage.MessageType t = m.type;
            if (t == null) continue;
            if (t == ChatMessage.MessageType.AI) {
                turns++;
                tokens += Math.max(0, m.tokensGenerated);
                durationMs += Math.max(0, m.generationTimeMs);
                if (m.modelInfo != null) cost += Math.max(0, m.modelInfo.costEstimate);
                if (m.thinkingRounds != null) thinkingRounds += m.thinkingRounds.size();
                if (m.thinkingContent != null && !m.thinkingContent.trim().isEmpty()) thinkingRounds++;
            } else if (t == ChatMessage.MessageType.THINKING) {
                thinkingRounds++;
            } else if (t == ChatMessage.MessageType.TOOL_CALL) {
                tools++;
            }
        }

        pillDuration.setText("⏱ " + formatDuration(durationMs));
        pillTurns.setText("🔁 " + turns);
        pillTools.setText("🛠 " + tools);
        pillTokens.setText("🔵 " + formatTokens(tokens));
        pillCost.setText("💰 " + formatCost(cost));

        pillDuration.setVisibility(durationMs > 0 ? VISIBLE : GONE);
        pillTokens.setVisibility(tokens > 0 ? VISIBLE : GONE);
        pillCost.setVisibility(cost > 0 ? VISIBLE : GONE);
        pillTools.setVisibility(tools > 0 ? VISIBLE : GONE);
        pillTurns.setVisibility(turns > 0 ? VISIBLE : GONE);
    }

    /** 明细对话框：对齐 dsh stat-dialog 的分解展示 */
    private void showDetailDialog() {
        if (messages == null) return;
        int turns = 0, tools = 0, tokens = 0, thinking = 0, user = 0;
        long durationMs = 0;
        double cost = 0;
        double tokensPerSec = 0;
        int tpsCount = 0;
        for (ChatMessage m : messages) {
            if (m == null || m.type == null) continue;
            switch (m.type) {
                case AI:
                    turns++;
                    tokens += Math.max(0, m.tokensGenerated);
                    durationMs += Math.max(0, m.generationTimeMs);
                    if (m.modelInfo != null) cost += Math.max(0, m.modelInfo.costEstimate);
                    if (m.thinkingRounds != null) thinking += m.thinkingRounds.size();
                    if (m.thinkingContent != null && !m.thinkingContent.trim().isEmpty()) thinking++;
                    if (m.tokensPerSecond > 0) { tokensPerSec += m.tokensPerSecond; tpsCount++; }
                    break;
                case THINKING: thinking++; break;
                case TOOL_CALL: tools++; break;
                case USER: user++; break;
                default: break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("AI 回复：").append(turns).append(" 轮\n");
        sb.append("用户消息：").append(user).append(" 条\n");
        sb.append("思考块：").append(thinking).append(" 段\n");
        sb.append("工具调用：").append(tools).append(" 次\n");
        sb.append("生成 Token：").append(formatTokens(tokens)).append("\n");
        sb.append("生成耗时：").append(formatDuration(durationMs)).append("\n");
        if (tpsCount > 0) {
            sb.append(String.format(Locale.US, "平均速度：%.1f token/s\n", tokensPerSec / tpsCount));
        }
        if (cost > 0) sb.append("估算费用：").append(formatCost(cost)).append("\n");
        new AlertDialog.Builder(getContext())
                .setTitle("会话统计")
                .setMessage(sb.toString().trim())
                .setPositiveButton("好的", null)
                .show();
    }

    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60, r = s % 60;
        if (m < 60) return m + "m " + r + "s";
        long h = m / 60, mr = m % 60;
        return h + "h " + mr + "m";
    }

    public static String formatTokens(long n) {
        if (n < 0) n = 0;
        if (n < 1000) return n + " tok";
        if (n < 1000000) return String.format(Locale.US, "%.1fk", n / 1000.0);
        return String.format(Locale.US, "%.2fM", n / 1000000.0);
    }

    public static String formatCost(double cost) {
        if (cost <= 0) return "--";
        if (cost < 0.01) return "¥" + String.format(Locale.US, "%.4f", cost);
        return "¥" + String.format(Locale.US, "%.2f", cost);
    }

    private int dp(int v) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * v);
    }
}
