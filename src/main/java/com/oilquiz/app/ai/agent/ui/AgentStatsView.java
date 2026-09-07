package com.oilquiz.app.ai.agent.ui;

import com.oilquiz.app.theme.ThemeColors;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.agent.online.OnlineToolUsageTracker;

import java.util.List;
import java.util.Map;

/**
 * Agent 统计管理视图：工具使用统计 + 缓存信息。
 * 数据源：OnlineToolManager（工具使用链追踪 + 结果缓存）。
 */
public class AgentStatsView {

    private final Context context;
    private LinearLayout container;

    public AgentStatsView(Context context) {
        this.context = context;
    }

    public View build(ViewGroup root) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("📊 Agent 统计");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        page.addView(title);

        TextView desc = new TextView(context);
        desc.setText("工具使用情况与结果缓存。统计持久化保存，帮助 Agent 学习常用组合。");
        desc.setTextSize(12);
        desc.setTextColor(color(R.color.text_secondary));
        desc.setPadding(0, dp(4), 0, dp(12));
        page.addView(desc);

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        page.addView(container);

        refresh();
        return page;
    }

    private void refresh() {
        container.removeAllViews();
        OnlineToolManager manager = OnlineToolManager.getInstance(context);

        // 缓存信息卡片
        container.addView(createInfoCard(
                "💾 工具结果缓存",
                "缓存条目: " + manager.getCacheSize() + "\n" +
                "说明: 只读工具（天气/搜索/图片等）结果缓存 5 分钟，减少重复请求。",
                "清空缓存", v -> {
                    manager.clearCache();
                    Toast.makeText(context, "已清空缓存", Toast.LENGTH_SHORT).show();
                    refresh();
                }));

        // 全局统计
        container.addView(createInfoCard(
                "📈 全局统计",
                manager.getUsageStats() + "\n说明: 持久化保存，跨重启累积。",
                null, null));

        // 工具使用明细
        OnlineToolUsageTracker tracker = manager.getUsageTracker();
        if (tracker != null) {
            Map<String, OnlineToolUsageTracker.ToolStats> stats = tracker.getAllStats();
            if (!stats.isEmpty()) {
                TextView detailTitle = new TextView(context);
                detailTitle.setText("工具使用明细");
                detailTitle.setTextSize(14);
                detailTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                detailTitle.setTextColor(color(R.color.text_primary));
                detailTitle.setPadding(0, dp(12), 0, dp(6));
                container.addView(detailTitle);

                for (Map.Entry<String, OnlineToolUsageTracker.ToolStats> e : stats.entrySet()) {
                    OnlineToolUsageTracker.ToolStats s = e.getValue();
                    container.addView(createToolStatsItem(s));
                }
            }

            // 学习到的组合模式
            List<OnlineToolUsageTracker.ToolPattern> patterns = tracker.discoverPatterns();
            if (!patterns.isEmpty()) {
                TextView patternTitle = new TextView(context);
                patternTitle.setText("🤖 已学习的工具组合");
                patternTitle.setTextSize(14);
                patternTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                patternTitle.setTextColor(color(R.color.text_primary));
                patternTitle.setPadding(0, dp(12), 0, dp(6));
                container.addView(patternTitle);

                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (OnlineToolUsageTracker.ToolPattern p : patterns) {
                    if (shown >= 10) break;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("• ").append(p.toolA).append(" + ").append(p.toolB)
                            .append("（").append(p.count).append(" 次）");
                    shown++;
                }
                TextView patternTv = new TextView(context);
                patternTv.setText(sb.toString());
                patternTv.setTextSize(12);
                patternTv.setTextColor(color(R.color.text_secondary));
                patternTv.setPadding(0, dp(4), 0, dp(8));
                container.addView(patternTv);
            }
        }

        if (container.getChildCount() == 0) {
            TextView empty = new TextView(context);
            empty.setText("暂无统计数据。\n使用 Agent 工具后，这里会显示使用情况。");
            empty.setTextSize(13);
            empty.setTextColor(color(R.color.text_tertiary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            container.addView(empty);
        }
    }

    private View createInfoCard(String title, String content, String actionText,
                                View.OnClickListener action) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(cardBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(6));
        card.setLayoutParams(cardLp);

        LinearLayout headRow = new LinearLayout(context);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(14);
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(color(R.color.text_primary));
        headRow.addView(titleTv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionText != null && action != null) {
            TextView actionTv = new TextView(context);
            actionTv.setText(actionText);
            actionTv.setTextSize(12);
            actionTv.setTextColor(color(R.color.error));
            actionTv.setPadding(dp(6), dp(2), dp(6), dp(2));
            actionTv.setOnClickListener(action);
            headRow.addView(actionTv);
        }
        card.addView(headRow);

        TextView contentTv = new TextView(context);
        contentTv.setText(content);
        contentTv.setTextSize(12);
        contentTv.setTextColor(color(R.color.text_secondary));
        contentTv.setPadding(0, dp(4), 0, 0);
        card.addView(contentTv);
        return card;
    }

    private View createToolStatsItem(OnlineToolUsageTracker.ToolStats s) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.setBackground(cardBackground());
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(4));
        row.setLayoutParams(rowLp);

        TextView nameTv = new TextView(context);
        nameTv.setText(s.toolName);
        nameTv.setTextSize(12);
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(color(R.color.text_primary));
        row.addView(nameTv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView statsTv = new TextView(context);
        statsTv.setText(String.format(java.util.Locale.US,
                "%d次 · 成功率%.0f%% · 平均%.0fms",
                s.totalCalls, s.successRate * 100, s.avgExecutionTime));
        statsTv.setTextSize(11);
        statsTv.setTextColor(color(R.color.text_secondary));
        row.addView(statsTv);
        return row;
    }

    private int color(int resId) {
        return ThemeColors.get(context, resId);
    }

    private android.graphics.drawable.Drawable cardBackground() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.surface_variant));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), color(R.color.outline));
        return gd;
    }

    private int dp(float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
