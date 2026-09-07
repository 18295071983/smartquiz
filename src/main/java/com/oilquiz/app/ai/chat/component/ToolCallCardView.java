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
 * 工具执行组件：单行显示，执行中显示工具名+状态，执行后追加简略结果摘要。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "toolName": "ai_weather",
 *   "status": "running | success | failed",
 *   "summary": "北京 26℃ 晴"   // 执行后的简略结果摘要
 * }
 * </pre>
 */
public class ToolCallCardView implements ChatComponent {

    private static final int MAX_SUMMARY_LEN = 50;

    @Override
    public String getType() {
        return "tool_call";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && !TextUtils.isEmpty(data.props.optString("toolName", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String toolName = p.optString("toolName", "工具");
        String status = p.optString("status", "running");
        // 优先读取完整 result 生成单行摘要；兼容旧数据直接读 summary 字段
        String summary = summarize(p.optString("result", p.optString("summary", "")));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 2));

        TextView iconTv = new TextView(context);
        iconTv.setText("🔧");
        iconTv.setTextSize(12);
        iconTv.setPadding(0, 0, dp(context, 5), 0);
        row.addView(iconTv);

        TextView nameTv = new TextView(context);
        nameTv.setText(toolName);
        nameTv.setTextSize(12);
        nameTv.setTextColor(ComponentColors.textSecondary(context));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(nameTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 状态 + 简略摘要
        TextView statusTv = new TextView(context);
        String statusText;
        int statusColor;
        switch (status) {
            case "success":
                statusText = " ✅ " + summary;
                statusColor = ComponentColors.success(context);
                break;
            case "failed":
                statusText = " ❌ " + (TextUtils.isEmpty(summary) ? "失败" : summary);
                statusColor = ComponentColors.error(context);
                break;
            default:
                statusText = " ⏳ 执行中";
                statusColor = ComponentColors.accent(context);
                break;
        }
        statusTv.setText(statusText);
        statusTv.setTextSize(11);
        statusTv.setTextColor(statusColor);
        statusTv.setSingleLine(true);
        statusTv.setEllipsize(TextUtils.TruncateAt.END);
        statusTv.setMaxLines(1);
        row.addView(statusTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    /** 简略摘要：清理 JSON 标记、压缩空白、截断 */
    public static String summarize(String result) {
        if (result == null || result.trim().isEmpty()) return "";
        String clean = result.trim();
        // 去除 JSON 花括号与引号（如 {"city":"北京","temp":"26"} → city:北京,temp:26）
        clean = clean.replaceAll("[{}\"]", "");
        // 压缩空白
        clean = clean.replaceAll("\\s+", " ");
        // 去掉常见前缀标记（成功/失败标签）
        clean = clean.replaceAll("^(成功|失败|完成|查询|执行)[:：]?\\s*", "");
        if (clean.length() > MAX_SUMMARY_LEN) {
            clean = clean.substring(0, MAX_SUMMARY_LEN) + "...";
        }
        return clean;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
