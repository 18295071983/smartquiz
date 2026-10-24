package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;

import androidx.mediarouter.app.MediaRouteButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频全屏页（2026-10-25，播放器 P0）。
 *
 * <p>不新建播放器、不持有 Surface 生命周期：直接拿 {@link AudioPlaybackManager#playerOrNull()}
 * 那个进程级 ExoPlayer，退出时只 {@code setPlayer(null)}，播放/声音继续在
 * {@code AudioPlaybackService} 里。这样聊天卡片和全屏页看的是同一个进度，不会出现
 * "进全屏从头播/退出后声音断"。</p>
 *
 * <p>横屏、沉浸式、保持常亮；左上角切"适应/裁剪"，右上角关闭。播放器控制条仍用
 * Media3 默认的 {@link PlayerView}，字幕/音轨/PiP 在 P1 再接。</p>
 */
public class VideoFullscreenActivity extends Activity {

    private PlayerView playerView;
    private TextView ratioButton;
    private TextView subtitleButton;
    private TextView audioButton;
    private TextView closeButton;
    private CastContext castContext;
    private MediaRouteButton castButton;

    private final SessionManagerListener<CastSession> castListener =
            new SessionManagerListener<CastSession>() {
                @Override
                public void onSessionStarted(CastSession session, String sessionId) {
                    castCurrent(session);
                }

                @Override
                public void onSessionResumed(CastSession session, boolean wasSuspended) {
                    castCurrent(session);
                }

                @Override
                public void onSessionStartFailed(CastSession session, int error) {
                    Toast.makeText(VideoFullscreenActivity.this,
                            "投屏连接失败", Toast.LENGTH_SHORT).show();
                }

                @Override public void onSessionEnded(CastSession session, int error) { }
                @Override public void onSessionStarting(CastSession session) { }
                @Override public void onSessionEnding(CastSession session) { }
                @Override public void onSessionResuming(CastSession session, String sessionId) { }
                @Override public void onSessionResumeFailed(CastSession session, int error) { }
                @Override public void onSessionSuspended(CastSession session, int reason) { }
            };

    /** 从卡片打开；非 Activity Context 补 NEW_TASK，避免 crash。 */
    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, VideoFullscreenActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ExoPlayer player = AudioPlaybackManager.get(this).playerOrNull();
        if (player == null) {
            Toast.makeText(this, "播放器尚未就绪，请先在卡片里播放", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setPlayer(player);
        root.addView(playerView);

        final boolean[] zoom = {false};
        TextView ratio = chromeButton(zoom[0] ? "裁剪" : "适应");
        ratioButton = ratio;
        ratio.setOnClickListener(v -> {
            zoom[0] = !zoom[0];
            playerView.setResizeMode(zoom[0]
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            ((TextView) v).setText(zoom[0] ? "裁剪" : "适应");
        });

        TextView subs = chromeButton("字幕");
        subtitleButton = subs;
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.leftMargin = dp(8);
        subs.setLayoutParams(subLp);
        subs.setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_TEXT));

        TextView audio = chromeButton("音轨");
        audioButton = audio;
        LinearLayout.LayoutParams audioLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        audioLp.leftMargin = dp(8);
        audio.setLayoutParams(audioLp);
        audio.setOnClickListener(v -> showTrackDialog(C.TRACK_TYPE_AUDIO));

        try {
            castContext = CastContext.getSharedInstance(this);
            castContext.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
        } catch (Throwable ignored) {
            castContext = null;   // 无 Play Services / 未配置 Cast：不显示按钮，不影响播放
        }

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(ratio);
        topBar.addView(subs);
        topBar.addView(audio);
        if (castContext != null) {
            castButton = new MediaRouteButton(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), castButton);
            LinearLayout.LayoutParams castLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            castLp.leftMargin = dp(8);
            castButton.setLayoutParams(castLp);
            topBar.addView(castButton);
        }
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        topLp.topMargin = dp(16);
        topLp.leftMargin = dp(16);
        root.addView(topBar, topLp);

        TextView close = chromeButton("关闭");
        closeButton = close;
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeLp.topMargin = dp(16);
        closeLp.rightMargin = dp(16);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
        hideSystemBars();
    }

    private void showTrackDialog(int type) {
        if (playerView == null) return;
        Player player = playerView.getPlayer();
        if (player == null) return;
        List<TrackGroup> groups = new ArrayList<>();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() == type && group.isSupported()) {
                groups.add(group.getMediaTrackGroup());
            }
        }
        List<String> labels = new ArrayList<>();
        labels.add("关闭");
        for (TrackGroup group : groups) {
            Format f = group.getFormat(0);
            String label = f.label == null ? "" : f.label.toString();
            if (label.isEmpty() && f.language != null) label = f.language;
            if (label.isEmpty()) {
                label = (type == C.TRACK_TYPE_TEXT ? "字幕 " : "音轨 ") + labels.size();
            }
            labels.add(label);
        }
        new AlertDialog.Builder(this)
                .setTitle(type == C.TRACK_TYPE_TEXT ? "选择字幕" : "选择音轨")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    TrackSelectionParameters.Builder b =
                            player.getTrackSelectionParameters().buildUpon();
                    if (which == 0) {
                        b.setTrackTypeDisabled(type, true);
                    } else {
                        b.setTrackTypeDisabled(type, false);
                        b.clearOverridesOfType(type);
                        b.setOverrideForType(
                                new TrackSelectionOverride(groups.get(which - 1), 0));
                    }
                    player.setTrackSelectionParameters(b.build());
                })
                .show();
    }

    private void castCurrent(CastSession session) {
        if (session == null || playerView == null) return;
        Player player = playerView.getPlayer();
        if (player == null) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null
                || item.localConfiguration.uri == null) return;
        String url = item.localConfiguration.uri.toString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "本地文件不支持投屏，请用网络媒体", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = item.mediaMetadata == null || item.mediaMetadata.title == null
                ? "答题宝" : item.mediaMetadata.title.toString();
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, title);
        boolean live = false;
        try {
            live = player.isCurrentMediaItemLive();
        } catch (Throwable ignored) {
        }
        MediaInfo info = new MediaInfo.Builder(url)
                .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(castContentType(url))
                .setMetadata(metadata)
                .build();
        RemoteMediaClient client = session.getRemoteMediaClient();
        if (client == null) return;
        client.load(info).setResultCallback(result -> {
            if (!result.getStatus().isSuccess()) {
                Toast.makeText(VideoFullscreenActivity.this,
                        "投屏加载失败：" + result.getStatus().getStatusMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        try {
            player.pause();
        } catch (Throwable ignored) {
        }
    }

    private static String castContentType(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        if (lower.contains(".m3u8")) return "application/x-mpegURL";
        if (lower.contains(".mpd")) return "application/dash+xml";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private TextView chromeButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(16));
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (playerView == null) return;
        Player player = playerView.getPlayer();
        if (player == null || !player.isPlaying()) return;
        try {
            int width = player.getVideoSize().width;
            int height = player.getVideoSize().height;
            Rational ratio = width > 0 && height > 0
                    ? new Rational(width, height) : new Rational(16, 9);
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build());
        } catch (Throwable ignored) {
            // 设备/系统不支持 PiP：保持普通全屏，不让 Home 键变成崩溃
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (ratioButton != null) {
            ratioButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        if (subtitleButton != null) {
            subtitleButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        if (audioButton != null) {
            audioButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        if (closeButton != null) {
            closeButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        if (castButton != null) {
            castButton.setVisibility(inPip ? View.GONE : View.VISIBLE);
        }
        if (playerView != null) {
            if (inPip) {
                playerView.hideController();
            } else {
                hideSystemBars();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (castContext != null) {
            castContext.getSessionManager()
                    .removeSessionManagerListener(castListener, CastSession.class);
        }
        // 只解绑 Surface，不 release：播放器属于前台服务，退出全屏后声音继续。
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        // 通知卡片按当前曲目重新绑 Surface；否则全屏退出后卡片可能保持黑屏/空 Surface。
        AudioPlaybackManager.get(this).notifyStateNow();
        super.onDestroy();
    }
}
