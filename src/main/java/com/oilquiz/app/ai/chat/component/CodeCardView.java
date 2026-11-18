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
import com.oilquiz.app.ai.chat.component.ComponentColors;
import com.oilquiz.app.theme.Tokens;
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
        header.setPadding(Tokens.spaceMd(context) + Tokens.spaceHairline(context), Tokens.spaceSm(context),
                Tokens.spaceSm(context), Tokens.spaceSm(context));
        header.setBackgroundColor(ComponentColors.background(context));

        String langLabel = TextUtils.isEmpty(language) ? "代码" : language.toUpperCase();
        if (!TextUtils.isEmpty(title)) {
            langLabel = title + " · " + langLabel;
        }
        /**
         * 2026-11-14：承接原来 dsh_command 的状态（running/success/failed）。
         * 命令结果不再单独维护一套原生卡 —— 统一用 code_card，状态并入头部标签。
         */
        String status = p.optString("status", "");
        final boolean statusFailed = "failed".equals(status) || "error".equals(status);
        final boolean statusRunning = "running".equals(status);
        if (!TextUtils.isEmpty(status)) {
            langLabel += " · " + ("running".equals(status) ? "运行中"
                    : "failed".equals(status) ? "失败" : "完成");
        }
        TextView langTv = new TextView(context);
        langTv.setText(langLabel);
        langTv.setTextSize(Tokens.textSizeSp(langTv.getContext(), Tokens.TEXT_MICRO));
        langTv.setTextColor(statusFailed ? ComponentColors.error(context) : (statusRunning ? ComponentColors.accent(context) : ComponentColors.textTertiary(context)));
        header.addView(langTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = new TextView(context);
        copyBtn.setText("复制");
        copyBtn.setTextSize(Tokens.textSizeSp(copyBtn.getContext(), Tokens.TEXT_CAPTION));
        copyBtn.setTextColor(ComponentColors.accent(context));
        copyBtn.setPadding(Tokens.spaceMd(context), Tokens.spaceHairline(context),
                Tokens.spaceMd(context), Tokens.spaceHairline(context));
        copyBtn.setBackground(copyButtonBackground(context));
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("code", code));
            Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show();
        });
        header.addView(copyBtn);
        // 运行中：头部语言标签前一颗呼吸点（2026-11-18）。命令卡就是"执行效果"的主体，
        // 之前只有一行静止的"运行中"；点用系统时钟相位，卡片重建后仍连续。
        if (statusRunning) {
            com.oilquiz.app.ui.widget.LiveDotsView dot =
                    new com.oilquiz.app.ui.widget.LiveDotsView(context);
            dot.setDotCount(1);
            dot.setDotColor(ComponentColors.accent(context));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dotLp.gravity = Gravity.CENTER_VERTICAL;
            dotLp.rightMargin = Tokens.spaceSm(context);
            header.addView(dot, 0, dotLp);
            dot.start();
        }
        card.addView(header);

        // 代码内容
        TextView codeTv = new TextView(context);
        codeTv.setText(code);
        codeTv.setTextSize(Tokens.textSizeSp(codeTv.getContext(), Tokens.TEXT_FOOTNOTE));
        codeTv.setTextColor(ComponentColors.textPrimary(context));
        codeTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        codeTv.setLineSpacing(0, 1.3f);
        codeTv.setPadding(Tokens.spaceMd(context) + Tokens.spaceHairline(context), Tokens.spaceMd(context),
                Tokens.spaceMd(context) + Tokens.spaceHairline(context), Tokens.spaceMd(context) + Tokens.spaceHairline(context));
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
            outHead.setTextSize(Tokens.textSizeSp(outHead.getContext(), Tokens.TEXT_MICRO));
            outHead.setTextColor(ComponentColors.textTertiary(context));
            outHead.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 2));
            card.addView(outHead);

            String shown = TextUtils.isEmpty(stderr) ? stdout : stderr;
            TextView outTv = new TextView(context);
            outTv.setText(shown);
            outTv.setTextSize(Tokens.textSizeSp(outTv.getContext(), Tokens.TEXT_CAPTION));
            outTv.setTextColor(stderr.isEmpty()
                    ? ComponentColors.textSecondary(context)
                    : ComponentColors.error(context));
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
        gd.setColor(ComponentColors.fieldBg(context));
        gd.setCornerRadius(Tokens.radiusLg(context));
        gd.setStroke(Math.max(1, Tokens.spaceHairline(context) / 2), ComponentColors.border(context));
        return gd;
    }

    private static android.graphics.drawable.Drawable copyButtonBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(withAlpha(ComponentColors.accent(context), 0.14f));
        gd.setCornerRadius(Tokens.radiusSm(context));
        return gd;
    }

    /** 给主题色叠透明度（复制按钮底色等）。 */
    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
