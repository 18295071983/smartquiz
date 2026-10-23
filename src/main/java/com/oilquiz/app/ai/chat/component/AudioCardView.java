package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
        seekBar.setEnabled(false);   // 时长未知前不可拖；READY 后由 syncUi 打开
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
        // 进度刷新只能在主线程做：Media3 的 ExoPlayer 只能在创建它的线程上访问。
        // 旧实现用后台线程轮询 isPlaying()/getCurrentPosition()，每一轮都在后台线程抛
        // IllegalStateException，又被 catch(Exception ignored) 吞掉 → 循环空转，
        // 进度条和秒数永远停在 00:00（真机现象）。改为主线程 Handler 定时同步，
        // 与 FileRenderActivity#startAudioProgress 同一套写法（postDelayed + 自引用退出）。
        final Handler ticker = new Handler(Looper.getMainLooper());
        final int[] lastProgress = {-1};
        final Runnable[] syncUi = {null};
        final Runnable[] tick = {null};

        // 用播放器真实位置刷新进度条/秒数；未知时长（流媒体/尚未解析）显示 --:-- 并禁用拖动
        syncUi[0] = () -> {
            ExoPlayer mp = player[0];
            if (mp == null) return;
            try {
                long dur = mp.getDuration();
                long pos = Math.max(0, mp.getCurrentPosition());
                boolean known = dur > 0;
                seekBar.setEnabled(known);
                if (!userSeeking[0] && known) {
                    int progress = progressOf(pos, dur);
                    if (progress != lastProgress[0]) {
                        lastProgress[0] = progress;
                        seekBar.setProgress(progress);
                    }
                }
                String text = formatProgressText(pos, dur);
                if (!text.contentEquals(timeTv.getText())) timeTv.setText(text);
            } catch (Throwable ignored) {
            }
        };

        tick[0] = new Runnable() {
            @Override
            public void run() {
                if (tick[0] != this) return;
                ExoPlayer mp = player[0];
                if (mp == null) return;
                int state = mp.getPlaybackState();
                // 暂停时也同步：拖动/暂停后秒数要立刻对得上；ENDED 交给它自己的回调收尾
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    syncUi[0].run();
                }
                if (tick[0] == this) ticker.postDelayed(this, 400);
            }
        };

        final Runnable startTicker = () -> {
            // 只在已入树时轮询：视图创建后没被挂上（丢弃/复用失败）就 post 会一直空转
            if (tick[0] == null || !card.isAttachedToWindow()) return;
            ticker.removeCallbacks(tick[0]);
            ticker.post(tick[0]);
        };

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
                        // 不要写死 "00:00"：缓冲/暂停续播时当前位置可能不在开头
                        syncUi[0].run();
                        startTicker.run();
                    } else if (playbackState == Player.STATE_ENDED) {
                        btnPlay.setText("▶");
                        lastProgress[0] = -1;
                        seekBar.setProgress(0);
                        timeTv.setText("00:00 / " + formatTime(player[0].getDuration()));
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    btnPlay.setText(isPlaying ? "⏸" : "▶");
                    if (isPlaying) startTicker.run();
                    else syncUi[0].run();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    btnPlay.setText("⚠");
                    seekBar.setEnabled(false);
                }
            });

            btnPlay.setOnClickListener(v -> {
                try {
                    ExoPlayer mp = player[0];
                    if (mp == null) return;
                    if (mp.isPlaying()) {
                        mp.pause();
                    } else {
                        // ENDED 之后 ExoPlayer 不会自己回到开头，play() 前必须 seekTo(0)，
                        // 否则按钮变了图标却没有声音
                        if (mp.getPlaybackState() == Player.STATE_ENDED) {
                            lastProgress[0] = -1;
                            mp.seekTo(0);
                            seekBar.setProgress(0);
                            syncUi[0].run();
                        }
                        mp.play();
                        startTicker.run();
                    }
                } catch (Throwable e) {
                    btnPlay.setText("⚠");
                }
            });

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;      // 程序刷新进度不能覆盖用户拖动
                    ExoPlayer mp = player[0];
                    if (mp == null) return;
                    try {
                        long dur = mp.getDuration();
                        if (dur > 0) {
                            // 拖动中先显示落点，松手才真正跳转
                            timeTv.setText(formatProgressText(dur * progress / 1000, dur));
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking[0] = true; }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    userSeeking[0] = false;
                    try {
                        ExoPlayer mp = player[0];
                        if (mp != null) {
                            long dur = mp.getDuration();
                            if (dur > 0) {
                                lastProgress[0] = -1;
                                mp.seekTo(dur * sb.getProgress() / 1000);
                            }
                            syncUi[0].run();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            // 卡片移除时停刷新并释放播放器（detach 回调在主线程，符合 Media3 线程要求）
            card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    startTicker.run();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    try {
                        ticker.removeCallbacks(tick[0]);
                        tick[0] = null;
                        if (player[0] != null) {
                            player[0].release();
                            player[0] = null;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            // 视图已入树（复用场景）时 attach 回调不会再触发，这里补一次
            if (card.isAttachedToWindow()) startTicker.run();
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
