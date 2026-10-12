package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Agent 状态徽章组件（agent_status）：流式生成期间的气泡内步骤行。
 *
 * 取代 ChatAdapter 里硬编码的 agentStatus TextView。props 声明状态类型 + 文案 + 是否转圈，
 * 视觉与 badge 一致但语义化更强（thinking/tool/done/error）。
 *
 * <p>props 示例：
 * <pre>
 * {
 *   "status": "thinking",   // thinking / tool / done / error
 *   "text": "🔧 调用工具 get_weather",
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
        String text = p.optString("text", "");
        boolean spinning = p.optBoolean("spinning",
                "thinking".equals(status) || "tool".equals(status));
        if (TextUtils.isEmpty(text)) {
            switch (status) {
                case "tool":
                    String tool = p.optString("toolName", "工具");
                    text = "🔧 调用 " + tool;
                    break;
                case "done": text = "✅ 执行完成"; break;
                case "error": text = "❌ 执行失败"; break;
                case "thinking":
                default: text = "🧠 思考中"; break;
            }
        }

        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 6);
        chip.setPadding(pad + dp(context, 2), pad, pad + dp(context, 2), pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(context, 10));
        bg.setColor(bgColor(status));
        bg.setStroke(dp(context, 1), strokeColor(status));
        chip.setBackground(bg);

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(textColor(status));
        chip.addView(label);

        if (spinning) {
            ProgressBar pb = new ProgressBar(context, null,
                    android.R.attr.progressBarStyleSmall);
            int size = dp(context, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(dp(context, 6));
            pb.setLayoutParams(lp);
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(textColor(status)));
            chip.addView(pb);
        }

        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outer.bottomMargin = dp(context, 4);
        chip.setLayoutParams(outer);
        return chip;
    }

    private int bgColor(String s) {
        switch (s) {
            case "done": return 0x1410B981;
            case "error": return 0x1FEF4444;
            case "tool": return 0x1A6366F1;
            default: return 0x0F8B5CF6;
        }
    }
    private int strokeColor(String s) {
        switch (s) {
            case "done": return 0x6610B981;
            case "error": return 0x66EF4444;
            case "tool": return 0x666366F1;
            default: return 0x668B5CF6;
        }
    }
    private int textColor(String s) {
        switch (s) {
            case "done": return 0xFF047857;
            case "error": return 0xFFB91C1C;
            case "tool": return 0xFF4338CA;
            default: return 0xFF6D28D9;
        }
    }

    private int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
