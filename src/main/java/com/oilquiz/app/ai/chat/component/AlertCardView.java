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
 * 提醒卡片组件：成功/警告/错误/信息 四种类型，带左侧色条与图标。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "success" | "warning" | "error" | "info",
 *   "title": "操作成功",
 *   "content": "文件已保存到 /sdcard/xxx",
 *   "actions": [{"label":"打开","link":"file:///..."}]   // 可选
 * }
 * </pre>
 */
public class AlertCardView implements ChatComponent {

    @Override
    public String getType() {
        return "alert_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("title") || data.props.has("content"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String type = p.optString("type", "info");
        String title = p.optString("title", "");
        String content = p.optString("content", "");

        // 按类型取色
        int accentColor;
        String icon;
        switch (type) {
            case "success":
                accentColor = ComponentColors.success(context);
                icon = "✅";
                break;
            case "warning":
                accentColor = ComponentColors.warning(context);
                icon = "⚠️";
                break;
            case "error":
                accentColor = ComponentColors.error(context);
                icon = "❌";
                break;
            case "info":
            default:
                accentColor = ComponentColors.accent(context);
                icon = "ℹ️";
                break;
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 左侧色条
        View colorBar = new View(context);
        colorBar.setBackgroundColor(accentColor);
        card.addView(colorBar, new LinearLayout.LayoutParams(dp(context, 4),
                LinearLayout.LayoutParams.MATCH_PARENT));

        // 图标
        TextView iconTv = new TextView(context);
        iconTv.setText(icon);
        iconTv.setTextSize(18);
        iconTv.setPadding(dp(context, 10), 0, dp(context, 8), 0);
        iconTv.setGravity(Gravity.TOP);
        card.addView(iconTv);

        // 文本列
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(13);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textCol.addView(titleTv);
        }

        if (!TextUtils.isEmpty(content)) {
            TextView contentTv = new TextView(context);
            contentTv.setText(content);
            contentTv.setTextSize(12);
            contentTv.setTextColor(ComponentColors.textSecondary(context));
            contentTv.setPadding(0, dp(context, 2), 0, 0);
            textCol.addView(contentTv);
        }

        // 动作行：actions 按钮（打开链接/复制等），点击真实响应
        ComponentActions.renderActions(textCol, context, p.optJSONArray("actions"));
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
