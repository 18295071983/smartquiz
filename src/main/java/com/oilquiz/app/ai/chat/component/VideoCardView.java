package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;
import com.oilquiz.app.ui.activity.VideoFullscreenActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 *   "subtitles": [       // 可选：外挂字幕；也兼容 "subtitle": "单条地址"
 *     {"url": "https://.../zh.srt", "lang": "zh", "label": "中文", "default": true}
 *   ]
 *   "headers": {         // 可选：UA/Referer/Cookie 等防盗链头
 *     "User-Agent": "...", "Referer": "...", "Cookie": "..."
 *   }
 *   "artwork": "https://.../cover.jpg"   // 可选：通知栏/锁屏封面
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
        final List<MediaItem.SubtitleConfiguration> subtitles = parseSubtitles(context, p);
        final java.util.Map<String, String> headers = PlaybackHeaderParser.parse(p);
        ArtworkResolver.apply(context, p);

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

        // 视频容器：先给 220dp 占位，拿到视频尺寸后按比例重算（竖屏/超宽都不再硬塞 16:9）
        FrameLayout videoFrame = new FrameLayout(context);
        videoFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 220)));

        // 只有 PlayerView 默认控制条；全屏/比例由卡片按钮 + VideoFullscreenActivity 提供
        final PlayerView playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);

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

        final ImageView fullscreen = new ImageView(context);
        fullscreen.setImageResource(R.drawable.ic_card_fullscreen);
        fullscreen.setColorFilter(android.graphics.Color.WHITE);
        fullscreen.setContentDescription("全屏播放");
        fullscreen.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        android.graphics.drawable.GradientDrawable fsBg =
                new android.graphics.drawable.GradientDrawable();
        fsBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        fsBg.setColor(0x99000000);
        fullscreen.setBackground(fsBg);
        FrameLayout.LayoutParams lpFs = new FrameLayout.LayoutParams(
                dp(context, 36), dp(context, 36), Gravity.TOP | Gravity.END);
        lpFs.topMargin = dp(context, 8);
        lpFs.rightMargin = dp(context, 8);
        fullscreen.setLayoutParams(lpFs);
        fullscreen.setVisibility(View.GONE);
        fullscreen.setClickable(true);

        videoFrame.addView(playerView);
        videoFrame.addView(loading);
        videoFrame.addView(playOverlay);
        videoFrame.addView(errorTv);
        videoFrame.addView(fullscreen);
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
        final Player.Listener[] videoSizeListener = {null};

        // 只解绑 Surface，不停播放；同时把比例/常亮恢复成默认，避免回收视图留下旧尺寸。
        final Runnable unbindSurface = () -> {
            Player attached = playerView.getPlayer();
            if (attached != null && videoSizeListener[0] != null) {
                attached.removeListener(videoSizeListener[0]);
            }
            videoSizeListener[0] = null;
            playerView.setPlayer(null);
            playerView.setKeepScreenOn(false);
            surfaceBound[0] = false;
            fullscreen.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = videoFrame.getLayoutParams();
            if (lp != null) {
                lp.height = dp(videoFrame.getContext(), 220);
                videoFrame.setLayoutParams(lp);
            }
        };
        // 进全屏前先交还卡片 Surface，否则同一个 ExoPlayer 会被两个 PlayerView 抢输出；
        // 全屏页关闭时会 notifyStateNow()，卡片监听器收到状态后按当前曲目重新 bind。
        fullscreen.setOnClickListener(v -> {
            unbindSurface.run();
            VideoFullscreenActivity.start(v.getContext());
        });

        // 只在"本卡是当前曲目"时把真实播放器挂到 PlayerView 上（同进程实例，能出画面）
        final Runnable bindSurface = () -> mgr.whenPlayerReady(player -> {
            if (!card.isAttachedToWindow()) return;      // 视图已回收，别再持有
            if (!trackUri.equals(currentMediaId(player))) return;
            if (surfaceBound[0]) return;
            if (videoSizeListener[0] == null) {
                videoSizeListener[0] = new Player.Listener() {
                    @Override
                    public void onVideoSizeChanged(VideoSize videoSize) {
                        applyVideoSize(videoFrame, card, videoSize.width, videoSize.height);
                    }
                };
            }
            player.addListener(videoSizeListener[0]);
            playerView.setPlayer(player);
            surfaceBound[0] = true;
            playOverlay.setVisibility(View.GONE);
            fullscreen.setVisibility(View.VISIBLE);
            VideoSize size = player.getVideoSize();
            applyVideoSize(videoFrame, card, size.width, size.height);
        });

        final AudioPlaybackManager.Listener listener = state -> {
            boolean current = state.isTrack(trackUri);
            if (!current) {
                unbindSurface.run();
                loading.setVisibility(View.GONE);
                // 服务没连上（没有 mediaId 却带 error）要说出来，不能只是"点了没反应"
                boolean serviceDown = state.error != null && state.mediaId.isEmpty();
                if (serviceDown) {
                    errorTv.setText("⚠ 播放服务连接失败\n点此重试");
                    errorTv.setVisibility(View.VISIBLE);
                } else {
                    errorTv.setVisibility(View.GONE);
                }
                playOverlay.setVisibility(View.VISIBLE);
                hint.setText(serviceDown ? "▶ 点此重试（播放器服务未连接）"
                        : "▶ 点击播放（划走不中断，回来接着播）");
                return;
            }
            boolean error = state.error != null;
            playOverlay.setVisibility(state.ended || error ? View.VISIBLE : View.GONE);
            loading.setVisibility(state.buffering ? View.VISIBLE : View.GONE);
            errorTv.setVisibility(error ? View.VISIBLE : View.GONE);
            fullscreen.setVisibility(error ? View.GONE
                    : (surfaceBound[0] ? View.VISIBLE : View.GONE));
            playerView.setKeepScreenOn(!error && (state.playing || state.playWhenReady));
            if (error) {
                errorTv.setText("⚠ 视频播放失败: " + state.error + "\n点此重试");
                unbindSurface.run();
                hint.setText("▶ 点此重试");
                return;
            }
            if (!surfaceBound[0]) bindSurface.run();
            hint.setText("点画面显示控制条；右上角可全屏");
        };

        View.OnClickListener retry = v ->
                mgr.playOrToggle(trackUri, title, "", loop, speed, subtitles, headers);
        playOverlay.setOnClickListener(retry);
        errorTv.setClickable(true);
        errorTv.setOnClickListener(retry);

        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (registered[0]) return;
                registered[0] = true;
                mgr.addListener(listener);
                // autoPlay 只在"当前没有别的媒体在播"时生效：列表回收重绑不该抢掉正在放的东西
                if (autoPlay && !autoPlayTried[0] && mgr.snapshot().mediaId.isEmpty()) {
                    autoPlayTried[0] = true;
                    mgr.playOrToggle(trackUri, title, "", loop, speed, subtitles, headers);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // 只解绑画面，不停播放：播放器在 AudioPlaybackService 里
                unbindSurface.run();
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
                mgr.playOrToggle(trackUri, title, "", loop, speed, subtitles, headers);
            }
        }

        return card;
    }

    /**
     * 解析 props.subtitles（数组，或单条 props.subtitle）。地址走 MediaPathResolver，
     * 与视频/音频同一套本地路径/工作区相对路径口径；MIME 按扩展名兜底。
     */
    static List<MediaItem.SubtitleConfiguration> parseSubtitles(Context context, JSONObject p) {
        List<MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        if (context == null || p == null) return out;

        JSONArray arr = p.optJSONArray("subtitles");
        boolean hasDefault = false;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optBoolean("default", o.optBoolean("is_default", false))) {
                    hasDefault = true;
                    break;
                }
            }
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String raw = firstNonEmpty(o, "url", "src", "path", "uri");
                if (raw.isEmpty()) continue;
                String mime = firstNonEmpty(o, "mime", "type");
                String lang = firstNonEmpty(o, "lang", "language");
                String label = firstNonEmpty(o, "label", "name");
                boolean isDefault = o.optBoolean("default", o.optBoolean("is_default", false))
                        || (!hasDefault && out.isEmpty());
                MediaItem.SubtitleConfiguration sub =
                        buildSubtitle(context, raw, mime, lang, label, isDefault);
                if (sub != null) out.add(sub);
            }
        }
        if (out.isEmpty()) {
            String single = p.optString("subtitle", "");
            if (single.isEmpty()) single = p.optString("subtitle_url", "");
            if (!single.isEmpty()) {
                MediaItem.SubtitleConfiguration sub =
                        buildSubtitle(context, single, "", "", "", true);
                if (sub != null) out.add(sub);
            }
        }
        return out;
    }

    private static MediaItem.SubtitleConfiguration buildSubtitle(
            Context context, String raw, String mime, String lang, String label, boolean isDefault) {
        String resolved = MediaPathResolver.resolve(context, raw);
        if (resolved == null || resolved.isEmpty()) return null;
        Uri uri;
        String lower = resolved.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("content://") || lower.startsWith("file://")) {
            uri = Uri.parse(resolved);
        } else {
            uri = Uri.fromFile(new File(resolved));
        }
        String actualMime = mime == null || mime.isEmpty() ? subtitleMimeOf(resolved) : mime;
        MediaItem.SubtitleConfiguration.Builder b =
                new MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(actualMime)
                        .setLanguage(lang == null || lang.isEmpty() ? "und" : lang);
        if (label != null && !label.isEmpty()) b.setLabel(label);
        if (isDefault) b.setSelectionFlags(C.SELECTION_FLAG_DEFAULT);
        try {
            return b.build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String subtitleMimeOf(String uri) {
        if (uri == null) return MimeTypes.APPLICATION_SUBRIP;
        String path = uri;
        int cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (lower.endsWith(".ass") || lower.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (lower.endsWith(".ttml") || lower.endsWith(".xml")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String key : keys) {
            String v = o.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** 按视频宽高比算容器高度，夹在卡片的合理区间；竖屏/超宽都不硬塞 16:9。 */
    static int fitHeight(int containerWidth, int videoWidth, int videoHeight,
                         int minHeight, int maxHeight) {
        if (containerWidth <= 0 || videoWidth <= 0 || videoHeight <= 0) return minHeight;
        int h = Math.round(containerWidth * (float) videoHeight / videoWidth);
        return Math.max(minHeight, Math.min(maxHeight, h));
    }

    private static void applyVideoSize(FrameLayout frame, View card, int videoWidth, int videoHeight) {
        if (frame == null || videoWidth <= 0 || videoHeight <= 0) return;
        frame.post(() -> {
            if (!frame.isAttachedToWindow()) return;
            int width = frame.getWidth();
            if (width <= 0) {
                width = card.getWidth() - card.getPaddingLeft() - card.getPaddingRight();
            }
            if (width <= 0) return;
            int h = fitHeight(width, videoWidth, videoHeight,
                    dp(frame.getContext(), 160), dp(frame.getContext(), 420));
            ViewGroup.LayoutParams lp = frame.getLayoutParams();
            if (lp != null && lp.height != h) {
                lp.height = h;
                frame.setLayoutParams(lp);
            }
        });
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
