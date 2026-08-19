package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 文件列表卡片组件：图标 + 文件名 + 大小 + 路径（文件/目录浏览结果）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "目录内容",
 *   "path": "/sdcard/Documents",            // 可选，当前路径
 *   "files": [
 *     {"name":"report.pdf","size":"1.2 MB","type":"file","path":"/sdcard/..."},
 *     {"name":"images","size":"","type":"dir"}
 *   ]
 * }
 * </pre>
 */
public class FileListCardView implements ChatComponent {

    @Override
    public String getType() {
        return "file_list";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("files") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        String path = p.optString("path", "");
        JSONArray files = p.optJSONArray("files");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 标题 + 路径
        if (!TextUtils.isEmpty(title) || !TextUtils.isEmpty(path)) {
            TextView titleTv = new TextView(context);
            if (!TextUtils.isEmpty(title)) {
                titleTv.setText(title + (TextUtils.isEmpty(path) ? "" : " · " + path));
            } else {
                titleTv.setText(path);
            }
            titleTv.setTextSize(13);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(context, 4), 0, dp(context, 4));

                String type = f.optString("type", "file");
                final String rowPath = f.optString("path", "");
                // 文件行可点击打开（file/目录均可，path 缺失时不可点）
                if (!TextUtils.isEmpty(rowPath)) {
                    row.setClickable(true);
                    row.setFocusable(true);
                    row.setOnClickListener(v ->
                            com.oilquiz.app.ai.chat.component.ComponentActions.openLink(context, rowPath));
                }

                String icon = "dir".equals(type) ? "📁" : fileIcon(f.optString("name", ""));
                TextView iconTv = new TextView(context);
                iconTv.setText(icon);
                iconTv.setTextSize(16);
                iconTv.setPadding(0, 0, dp(context, 8), 0);
                row.addView(iconTv);

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                String name = f.optString("name", "");
                TextView nameTv = new TextView(context);
                nameTv.setText(TextUtils.isEmpty(name) ? "未命名" : name);
                nameTv.setTextSize(13);
                nameTv.setTextColor(ComponentColors.textPrimary(context));
                nameTv.setSingleLine(true);
                nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                textCol.addView(nameTv);

                String pathText = f.optString("path", "");
                if (!TextUtils.isEmpty(pathText)) {
                    TextView pathTv = new TextView(context);
                    pathTv.setText(pathText);
                    pathTv.setTextSize(10);
                    pathTv.setTextColor(ComponentColors.textTertiary(context));
                    pathTv.setSingleLine(true);
                    pathTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    textCol.addView(pathTv);
                }

                row.addView(textCol);

                String size = f.optString("size", "");
                if (!TextUtils.isEmpty(size)) {
                    TextView sizeTv = new TextView(context);
                    sizeTv.setText(size);
                    sizeTv.setTextSize(11);
                    sizeTv.setTextColor(ComponentColors.textSecondary(context));
                    sizeTv.setPadding(dp(context, 8), 0, 0, 0);
                    row.addView(sizeTv);
                }

                card.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        return card;
    }

    private String fileIcon(String name) {
        if (name == null) return "📄";
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "📘";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "📗";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📙";
        if (lower.endsWith(".zip") || lower.endsWith(".rar")) return "🗜️";
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".gif")) return "🖼️";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return "🎵";
        if (lower.endsWith(".mp4") || lower.endsWith(".avi")) return "🎬";
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".py")) return "💻";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "📝";
        return "📄";
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
