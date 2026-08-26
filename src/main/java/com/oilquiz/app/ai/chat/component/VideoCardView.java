package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.File;

/**
 * 视频播放组件（ExoPlayer/Media3）：消息内直接播放视频。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "url": "https://.../a.mp4 | /storage/.../a.mp4 | file://... | content://... | files/xxx.mp4(工作区)",
 *   "title": "可选标题",
 *   "autoPlay": false,   // 可选，默认 false（点击播放）
 *   "loop": false,       // 可选，默认 false
 *   "speed": 1.0         // 可选，播放倍速（0.5~2.0）
 * }
 * </pre>
 *
 * 支持：网络 URL（http/https，含流媒体）、本地绝对路径、file:// 、content:// 、工作区相对路径。
 * ExoPlayer 特性：流媒体自适应、倍速、手势调节音量/亮度/进度、控制器 UI。
 */
public class VideoCardView implements ChatComponent {

    @Override
    public String getType() {
        return "video";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        return !resolveUrl(data.props).isEmpty();
    }

    /** 兼容多字段名取视频地址：url/src/media/video_url/uri/value */
    private static String resolveUrl(JSONObject p) {
        String url = p.optString("url", "");
        if (url.isEmpty()) url = p.optString("src", "");
        if (url.isEmpty()) url = p.optString("media", "");
        if (url.isEmpty()) url = p.optString("video_url", "");
        if (url.isEmpty()) url = p.optString("uri", "");
        if (url.isEmpty()) url = p.optString("value", "");
        return url;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String url = resolveUrl(p);
        String title = p.optString("title", "");
        boolean loop = p.optBoolean("loop", false);
        float speed = (float) p.optDouble("speed", 1.0);

        // 解析路径：工作区相对路径 → 绝对路径
        String resolved = resolveMediaPath(context, url);
        if (resolved == null) {
            return createErrorView(context, "无法解析视频地址: " + url);
        }
        Uri uri = Uri.parse(resolved.startsWith("content://") || resolved.startsWith("http://")
                || resolved.startsWith("https://") ? resolved : "file://" + resolved);
        File f = null;
        if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")
                && !uri.getScheme().equals("content")) {
            f = new File(uri.getPath());
        }
        if (f != null && !f.exists()) {
            return createErrorView(context, "视频文件不存在: " + f.getAbsolutePath());
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        card.setBackground(cardBackground(context));

        if (title != null && !title.isEmpty()) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        // 视频容器：16:9 区域
        final int videoHeight = dp(context, 220);
        FrameLayout videoFrame = new FrameLayout(context);
        LinearLayout.LayoutParams vfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight);
        videoFrame.setLayoutParams(vfLp);

        // ExoPlayer 播放器视图（自带控制条/手势：进度、音量、亮度、倍速）
        final PlayerView playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);

        final ProgressBar loading = new ProgressBar(context);
        FrameLayout.LayoutParams lpLoading = new FrameLayout.LayoutParams(
                dp(context, 36), dp(context, 36));
        lpLoading.gravity = android.view.Gravity.CENTER;
        loading.setLayoutParams(lpLoading);

        videoFrame.addView(playerView);
        videoFrame.addView(loading);
        card.addView(videoFrame);

        final ExoPlayer[] player = {null};
        try {
            player[0] = new ExoPlayer.Builder(context).build();
            playerView.setPlayer(player[0]);

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
                    if (playbackState == Player.STATE_BUFFERING) {
                        loading.setVisibility(View.VISIBLE);
                    } else if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                        loading.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    loading.setVisibility(View.GONE);
                    videoFrame.removeAllViews();
                    videoFrame.addView(createErrorView(context,
                            "视频播放失败: " + (error.getErrorCodeName() != null ? error.getErrorCodeName() : "未知错误")));
                    releasePlayer(player);
                }
            });

            if (p.optBoolean("autoPlay", false)) {
                player[0].play();
            } else {
                // 未自动播放：显示第一帧 + 控制条提示
                player[0].setPlayWhenReady(false);
                playerView.setKeepContentOnPlayerReset(true);
            }

            // 卡片移除时释放播放器
            card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    releasePlayer(player);
                }
            });
        } catch (Exception e) {
            loading.setVisibility(View.GONE);
            releasePlayer(player);
            return createErrorView(context, "视频初始化失败: " + e.getMessage());
        }

        // 底部提示
        TextView hint = new TextView(context);
        hint.setText("▶ 点击播放视频（支持倍速/手势）");
        hint.setTextSize(11);
        hint.setTextColor(ComponentColors.textTertiary(context));
        hint.setPadding(0, dp(context, 6), 0, 0);
        card.addView(hint);

        return card;
    }

    private void releasePlayer(ExoPlayer[] holder) {
        try {
            if (holder != null && holder[0] != null) {
                holder[0].release();
                holder[0] = null;
            }
        } catch (Exception ignored) {
        }
    }

    /** 解析媒体地址：工作区相对路径（files/xxx、tmp/xxx）→ 绝对路径 */
    private String resolveMediaPath(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        String u = url.trim();
        if (u.startsWith("content://") || u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file://") || u.startsWith("/")) {
            return u;
        }
        // 工作区相对路径
        try {
            com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context);
            File resolved = ws.resolveExistingFile(u);
            if (resolved != null && resolved.exists()) {
                return resolved.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        // 兜底：直接当绝对路径
        return u;
    }

    private View createErrorView(Context context, String msg) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
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
