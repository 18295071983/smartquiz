package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;

import org.json.JSONObject;

import java.io.File;

/**
 * 音乐/音频播放组件（消息内播放音频）。
 *
 * <p><b>2026-10-23：播放器改成进程级单例</b>。卡片不再自己 new ExoPlayer，播放器由
 * {@link com.oilquiz.app.ai.chat.media.AudioPlaybackService}（MediaSessionService）持有，
 * 本卡片只通过 {@link AudioPlaybackManager} 读状态、发指令：
 * <ul>
 *   <li>卡片划出屏幕/列表回收/切页面 → 只注销监听，<b>播放继续</b>（旧实现 detach 即 release）；</li>
 *   <li>切后台/锁屏继续播，通知栏与系统媒体键可控制；</li>
 *   <li>同一首歌出现两张卡时，两张卡的进度天然一致（同一个播放器）。</li>
 * </ul>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "url": "https://.../a.mp3 | /storage/.../a.mp3 | file://... | content://... | files/xxx.mp3(工作区)",
 *   "title": "可选标题（如歌曲名）",
 *   "artist": "可选艺术家",
 *   "loop": false,
 *   "speed": 1.0         // 可选倍速（切到本卡时应用）
 * }
 * </pre>
 *
 * <p>支持：网络 URL（http/https，含流媒体）、本地绝对路径、file:// 、content:// 、工作区相对路径。
 * 播放控制：播放/暂停、进度条拖动、当前/总时长、倍速。
 */
public class AudioCardView implements ChatComponent {

    @Override
    public String getType() {
        return "audio";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        return !resolveUrl(data.props).isEmpty();
    }

    /** 兼容多字段名取音频地址：url/src/media/audio_url/uri/value */
    private static String resolveUrl(JSONObject p) {
        String url = p.optString("url", "");
        if (url.isEmpty()) url = p.optString("src", "");
        if (url.isEmpty()) url = p.optString("media", "");
        if (url.isEmpty()) url = p.optString("audio_url", "");
        if (url.isEmpty()) url = p.optString("uri", "");
        if (url.isEmpty()) url = p.optString("value", "");
        return url;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String url = resolveUrl(p);
        String title = p.optString("title", "");
        String artist = p.optString("artist", "");
        boolean loop = p.optBoolean("loop", false);
        float speed = (float) p.optDouble("speed", 1.0);

        // 路径口径走 MediaPathResolver（工作区相对路径/省略号截断名等统一兜底）
        String resolved = url == null ? "" : url.trim();
        if (!resolved.isEmpty()) {
            resolved = MediaPathResolver.resolve(context, resolved);
        }
        if (resolved.isEmpty()) {
            return createErrorView(context, "无法解析音频地址: " + url);
        }
        // file:// 必须原样认识，否则会拼成 file://file://…（旧实现有这个坑）
        Uri uri;
        if (resolved.startsWith("content://") || resolved.startsWith("http://")
                || resolved.startsWith("https://") || resolved.startsWith("file://")) {
            uri = Uri.parse(resolved);
        } else {
            uri = Uri.parse("file://" + resolved);
        }
        File f = null;
        String scheme = uri.getScheme();
        if (scheme != null && !scheme.equals("http") && !scheme.equals("https")
                && !scheme.equals("content")) {
            f = new File(uri.getPath());
        }
        if (f != null && !f.exists()) {
            return createErrorView(context, "音频文件不存在: " + f.getAbsolutePath());
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setBackground(cardBackground(context));

        // 头部：音乐图标 + 标题 + 艺术家
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(context);
        icon.setText("🎵");
        icon.setTextSize(22);
        icon.setPadding(0, 0, dp(context, 10), 0);
        header.addView(icon);

        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        if (title != null && !title.isEmpty()) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleBox.addView(titleTv);
        }
        if (artist != null && !artist.isEmpty()) {
            TextView artistTv = new TextView(context);
            artistTv.setText(artist);
            artistTv.setTextSize(11);
            artistTv.setTextColor(ComponentColors.textSecondary(context));
            titleBox.addView(artistTv);
        }
        if (titleBox.getChildCount() == 0) {
            TextView t = new TextView(context);
            t.setText("音频播放");
            t.setTextSize(14);
            t.setTextColor(ComponentColors.textPrimary(context));
            titleBox.addView(t);
        }
        header.addView(titleBox);
        card.addView(header);

        // 控制区：播放/暂停按钮 + 进度条 + 时间
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(context, 10), 0, 0);

        final TextView btnPlay = new TextView(context);
        btnPlay.setText("▶");
        btnPlay.setTextSize(22);
        btnPlay.setTextColor(ComponentColors.accent(context));
        btnPlay.setPadding(0, 0, dp(context, 12), 0);
        btnPlay.setClickable(true);
        controls.addView(btnPlay);

