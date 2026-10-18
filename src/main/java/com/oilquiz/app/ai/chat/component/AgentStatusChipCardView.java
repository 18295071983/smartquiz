package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.oilquiz.app.R;

import org.json.JSONObject;

/**
 * Agent 状态徽章组件（agent_status）：流式生成期间的气泡内步骤行。
 *
 * <p>Track B0-4：12 个写死颜色（浅色模式下发紫/发绿看不清）改为 {@link ComponentTheme}
 * 语义色派生；🧠/🔧/✅/❌ 换成矢量图标；模型传来的 text 里的 emoji 前缀剥掉，
 * 保证这一行与新版执行 UI 的图标语言一致。
 *
 * <p>props 示例：
 * <pre>
 * {
 *   "status": "thinking",   // thinking / tool / done / error
 *   "text": "调用 get_weather",
 *   "spinning": true,       // 是否显示小转圈
 *   "toolName": "get_weather"  // 可选，若 status=tool 且未提供 text 时自动生成
 * }
 * </pre>
 */
public class AgentStatusChipCardView implements ChatComponent {

    @Override
    public String getType() {
        return "agent_status";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        return !TextUtils.isEmpty(data.props.optString("text", ""))
                || !TextUtils.isEmpty(data.props.optString("status", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String status = p.optString("status", "thinking");
        boolean spinning = p.optBoolean("spinning",
                "thinking".equals(status) || "tool".equals(status));
        String text = p.optString("text", "");
        if (TextUtils.isEmpty(text)) {
            switch (status) {
                case "tool":
                    String tool = p.optString("toolName",
                            context.getString(R.string.agent_status_default_tool));
                    text = context.getString(R.string.agent_status_tool, tool);
                    break;
                case "done":
                    text = context.getString(R.string.agent_status_done);
                    break;
                case "error":
                    text = context.getString(R.string.agent_status_error);
                    break;
                case "thinking":
                default:
                    text = context.getString(R.string.agent_status_thinking);
                    break;
            }
        } else {
            text = stripEmoji(text);
        }

        int accent = accentColor(context, status);

        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int padV = ComponentTheme.dp(context, 4);
        int padH = ComponentTheme.dp(context, 8);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackground(stateChip(context, accent));
        chip.setMinimumHeight(ComponentTheme.dp(context, 26));

        ImageView icon = new ImageView(context);
        int iconSize = ComponentTheme.dp(context, 13);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(ComponentTheme.dp(context, 5));
        icon.setLayoutParams(iconLp);
        Drawable d = ContextCompat.getDrawable(context, iconFor(status));
        if (d != null) {
            d = DrawableCompat.wrap(d.mutate());
            DrawableCompat.setTintList(d, ColorStateList.valueOf(accent));
            icon.setImageDrawable(d);
        }
        icon.setContentDescription(status);
        chip.addView(icon);

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.metaTextSize());
        label.setTextColor(accent);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (spinning) {
            ProgressBar pb = new ProgressBar(context, null,
                    android.R.attr.progressBarStyleSmall);
            int size = ComponentTheme.dp(context, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(ComponentTheme.dp(context, 6));
            pb.setLayoutParams(lp);
            pb.setIndeterminateTintList(ColorStateList.valueOf(accent));
            chip.addView(pb);
        }

        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outer.bottomMargin = ComponentTheme.dp(context, 4);
        chip.setLayoutParams(outer);
        return chip;
    }

    private static int iconFor(String status) {
        switch (status) {
            case "tool":
                return R.drawable.ic_ai_tool_call;
            case "done":
                return R.drawable.ic_ai_success;
            case "error":
                return R.drawable.ic_ai_error;
            case "thinking":
            default:
                return R.drawable.ic_ai_thinking;
        }
    }

    /** 状态语义色：只有 success/brand/danger/textSecondary 四种，跟随主题 */
    private static int accentColor(Context context, String status) {
        switch (status) {
            case "done":
                return ComponentTheme.success(context);
            case "error":
                return ComponentTheme.danger(context);
            case "tool":
                return ComponentTheme.brand(context);
            case "thinking":
            default:
                return ComponentTheme.textSecondary(context);
        }
    }

    /** 状态徽章底：同一语义色的低透明底 + 中透明描边，不再各状态写死色值 */
    private static GradientDrawable stateChip(Context context, int accent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(ComponentTheme.dp(context, 10));
        gd.setColor(ComponentTheme.withAlpha(accent, 0x1A));
        gd.setStroke(ComponentTheme.dp(context, 1), ComponentTheme.withAlpha(accent, 0x66));
        return gd;
    }

    /**
     * 剥掉文案里的 emoji 前缀（模型常照抄旧提示词输出"🔧 调用工具"）。
     *
     * <p>只按码点范围剔除，不动中文与正常符号，剔完再收敛多余空格。
     */
    private static String stripEmoji(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            if (!isEmoji(cp)) sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // 图形 emoji 主区
                || (cp >= 0x2600 && cp <= 0x27BF)  // 杂项符号与装饰符
                || cp == 0xFE0F || cp == 0x200D    // 变体选择符 / 零宽连接符
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }
}
