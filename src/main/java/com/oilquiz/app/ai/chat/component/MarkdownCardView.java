package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Markdown 内容卡片组件：渲染 Markdown 文本（加粗/斜体/列表/链接/代码块/表格等）为富文本。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "content": "**加粗** 和 *斜体* 和 [链接](https://...) 等 Markdown 内容",
 *   "title": "可选标题"
 * }
 * </pre>
 *
 * 用途：内容框架的文本承载组件——Agent 输出富文本段落/说明/总结时使用，
 * 替代纯文本组件（note_card 等不支持 Markdown 排版）。
 */
public class MarkdownCardView implements ChatComponent {

    @Override
    public String getType() {
        return "markdown_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("content") || data.props.has("text"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String content = p.optString("content", "");
        if (content.isEmpty()) content = p.optString("text", "");
        String title = p.optString("title", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (!content.trim().isEmpty()) {
            TextView contentTv = new TextView(context);
            contentTv.setTextSize(13);
            contentTv.setTextColor(ComponentColors.textPrimary(context));
            contentTv.setLineSpacing(0f, 1.3f);
            contentTv.setMovementMethod(LinkMovementMethod.getInstance());
            try {
                // 用现有 MarkdownRenderer 渲染富文本（加粗/列表/链接/代码块等）
                Spanned spanned = com.oilquiz.app.ai.chat.render.MarkdownRenderer.render(content, context);
                contentTv.setText(spanned != null ? spanned : content);
            } catch (Throwable t) {
                // Markdown 渲染失败降级纯文本，不阻塞内容展示
                contentTv.setText(content);
            }
            card.addView(contentTv);
        }

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
