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

    /**
     * 是否由本卡渲染。
     *
     * <p><b>2026-11-16 放宽</b>：以前要求 {@code code} 或 {@code command} 必填，字段名一换
     * （content/text/script/source…）就判"数据不匹配"，整卡退回通用兜底卡（用户看到原始 JSON）。
     * 代码卡的关键是"这段文本用等宽块展示"，不该因字段命名拒渲染 —— 这里只要求 props 非空，
     * 正文在 createView 里按别名取，取不到就展示格式化后的 props 本身（比裸 JSON 兜底卡可读）。</p>
     */
    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.length() > 0;
    }

    /** 代码正文的字段别名：DSH 侧历史上用过多种命名，按优先级取第一个非空。 */
    private static final String[] CODE_KEYS = {
            "code", "command", "cmd", "source", "script", "snippet", "content", "text", "body"
    };

    /** 取代码正文；全都为空时退回格式化 props（保证永远有内容可显示）。 */
    private static String resolveCodeBody(JSONObject p) {
        for (String k : CODE_KEYS) {
            String v = p.optString(k, "");
            if (!TextUtils.isEmpty(v)) {
                return v;
            }
        }
        try {
            return p.toString(2);
        } catch (Throwable t) {
            return String.valueOf(p);
        }
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String language = p.optString("language", TextUtils.isEmpty(p.optString("command", "")) ? "" : "shell");
        // DSH 的命令卡（command/run）复用本组件：命令体走 command 字段，等同 code
        String code = resolveCodeBody(p);
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
        /**
         * 2026-11-14：承接原来 dsh_command 的状态（running/success/failed）。
         * 命令结果不再单独维护一套原生卡 —— 统一用 code_card，状态并入头部标签。
         */
        String status = p.optString("status", "");
        if (!TextUtils.isEmpty(status)) {
            langLabel += " · " + ("running".equals(status) ? "运行中"
                    : "failed".equals(status) ? "失败" : "完成");
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

        // 执行输出（2026-11-16）：python/js/shell 的 stdout/stderr/exitCode。
        // 这四个都是可选字段：缺了整段不显示（老数据零影响），有了才占位。
        String stdout = p.optString("stdout", "");
        String stderr = p.optString("stderr", "");
        String exitCode = p.optString("exitCode", p.optString("exit_code", ""));
        String duration = p.optString("duration", "");
        if (!TextUtils.isEmpty(stdout) || !TextUtils.isEmpty(stderr) || !TextUtils.isEmpty(exitCode)) {
            StringBuilder head = new StringBuilder("输出");
            if (!TextUtils.isEmpty(exitCode)) head.append(" · 退出码 ").append(exitCode);
            if (!TextUtils.isEmpty(duration)) head.append(" · ").append(duration);
            TextView outHead = new TextView(context);
            outHead.setText(head.toString());
            outHead.setTextSize(10);
            outHead.setTextColor(ThemeColors.get(R.color.hc_ff94a3b8));
            outHead.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 2));
            card.addView(outHead);

            String shown = TextUtils.isEmpty(stderr) ? stdout : stderr;
            TextView outTv = new TextView(context);
            outTv.setText(shown);
            outTv.setTextSize(11);
            outTv.setTextColor(stderr.isEmpty()
                    ? ThemeColors.get(R.color.hc_ffe2e8f0)
                    : ThemeColors.get(R.color.hc_ffe11d48));
            outTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            outTv.setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 10));
            outTv.setTextIsSelectable(true);
            card.addView(outTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

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
