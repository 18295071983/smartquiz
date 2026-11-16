package com.oilquiz.app.ai.chat.component;

import com.oilquiz.app.theme.Tokens;

import android.content.Context;
import android.text.TextUtils;
import com.oilquiz.app.R;
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
        String args = p.optString("args", "");
        String rawResult = p.optString("result", "");
        String summary = summarize(TextUtils.isEmpty(rawResult)
                ? p.optString("summary", "") : rawResult);
        boolean failed = "failed".equals(status) || "error".equals(status);
        boolean running = !failed && !"success".equals(status) && !"completed".equals(status);

        String displayLabel = com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(toolName,
                com.oilquiz.app.ai.chat.ToolMetaRegistry.actionOf(args));
        if (TextUtils.isEmpty(displayLabel)) displayLabel = "工具";

        String detail;
        int detailColor;
        if (failed) {
            detail = describeFailure(p, summary);
            detailColor = ComponentColors.error(context);
        } else if (running) {
            String hint = p.optString("statusText", "");
            detail = TextUtils.isEmpty(hint) ? describeArgs(args) : hint;
            detailColor = ComponentColors.accent(context);
        } else {
            detail = TextUtils.isEmpty(summary) ? describeArgs(args) : summary;
            detailColor = ComponentColors.textSecondary(context);
        }

        // 2026-11-16 重设计：两行结构（与聊天工具明细行同一套信息层级）
        // 第一行：工具图标 + 工具名（粗体）+ 状态标签（语义色）；第二行：参数/结果摘要。
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Tokens.spaceXs(context), Tokens.spaceHairline(context),
                Tokens.spaceXs(context), Tokens.spaceHairline(context));

        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView iconIv = new android.widget.ImageView(context);
        iconIv.setImageResource(R.drawable.ic_ai_tool);
        androidx.core.widget.ImageViewCompat.setImageTintList(iconIv,
                android.content.res.ColorStateList.valueOf(
                        failed ? ComponentColors.error(context) : ComponentColors.accent(context)));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Tokens.spaceLg(context), Tokens.spaceLg(context));
        iconLp.rightMargin = Tokens.spaceXs(context);
        row1.addView(iconIv, iconLp);

        TextView nameTv = new TextView(context);
        nameTv.setText(displayLabel);
        nameTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.chat_v2_think_text));
        nameTv.setTextColor(ComponentColors.textPrimary(context));
        nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row1.addView(nameTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView statusTv = new TextView(context);
        statusTv.setTag("tool_status");
        statusTv.setText(running ? "运行中" : failed ? "失败" : "完成");
        statusTv.setTextSize(Tokens.textSizeSp(statusTv.getContext(), Tokens.TEXT_MICRO));
        statusTv.setTextColor(running ? ComponentColors.accent(context)
                : failed ? ComponentColors.error(context) : ComponentColors.success(context));
        statusTv.setTag("tool_status");
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.leftMargin = Tokens.spaceXs(context);
        row1.addView(statusTv, statusLp);
        root.addView(row1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView detailTv = new TextView(context);
        detailTv.setTag("tool_detail");
        detailTv.setText(detail);
        detailTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.chat_v2_status_text));
        detailTv.setTextColor(detailColor);
        detailTv.setMaxLines(2);
        detailTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = Tokens.spaceHairline(context);
        root.addView(detailTv, detailLp);

        // 长摘要可点击展开/收起（默认 2 行；展开 10 行），不额外加按钮占高度
        boolean longDetail = detail != null && detail.length() > 80;
        if (longDetail) {
            root.setClickable(true);
            root.setFocusable(true);
            root.setOnClickListener(v -> {
                boolean expanded = detailTv.getMaxLines() != 2;
                detailTv.setMaxLines(expanded ? 2 : 10);
                detailTv.setEllipsize(expanded ? TextUtils.TruncateAt.END : null);
            });
            androidx.core.view.ViewCompat.setStateDescription(root, "点击展开或收起工具详情");
        }
        return root;
    }

    /** 参数摘要：args 可能是 JSON/空；压成 key=value, … 一行（最多 80 字）。 */
    static String describeArgs(String args) {
        if (TextUtils.isEmpty(args)) return "";
        String s = args.trim();
        try {
            JSONObject obj = s.startsWith("{") ? new JSONObject(s) : null;
            if (obj != null) {
                StringBuilder sb = new StringBuilder();
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = String.valueOf(obj.opt(k));
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(k).append('=').append(v);
                    if (sb.length() > 80) break;
                }
                s = sb.toString();
            }
        } catch (Throwable ignored) {
            // 非 JSON 直接展示原文
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 失败详情：优先结构化字段（stderr/exit_code），否则取原始结果；附耗时。 */
    static String describeFailure(JSONObject p, String summary) {
        String detail = p.optString("stderr", "");
        String exit = p.optString("exitCode", "");
        if (TextUtils.isEmpty(detail)) {
            String raw = p.optString("result", "");
            JSONObject obj = null;
            try {
                if (raw.startsWith("{")) obj = new JSONObject(raw);
            } catch (Throwable ignored) {
            }
            if (obj != null) {
                detail = obj.optString("stderr", "");
                if (TextUtils.isEmpty(detail)) detail = obj.optString("error", "");
                if (exit.isEmpty()) exit = obj.optString("exit_code", obj.optString("exitCode", ""));
            } else {
                detail = raw;
            }
        }
        // 结构化字段优先：summary 是把整段 result 压平来的，里面混着 stdout/stderr，再拼一次会重复
        boolean structured = !TextUtils.isEmpty(detail) && !detail.equals(p.optString("result", ""));
        String head = structured ? "失败" : (TextUtils.isEmpty(summary) ? "失败" : summary);
        if (!exit.isEmpty()) head = head + "（退出码 " + exit + "）";
        if (!TextUtils.isEmpty(detail)) {
            detail = detail.replaceAll("\\s+", " ").trim();
            if (detail.length() > 120) detail = detail.substring(0, 120) + "...";
            head = head + "｜" + detail;
        }
        String dur = formatDuration(p.optLong("durationMs", 0L));
        return TextUtils.isEmpty(dur) ? head : head + "（" + dur + "）";
    }

    /** 1234ms → 1.2s；0 → 空 */
    static String formatDuration(long ms) {
        if (ms <= 0L) return "";
        if (ms < 1000L) return ms + "ms";
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
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