        final SeekBar seekBar = new SeekBar(context);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.weight = 1f;
        seekBar.setLayoutParams(sbLp);
        seekBar.setMax(1000);
        seekBar.setEnabled(false);   // 不是当前曲目/时长未知时不可拖
        controls.addView(seekBar);

        final TextView timeTv = new TextView(context);
        timeTv.setText(formatProgressText(0, 0));
        timeTv.setTextSize(10);
        timeTv.setTextColor(ComponentColors.textTertiary(context));
        timeTv.setPadding(dp(context, 8), 0, 0, 0);
        controls.addView(timeTv);

        card.addView(controls);

        // ==================== 单例播放器的界面壳 ====================
        // 这张卡片只是"遥控器"：播放/暂停/跳转都发给 AudioPlaybackManager，进度由它
        // 在主线程按 ticker 推回来；detach 只注销监听，播放器仍活在 AudioPlaybackService 里。
        final String trackUri = uri.toString();
        final AudioPlaybackManager mgr = AudioPlaybackManager.get(context);
        final boolean[] userSeeking = {false};
        final boolean[] registered = {false};
        final boolean[] isCurrent = {false};
        final long[] currentDuration = {0};
        final int[] lastProgress = {-1};

        final AudioPlaybackManager.Listener listener = state -> {
            boolean current = state.isTrack(trackUri);
            isCurrent[0] = current;
            currentDuration[0] = state.durationMs;
            boolean known = current && state.durationMs > 0;

            btnPlay.setText(state.error != null && current ? "⚠"
                    : (current && state.playing ? "⏸" : "▶"));
            seekBar.setEnabled(known);

            if (!current) {
                // 别的曲目在放：这张卡回到待播状态（时长未知显示 --:--）
                lastProgress[0] = -1;
                if (seekBar.getProgress() != 0) seekBar.setProgress(0);
                String idle = formatProgressText(0, 0);
                if (!idle.contentEquals(timeTv.getText())) timeTv.setText(idle);
                return;
            }
            if (!userSeeking[0] && known) {
                int progress = progressOf(state.positionMs, state.durationMs);
                if (progress != lastProgress[0]) {
                    lastProgress[0] = progress;
                    seekBar.setProgress(progress);
                }
            }
            String text = formatProgressText(state.positionMs, state.durationMs);
            if (!text.contentEquals(timeTv.getText())) timeTv.setText(text);
        };

        btnPlay.setOnClickListener(v ->
                mgr.playOrToggle(trackUri, title, artist, loop, speed));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || !isCurrent[0] || currentDuration[0] <= 0) return;
                // 拖动中先显示落点，松手才真正跳转
                String text = formatProgressText(currentDuration[0] * progress / 1000,
                        currentDuration[0]);
                if (!text.contentEquals(timeTv.getText())) timeTv.setText(text);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking[0] = true; }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking[0] = false;
                if (!isCurrent[0] || currentDuration[0] <= 0) return;
                lastProgress[0] = -1;
                mgr.seekTo(currentDuration[0] * sb.getProgress() / 1000);
            }
        });

        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (registered[0]) return;
                registered[0] = true;
                mgr.addListener(listener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // 只注销 UI 监听：播放器在服务里，划走继续播
                if (!registered[0]) return;
                registered[0] = false;
                mgr.removeListener(listener);
            }
        });
        // 复用已有视图时 attach 回调不会再触发，这里补一次
        if (card.isAttachedToWindow()) {
            registered[0] = true;
            mgr.addListener(listener);
        }

        return card;
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /**
     * 进度文字：总时长未知（<= 0，流媒体或尚未 READY）时显示 {@code --:--}，
     * 不要拿 {@code C.TIME_UNSET} 去格式化成 00:00 冒充"零时长"。
     */
    static String formatProgressText(long posMs, long durMs) {
        return formatTime(posMs) + " / " + (durMs > 0 ? formatTime(durMs) : "--:--");
    }

    /**
     * 当前位置 → SeekBar(0..1000) 进度；时长未知返回 -1（调用方据此跳过 setProgress
     * 并禁用拖动）。位置超界时夹取，避免 setProgress 抛 IllegalArgumentException。
     */
    static int progressOf(long posMs, long durMs) {
        if (durMs <= 0) return -1;
        long p = posMs * 1000 / durMs;
        if (p < 0) return 0;
        return (int) Math.min(1000, p);
    }

    private View createErrorView(Context context, String msg) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setBackground(cardBackground(context));
        TextView tv = new TextView(context);
        tv.setText("⚠ " + msg);
        tv.setTextSize(12);
        tv.setTextColor(ComponentColors.error(context));
        card.addView(tv);
        return card;
    }

    private android.graphics.drawable.Drawable cardBackground(Context context) {
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
