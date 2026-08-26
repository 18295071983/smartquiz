package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONObject;

import java.io.File;

/**
 * 音乐/音频播放组件（ExoPlayer/Media3）：消息内播放音频。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "url": "https://.../a.mp3 | /storage/.../a.mp3 | file://... | content://... | files/xxx.mp3(工作区)",
 *   "title": "可选标题（如歌曲名）",
 *   "artist": "可选艺术家",
 *   "loop": false,
 *   "speed": 1.0         // 可选倍速
 * }
 * </pre>
 *
 * 支持：网络 URL（http/https，含流媒体）、本地绝对路径、file:// 、content:// 、工作区相对路径。
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

        String resolved = resolveMediaPath(context, url);
        if (resolved == null) {
            return createErrorView(context, "无法解析音频地址: " + url);
        }
        Uri uri = Uri.parse(resolved.startsWith("content://") || resolved.startsWith("http://")
                || resolved.startsWith("https://") ? resolved : "file://" + resolved);
        File f = null;
        if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")
                && !uri.getScheme().equals("content")) {
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
        controls.addView(seekBar);

        final TextView timeTv = new TextView(context);
        timeTv.setText("00:00 / 00:00");
        timeTv.setTextSize(10);
        timeTv.setTextColor(ComponentColors.textTertiary(context));
        timeTv.setPadding(dp(context, 8), 0, 0, 0);
        controls.addView(timeTv);

        card.addView(controls);

        final ExoPlayer[] player = {null};
        final boolean[] userSeeking = {false};

        try {
            player[0] = new ExoPlayer.Builder(context).build();
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(uri)
                    .build();
            player[0].setMediaItem(mediaItem);
            player[0].setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            if (speed > 0.1f && speed <= 2.0f) {
                player[0].setPlaybackSpeed(speed);
            }
            player[0].prepare();

            player[0].addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        long dur = player[0].getDuration();
                        timeTv.setText("00:00 / " + formatTime(dur));
                    } else if (playbackState == Player.STATE_ENDED) {
                        btnPlay.setText("▶");
                        seekBar.setProgress(0);
                        timeTv.setText("00:00 / " + formatTime(player[0].getDuration()));
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    btnPlay.setText(isPlaying ? "⏸" : "▶");
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    btnPlay.setText("⚠");
                }
            });

            btnPlay.setOnClickListener(v -> {
                try {
                    if (player[0] == null) return;
                    if (player[0].isPlaying()) {
                        player[0].pause();
                    } else {
                        player[0].play();
                    }
                } catch (Exception e) {
                    btnPlay.setText("⚠");
                }
            });

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {}
                @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking[0] = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking[0] = false;
                    try {
                        if (player[0] != null) {
                            long dur = player[0].getDuration();
                            if (dur > 0) {
                                player[0].seekTo(dur * sb.getProgress() / 1000);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

            // 进度刷新线程
            final Thread[] updater = {null};
            updater[0] = new Thread(() -> {
                while (player[0] != null) {
                    try {
                        final ExoPlayer mp = player[0];
                        if (mp.isPlaying() && !userSeeking[0]) {
                            final long pos = mp.getCurrentPosition();
                            final long dur = mp.getDuration();
                            card.post(() -> {
                                if (dur > 0) {
                                    seekBar.setProgress((int) (pos * 1000 / dur));
                                    timeTv.setText(formatTime(pos) + " / " + formatTime(dur));
                                }
                            });
                        }
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {
                    }
                }
            });
            updater[0].setDaemon(true);
            updater[0].start();

            // 卡片移除时释放播放器
            card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    try {
                        if (updater[0] != null) updater[0].interrupt();
                        if (player[0] != null) {
                            player[0].release();
                            player[0] = null;
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            try { if (player[0] != null) player[0].release(); } catch (Exception ignored) {}
            return createErrorView(context, "音频初始化失败: " + e.getMessage());
        }

        return card;
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    /** 解析媒体地址：工作区相对路径（files/xxx、tmp/xxx）→ 绝对路径 */
    private String resolveMediaPath(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        String u = url.trim();
        if (u.startsWith("content://") || u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file://") || u.startsWith("/")) {
            return u;
        }
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            File resolved = ws.resolveExistingFile(u);
            if (resolved != null && resolved.exists()) {
                return resolved.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return u;
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
