package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 链接卡片组件：标题 + 描述 + 域名，点击打开网页。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "文章标题",
 *   "description": "摘要（可选）",
 *   "url": "https://example.com/article",
 *   "domain": "example.com（可选，默认从 url 解析）"
 * }
 * </pre>
 */
public class LinkCardView implements ChatComponent {

    @Override
    public String getType() {
        return "link_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("url", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        final String url = p.optString("url", "");
        String title = p.optString("title", "");
        String description = p.optString("description", "");
        String domain = p.optString("domain", "");
        if (TextUtils.isEmpty(domain)) {
            domain = parseDomain(url);
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openUrl(context, url));

        // 标题
        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(titleTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 描述
        if (!TextUtils.isEmpty(description)) {
            TextView descTv = new TextView(context);
            descTv.setText(description);
            descTv.setTextSize(12);
            descTv.setTextColor(ComponentColors.textSecondary(context));
            descTv.setMaxLines(3);
            descTv.setEllipsize(TextUtils.TruncateAt.END);
            descTv.setPadding(0, dp(context, 2), 0, dp(context, 4));
            card.addView(descTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 底部：域名 + 箭头
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        TextView domainTv = new TextView(context);
        domainTv.setText(TextUtils.isEmpty(domain) ? "打开链接" : domain);
        domainTv.setTextSize(11);
        domainTv.setTextColor(ComponentColors.accent(context));
        footer.addView(domainTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrowTv = new TextView(context);
        arrowTv.setText("↗");
        arrowTv.setTextSize(14);
        arrowTv.setTextColor(ComponentColors.textTertiary(context));
        footer.addView(arrowTv);
        card.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    private static String parseDomain(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return "";
            // 去掉 www. 前缀
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
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
