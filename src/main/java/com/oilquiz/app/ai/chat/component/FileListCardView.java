package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文件列表卡片组件（目录浏览结果，B1-4 起接 {@link CardShell}）。
 *
 * <p>支持应用内文件夹导航：点目录项直接在卡内刷新列表，「上一级」收进 footer 动作；
 * 文件项点击仍走 {@link ComponentActions#openLink} 打开。图标不再是 emoji（📁/🖼/⬆），
 * 与 {@link FileCardView} 共用 {@link FileGlyph} 一套口径。
 *
 * <p>条目超过 {@link #MAX_VISIBLE_ROWS} 时截断并给出总数（与 table_card 同策略：
 * 卡内放纵向滚动会和聊天列表抢手势）。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "目录内容",
 *   "path": "/sdcard/Documents",
 *   "files": [{"name":"report.pdf","size":"1.2 MB","type":"file|dir","path":"/sdcard/Documents/report.pdf"}]
 * }
 * </pre>
 *
 * <p>条目 {@code path} 期望是<b>完整文件路径</b>；模型实际常写成"目录 + 单独一个 name 字段"
 * （如 {@code path:"/…/files/"} + {@code name:"x.mp4"}，真机卡片实测）。这种形态由
 * {@link MediaPathResolver#resolveEntryToFile} 在渲染时补齐成真实文件（历史会话里的旧卡片
 * 一起治好），目录/文件的判定也随之以上盘结果为准，不按 {@code type} 字面判——
 * 模型写过 {@code type:"视频 720P 无水印"} 这种自由文本。</p>
 */
public class FileListCardView implements ChatComponent {

    /** 卡内最多显示的条目数 */
    private static final int MAX_VISIBLE_ROWS = 30;

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
        CardShell shell = CardShell.create(context);
        renderInto(context, shell, data.props != null ? data.props : new JSONObject());
        return shell;
    }

    /** 初始渲染与导航刷新共用：同一张壳，只重填 header/body/footer */
    private void renderInto(Context context, CardShell shell, JSONObject props) {
        String title = props.optString("title", "");
        String path = props.optString("path", "");
        JSONArray files = props.optJSONArray("files");
        int count = files == null ? 0 : files.length();

        shell.setHeader(TextUtils.isEmpty(title) ? path : title,
                count > 0 ? context.getString(R.string.card_shell_item_count, count) : null);
        shell.setSubtitle(TextUtils.isEmpty(title) ? null : path);
        shell.clearBody().clearActions();

        if (!TextUtils.isEmpty(path) && !"/".equals(path)) {
            final String upTarget = parentPath(path);
            final String baseTitle = title;
            shell.addAction(context.getString(R.string.file_list_up), false,
                    () -> navigate(context, shell, upTarget, baseTitle));
        }

        if (count == 0) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.file_list_empty));
            return;
        }
        int visible = Math.min(count, MAX_VISIBLE_ROWS);
        for (int i = 0; i < visible; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            shell.addBody(buildRow(context, f));
        }
        if (count > visible) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.file_list_truncated, count, visible));
        }
    }

    /** 一行：类型图标 + 名称（+ 路径）+ 大小 */
    private View buildRow(Context context, JSONObject f) {
        String type = f.optString("type", "file");
        final String rawPath = f.optString("path", "");
        final String name = f.optString("name", "");
        String size = f.optString("size", "");

        // 条目定位统一走 MediaPathResolver：模型常把 path 写成目录、文件名只留在 name 里
        // （真机卡片实测），按原 path 打开就是"暂不支持打开文件夹"；这里补齐成真实文件，
        // 目录/文件的判定也随之以上盘结果为准，不再信模型随手写的 type（见过"视频 720P 无水印"）。
        // 渲染期只做 exists() 判断，全量工作区搜索留在点击时做一次（一卡 30 行，逐行扫盘会卡）。
        java.io.File target = MediaPathResolver.resolveEntryOnDisk(rawPath, name);
        final String rowPath = target != null ? target.getAbsolutePath()
                : (!TextUtils.isEmpty(rawPath) ? rawPath : name.trim());
        boolean isDir = target != null ? target.isDirectory() : "dir".equals(type);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ComponentTheme.dp(context, 40));
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        int iconRes = FileGlyph.iconRes(type, name);
        ImageView icon = new ImageView(context);
        int iconSize = ComponentTheme.dp(context, 18);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(ComponentTheme.dp(context, 8));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconRes);
        ImageViewCompat.setImageTintList(icon,
                ColorStateList.valueOf(FileGlyph.tint(context, iconRes)));
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView nameTv = new TextView(context);
        nameTv.setText(TextUtils.isEmpty(name)
                ? context.getString(R.string.file_list_unnamed) : name);
        nameTv.setTextSize(ComponentTheme.bodyTextSize());
        nameTv.setTextColor(ComponentTheme.textPrimary(context));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textCol.addView(nameTv);
        row.addView(textCol);

        if (!TextUtils.isEmpty(size)) {
            TextView sizeTv = new TextView(context);
            sizeTv.setText(size);
            sizeTv.setTextSize(ComponentTheme.metaTextSize());
            sizeTv.setTextColor(ComponentTheme.textTertiary(context));
            sizeTv.setPadding(ComponentTheme.dp(context, 8), 0, 0, 0);
            row.addView(sizeTv);
        }

        // 目录：卡内进入；文件：交回应用打开；没有路径的条目不可点（点了没反应最伤信任）
        if (!TextUtils.isEmpty(rowPath)) {
            row.setClickable(true);
            row.setFocusable(true);
            if (isDir) {
                row.setOnClickListener(v -> navigate(context,
                        (CardShell) findShell(row), rowPath, ""));
            } else {
                row.setOnClickListener(v -> ComponentActions.openLink(context, rowPath));
            }
        }
        return row;
    }

    /** 应用内进入目录：读取目录内容并刷新卡片（相对路径也先过工作区解析） */
    private void navigate(Context context, CardShell shell, String dirPath, String baseTitle) {
        if (TextUtils.isEmpty(dirPath)) return;
        File dir = MediaPathResolver.resolveToFile(context, dirPath);
        if (dir == null) {
            dir = new File(dirPath.startsWith("file://")
                    ? HtmlSandbox.filePathFromUrl(dirPath) : dirPath);
        }
        if (!dir.exists() || !dir.isDirectory()) {
            shell.setState(CardShell.State.ERROR,
                    context.getString(R.string.file_list_dir_missing));
            return;
        }
        File[] children = dir.listFiles();
        JSONArray items = new JSONArray();
        if (children != null) {
            List<File> dirs = new ArrayList<>();
            List<File> plain = new ArrayList<>();
            for (File c : children) {
                if (c.isDirectory()) dirs.add(c); else plain.add(c);
            }
            dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            plain.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File d : dirs) items.put(entry(d.getName(), d.getAbsolutePath(), "dir", ""));
            for (File f : plain) {
                items.put(entry(f.getName(), f.getAbsolutePath(), "file", formatSize(f.length())));
            }
        }
        try {
            JSONObject np = new JSONObject();
            np.put("title", baseTitle);
            np.put("path", dir.getAbsolutePath());
            np.put("files", items);
            renderInto(context, shell, np);
        } catch (Exception ignore) {
            // 组装失败保持原视图，不清空卡片
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

    /** 从行 View 往上找回所属的 CardShell（导航要在同一张卡里刷新） */
    private static CardShell findShell(View view) {
        View v = view;
        while (v != null) {
            if (v instanceof CardShell) return (CardShell) v;
            if (!(v.getParent() instanceof View)) return null;
            v = (View) v.getParent();
        }
        return null;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1048576.0));
    }
}
