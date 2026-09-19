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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 *
 * 2026-09-14 增强：支持【应用内文件夹导航】——
 * - 点击目录项（type=dir）→ 组件内直接读取该目录并刷新列表（不再跳到系统文件管理器）；
 * - 顶部提供「⬆ 上一级」返回父目录（path 存在且非根时显示）；
 * - 文件项保持点击打开（openLink）。
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
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));
        buildContent(context, card, p);
        return card;
    }

    /** 重建卡片内容（初始渲染 / 导航刷新共用） */
    private void buildContent(Context context, LinearLayout card, JSONObject props) {
        card.removeAllViews();
        String title = props.optString("title", "");
        String path = props.optString("path", "");
        JSONArray files = props.optJSONArray("files");

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

        // 上一级导航（path 存在且非根时）
        if (!TextUtils.isEmpty(path) && !"/".equals(path)) {
            LinearLayout upRow = makeRow(context, "⬆", "上一级", "");
            upRow.setOnClickListener(v -> navigateTo(context, card, parentPath(path), title));
            card.addView(upRow, rowLayoutParams());
        }

        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;

                String type = f.optString("type", "file");
                final String rowPath = f.optString("path", "");
                boolean isDir = "dir".equals(type);
                String name = f.optString("name", "");

                LinearLayout row = makeRow(context,
                        isDir ? "📁" : fileIcon(name),
                        TextUtils.isEmpty(name) ? "未命名" : name,
                        rowPath);
                // 目录项：应用内进入浏览；文件项：点击打开（path 缺失时不可点）
                if (!TextUtils.isEmpty(rowPath)) {
                    row.setClickable(true);
                    row.setFocusable(true);
                    if (isDir) {
                        row.setOnClickListener(v -> navigateTo(context, card, rowPath, title));
                    } else {
                        row.setOnClickListener(v ->
                                com.oilquiz.app.ai.chat.component.ComponentActions.openLink(context, rowPath));
                    }
                }

                // 大小（文件显示；目录可显示条目占位由导航后填充）
                String size = f.optString("size", "");
                if (!TextUtils.isEmpty(size)) {
                    TextView sizeTv = new TextView(context);
                    sizeTv.setText(size);
                    sizeTv.setTextSize(11);
                    sizeTv.setTextColor(ComponentColors.textSecondary(context));
                    sizeTv.setPadding(dp(context, 8), 0, 0, 0);
                    row.addView(sizeTv);
                }

                card.addView(row, rowLayoutParams());
            }
        }
    }

    /** 应用内进入目录：读取该目录内容并刷新卡片（纯组件内导航，不跳系统） */
    private void navigateTo(Context context, LinearLayout card, String dirPath, String baseTitle) {
        if (dirPath == null || dirPath.isEmpty()) return;
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        JSONArray items = new JSONArray();
        if (children != null) {
            // 目录优先、文件在后（同目录内按名称排序）
            List<File> dirs = new ArrayList<>();
            List<File> files = new ArrayList<>();
            for (File c : children) {
                if (c.isDirectory()) dirs.add(c);
                else files.add(c);
            }
            dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File d : dirs) {
                items.put(entry(d.getName(), d.getAbsolutePath(), "dir", ""));
            }
            for (File f : files) {
                items.put(entry(f.getName(), f.getAbsolutePath(), "file", formatSize(f.length())));
            }
        }
        try {
            JSONObject np = new JSONObject();
            np.put("title", baseTitle);
            np.put("path", dirPath);
            np.put("files", items);
            buildContent(context, card, np);
        } catch (Exception ignore) {
        }
    }

    private static JSONObject entry(String name, String path, String type, String size) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("path", path);
            o.put("type", type);
            o.put("size", size);
        } catch (Exception ignore) {
        }
        return o;
    }

    private static String parentPath(String path) {
        if (path == null) return "/";
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return "/";
        return path.substring(0, idx);
    }

    /** 构造一行（图标 + 文本列），大小由调用方追加 */
    private LinearLayout makeRow(Context context, String icon, String name, String pathText) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 4), 0, dp(context, 4));

        TextView iconTv = new TextView(context);
        iconTv.setText(icon);
        iconTv.setTextSize(16);
        iconTv.setPadding(0, 0, dp(context, 8), 0);
        row.addView(iconTv);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView nameTv = new TextView(context);
        nameTv.setText(name);
        nameTv.setTextSize(13);
        nameTv.setTextColor(ComponentColors.textPrimary(context));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textCol.addView(nameTv);

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
        return row;
    }

    private static LinearLayout.LayoutParams rowLayoutParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
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
