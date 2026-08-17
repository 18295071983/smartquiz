package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 便签/引用卡片组件：摘要、结论、引用、重要提示（左侧竖线 + 斜体弱背景）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "note" | "quote" | "tip" | "summary",
 *   "content": "关键结论：...",
 *   "author": "来源/标签（可选）"
 * }
 * </pre>
 */
public class NoteCardView implements ChatComponent {

    @Override
    public String getType() {
        return "note_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.has("content");
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String type = p.optString("type", "note");
        String content = p.optString("content", "");
        String author = p.optString("author", "");

        int accentColor;
        switch (type) {
            case "quote": accentColor = ComponentColors.accent(context); break;
            case "tip": accentColor = ComponentColors.success(context); break;
            case "summary": accentColor = ComponentColors.warning(context); break;
            default: accentColor = ComponentColors.textSecondary(context); break;
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 左侧竖线
        View bar = new View(context);
        bar.setBackgroundColor(accentColor);
        card.addView(bar, new LinearLayout.LayoutParams(dp(context, 3),
                LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(context, 10), 0, 0, 0);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(content)) {
            TextView contentTv = new TextView(context);
            contentTv.setText(content);
            contentTv.setTextSize(13);
            contentTv.setTextColor(ComponentColors.textPrimary(context));
            if ("quote".equals(type)) {
                contentTv.setTypeface(null, android.graphics.Typeface.ITALIC);
            }
            textCol.addView(contentTv);
        }

        if (!TextUtils.isEmpty(author)) {
            TextView authorTv = new TextView(context);
            authorTv.setText("— " + author);
            authorTv.setTextSize(11);
            authorTv.setTextColor(ComponentColors.textTertiary(context));
            authorTv.setGravity(Gravity.END);
            authorTv.setPadding(0, dp(context, 4), 0, 0);
            textCol.addView(authorTv);
        }

        card.addView(textCol);
        return card;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
