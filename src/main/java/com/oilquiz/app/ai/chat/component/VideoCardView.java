package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;

import org.json.JSONObject;

import java.io.File;

/**
 * 视频播放组件（消息内直接播放视频）。
 *
 * <p><b>2026-10-23：与音频共用同一个播放器单例</b>（{@link AudioPlaybackService} 持有）。
 * 卡片只负责把画面挂到那个播放器上：
 * <ul>
 *   <li>划出屏幕/列表回收 → 只解绑 Surface（{@code PlayerView.setPlayer(null)}），
 *       播放继续（声音不断），划回来从原位置接着播；</li>
 *   <li>切后台/锁屏继续播（前台服务 + 媒体通知）；</li>
 *   <li>与音频互斥：放视频会停掉正在放的音频，反之亦然——一个播放器只有一个当前曲目。</li>
 * </ul>
 *
 * <p>为什么视频能拿到真实播放器：{@code MediaController} 不提供 Surface，只有服务持有
 * 的 ExoPlayer 实例才能渲染画面；同进程、主线程访问，安全。
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "url": "https://.../a.mp4 | /storage/.../a.mp4 | file://... | content://... | files/xxx.mp4(工作区)",
 *   "title": "可选标题",
 *   "autoPlay": false,   // 可选：仅在当前没有别的媒体在播时自动开始，避免回滚列表时抢播
 *   "loop": false,       // 可选
 *   "speed": 1.0         // 可选：播放倍速（0.5~2.0）
 * }
 * </pre>
 *
 * 支持：网络 URL（http/https，含流媒体）、本地绝对路径、file:// 、content:// 、工作区相对路径。
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
        boolean autoPlay = p.optBoolean("autoPlay", false);
        float speed = (float) p.optDouble("speed", 1.0);

        // 路径口径与音频卡一致：统一走 MediaPathResolver
        String resolved = url == null ? "" : url.trim();
        if (!resolved.isEmpty()) {
            resolved = MediaPathResolver.resolve(context, resolved);
        }
        if (resolved.isEmpty()) {
            return createErrorView(context, "无法解析视频地址: " + url);
        }
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
        FrameLayout videoFrame = new FrameLayout(context);
        videoFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 220)));

        // PlayerView 自带控制条/手势（进度、音量、亮度、倍速）；只在本卡是"当前曲目"时绑播放器
        final PlayerView playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);

        final ProgressBar loading = new ProgressBar(context);
        FrameLayout.LayoutParams lpLoading = new FrameLayout.LayoutParams(
                dp(context, 36), dp(context, 36));
        lpLoading.gravity = Gravity.CENTER;
        loading.setLayoutParams(lpLoading);
        loading.setVisibility(View.GONE);

        // 非当前曲目时盖在画面上的播放入口（点它才把播放器切到本卡并挂 Surface）
        final TextView playOverlay = new TextView(context);
        playOverlay.setText("▶");
        playOverlay.setTextSize(30);
        playOverlay.setTextColor(android.graphics.Color.WHITE);
        playOverlay.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0x99000000);
        playOverlay.setBackground(circle);
        FrameLayout.LayoutParams lpPlay = new FrameLayout.LayoutParams(
                dp(context, 56), dp(context, 56));
        lpPlay.gravity = Gravity.CENTER;
        playOverlay.setLayoutParams(lpPlay);
        playOverlay.setClickable(true);

        final TextView errorTv = new TextView(context);
        errorTv.setTextColor(ComponentColors.error(context));
        errorTv.setTextSize(12);
        errorTv.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lpErr = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpErr.gravity = Gravity.CENTER;
        errorTv.setLayoutParams(lpErr);
        errorTv.setVisibility(View.GONE);

        videoFrame.addView(playerView);
        videoFrame.addView(loading);
        videoFrame.addView(playOverlay);
        videoFrame.addView(errorTv);
        card.addView(videoFrame);

        final TextView hint = new TextView(context);
        hint.setText("▶ 点击播放（划走不中断，回来接着播）");
        hint.setTextSize(11);
        hint.setTextColor(ComponentColors.textTertiary(context));
        hint.setPadding(0, dp(context, 6), 0, 0);
        card.addView(hint);

        // ==================== 共用播放器的 Surface 绑定 ====================
        final String trackUri = uri.toString();
        final AudioPlaybackManager mgr = AudioPlaybackManager.get(context);
        final boolean[] registered = {false};
        final boolean[] surfaceBound = {false};
        final boolean[] autoPlayTried = {false};

        // 只在"本卡是当前曲目"时把真实播放器挂到 PlayerView 上（同进程实例，能出画面）
        final Runnable bindSurface = () -> mgr.whenPlayerReady(player -> {
            if (!card.isAttachedToWindow()) return;      // 视图已回收，别再持有
            if (!trackUri.equals(currentMediaId(player))) return;
            if (surfaceBound[0]) return;
            playerView.setPlayer(player);
            surfaceBound[0] = true;
            playOverlay.setVisibility(View.GONE);
        });

        final AudioPlaybackManager.Listener listener = state -> {
            boolean current = state.isTrack(trackUri);
            if (!current) {
                if (surfaceBound[0]) {
                    playerView.setPlayer(null);
                    surfaceBound[0] = false;
                }
                loading.setVisibility(View.GONE);
                // 服务没连上（没有 mediaId 却带 error）要说出来，不能只是"点了没反应"
                boolean serviceDown = state.error != null && state.mediaId.isEmpty();
                if (serviceDown) {
                    errorTv.setText("⚠ 播放服务连接失败");
                    errorTv.setVisibility(View.VISIBLE);
                } else {
                    errorTv.setVisibility(View.GONE);
                }
                playOverlay.setVisibility(View.VISIBLE);
                hint.setText(serviceDown ? "▶ 点此重试（播放器服务未连接）"
                        : "▶ 点击播放（划走不中断，回来接着播）");
                return;
            }
            playOverlay.setVisibility(state.ended ? View.VISIBLE : View.GONE);
            loading.setVisibility(state.buffering ? View.VISIBLE : View.GONE);
            errorTv.setVisibility(state.error != null ? View.VISIBLE : View.GONE);
            if (state.error != null) {
                errorTv.setText("⚠ 视频播放失败: " + state.error);
                if (surfaceBound[0]) {
                    playerView.setPlayer(null);
                    surfaceBound[0] = false;
                }
                return;
            }
            if (!surfaceBound[0]) bindSurface.run();
            hint.setText("点画面显示控制条（进度/倍速/手势）");
        };

        playOverlay.setOnClickListener(v ->
                mgr.playOrToggle(trackUri, title, "", loop, speed));

        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (registered[0]) return;
                registered[0] = true;
                mgr.addListener(listener);
                // autoPlay 只在"当前没有别的媒体在播"时生效：列表回收重绑不该抢掉正在放的东西
                if (autoPlay && !autoPlayTried[0] && mgr.snapshot().mediaId.isEmpty()) {
                    autoPlayTried[0] = true;
                    mgr.playOrToggle(trackUri, title, "", loop, speed);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // 只解绑画面，不停播放：播放器在 AudioPlaybackService 里
                if (surfaceBound[0]) {
                    playerView.setPlayer(null);
                    surfaceBound[0] = false;
                }
                if (!registered[0]) return;
                registered[0] = false;
                mgr.removeListener(listener);
            }
        });
        if (card.isAttachedToWindow()) {
            registered[0] = true;
            mgr.addListener(listener);
            if (autoPlay && !autoPlayTried[0] && mgr.snapshot().mediaId.isEmpty()) {
                autoPlayTried[0] = true;
                mgr.playOrToggle(trackUri, title, "", loop, speed);
            }
        }

        return card;
    }

    private static String currentMediaId(ExoPlayer player) {
        try {
            MediaItem item = player.getCurrentMediaItem();
            return item != null && item.mediaId != null ? item.mediaId : "";
        } catch (Throwable t) {
            return "";
        }
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
