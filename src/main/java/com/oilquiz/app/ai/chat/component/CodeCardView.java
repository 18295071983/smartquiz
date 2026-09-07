package com.oilquiz.app.ai.chat.component;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * 代码卡片组件：深色代码块 + 语言标签 + 一键复制。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "language": "java",
 *   "code": "public class A {}",
 *   "title": "示例（可选）"
 * }
 * </pre>
 */
public class CodeCardView implements ChatComponent {

    @Override
    public String getType() {
        return "code_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("code", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String language = p.optString("language", "");
        String code = p.optString("code", "");
        String title = p.optString("title", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, 0);
        card.setBackground(codeBackground(context));

        // 头部：语言标签 + 复制按钮
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 10), dp(context, 6), dp(context, 6), dp(context, 6));
        header.setBackgroundColor(ThemeColors.get(R.color.hc_ff1e293b));

        String langLabel = TextUtils.isEmpty(language) ? "代码" : language.toUpperCase();
        if (!TextUtils.isEmpty(title)) {
            langLabel = title + " · " + langLabel;
        }
        TextView langTv = new TextView(context);
        langTv.setText(langLabel);
        langTv.setTextSize(10);
        langTv.setTextColor(ThemeColors.get(R.color.hc_ff94a3b8));
        header.addView(langTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = new TextView(context);
        copyBtn.setText("复制");
        copyBtn.setTextSize(11);
        copyBtn.setTextColor(ThemeColors.get(R.color.hc_ff60a5fa));
        copyBtn.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        copyBtn.setBackground(copyButtonBackground(context));
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("code", code));
            Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show();
        });
        header.addView(copyBtn);
        card.addView(header);

        // 代码内容
        TextView codeTv = new TextView(context);
        codeTv.setText(code);
        codeTv.setTextSize(12);
        codeTv.setTextColor(ThemeColors.get(R.color.hc_ffe2e8f0));
        codeTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        codeTv.setLineSpacing(0, 1.3f);
        codeTv.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 10));
        codeTv.setTextIsSelectable(true);
        card.addView(codeTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return card;
    }

    private static android.graphics.drawable.Drawable codeBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ThemeColors.get(R.color.hc_ff0f172a));
        gd.setCornerRadius(dp(context, 10));
        return gd;
    }

    private static android.graphics.drawable.Drawable copyButtonBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ThemeColors.get(R.color.hc_1a60a5fa));
        gd.setCornerRadius(dp(context, 5));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
