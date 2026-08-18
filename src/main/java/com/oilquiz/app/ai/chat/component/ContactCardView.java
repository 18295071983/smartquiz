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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 联系/操作卡片组件：手机号/邮箱/电话链接，点击可拨号或发信。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "phone" | "email" | "sms" | "map",
 *   "title": "联系人",
 *   "value": "13800138000",              // 手机号/邮箱/坐标查询
 *   "description": "可选说明",
 *   "actions": [{"label":"拨打","action":"dial"}, {"label":"短信","action":"sms"}]  // 可选
 * }
 * </pre>
 */
public class ContactCardView implements ChatComponent {

    @Override
    public String getType() {
        return "contact_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("value", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String type = p.optString("type", "phone");
        String title = p.optString("title", "");
        final String value = p.optString("value", "");
        String description = p.optString("description", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 标题行
        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(13);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(titleTv);
        }

        // 值（大号展示）
        TextView valueTv = new TextView(context);
        valueTv.setText(value);
        valueTv.setTextSize(18);
        valueTv.setTextColor(ComponentColors.accent(context));
        valueTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valueTv.setPadding(0, dp(context, 4), 0, 0);
        card.addView(valueTv);

        if (!TextUtils.isEmpty(description)) {
            TextView descTv = new TextView(context);
            descTv.setText(description);
            descTv.setTextSize(11);
            descTv.setTextColor(ComponentColors.textSecondary(context));
            descTv.setPadding(0, dp(context, 2), 0, 0);
            card.addView(descTv);
        }

        // 动作行：默认按 type 生成，可被 actions 覆盖
        JSONArray actions = p.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(context, 8), 0, 0);
            if ("phone".equals(type) || "sms".equals(type)) {
                row.addView(actionButton(context, "📞 拨打", v -> dial(context, value)));
                row.addView(actionButton(context, "💬 短信", v -> sms(context, value)));
            } else if ("email".equals(type)) {
                row.addView(actionButton(context, "✉️ 发邮件", v -> email(context, value)));
            } else if ("map".equals(type)) {
                row.addView(actionButton(context, "🗺️ 打开地图", v -> openMap(context, value)));
            }
            if (row.getChildCount() > 0) {
                card.addView(row);
            }
        }
        return card;
    }

    private TextView actionButton(Context context, String text, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(ComponentColors.accent(context));
        btn.setPadding(0, 0, dp(context, 16), 0);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void dial(Context context, String phone) {
        try {
            context.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
        } catch (Exception e) {
            Toast.makeText(context, "无法打开拨号", Toast.LENGTH_SHORT).show();
        }
    }

    private void sms(Context context, String phone) {
        try {
            context.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone)));
        } catch (Exception e) {
            Toast.makeText(context, "无法打开短信", Toast.LENGTH_SHORT).show();
        }
    }

    private void email(Context context, String address) {
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address));
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开邮件", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMap(Context context, String query) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(query))));
        } catch (Exception e) {
            Toast.makeText(context, "无法打开地图", Toast.LENGTH_SHORT).show();
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
