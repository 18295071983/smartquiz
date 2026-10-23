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
import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 播放列表卡（音频/视频队列，2026-10-23）。
 *
 * <p>为什么有它：多音频结果此前是 N 张独立 {@link AudioCardView}——同一批产物不知道
 * "第几首 / 共几首"，也没有上下首。播放器已经提升为进程级单例（{@link AudioPlaybackService}），
 * 一个队列是它天然的能力：{@link AudioPlaybackManager#playQueue} 把整组曲目交给播放器，
 * 通知栏的上一首/下一首、锁屏控件随之可用。</p>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "播放列表",
 *   "items": [
 *     {"name":"歌名", "url":"https://... 或 /storage/... 或工作区相对路径",
 *      "artist":"歌手（可选）", "size":"3.2 MB（可选）"}
 *   ]
 * }
 * </pre>
 * 行点击 = 从这首开始播；正在播的那行高亮并显示 ▶。detach 只注销监听，播放继续。
 */
public class PlaylistCardView implements ChatComponent {

    private static final int MAX_ROWS = 50;

    @Override
    public String getType() {
        return "playlist";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        // 有 items 就归本卡处理：单条缺 url 时渲染空态，而不是掉进"原始 JSON"兜底
        JSONArray items = itemsOf(data.props);
        return items != null && items.length() > 0;
    }

    private static JSONArray itemsOf(JSONObject p) {
        JSONArray a = p.optJSONArray("items");
        if (a == null) a = p.optJSONArray("audios");
        if (a == null) a = p.optJSONArray("files");
        return a;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject props = data.props != null ? data.props : new JSONObject();
        List<Row> rows = parseItems(itemsOf(props));
        String title = props.optString("title", "");

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_file_media)
                .setHeader(TextUtils.isEmpty(title)
                                ? context.getString(R.string.card_playlist_title) : title,
                        rows.isEmpty() ? null
                                : context.getString(R.string.card_playlist_count, rows.size()));

        if (rows.isEmpty()) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_playlist_empty));
            return shell;
        }

        // 地址解析一次（工作区相对路径、省略号截断名），点击与高亮都用同一份
        final AudioPlaybackManager mgr = AudioPlaybackManager.get(context);
        final List<String> uris = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        for (Row r : rows) {
            String resolved = MediaPathResolver.resolve(context, r.url);
            uris.add(TextUtils.isEmpty(resolved) ? r.url : resolved);
            titles.add(r.name);
        }

        final List<TextView> indexViews = new ArrayList<>();
        final List<TextView> nameViews = new ArrayList<>();
        final boolean[] registered = {false};
        final int shown = Math.min(rows.size(), MAX_ROWS);

        for (int i = 0; i < shown; i++) {
            final int index = i;
            Row row = rows.get(i);

            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(ComponentTheme.dp(context, 44));
            line.setPadding(0, ComponentTheme.dp(context, 4), 0, ComponentTheme.dp(context, 4));
            line.setClickable(true);
            line.setFocusable(true);

            TextView idx = new TextView(context);
            idx.setText(String.format(Locale.US, "%02d", i + 1));
            idx.setTextSize(ComponentTheme.metaTextSize());
            idx.setTextColor(ComponentTheme.textTertiary(context));
            idx.setPadding(0, 0, ComponentTheme.dp(context, 10), 0);
            line.addView(idx);
            indexViews.add(idx);

            int iconRes = FileGlyph.iconRes("audio", row.name);
            ImageView icon = new ImageView(context);
            int iconSize = ComponentTheme.dp(context, 16);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconLp.setMarginEnd(ComponentTheme.dp(context, 8));
            icon.setLayoutParams(iconLp);
            icon.setImageResource(iconRes);
            ImageViewCompat.setImageTintList(icon,
                    ColorStateList.valueOf(FileGlyph.tint(context, iconRes)));
            line.addView(icon);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView nameTv = new TextView(context);
            nameTv.setText(TextUtils.isEmpty(row.name) ? "未命名" : row.name);
            nameTv.setTextSize(ComponentTheme.bodyTextSize());
            nameTv.setTextColor(ComponentTheme.textPrimary(context));
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            textCol.addView(nameTv);
            nameViews.add(nameTv);

            String sub = row.artist;
            if (!TextUtils.isEmpty(row.size)) {
                sub = TextUtils.isEmpty(sub) ? row.size : sub + " · " + row.size;
            }
            if (!TextUtils.isEmpty(sub)) {
                TextView subTv = new TextView(context);
                subTv.setText(sub);
                subTv.setTextSize(ComponentTheme.metaTextSize());
                subTv.setTextColor(ComponentTheme.textTertiary(context));
                subTv.setSingleLine(true);
                subTv.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(subTv);
            }
            line.addView(textCol);

            line.setOnClickListener(v -> mgr.playQueue(uris, titles, index));
            shell.addBody(line);
        }
        if (rows.size() > shown) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.file_list_truncated, rows.size(), shown));
        }

        if (rows.size() > 1) {
            shell.addAction(context.getString(R.string.card_playlist_prev), false, mgr::skipToPrevious);
            shell.addAction(context.getString(R.string.card_playlist_next), false, mgr::skipToNext);
        }

        final AudioPlaybackManager.Listener listener = state -> {
            int currentIndex = -1;
            for (int i = 0; i < uris.size(); i++) {
                if (state.isTrack(uris.get(i))) {
                    currentIndex = i;
                    break;
                }
            }
            for (int i = 0; i < indexViews.size(); i++) {
                boolean current = i == currentIndex;
                indexViews.get(i).setText(current ? "▶"
                        : String.format(Locale.US, "%02d", i + 1));
                indexViews.get(i).setTextColor(current
                        ? ComponentTheme.brand(context) : ComponentTheme.textTertiary(context));
                nameViews.get(i).setTextColor(current
                        ? ComponentTheme.brand(context) : ComponentTheme.textPrimary(context));
            }
            if (currentIndex >= 0 && currentIndex < rows.size()) {
                shell.setSubtitle(context.getString(R.string.card_playlist_now_playing,
                        rows.get(currentIndex).name));
            } else {
                shell.setSubtitle(null);
            }
        };

        shell.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (registered[0]) return;
                registered[0] = true;
                mgr.addListener(listener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (!registered[0]) return;
                registered[0] = false;
                mgr.removeListener(listener);   // 只注销 UI，播放继续
            }
        });
        if (shell.isAttachedToWindow()) {
            registered[0] = true;
            mgr.addListener(listener);
        }
        return shell;
    }

    // ---------------------------------------------------------------- 数据

    /** 解析条目（纯函数，单测用）：只收有 url/path 的行，最多 50 条 */
    static List<Row> parseItems(JSONArray items) {
        List<Row> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < MAX_ROWS; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "");
            if (url.isEmpty()) url = o.optString("path", "");
            if (url.isEmpty()) url = o.optString("uri", "");
            if (url.isEmpty()) url = o.optString("src", "");
            if (url.isEmpty()) continue;
            Row r = new Row();
            r.url = url;
            r.name = o.optString("name", o.optString("title", ""));
            r.artist = o.optString("artist", "");
            r.size = o.optString("size", "");
            out.add(r);
        }
        return out;
    }

    static final class Row {
        String name;
        String url;
        String artist;
        String size;
    }
}
